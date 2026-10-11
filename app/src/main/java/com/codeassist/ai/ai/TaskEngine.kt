package com.codeassist.ai.ai

import android.content.Context
import com.codeassist.ai.data.Message
import com.codeassist.ai.data.Role
import com.codeassist.ai.data.Store
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Tools still run one after another on the calling (main) thread, like Tier-0 did: the Android calls behind them (Clock
 * intents, torch) are not meant for a worker thread. Phase 3B-2 adds the first SLOW tool (weather, a network call): it
 * runs on a worker thread under [StepTimeout] (8 s), after every quick step that is ready, while the main thread stays
 * free. The user can press Stop at any time ([stop]); the progress line ("Kaam 2/3 chal raha hai") goes to a [Monitor].
 * The audit's "parallelism limit ~3" is still not built: there is only one slow tool and one step is waited for at a time.
 */
object TaskEngine {
    private val gson = Gson()
    private var recovered = false

    /** Who wants to know how a plan is going (the chat screen's run). Both calls may come from any thread. */
    interface Monitor {
        /** The plan was created and saved: [planId] is what [stop] needs. */
        fun onPlan(planId: String)

        /** A short line for the screen, e.g. "Kaam 2/3 chal raha hai: Mausam". */
        fun onProgress(line: String)
    }

    private fun init(app: Context) {
        Store.init(app)
        ActivityLog.init(app)
        ConvKpi.init(app)
        TaskQueue.init(app)
    }

    // ---------- run ----------

    /** Called by [PlanExecutor.run] with a plan the brain proposed and the code has already validated. */
    suspend fun run(
        app: Context, chatId: String, messageId: String, ack: String, tasks: List<PlanTask>,
        monitor: Monitor? = null
    ): PlanExecutor.Outcome {
        init(app)
        val plan = TaskGraph.build(UUID.randomUUID().toString(), chatId, messageId, ack, tasks, System.currentTimeMillis())
        TaskQueue.save(plan)
        ConvKpi.inc("plan_runs")
        if (tasks.size > 1) ConvKpi.inc("plan_multi")
        if (tasks.any { it.dependsOn.isNotEmpty() }) ConvKpi.inc("plan_with_deps")
        if (tasks.any { TaskGraph.isSlow(it.tool) }) ConvKpi.inc("plan_with_slow")
        monitor?.onPlan(plan.id)
        executeAsync(app, plan, monitor)
        return outcome(plan)
    }

    /**
     * Runs every node that can run now, quick ones first. A slow node is waited for on a worker thread. Stops when nothing
     * is ready (all done, or the rest waits for a tap) or when the user pressed Stop.
     */
    private suspend fun executeAsync(app: Context, plan: TaskGraph.Plan, monitor: Monitor?) {
        var guard = 0
        while (guard < 100 && !plan.stopped) {
            guard++
            val skipped = TaskGraph.propagateSkips(plan)
            if (skipped > 0) ConvKpi.inc("plan_node_skipped", skipped.toLong())
            val allowConfirm = TaskGraph.waitingNode(plan) == null
            val n = TaskGraph.nextReady(plan, allowConfirm) ?: break
            val task = prepare(plan, n) ?: continue
            if (TaskGraph.isSlow(n.tool)) performSlow(app, plan, n, task, monitor) else perform(app, plan, n, task)
        }
        TaskQueue.save(plan)
    }

    /**
     * The same loop for the callers that run on the main thread and must not wait for the network (the user's tap, crash
     * recovery, a plan edit). A slow node never gets here ready: [ToolSpecs.check] keeps it from waiting for a tap and
     * [TaskGraph.recover] cancels it after a crash. If one still shows up it is cancelled, not run.
     */
    private fun executeSync(app: Context, plan: TaskGraph.Plan) {
        var guard = 0
        while (guard < 100 && !plan.stopped) {
            guard++
            val skipped = TaskGraph.propagateSkips(plan)
            if (skipped > 0) ConvKpi.inc("plan_node_skipped", skipped.toLong())
            val allowConfirm = TaskGraph.waitingNode(plan) == null
            val n = TaskGraph.nextReady(plan, allowConfirm) ?: break
            if (TaskGraph.isSlow(n.tool)) {
                n.state = TaskGraph.State.CANCELLED
                n.reason = "network wala kaam yahan nahi chal sakta"
                n.line = TaskGraph.label(n.tool) + ": ye abhi nahi chala sakta, dobara bolo."
                ConvKpi.inc("plan_slow_dropped")
                TaskQueue.save(plan)
                continue
            }
            val task = prepare(plan, n) ?: continue
            perform(app, plan, n, task)
        }
        TaskQueue.save(plan)
    }

    /**
     * Validates [n] and asks the policy gate. Returns the task to run, or null when the node was already dealt with
     * (FAILED, or WAITING_CONFIRM for a T2 step).
     */
    private fun prepare(plan: TaskGraph.Plan, n: TaskGraph.Node): PlanTask? {
        val task = PlanTask(n.id, n.tool, n.args, n.dependsOn)
        val problem = ToolSpecs.validate(task)
        if (problem != null) {
            fail(plan, n, TaskGraph.label(n.tool) + " nahi chal sakti: " + problem + ".", problem)
            return null
        }
        val decision = PolicyGate.check(task)
        if (decision is PolicyGate.Decision.Deny) {
            fail(plan, n, decision.reason, decision.reason)
            return null
        }
        if (decision is PolicyGate.Decision.Confirm) {
            n.state = TaskGraph.State.WAITING_CONFIRM
            n.readback = decision.readback
            n.waitingSince = System.currentTimeMillis()
            ConvKpi.inc("t2_waiting")
            TaskQueue.save(plan)
            return null
        }
        return task
    }

    private fun fail(plan: TaskGraph.Plan, n: TaskGraph.Node, line: String, reason: String) {
        n.state = TaskGraph.State.FAILED
        n.line = line
        n.reason = reason
        ConvKpi.inc("plan_node_failed")
        TaskQueue.save(plan)
    }

    /** The hard gate and the RUNNING mark. Returns false when the node was refused (it is FAILED then). */
    private fun begin(plan: TaskGraph.Plan, n: TaskGraph.Node): Boolean {
        if (!PolicyGate.mayExecute(n.tool, n.confirmed)) {
            // cannot happen through the normal paths: T2 only gets here after a tap. If it ever does, it is counted.
            ConvKpi.inc("t2_blocked")
            fail(plan, n, TaskGraph.label(n.tool) + ": bina confirm ke ye action nahi chalegi.", "confirm nahi mila")
            return false
        }
        n.state = TaskGraph.State.RUNNING
        n.attempts = n.attempts + 1
        TaskQueue.save(plan)
        return true
    }

    /** Writes the result of a tool into its node. */
    private fun finish(plan: TaskGraph.Plan, n: TaskGraph.Node, r: Tier0.Result) {
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

    /** Runs one quick tool on the calling thread. The RUNNING state is on disk before the tool starts. */
    private fun perform(app: Context, plan: TaskGraph.Plan, n: TaskGraph.Node, task: PlanTask) {
        if (!begin(plan, n)) return
        val label = TaskGraph.label(n.tool)
        val r: Tier0.Result = try {
            ToolRunner.execute(app, task)
        } catch (e: Throwable) {
            Tier0.Result(label, label + " nahi chala: " + (e.message ?: e.javaClass.simpleName), ok = false)
        }
        finish(plan, n, r)
    }

    /**
     * Runs one slow (network) tool on a worker thread with a hard time limit. While it waits: the progress line, one or two
     * "der se aa raha hai" lines ([PlanProgress]), and a look at Stop every 100 ms. If Stop came, the node is already
     * CANCELLED by [stop] and whatever the tool returns later is dropped.
     */
    private suspend fun performSlow(
        app: Context, plan: TaskGraph.Plan, n: TaskGraph.Node, task: PlanTask, monitor: Monitor?
    ) {
        if (!begin(plan, n)) return
        monitor?.onProgress(PlanProgress.line(plan, n))
        var sent = 0
        var lastNudgeAt = 0L
        val label = TaskGraph.label(n.tool)
        val out = withContext(Dispatchers.IO) {
            StepTimeout.call(
                timeoutMs = TaskGraph.STEP_TIMEOUT_MS,
                stopped = { plan.stopped },
                onTick = { waited ->
                    if (monitor != null && PlanProgress.shouldNudge(waited, sent, waited - lastNudgeAt)) {
                        sent++
                        lastNudgeAt = waited
                        monitor.onProgress(PlanProgress.slowLine(n, waited))
                    }
                },
                block = { ToolRunner.execute(app, task) }
            )
        }
        if (sent > 0) ConvKpi.inc("progress_nudges", sent.toLong())
        if (plan.stopped || n.state != TaskGraph.State.RUNNING) {
            ConvKpi.inc("plan_late_dropped")
            return
        }
        when (out) {
            is StepTimeout.Outcome.Done<*> -> finish(plan, n, out.value as Tier0.Result)
            is StepTimeout.Outcome.Crashed -> {
                val e = out.error
                fail(plan, n, label + " nahi chala: " + (e.message ?: e.javaClass.simpleName), "tool crash")
            }
            StepTimeout.Outcome.TimedOut -> {
                ConvKpi.inc("step_timeouts")
                n.state = TaskGraph.State.FAILED
                n.reason = (TaskGraph.STEP_TIMEOUT_MS / 1000).toString() + " second mein jawab nahi aaya"
                n.line = label + ": " + (TaskGraph.STEP_TIMEOUT_MS / 1000) + " second mein jawab nahi aaya, isliye chhod diya."
                ConvKpi.inc("plan_node_failed")
                TaskQueue.save(plan)
            }
            StepTimeout.Outcome.Stopped -> {
                // stop() normally got here first; this only covers a Stop that arrived without it
                TaskGraph.stop(plan, System.currentTimeMillis())
                TaskQueue.save(plan)
            }
        }
    }

    // ---------- stop (3B-2) ----------

    /**
     * The user pressed Stop on a running reply: every step that has not finished is cancelled ([TaskGraph.stop], INTERRUPT
     * class). Finished steps stay, so the reply still says what already happened and keeps its Undo chip. Returns the
     * new state of the reply, or null when the plan is gone. Runs on the main thread, like every state change.
     */
    fun stop(app: Context, planId: String): PlanExecutor.Outcome? {
        init(app)
        val plan = TaskQueue.get(planId) ?: return null
        val cancelled = TaskGraph.stop(plan, System.currentTimeMillis())
        ConvKpi.inc("interrupt_stop")
        if (cancelled > 0) ConvKpi.inc("interrupt_nodes_cancelled", cancelled.toLong())
        TaskQueue.save(plan)
        return outcome(plan)
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
        executeSync(app, plan)
        return outcome(plan)
    }

    // ---------- the reply ----------

    /** Turns the current state of [plan] into the chat reply: text, chip, undo ids and the waiting confirmation. */
    fun outcome(plan: TaskGraph.Plan): PlanExecutor.Outcome {
        val lines = TaskGraph.lines(plan)
        var base = if (lines.isEmpty()) "" else ReplyComposer.results(plan.ack, lines)
        // after a Stop the reply says so in one sentence instead of the "x kaam chhod diye" summary
        val summary = if (plan.stopped) null else TaskGraph.summaryLine(plan)
        if (summary != null && base.isNotBlank()) base = base + " " + summary
        if (plan.stopped) base = ("Theek hai, ruk gaya. " + base).trim()

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

    // ---------- plan edit (3C) ----------

    /** What [applyEdit] did: the sentence for the user, and whether the plan changed. */
    class EditResult(val reply: String, val changed: Boolean)

    /**
     * "Petrol wala kaam hata do" for a step of a stored plan ([PlanEdit.pick] found it). A step that has not run yet is
     * cancelled and its dependents are skipped; a step that ran is taken back through its Undo when it has one. After the
     * change the plan goes on with whatever is ready (a second confirmation may now appear), and the plan's own reply in
     * the chat is rewritten in place so the old Haan / Nahi row cannot be tapped any more.
     */
    fun applyEdit(app: Context, planId: String, nodeId: String): EditResult? {
        init(app)
        val plan = TaskQueue.get(planId) ?: return null
        val n = plan.node(nodeId) ?: return null
        val name = TaskGraph.label(n.tool)
        ConvKpi.inc("plan_edit_runs")
        val reply: String
        when (PlanEdit.actionFor(n)) {
            PlanEdit.Action.CANCEL_NODE -> {
                n.state = TaskGraph.State.CANCELLED
                n.reason = "tumne hata diya"
                n.line = name + ": tumne hata diya, isliye nahi chalaya."
                n.readback = null
                val skipped = TaskGraph.propagateSkips(plan)
                ConvKpi.inc("plan_edit_removed")
                if (skipped > 0) ConvKpi.inc("plan_node_skipped", skipped.toLong())
                TaskQueue.save(plan)
                executeSync(app, plan)
                reply = "Theek hai, $name wala kaam hata diya." +
                    (if (skipped > 0) " Uske baad ke $skipped kaam bhi chhod diye, kyunki wo isi par tike the." else "")
            }
            PlanEdit.Action.UNDO_DONE -> {
                val id = n.undoEntryId.orEmpty()
                val said = ActivityLog.undo(app, id)
                val undone = ActivityLog.get(id)?.undone == true
                if (!undone) {
                    return EditResult("$name wala kaam wapas nahi ho paya: " + said.trim(), false)
                }
                n.state = TaskGraph.State.CANCELLED
                n.reason = "tumne hata diya, wapas kiya"
                n.line = name + ": tumne hata diya, wapas kar diya."
                n.undoEntryId = null
                val skipped = TaskGraph.propagateSkips(plan)
                ConvKpi.inc("plan_edit_undone")
                if (skipped > 0) ConvKpi.inc("plan_node_skipped", skipped.toLong())
                TaskQueue.save(plan)
                executeSync(app, plan)
                reply = "Theek hai, $name wala kaam wapas kar diya (" + said.trim() + ")"
            }
            PlanEdit.Action.NOTHING_TO_UNDO -> {
                reply = if (n.readOnly) {
                    "$name sirf ek jawab tha, ise hatane ko kuch nahi hai."
                } else {
                    "$name ho chuka hai aur ise wapas nahi kiya ja sakta. Phone mein khud check kar lo."
                }
                return EditResult(reply, false)
            }
            PlanEdit.Action.NOT_RUN -> return EditResult("$name wala kaam pehle hi nahi hua, hatane ko kuch nahi.", false)
            PlanEdit.Action.BUSY -> return EditResult("$name abhi chal raha hai. Thodi der baad bolo, ya Stop dabao.", false)
        }
        refreshMessage(plan)
        return EditResult(reply, true)
    }

    /** Rewrites the chat message that carries [plan]'s reply (text, chip, undo ids, Haan / Nahi row). Never creates one. */
    private fun refreshMessage(plan: TaskGraph.Plan) {
        if (plan.chatId.isBlank() || plan.messageId.isBlank()) return
        val out = outcome(plan)
        val list = Store.messages(plan.chatId)
        val i = list.indexOfFirst { it.id == plan.messageId }
        if (i < 0) return
        val undo: String? = if (out.undoIds.isEmpty()) null else out.undoIds.joinToString(",")
        list[i] = list[i].copy(text = out.text, undoId = undo, actions = out.chip, pending = out.pendingJson)
        Store.saveMessages(plan.chatId, list)
    }

    /** The newest stored plan whose reply is the chat message [messageId], or null. */
    fun planOfMessage(app: Context, messageId: String): TaskGraph.Plan? {
        init(app)
        return TaskQueue.all().firstOrNull { it.messageId == messageId }
    }

    /** Settings > Debug: task engine > "Plans clear". Returns how many plans were removed. Plans that still wait for a tap go too. */
    fun clearPlans(app: Context): Int {
        init(app)
        val n = TaskQueue.count()
        TaskQueue.clear()
        ConvKpi.inc("plans_cleared", n.toLong())
        return n
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
                executeSync(app, plan)
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
