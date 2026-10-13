package com.codeassist.ai.ai

import java.io.IOException
import java.io.InterruptedIOException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** B2 routing policy. Only network/transient Gemini failures may switch this turn to local Qwen. */
object FallbackPolicy {
    enum class Level(val label: String, val key: String) {
        L1_DEGRADED("L1 degraded", "l1"),
        L2_OFFLINE("L2 offline", "l2")
    }

    /** Unknown host / no route means offline; a timeout or other I/O failure is degraded. */
    fun forNetworkError(error: Throwable): Level? {
        var cause = error
        val seen = HashSet<Throwable>()
        while (cause.cause != null && seen.add(cause)) cause = cause.cause!!
        return when (cause) {
            is UnknownHostException, is NoRouteToHostException -> Level.L2_OFFLINE
            is SocketTimeoutException, is InterruptedIOException, is IOException -> Level.L1_DEGRADED
            else -> null
        }
    }

    /** HTTP errors eligible for local fallback; auth, request, model, and safety errors are not. */
    fun forHttpStatus(status: Int): Level? =
        if (status == 408 || status == 429 || status in 500..599) Level.L1_DEGRADED else null

    // ---------- T2: heavy plan questions (test chat 13 Oct 2026) ----------

    private val planNouns = setOf(
        "plan", "roadmap", "blueprint", "architecture", "prd", "spec", "specs", "specification", "strategy", "milestones", "prompt"
    )
    private val buildWords = setOf(
        "banao", "bana", "banaao", "banado", "likho", "likh", "tayyar", "taiyaar", "chahiye", "dedo", "batao", "bata", "btao",
        "do", "de", "dena", "dijiye",
        "create", "make", "write", "draft", "generate", "prepare", "design", "build", "give", "suggest", "need"
    )
    private val heavyWords = setOf(
        "poora", "pura", "complete", "full", "detailed", "detail", "step", "phase", "phases", "wise", "saare", "sabhi",
        "scratch", "roadmap", "blueprint", "architecture", "milestones"
    )
    private val wordSplit = Regex("[^\\p{L}\\p{N}]+")
    private const val HEAVY_MIN_WORDS = 6
    private const val HEAVY_LONG_WORDS = 12

    /** Appended to the caption of a reply that Gemini wrote for a heavy plan question while the phone brain was chosen. */
    const val HEAVY_NOTE = " · bhaari plan sawaal"

    /** Appended to the caption when Gemini could not be reached for such a question and Gemma answered. */
    const val HEAVY_FAILED_NOTE = " · bhaari plan sawaal: Gemini nahi mila"

    /**
     * True for a request to WRITE a plan / roadmap / spec / prompt for a project: long, structured, and done badly by a small
     * phone model. A word list on purpose (a stop-gap until the plan builder of phase T3): a plan noun, a "make it" word, and
     * either a long message or a word such as "poora", "complete", "detailed", "phase".
     */
    fun isHeavyPlan(text: String): Boolean {
        val ws = text.lowercase().split(wordSplit).filter { it.isNotEmpty() }
        if (ws.size < HEAVY_MIN_WORDS) return false
        if (ws.none { it in planNouns }) return false
        if (ws.none { it in buildWords }) return false
        return ws.size >= HEAVY_LONG_WORDS || ws.any { it in heavyWords }
    }

    /**
     * A heavy plan question goes to Gemini when the user picked the phone brain, a Gemini key is saved and the switch in
     * Settings is on. Every other case stays on the chosen brain.
     */
    fun escalateHeavyPlan(text: String, provider: String, hasGeminiKey: Boolean, enabled: Boolean): Boolean =
        enabled && hasGeminiKey && provider == "local" && isHeavyPlan(text)

    fun transitionLine(level: Level): String = when (level) {
        Level.L1_DEGRADED -> "Gemini slow ya busy hai; is turn ke liye Gemma 4 fallback try kar raha hoon."
        Level.L2_OFFLINE -> "Internet nahi mila; is turn ke liye phone par Gemma 4 fallback try kar raha hoon."
    }

    fun successLine(level: Level): String = when (level) {
        Level.L1_DEGRADED ->
            "Gemini slow ya busy tha. Ye jawab phone par Gemma 4 se bana; agli turn mein Gemini phir try hoga."
        Level.L2_OFFLINE ->
            "Internet nahi mila. Ye jawab phone par Gemma 4 se bana; connection aane par Gemini agli turn mein phir try hoga."
    }
}
