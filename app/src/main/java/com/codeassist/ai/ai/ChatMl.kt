package com.codeassist.ai.ai

import com.codeassist.ai.data.Message
import com.codeassist.ai.data.Role

/**
 * Qwen2.5 chat format (ChatML), built by hand. Pure Kotlin, no Android types, so it is unit-tested on the JVM.
 *
 *   <|im_start|>system\n..<|im_end|>\n<|im_start|>user\n..<|im_end|>\n<|im_start|>assistant\n
 *
 * The llama-android "free" complete() treats the prompt as RAW text (the earlier Phi build needed the same
 * workaround), so without this the model just continues the user's words and never emits <|im_end|>.
 * The template ends in an open assistant header. The system prompt is part of this text, so the caller
 * passes an empty system prompt to the library.
 */
object ChatMl {
    private const val SYSTEM = "<|im_start|>system\n"
    private const val USER = "<|im_start|>user\n"
    private const val ASSISTANT = "<|im_start|>assistant\n"
    private const val END = "<|im_end|>\n"

    private val markers = listOf("<|im_end|>", "<|im_start|>", "<|endoftext|>")
    private val tag = Regex("<\\|[a-z_]+\\|>")

    /**
     * Newest turns are kept first, as many as fit in [budgetChars]; the latest user message always survives
     * (its head is trimmed only when it alone exceeds the budget). Failed replies are never sent back.
     */
    fun build(system: String, previous: List<Message>, latest: String, budgetChars: Int): String {
        val sys = SYSTEM + strip(system.trim()) + END
        val newest = if (latest.length > budgetChars) {
            "[...earlier part trimmed...]\n" + latest.takeLast(budgetChars)
        } else {
            latest
        }
        var remaining = budgetChars - newest.length - sys.length
        val turns = ArrayList<String>()
        if (remaining > 240) {
            for (m in previous.asReversed()) {
                var t = m.text.trim()
                if (t.isEmpty() || m.state != null) continue
                if (t.length > 700) t = t.take(700) + "..."
                val head = if (m.role == Role.USER) USER else ASSISTANT
                val turn = head + strip(t) + END
                if (turn.length > remaining) break
                turns.add(turn)
                remaining -= turn.length
            }
            turns.reverse()
            // the history must start with a user turn
            while (turns.isNotEmpty() && !turns[0].startsWith(USER)) turns.removeAt(0)
        }
        return sys + turns.joinToString("") + USER + strip(newest) + END + ASSISTANT
    }

    /** System text that also carries the tool list and the rules for using it (Qwen2.5 <tools> format). */
    fun withTools(system: String, rules: String, toolsBlock: String): String =
        system.trim() + "\n" + rules.trim() + "\n\n" + toolsBlock.trim()

    /**
     * One repair round: the model's own broken answer, then a user note saying what was wrong, then an open
     * assistant header again. [prompt] must be a finished [build] result.
     */
    fun appendTurn(prompt: String, assistantRaw: String, userNote: String): String =
        prompt + strip(assistantRaw.trim()) + END + USER + strip(userNote.trim()) + END + ASSISTANT

    /** Cuts the raw completion at the first ChatML marker, in case the library leaves special tokens in the text. */
    fun clean(raw: String): String {
        val cut = markers.map { raw.indexOf(it) }.filter { it >= 0 }.minOrNull()
        val t = if (cut != null) raw.substring(0, cut) else raw
        return t.trim()
    }

    /** Removes anything that looks like a special token, so user text can never fake a turn boundary. */
    private fun strip(t: String): String = t.replace(tag, "")
}
