package com.codeassist.ai.ai

/**
 * Audit Sec 9.7 (Phase 3B-2): ONE progress monitor, driven by real task state, never by a guess. The text is short and
 * shown on the screen while the user waits ("Kaam 2/3 chal raha hai: Mausam"); it is not spoken (see BUILD_NOTES).
 *
 * Rules from the audit: it speaks only when the user is waiting for a result and the step is slower than expected,
 * at least [NUDGE_GAP_MS] between two updates, at most [NUDGE_MAX] per step, and a quick step gets no filler at all.
 * Pure Kotlin, JVM-tested.
 */
object PlanProgress {
    /** A slow step gets its first "der lag rahi hai" line after this long. */
    const val NUDGE_FIRST_MS = 4000L
    const val NUDGE_GAP_MS = 6000L
    const val NUDGE_MAX = 2

    private fun finished(s: TaskGraph.State): Boolean =
        s == TaskGraph.State.COMPLETED || s == TaskGraph.State.FAILED || s == TaskGraph.State.SKIPPED ||
            s == TaskGraph.State.CANCELLED

    /** "Kaam 2/3 chal raha hai: Mausam" for a plan of 2 or more steps, "Mausam chal raha hai" for a single step. */
    fun line(plan: TaskGraph.Plan, n: TaskGraph.Node): String {
        val total = plan.nodes.size
        val name = TaskGraph.label(n.tool)
        if (total < 2) return name + " chal raha hai"
        val position = minOf(total, plan.nodes.count { finished(it.state) } + 1)
        return "Kaam $position/$total chal raha hai: $name"
    }

    /** True when a slow step that has waited [elapsedMs] should get another "der lag rahi hai" line. */
    fun shouldNudge(elapsedMs: Long, sent: Int, sinceLastMs: Long): Boolean {
        if (sent >= NUDGE_MAX) return false
        if (elapsedMs < NUDGE_FIRST_MS) return false
        return sent == 0 || sinceLastMs >= NUDGE_GAP_MS
    }

    /** The real reason is the network, so the line says so (audit: "reason codes come from the transport, not a guess"). */
    fun slowLine(n: TaskGraph.Node, elapsedMs: Long): String =
        TaskGraph.label(n.tool) + " ka jawab der se aa raha hai (" + (elapsedMs / 1000) + " s). Network slow ho sakta hai."
}
