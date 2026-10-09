package com.codeassist.ai.ai

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Audit Sec 8.4 / 9.6 (Phase 3): a plan is a DAG of task nodes. This file holds the model and every rule that needs
 * no Android class, so it is unit-tested on the JVM:
 *
 *  - validation of the `after` links (unknown id, self link, duplicate id, loop),
 *  - which node runs next (dependencies first, then priority class, then the order the brain wrote them),
 *  - partial failure: when a node fails, only the nodes that depend on it are SKIPPED, the rest keep running,
 *  - what to do with a plan that a process crash left half-done ([recover]).
 *
 * The executor ([TaskEngine]) and the durable store ([TaskQueue]) sit on top of this.
 */
object TaskGraph {
    /** Audit task states (Sec 8.4): terminal ones are COMPLETED / FAILED / CANCELLED / SKIPPED. */
    enum class State { PENDING, RUNNING, WAITING_CONFIRM, COMPLETED, FAILED, CANCELLED, SKIPPED }

    /**
     * Audit Sec 9.6 priority classes. INTERRUPT (stop, correction, safety) is reserved for part 3B; today every tool
     * is NOW (the user waits for the answer or sees the effect at once) or SOON (a thing set for later).
     */
    enum class PClass(val rank: Int) { INTERRUPT(0), NOW(1), SOON(2), LATER(3) }

    /**
     * A plan older than this is not resumed after a crash. A tool that was never started is dropped, not run late:
     * a "10 minute timer" started 40 minutes after it was asked for is worse than none.
     */
    const val RESUME_WINDOW_MS = 2 * 60 * 1000L

    /** One step of a plan. Every field is plain data, so the whole plan can be stored as JSON. */
    class Node(
        val id: String,
        val tool: String,
        val args: Map<String, String>,
        val dependsOn: List<String>,
        val pclass: PClass,
        var state: State = State.PENDING
    ) {
        /** Why it failed / was skipped / cancelled (short Hinglish, no trailing dot). */
        var reason: String? = null

        /** The sentence this node adds to the reply once it has run. */
        var line: String? = null

        /** Short chip title ("Torch", "Alarm") of a node that really did something. */
        var title: String? = null
        var readOnly: Boolean = false
        var undoEntryId: String? = null

        /** Readback sentence of a T2 node that waits for the user's tap. */
        var readback: String? = null

        /** True only after the user tapped Haan. A T2 node never runs without it ([PolicyGate.mayExecute]). */
        var confirmed: Boolean = false
        var attempts: Int = 0

        /** The process died while this node ran and the tool is not idempotent: it may or may not have happened. */
        var unsure: Boolean = false
        var waitingSince: Long = 0L
    }

    class Plan(
        val id: String,
        val chatId: String,
        /** Id of the chat message that carries this plan's reply (so a recovered plan can find or create it). */
        val messageId: String,
        val createdAt: Long,
        val ack: String,
        val nodes: MutableList<Node>
    ) {
        /** True for the plan the "kill-process resume" debug test wrote. */
        var debug: Boolean = false

        fun node(nodeId: String): Node? = nodes.firstOrNull { it.id == nodeId }
    }

    class Counts(
        val done: Int,
        val failed: Int,
        val skipped: Int,
        val cancelled: Int,
        val waiting: Int,
        val open: Int
    )

    // ---------- tool facts ----------

    /** Short Hinglish name of a tool for reply lines. */
    fun label(tool: String): String = when (tool) {
        "time_now" -> "Time"
        "date_today" -> "Date"
        "battery_level" -> "Battery"
        "torch_set" -> "Torch"
        "timer_set" -> "Timer"
        "alarm_set" -> "Alarm"
        "app_open" -> "App"
        "call_dial" -> "Call"
        else -> tool
    }

    /** Read-only answers and things with an instant visible effect are NOW; things set for later are SOON. */
    fun priorityOf(tool: String): PClass {
        val spec = ToolSpecs.find(tool) ?: return PClass.SOON
        if (spec.readOnly) return PClass.NOW
        return when (tool) {
            "torch_set", "app_open", "call_dial" -> PClass.NOW
            else -> PClass.SOON
        }
    }

    fun needsConfirm(tool: String): Boolean = ToolSpecs.find(tool)?.tier == "T2"

    // ---------- building and validating ----------

    fun build(
        id: String, chatId: String, messageId: String, ack: String,
        tasks: List<PlanTask>, now: Long
    ): Plan {
        val nodes = ArrayList<Node>()
        for (t in tasks) {
            val n = Node(t.id, t.tool, t.args, t.dependsOn, priorityOf(t.tool))
            n.readOnly = ToolSpecs.find(t.tool)?.readOnly == true
            nodes.add(n)
        }
        return Plan(id, chatId, messageId, now, ack, nodes)
    }

    /** Null when the `after` links form a proper DAG, otherwise a short English reason (it goes back to the model). */
    fun validate(tasks: List<PlanTask>): String? {
        val ids = HashSet<String>()
        for (t in tasks) {
            if (!ids.add(t.id)) return "duplicate task id '" + t.id + "'"
        }
        for (t in tasks) {
            for (d in t.dependsOn) {
                if (d == t.id) return "task " + t.id + " cannot wait for itself"
                if (!ids.contains(d)) return "task " + t.id + " waits for unknown task '" + d + "'"
            }
        }
        return if (hasLoop(tasks)) "the 'after' links form a loop" else null
    }

    private fun hasLoop(tasks: List<PlanTask>): Boolean {
        val remaining = LinkedHashMap<String, MutableSet<String>>()
        for (t in tasks) remaining[t.id] = t.dependsOn.toMutableSet()
        while (remaining.isNotEmpty()) {
            val free = ArrayList<String>()
            for ((id, deps) in remaining) if (deps.isEmpty()) free.add(id)
            if (free.isEmpty()) return true
            for (id in free) remaining.remove(id)
            for (deps in remaining.values) deps.removeAll(free.toSet())
        }
        return false
    }

    // ---------- running order ----------

    private fun depsCompleted(plan: Plan, n: Node): Boolean {
        for (d in n.dependsOn) {
            val dep = plan.node(d) ?: return false
            if (dep.state != State.COMPLETED) return false
        }
        return true
    }

    /**
     * The next node to run: PENDING, every dependency COMPLETED, lowest priority rank first, and among equals the one
     * the brain wrote first. With [allowConfirm] false a node that needs the user's tap is not offered (only one
     * confirmation can wait at a time, because a chat message has one Haan / Nahi row).
     */
    fun nextReady(plan: Plan, allowConfirm: Boolean): Node? {
        var best: Node? = null
        for (n in plan.nodes) {
            if (n.state != State.PENDING) continue
            if (!allowConfirm && needsConfirm(n.tool)) continue
            if (!depsCompleted(plan, n)) continue
            val b = best
            if (b == null || n.pclass.rank < b.pclass.rank) best = n
        }
        return best
    }

    fun waitingNode(plan: Plan): Node? = plan.nodes.firstOrNull { it.state == State.WAITING_CONFIRM }

    /**
     * Partial failure (audit Sec 9.6): every PENDING node that depends, directly or through others, on a FAILED /
     * CANCELLED / SKIPPED node becomes SKIPPED with the reason. Independent nodes are left alone. Returns how many
     * nodes were newly skipped. A node behind a WAITING_CONFIRM node just stays PENDING.
     */
    fun propagateSkips(plan: Plan): Int {
        var total = 0
        var changed = true
        while (changed) {
            changed = false
            for (n in plan.nodes) {
                if (n.state != State.PENDING) continue
                for (d in n.dependsOn) {
                    val dep = plan.node(d) ?: continue
                    if (dep.state == State.FAILED || dep.state == State.CANCELLED || dep.state == State.SKIPPED) {
                        n.state = State.SKIPPED
                        n.reason = "pehle wala kaam (" + d + ") nahi hua"
                        changed = true
                        total++
                        break
                    }
                }
            }
        }
        return total
    }

    fun counts(plan: Plan): Counts {
        var done = 0
        var failed = 0
        var skipped = 0
        var cancelled = 0
        var waiting = 0
        var open = 0
        for (n in plan.nodes) {
            when (n.state) {
                State.COMPLETED -> done++
                State.FAILED -> failed++
                State.SKIPPED -> skipped++
                State.CANCELLED -> cancelled++
                State.WAITING_CONFIRM -> waiting++
                State.PENDING, State.RUNNING -> open++
            }
        }
        return Counts(done, failed, skipped, cancelled, waiting, open)
    }

    fun isFinished(plan: Plan): Boolean {
        val c = counts(plan)
        return c.waiting == 0 && c.open == 0
    }

    // ---------- crash recovery ----------

    /**
     * True when a process crash left work behind that the app can still act on: a node that was RUNNING, or a PENDING
     * node that is ready. A PENDING node that only waits for the user's tap is a normal, delivered state, not a crash.
     */
    fun needsRecovery(plan: Plan): Boolean {
        for (n in plan.nodes) if (n.state == State.RUNNING) return true
        return nextReady(plan, waitingNode(plan) == null) != null
    }

    /**
     * Rules for a plan found after a restart (audit Sec 9.6, "Persistence"):
     *  - RUNNING + idempotent tool + plan still fresh -> PENDING again (it is simply run once more),
     *  - RUNNING + idempotent tool + plan stale -> CANCELLED,
     *  - RUNNING + tool that is not idempotent -> FAILED and marked [Node.unsure]: never run twice, say so honestly,
     *  - PENDING in a stale plan -> CANCELLED (dropped, not run late),
     *  - dependents of anything that ended badly -> SKIPPED.
     * Returns true when anything changed.
     */
    fun recover(plan: Plan, now: Long): Boolean {
        var changed = false
        val stale = now - plan.createdAt > RESUME_WINDOW_MS
        for (n in plan.nodes) {
            if (n.state == State.RUNNING) {
                val idempotent = ToolSpecs.find(n.tool)?.idempotent == true
                if (idempotent && !stale) {
                    n.state = State.PENDING
                    n.reason = "app beech mein band hui, dobara chalaya"
                } else if (idempotent) {
                    n.state = State.CANCELLED
                    n.reason = "app band hui aur bahut der ho gayi"
                    n.line = label(n.tool) + ": app band hui thi aur bahut der ho gayi, isliye dobara nahi chalaya."
                } else {
                    n.state = State.FAILED
                    n.unsure = true
                    n.reason = "app beech mein band ho gayi, pata nahi ye hua ya nahi"
                    n.line = label(n.tool) + ": app beech mein band ho gayi, pata nahi ye hua ya nahi. " +
                        "Phone mein ek baar check kar lo, main ise dobara nahi chalaunga."
                }
                changed = true
            } else if (n.state == State.PENDING && stale) {
                n.state = State.CANCELLED
                n.reason = "bahut der ho gayi"
                n.line = label(n.tool) + ": bahut der ho gayi, isliye nahi chalaya."
                changed = true
            }
        }
        if (propagateSkips(plan) > 0) changed = true
        return changed
    }

    // ---------- reply ----------

    /** One reply line per node that has an outcome. Nodes still waiting or pending say nothing yet. */
    fun lines(plan: Plan): List<ReplyComposer.Line> {
        val out = ArrayList<ReplyComposer.Line>()
        for (n in plan.nodes) {
            when (n.state) {
                State.COMPLETED -> {
                    val text = n.line
                    if (!text.isNullOrBlank()) out.add(ReplyComposer.Line(text, true, n.readOnly))
                }
                State.FAILED -> out.add(ReplyComposer.Line(n.line ?: (label(n.tool) + " nahi hua."), false, false))
                State.CANCELLED -> out.add(ReplyComposer.Line(n.line ?: (label(n.tool) + " cancel ho gaya."), false, false))
                State.SKIPPED -> out.add(
                    ReplyComposer.Line(
                        label(n.tool) + " chhod diya, kyunki " + (n.reason ?: "pehla kaam nahi hua") + ".",
                        false, false
                    )
                )
                else -> Unit
            }
        }
        return out
    }

    /** The one-line "2 kaam ho gaye, 1 nahi hua" summary, or null when nothing went wrong or the plan had one task. */
    fun summaryLine(plan: Plan): String? {
        val c = counts(plan)
        return ReplyComposer.partialSummary(c.done, c.failed, c.skipped + c.cancelled)
    }

    // ---------- debug (Settings > Debug: task engine) ----------

    /** Multi-line description of a plan for the debug screen. Times use the phone's default time zone. */
    fun describe(plan: Plan): String {
        val fmt = SimpleDateFormat("d MMM HH:mm:ss", Locale.ENGLISH)
        val sb = StringBuilder()
        sb.append(fmt.format(Date(plan.createdAt))).append(" · plan ").append(plan.id.take(6))
        if (plan.debug) sb.append(" · TEST")
        sb.append(" · ").append(plan.nodes.size).append(" kaam\n")
        for (n in plan.nodes) {
            sb.append("  ").append(n.id).append(' ').append(n.tool).append(' ').append(n.state.name)
            if (n.dependsOn.isNotEmpty()) sb.append(" (after ").append(n.dependsOn.joinToString(",")).append(')')
            if (n.attempts > 1) sb.append(" x").append(n.attempts)
            if (n.unsure) sb.append(" [pata nahi]")
            val why = n.reason
            if (!why.isNullOrBlank() && (n.state == State.FAILED || n.state == State.SKIPPED || n.state == State.CANCELLED)) {
                sb.append(" - ").append(why)
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    /**
     * The plan the kill-process test writes: t1 already done, t2 (a timer: NOT idempotent) and t5 (the date: idempotent)
     * were running when the process died, t3 never started, t4 waits for t2.
     * Expected after the restart ([debugOk]): t1 done, t2 FAILED + unsure (never run twice), t3 done, t4 SKIPPED,
     * t5 done after a second attempt.
     */
    fun debugSeed(chatId: String, messageId: String, now: Long): Plan {
        val tasks = listOf(
            PlanTask("t1", "time_now", emptyMap()),
            PlanTask("t2", "timer_set", mapOf("seconds" to "60")),
            PlanTask("t3", "battery_level", emptyMap()),
            PlanTask("t4", "torch_set", mapOf("on" to "false"), listOf("t2")),
            PlanTask("t5", "date_today", emptyMap())
        )
        val plan = build("dbg-" + now, chatId, messageId, "", tasks, now)
        plan.debug = true
        val t1 = plan.node("t1")
        if (t1 != null) {
            t1.state = State.COMPLETED
            t1.attempts = 1
            t1.title = "Time"
            t1.line = "Time pehle hi bata diya tha."
        }
        val t2 = plan.node("t2")
        if (t2 != null) {
            t2.state = State.RUNNING
            t2.attempts = 1
        }
        val t5 = plan.node("t5")
        if (t5 != null) {
            t5.state = State.RUNNING
            t5.attempts = 1
        }
        return plan
    }

    /** True when a recovered [debugSeed] plan ended in exactly the expected states. */
    fun debugOk(plan: Plan): Boolean {
        fun state(id: String): State? = plan.node(id)?.state
        val t2 = plan.node("t2")
        val t5 = plan.node("t5")
        return state("t1") == State.COMPLETED &&
            state("t2") == State.FAILED && t2 != null && t2.unsure &&
            state("t3") == State.COMPLETED &&
            state("t4") == State.SKIPPED &&
            state("t5") == State.COMPLETED && t5 != null && t5.attempts == 2
    }
}
