package com.codeassist.ai.ai

import android.content.Context
import com.codeassist.ai.data.Message
import com.codeassist.ai.data.Role
import com.codeassist.ai.data.Store
import com.google.gson.Gson
import java.util.UUID

/**
 * Audit Sec 9.6, Phase 3: the executor of a plan. The brain proposes a list of tool calls (some with `after`
 * links); this object turns it into a [TaskGraph.Plan], stores it in [TaskQueue] and runs it:
 *
 *  - nodes run in dependency order (then priority class, then the order written); the order rules are in [TaskGraph],
 *  - every node goes through [PolicyGate] first; a T2 node waits as WAITING_CONFIRM and runs only after the user's tap,
 *    one confirmation at a time, and [PolicyGate.mayExecute] is checked again right before any tool runs,
 *  - a node that fails skips only the nodes that depend on it, the other nodes keep running,
 *  - the plan is written to disk BEFORE each tool starts and after it ends, so [recover] knows after a process death
 *    what had really started.
 *
 * Tools still run one after another on the calling (main) thread, like Tier-0 did: all eight tools return at once, and the
 * Android calls behind them (Clock intents, torch) are not meant for a worker thread. The audit's "parallelism limit
 * ~3" matters when a tool can take seconds (web search); it is not needed for these eight and is not built.
 */
object TaskEngine {
    private val gson = Gson()
    private var recovered = false

    private fun init(app: Context) {
        Store.init(app)
        ActivityLog.init(app)
        ConvKpi.init(app)
        TaskQueue.init(app)
    }

    // ---------- run ----------

    /** Called by [PlanExecutor.run] with a plan the brain proposed and the code has already validated. */
    fun run(
        app: Context, chatId: String, messageId: String, ack: String, tasks: List<PlanTask>
    ): PlanExecutor.Outcome {
        init(app)
        val plan = TaskGraph.build(UUID.randomUUID().toString(), chatId, messageId, ack, tasks, System.currentTimeMillis())
        TaskQueue.save(plan)
        ConvKpi.inc("plan_runs")
        if (tasks.size > 1) ConvKpi.inc("plan_multi")
        if (tasks.any { it.dependsOn.isNotEmpty() }) ConvKpi.inc("plan_with_deps")
        execute(app, plan)
        return outcome(plan)
    }

    /** Runs every node that can run now. Stops when nothing is ready (all done, or the rest waits for a tap). */
    private fun execute(app: Context, plan: TaskGraph.Plan) {
        var guard = 0
        while (guard < 100) {
            guard++
            val skipped = TaskGraph.propagateSkips(plan)
            if (skipped > 0) ConvKpi.inc("plan_node_skipped", skipped.toLong())
            val allowConfirm = TaskGraph.waitingNode(plan) == null
            val n = TaskGraph.nextReady(plan, allowConfirm) ?: break
            step(app, plan, n)
        }
        TaskQueue.save(plan)
    }

    private fun step(app: Context, plan: TaskGraph.Plan, n: TaskGraph.Node) {
        val task = PlanTask(n.id, n.tool, n.args, n.dependsOn)
        val problem = ToolSpecs.validate(task)
        if (problem != null) {
            fail(plan, n, TaskGraph.label(n.tool) + " nahi chal sakti: " + problem + ".", problem)
            return
        }
        val decision = PolicyGate.check(task)
        if (decision is PolicyGate.Decision.Deny) {
            fail(plan, n, decision.reason, decision.reason)
        } else if (decision is PolicyGate.Decision.Confirm) {
            n.state = TaskGraph.State.WAITING_CONFIRM
            n.readback = decision.readback
            n.waitingSince = System.currentTimeMillis()
            ConvKpi.inc("t2_waiting")
            TaskQueue.save(plan)
        } else {
            perform(app, plan, n, task)
        }
    }

    private fun fail(plan: TaskGraph.Plan, n: TaskGraph.Node, line: String, reason: String) {
        n.state = TaskGraph.State.FAILED
        n.line = line
        n.reason = reason
        ConvKpi.inc("plan_node_failed")
        TaskQueue.save(plan)
    }

    /** Runs one tool. The RUNNING state is on disk before the tool starts. */
    private fun perform(app: Context, plan: TaskGraph.Plan, n: TaskGraph.Node, task: PlanTask) {
        if (!PolicyGate.mayExecute(n.tool, n.confirmed)) {
            // cannot happen through the normal paths: T2 only gets here after a tap. If it ever does, it is counted.
            ConvKpi.inc("t2_blocked")
            fail(plan, n, TaskGraph.label(n.tool) + ": bina confirm ke ye action nahi chalegi.", "confirm nahi mila")
            return
        }
        n.state = TaskGraph.State.RUNNING
        n.attempts = n.attempts + 1
        TaskQueue.save(plan)

        val label = TaskGraph.label(n.tool)
        val r: Tier0.Result = try {
            ToolRunner.execute(app, task)
        } catch (e: Throwable) {
            Tier0.Result(label, label + " nahi chala: " + (e.message ?: e.javaClass.simpleName), ok = false)
        }
        n.line = r.reply
        if (r.ok) {
            val entry = ActivityLog.add(r.title, r.tier, r.reply, r.undo)
            n.undoEntryId = if (r.undo != null) entry.id else null
            n.title = r.title
            n.state = TaskGraph.State.COMPLETED
            ConvKpi.inc("plan_node_ok")
        } else {
            n.state = TaskGraph.State.FAILED
            n.reason = r.reply
            ConvKpi.inc("plan_node_failed")
        }
        TaskQueue.save(plan)
    }

    // ---------- confirmation tap ----------

    /**
     * The user tapped Haan ([yes]) or Nahi on the waiting node. Returns the new state of the whole reply, or null when
     * the plan is gone (the caller then falls back to the old single-action path). After a Haan the node runs, then
     * everything that was waiting for it, up to the next confirmation.
     */
    fun confirm(app: Context, planId: String, nodeId: String, yes: Boolean): PlanExecutor.Outcome? {
        init(app)
        val plan = TaskQueue.get(planId) ?: return null
        val n = plan.node(nodeId) ?: return null
        if (n.state != TaskGraph.State.WAITING_CONFIRM) return outcome(plan)

        val now = System.currentTimeMillis()
        if (!yes) {
            n.state = TaskGraph.State.CANCELLED
            n.reason = "tumne cancel kiya"
            n.line = "Theek hai, cancel kar diya."
            ConvKpi.inc("t2_cancelled")
        } else if (PolicyGate.pendingExpired(n.waitingSince, now)) {
            n.state = TaskGraph.State.CANCELLED
            n.reason = "confirm purana ho gaya"
            n.line = "Ye confirm purana ho gaya (10 minute se zyada). Dobara bolo."
            ConvKpi.inc("t2_expired")
        } else {
            n.confirmed = true
            ConvKpi.inc("t2_confirmed")
            val task = PlanTask(n.id, n.tool, n.args, n.dependsOn)
            val problem = ToolSpecs.validate(task)
            if (problem != null) {
                fail(plan, n, "Ye action ab chal nahi sakti: " + problem, problem)
            } else if (!PolicyGate.canRunAfterConfirm(n.tool)) {
                fail(plan, n, "Ye action tap se confirm nahi hoti.", "tap se confirm nahi hoti")
            } else {
                perform(app, plan, n, task)
            }
        }
        TaskQueue.save(plan)
        execute(app, plan)
        return outcome(plan)
    }

    // ---------- the reply ----------

    /** Turns the current state of [plan] into the chat reply: text, chip, undo ids and the waiting confirmation. */
    fun outcome(plan: TaskGraph.Plan): PlanExecutor.Outcome {
        val lines = TaskGraph.lines(plan)
        var base = if (lines.isEmpty()) "" else ReplyComposer.results(plan.ack, lines)
        val summary = TaskGraph.summaryLine(plan)
        if (summary != null && base.isNotBlank()) base = base + " " + summary

        val waiting = TaskGraph.waitingNode(plan)
        var text = ReplyComposer.withPending(base, waiting?.readback)
        if (text.isBlank()) text = plan.ack.ifBlank { "Kuch nahi hua." }

        val titles = ArrayList<String>()
        val undo = ArrayList<String>()
        for (n in plan.nodes) {
            if (n.state != TaskGraph.State.COMPLETED) continue
            val t = n.title
            if (t != null && !titles.contains(t)) titles.add(t)
            val u = n.undoEntryId
            if (u != null) undo.add(u)
        }
        val chip: String? = when {
            titles.isNotEmpty() -> titles.joinToString(" · ")
            waiting != null -> "Confirm chahiye"
            else -> null
        }
        val pendingJson: String? = if (waiting == null) null else gson.toJson(
            PendingAction(waiting.tool, waiting.args, waiting.readback.orEmpty(), base, plan.id, waiting.id)
        )
        return PlanExecutor.Outcome(text, chip, undo, pendingJson, plan.id)
    }

    // ---------- crash recovery ----------

    /**
     * Called once per process, when the app opens. Looks at every stored plan the previous process left half-done,
     * applies the [TaskGraph.recover] rules, runs what may still run, and writes the result into the chat: the
     * reply message of that plan is updated, or created if the process died before it was saved. Plans that were
     * finished, or that only wait for the user's tap, are left alone.
     */
    fun recover(app: Context) {
        if (recovered) return
        recovered = true
        init(app)
        val now = System.currentTimeMillis()
        for (plan in TaskQueue.all()) {
            val open = TaskGraph.needsRecovery(plan)
            // a plan that ended, but whose reply never reached the chat (the process died in between), is reported too
            val undelivered = !open && now - plan.createdAt <= PolicyGate.PENDING_TTL_MS && !messageExists(plan)
            if (!open && !undelivered) continue

            val stale = now - plan.createdAt > TaskGraph.RESUME_WINDOW_MS
            if (open) {
                TaskGraph.recover(plan, now)
                execute(app, plan)
            }
            ConvKpi.inc("plan_recovered")
            for (n in plan.nodes) {
                if (n.attempts > 1) ConvKpi.inc("plan_resumed_nodes")
                if (n.unsure) ConvKpi.inc("plan_recover_unsure")
            }
            if (plan.debug) {
                val key = when {
                    stale -> "resume_test_late"
                    TaskGraph.debugOk(plan) -> "resume_test_pass"
                    else -> "resume_test_fail"
                }
                ConvKpi.inc(key)
            }
            report(plan, now)
        }
    }

    private fun messageExists(plan: TaskGraph.Plan): Boolean {
        if (plan.chatId.isBlank() || plan.messageId.isBlank()) return true // nothing could be reported anyway
        return Store.messages(plan.chatId).any { it.id == plan.messageId }
    }

    /** Writes the recovered plan's reply into its chat (update in place, or add when the message was never saved). */
    private fun report(plan: TaskGraph.Plan, now: Long) {
        if (plan.chatId.isBlank() || plan.messageId.isBlank()) return
        val meta = Store.chats().firstOrNull { it.id == plan.chatId } ?: return
        val out = outcome(plan)
        val text = "Pichli baar app beech mein band ho gayi thi. " + out.text
        val undo: String? = if (out.undoIds.isEmpty()) null else out.undoIds.joinToString(",")
        val note = "Resume · task engine"

        val list = Store.messages(plan.chatId)
        val i = list.indexOfFirst { it.id == plan.messageId }
        if (i >= 0) {
            list[i] = list[i].copy(text = text, undoId = undo, actions = out.chip, pending = out.pendingJson, note = note)
        } else {
            list.add(
                Message(
                    id = plan.messageId, role = Role.AI, text = text, engine = "tool", note = note,
                    undoId = undo, actions = out.chip, pending = out.pendingJson
                )
            )
        }
        Store.saveMessages(plan.chatId, list)
        meta.snippet = text.replace('\n', ' ').take(80)
        meta.time = now
        Store.updateChat(meta)
    }

    // ---------- debug ----------

    /**
     * Settings > Debug: task engine > "Test: kill-process resume". Writes the state of a plan that a crash interrupted
     * ([TaskGraph.debugSeed]) into the queue; the caller then kills the process. The next app start must recover it.
     */
    fun debugSeed(app: Context, chatId: String) {
        init(app)
        TaskQueue.save(TaskGraph.debugSeed(chatId, UUID.randomUUID().toString(), System.currentTimeMillis()))
    }
}
