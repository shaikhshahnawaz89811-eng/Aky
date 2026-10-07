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

    fun withPending(base: String, readback: String?): String {
        if (readback == null) return base
        val q = readback.trim() + " " + CONFIRM_HINT
        return if (base.isBlank()) q else base.trim() + " " + q
    }
}
