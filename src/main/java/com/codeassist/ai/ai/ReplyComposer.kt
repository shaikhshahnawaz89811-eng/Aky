package com.codeassist.ai.ai

/**
 * Audit Sec 9.7: one merged reply per turn. Read-only answers first, actions after them (each already a short
 * sentence), errors last. A pending T2 question goes at the very end. The model's own words around the tool
 * calls ("Theek hai, abhi karta hoon") are only used when nothing else was produced.
 */
object ReplyComposer {
    class Line(val text: String, val ok: Boolean, val readOnly: Boolean)

    const val CONFIRM_HINT = "Confirm karne ke liye neeche Haan dabao."

    /** The part of the reply that exists before any pending confirmation is added. */
    fun results(ack: String, lines: List<Line>): String {
        val parts = ArrayList<String>()
        for (l in lines) if (l.ok && l.readOnly) parts.add(l.text)
        for (l in lines) if (l.ok && !l.readOnly) parts.add(l.text)
        for (l in lines) if (!l.ok) parts.add(l.text)
        return if (parts.isEmpty()) ack.trim() else parts.joinToString(" ")
    }

    /**
     * Audit Sec 9.6, partial failure: when a plan of 2 or more tasks did not fully work, one closing line says how it
     * went and what the user can do next. [skipped] counts skipped and cancelled tasks. Null when nothing went wrong or
     * the plan had a single task (that task's own line already says everything).
     */
    fun partialSummary(done: Int, failed: Int, skipped: Int): String? {
        if (failed + skipped == 0) return null
        if (done + failed + skipped < 2) return null
        val sb = StringBuilder()
        if (done == 1) sb.append("1 kaam ho gaya")
        else if (done > 1) sb.append(done).append(" kaam ho gaye")
        else sb.append("Koi kaam nahi hua")
        // with nothing done, "koi kaam nahi hua" already covers the failed ones
        if (failed > 0 && done > 0) sb.append(", ").append(failed).append(" nahi hua")
        if (skipped > 0) sb.append(", ").append(skipped).append(" chhod diya")
        sb.append(". Chaho toh nahi hue kaam dobara bolo.")
        return sb.toString()
    }

    fun withPending(base: String, readback: String?): String {
        if (readback == null) return base
        val q = readback.trim() + " " + CONFIRM_HINT
        return if (base.isBlank()) q else base.trim() + " " + q
    }
}
