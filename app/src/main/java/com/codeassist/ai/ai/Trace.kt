package com.codeassist.ai.ai

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Audit PDF Sec 12.3: a per-turn trace with stage timings, so "why was this reply slow" has an answer.
 * Only timings and status are kept (never the text of a message). Lives in memory, last 50 turns.
 */
object Trace {
    class Turn(
        val time: Long,
        val engine: String,
        val route: String,
        val totalMs: Long,
        val firstTokenMs: Long,
        val loadMs: Long,
        val tokens: Int,
        val tokPerSec: Float,
        val status: String,
        val detail: String
    )

    private const val MAX = 50
    private val turns = ArrayList<Turn>()

    @Synchronized
    fun add(t: Turn) {
        turns.add(t)
        while (turns.size > MAX) turns.removeAt(0)
    }

    @Synchronized
    fun count(): Int = turns.size

    private fun sec(ms: Long): String = String.format(Locale.US, "%.1fs", ms / 1000.0)

    /** Plain text for the debug dialog / copy button. Newest first. */
    @Synchronized
    fun dump(): String {
        if (turns.isEmpty()) return "Abhi koi turn record nahi hua. Ek message bhejo, phir yahan aao."
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.ENGLISH)
        val sb = StringBuilder()
        val brain = turns.filter { it.route == "brain" && it.status == "ok" }.map { it.totalMs }.sorted()
        val fast = turns.filter { it.route == "tier0" }.map { it.totalMs }.sorted()
        sb.append("Turns: ").append(turns.size)
        if (brain.isNotEmpty()) sb.append(" · brain median ").append(sec(brain[brain.size / 2]))
        if (fast.isNotEmpty()) sb.append(" · tier-0 median ").append(sec(fast[fast.size / 2]))
        sb.append("\n(PDF targets: tier-0 \u2264 0.7s, normal reply \u2264 1.3s first audio)\n")
        for (t in turns.asReversed()) {
            sb.append("\n").append(fmt.format(Date(t.time))).append(" · ").append(t.engine)
                .append(" · ").append(t.route).append(" · ").append(t.status).append("\n")
            sb.append("  total ").append(sec(t.totalMs))
            if (t.firstTokenMs > 0) sb.append(" · first token ").append(sec(t.firstTokenMs))
            if (t.loadMs > 0) sb.append(" · model load ").append(sec(t.loadMs))
            if (t.tokens > 0) {
                sb.append(" · ").append(t.tokens).append(" tok")
                if (t.tokPerSec > 0f) sb.append(String.format(Locale.US, " · %.1f tok/s", t.tokPerSec))
            }
            if (t.detail.isNotBlank()) sb.append("\n  ").append(t.detail)
            sb.append("\n")
        }
        return sb.toString().trimEnd()
    }
}
