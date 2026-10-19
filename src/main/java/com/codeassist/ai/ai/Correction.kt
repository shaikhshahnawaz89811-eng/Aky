package com.codeassist.ai.ai

/**
 * Audit Sec 13, Phase 3 "corrections": the user fixes the thing the assistant just did. "Nahi, 9 baje" right after an
 * alarm means "make that alarm 9 o'clock", not a new chat question for the brain.
 *
 * This object is only the grammar (pure Kotlin, JVM-testable): it never touches the phone. [CorrectionRunner] does
 * the phone part. Rules, all deterministic ("LLM proposes, app disposes" - here the LLM is not even asked):
 *
 *  - A correction needs a RECENT reply that created exactly one alarm or one timer ([kindOf]). Any other situation
 *    returns null and the message goes on its normal way (Tier-0, then the brain).
 *  - The message must be short (10 words at most) and carry a correction cue ("nahi", "galat", "wait" ...) or be a
 *    plain cancel phrase ("rehne do", "cancel karo").
 *  - The new value is read from the words AFTER the last cue: "nahi 9 baje" and "8 baje nahi, 9 baje kar do" both give 9.
 *  - An Alarm reply is only corrected with a clock time, a Timer reply only with a length of time. A mismatch is not
 *    guessed: it returns null.
 */
object Correction {
    /** How long after an action a correction is still understood. Older replies are left to the brain. */
    const val WINDOW_MS = 30 * 60 * 1000L

    private const val MAX_WORDS = 10

    sealed class Fix {
        /** Change the last alarm to this clock time ([hour] is 24-hour). */
        class Alarm(val hour: Int, val minute: Int, val tomorrow: Boolean) : Fix()

        /** Change the last timer to this length. */
        class Timer(val seconds: Int) : Fix()

        /** Take the last alarm / timer back, set nothing new. */
        object Cancel : Fix()
    }

    private val cues = setOf(
        "nahi", "nahin", "nai", "no", "nope", "galat", "oops", "sorry", "wait", "ruko", "actually", "matlab", "arre", "are"
    )

    private val cancelFillers = setOf("nahi", "nahin", "nai", "no", "arre", "are", "ruko", "wait", "sorry", "please", "plz")
    private val cancelWords = setOf(
        "rehne do", "rehne de", "rahne do", "chhod do", "chod do", "cancel", "cancel karo", "cancel kar do",
        "hata do", "hata de", "hatao", "mat lagao", "mat karo", "wapas karo", "undo", "undo karo", "delete karo"
    )
    private val cancelObjects = setOf("wo", "woh", "ye", "yeh", "alarm", "timer", "usko", "isko")

    /**
     * The title of the last reply when it is a correctable alarm / timer, else null. [engine] and [note] describe a
     * Tier-0 reply ("tool", "Alarm"); [actions] is the chip of a planned reply ("Alarm"). [undoId] must hold exactly
     * one log entry (a reply with several actions is ambiguous and never corrected).
     */
    fun kindOf(
        engine: String?, note: String?, actions: String?, undoId: String?,
        replyTime: Long, now: Long
    ): String? {
        if (undoId.isNullOrBlank() || undoId.contains(',')) return null
        if (now - replyTime > WINDOW_MS || now < replyTime) return null
        val title = (if (engine == "tool") note else actions)?.trim() ?: return null
        return if (title == "Alarm" || title == "Timer") title else null
    }

    /** [raw] is the user's message, [kind] comes from [kindOf]. Null = this is not a correction of that action. */
    fun parse(raw: String, kind: String?): Fix? {
        if (kind != "Alarm" && kind != "Timer") return null
        val t = Tier0.clean(raw)
        if (t.isEmpty()) return null
        val words = t.split(' ')
        if (words.size > MAX_WORDS) return null

        if (isCancel(t, words)) return Fix.Cancel

        val lastCue = words.indexOfLast { it in cues }
        if (lastCue < 0 || lastCue == words.size - 1) return null
        val seg = words.subList(lastCue + 1, words.size).joinToString(" ")

        return if (kind == "Alarm") {
            val c = Tier0.clockTime(seg, needCue = false) ?: return null
            Fix.Alarm(c.hour, c.minute, c.tomorrow)
        } else {
            val s = Tier0.secondsFromText(seg) ?: return null
            Fix.Timer(s)
        }
    }

    private fun isCancel(t: String, words: List<String>): Boolean {
        // drop leading fillers ("nahi", "arre"), then trailing objects ("wo alarm") and "please"
        var from = 0
        while (from < words.size && words[from] in cancelFillers) from++
        var to = words.size
        while (to > from && (words[to - 1] in cancelObjects || words[to - 1] == "please")) to--
        if (from >= to) return false
        val core = words.subList(from, to).joinToString(" ")
        if (core in cancelWords) return true
        // "wo alarm rehne do", "alarm cancel karo": objects in front of the verb
        var f2 = from
        while (f2 < to && words[f2] in cancelObjects) f2++
        return f2 > from && f2 < to && words.subList(f2, to).joinToString(" ") in cancelWords
    }

    /**
     * The hour and minute of an alarm from its undo token ("alarm:CodeAssist alarm 08:30" -> 8, 30), or null for any
     * other token. Used to refuse "nahi 8:30" when the alarm already is 8:30 (the label is unique per time, so setting
     * and then dismissing the same label would delete the new alarm too).
     */
    fun alarmTimeOfToken(token: String?): Pair<Int, Int>? {
        if (token == null || !token.startsWith("alarm:")) return null
        val m = Regex("(\\d{2}):(\\d{2})$").find(token) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        return if (h in 0..23 && min in 0..59) Pair(h, min) else null
    }
}
