package com.codeassist.ai.ai

import com.codeassist.ai.data.Message

/**
 * The three prompt shapes the on-device model can be asked in. Which one works depends on what the
 * llama-android library does with the text (its "free" complete() may add its own chat template, may or may
 * not read <|im_start|> as special tokens, and may or may not stop at <|im_end|>). The app cannot know that
 * without a phone, so [LocalCalibration] tries them once with a tiny "Hello" and keeps the first one that
 * stops by itself and answers sensibly. Pure Kotlin: no Android types, unit-tested on the JVM.
 */
object LocalTemplate {
    enum class Kind(val key: String) {
        /** Hand-built ChatML, empty system prompt for the library (what the app did so far). */
        CHATML("chatml"),

        /** Plain text for the library: system prompt as systemPrompt, history folded into the user text. */
        LIB("lib"),

        /** "User: / Assistant:" lines with no special tokens; the reply is cut at the first invented turn. */
        PLAIN("plain");

        companion object {
            /** null for "auto" or anything unknown. */
            fun of(key: String?): Kind? {
                for (k in Kind.values()) {
                    if (k.key == key) return k
                }
                return null
            }
        }
    }

    /** [system] goes to the library's systemPrompt argument, [prompt] to its prompt argument. */
    class Built(val system: String, val prompt: String)

    const val PROBE_TOKENS = 48

    fun build(kind: Kind, system: String, history: List<Message>, latest: String, budgetChars: Int): Built =
        when (kind) {
            Kind.CHATML -> Built("", ChatMl.build(system, history, latest, budgetChars))
            Kind.LIB -> Built(system, Prompts.buildLocal(history, latest, budgetChars - system.length))
            Kind.PLAIN -> Built("", ChatMl.buildPlain(system, history, latest, budgetChars))
        }

    /** Final cut of a finished completion ([LocalLlm] already ran [ChatMl.clean]). */
    fun finish(kind: Kind, text: String): String =
        if (kind == Kind.PLAIN) ChatMl.cutDialog(text) else text

    /** What one test run showed. [good] = stopped by itself (or is the plain shape, which is cut by hand) and sane. */
    class Probe(val kind: Kind, val text: String, val tokens: Int, val stopped: Boolean, val sane: Boolean) {
        val good: Boolean get() = sane && (stopped || kind == Kind.PLAIN)

        fun line(): String {
            val shown = text.replace('\n', ' ').take(40)
            return kind.key + ": " + tokens + " tok, " + (if (stopped) "ruka" else "nahi ruka") + ", " +
                (if (sane) "saaf" else "gadbad") + " | " + shown
        }
    }

    /** [text] is the finished reply to "Hello", [tokens] what the library counted. */
    fun judge(kind: Kind, text: String, tokens: Int, probeTokens: Int = PROBE_TOKENS): Probe {
        val stopped = tokens in 1..(probeTokens - 4)
        val r = ReplyGuard.inspect(text, "Hello", false)
        val sane = r.ok && r.problem == null && text.isNotBlank() && text.length <= 260
        return Probe(kind, text, tokens, stopped, sane)
    }
}
