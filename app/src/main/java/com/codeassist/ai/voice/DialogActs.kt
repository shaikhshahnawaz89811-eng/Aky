package com.codeassist.ai.voice

/**
 * Audit PDF Sec 9.4 "Backchannel vs confirmation": the same word ("haan") can be a backchannel, an
 * answer to a pending question or a command. Only a few acts can be decided without an LLM; those are
 * handled here, everything else goes to the brain as a normal message. Pure Kotlin (JVM unit tested).
 */
object DialogActs {
    enum class Act { COMMAND, CONFIRM_YES, CONFIRM_NO, BACKCHANNEL, STOP, GOODBYE, RESUME, REPEAT }

    class Context(
        /** The assistant's last reply asked a question and the answer window (~10 s) is still open. */
        val pendingQuestion: Boolean,
        /** Interrupted speech is saved and still fresh (<60 s). */
        val hasResume: Boolean,
        /** Something was spoken recently that can be repeated. */
        val hasRepeat: Boolean,
        /** Listening without an explicit tap: follow-up window or continuous mode. */
        val sessionLike: Boolean
    )

    private val backchannel = setOf(
        "hmm", "hm", "hmm hmm", "haan", "han", "haa", "ha", "achha", "accha", "acha", "ok", "okay", "oh",
        "aha", "uh huh", "ji", "ji haan", "theek hai", "thik hai", "samajh gaya", "samajh gayi", "right",
        "i see", "yes", "yeah", "हम्म", "हाँ", "हां", "अच्छा", "जी", "ठीक है", "ओके"
    )
    private val yes = setOf(
        "haan", "han", "ha", "ji", "ji haan", "yes", "yeah", "yep", "sure", "ok", "okay", "theek hai",
        "thik hai", "bilkul", "kar do", "kardo", "bhej do", "haan kar do", "haan bhej do", "haan bolo",
        "हाँ", "हां", "जी", "ठीक है", "बिल्कुल"
    )
    private val no = setOf(
        "nahi", "nahin", "na", "no", "nope", "mat karo", "rehne do", "rahne do", "cancel", "nahi karo",
        "नहीं", "ना", "मत करो", "रहने दो"
    )
    private val goodbye = setOf(
        "bye", "bye bye", "goodbye", "good night", "alvida", "tata", "ok bye", "okay bye", "chalo bye",
        "shukriya bas", "thank you bas", "dhanyavaad", "बाय", "अलविदा", "शुक्रिया बस"
    )
    private val resume = setOf(
        "aage batao", "aage bolo", "aage", "haan bolo", "bolo", "continue", "go on", "keep going",
        "baaki batao", "baaki bolo", "jaari rakho", "phir bolo", "आगे बताओ", "आगे बोलो", "बोलो"
    )
    private val repeat = setOf(
        "dobara bolo", "phir se bolo", "repeat", "say again", "say that again", "kya kaha", "kya bola",
        "ek baar aur bolo", "dobara batao", "phir se batao", "दोबारा बोलो", "फिर से बोलो"
    )

    fun normalize(raw: String): String =
        raw.lowercase()
            .replace(Regex("[^\\p{L}\\p{M}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun classify(raw: String, ctx: Context): Act {
        val t = normalize(raw)
        if (t.isEmpty()) return Act.BACKCHANNEL
        if (t.split(' ').size > 4) return Act.COMMAND

        if (ctx.hasResume && t in resume) return Act.RESUME
        if (ctx.hasRepeat && t in repeat) return Act.REPEAT

        if (ctx.sessionLike) {
            if (SpeechText.isStopPhrase(raw)) return Act.STOP
            if (t in goodbye) return Act.GOODBYE
        }
        if (ctx.pendingQuestion) {
            if (t in yes) return Act.CONFIRM_YES
            if (t in no) return Act.CONFIRM_NO
        }
        // Without a pending question a bare "haan" / "hmm" is just the user nodding along.
        if (ctx.sessionLike && t in backchannel) return Act.BACKCHANNEL
        return Act.COMMAND
    }
}
