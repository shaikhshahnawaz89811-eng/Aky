package com.codeassist.ai.ai

/**
 * Audit Sec 11.1 / 11.2: the policy gate. The brain only proposes; this decides, in plain deterministic code.
 *
 *   T0 read-only / no side effect  -> run
 *   T1 reversible on the device    -> run + spoken ack + Undo
 *   T2 affects other people / apps -> read back, run only after the user's tap (never by voice alone)
 *   T3 financial / irreversible    -> never from here
 *
 * The confidence axis of the audit matrix is not implemented yet: the gate only ever asks MORE, never less.
 */
object PolicyGate {
    /** A tapped confirmation older than this is refused: the situation may have changed. */
    const val PENDING_TTL_MS = 10 * 60 * 1000L

    sealed class Decision {
        object Run : Decision()
        object RunWithUndo : Decision()
        class Confirm(val readback: String) : Decision()
        class Deny(val reason: String) : Decision()
    }

    fun check(task: PlanTask): Decision {
        val spec = ToolSpecs.find(task.tool) ?: return Decision.Deny("Ye action mujhe aati nahi.")
        return when (spec.tier) {
            "T0" -> Decision.Run
            "T1" -> Decision.RunWithUndo
            "T2" -> Decision.Confirm(readback(task))
            else -> Decision.Deny("Ye action phone par khud karni hogi, main ise nahi kar sakta.")
        }
    }

    fun readback(task: PlanTask): String = when (task.tool) {
        "call_dial" -> "Dialer mein " + ToolSpecs.cleanNumber(task.args["number"].orEmpty()) + " khol dun?"
        else -> "Ye action karun?"
    }

    /** Only a T2 tool may be started by a confirmation tap. */
    fun canRunAfterConfirm(tool: String): Boolean = ToolSpecs.find(tool)?.tier == "T2"

    fun pendingExpired(createdAt: Long, now: Long): Boolean = now - createdAt > PENDING_TTL_MS
}
