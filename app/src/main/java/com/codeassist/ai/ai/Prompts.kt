package com.codeassist.ai.ai

import com.codeassist.ai.data.Message
import com.codeassist.ai.data.Role
import com.codeassist.ai.data.Store

/** Prompt assembly for the two brains. Pure functions, no Android types. */
object Prompts {
    const val DEFAULT_SYSTEM =
        "You are CodeAssist AI, a helpful assistant for coding and everyday questions inside an Android app. " +
            "Reply in the language and script the user writes: if they write Hinglish in Roman letters, " +
            "answer in Roman Hinglish. Be clear and concise. Put code in Markdown code blocks. " +
            "If you are not sure, say so instead of guessing."

    private const val VOICE_HINT =
        "The user is talking to you by voice. Answer in one to three short sentences of plain spoken " +
            "language, without code blocks, lists or Markdown, unless they explicitly ask for code."

    fun system(viaVoice: Boolean): String {
        val sb = StringBuilder(Store.systemPrompt.trim())
        when (Store.replyLength) {
            "Short" -> sb.append("\nKeep every answer very short.")
            "Long" -> sb.append("\nGive detailed, step-by-step answers when that helps.")
        }
        if (viaVoice) sb.append("\n").append(VOICE_HINT)
        return sb.toString()
    }

    fun maxTokensLocal(length: String): Int = when (length) {
        "Short" -> 160
        "Long" -> 768
        else -> 384
    }

    /** Rough character budget for the prompt so prompt + reply fit the context window (~2.6 chars per token). */
    fun budgetChars(contextTokens: Int, maxTokens: Int): Int =
        ((contextTokens - maxTokens - 260) * 2.6).toInt().coerceAtLeast(700)

    fun combineLatest(text: String, attachmentText: String): String {
        val t = text.trim()
        val a = attachmentText.trim()
        return when {
            a.isEmpty() -> t
            t.isEmpty() -> a + "\n\nPlease read the attached content and summarise it."
            else -> a + "\n\n" + t
        }
    }

    /**
     * The free llama-android API is single-turn, so earlier turns are folded into the prompt text.
     * Newest turns are kept first; the latest user message always survives (its head is trimmed
     * only when it alone exceeds the budget).
     */
    fun buildLocal(previous: List<Message>, latest: String, budgetChars: Int): String {
        val newest = if (latest.length > budgetChars) {
            "[...earlier part trimmed...]\n" + latest.takeLast(budgetChars)
        } else {
            latest
        }
        var remaining = budgetChars - newest.length
        if (previous.isEmpty() || remaining < 240) return newest

        val lines = ArrayList<String>()
        for (m in previous.asReversed()) {
            var t = m.text.trim()
            if (t.isEmpty()) continue
            if (t.length > 700) t = t.take(700) + "..."
            val line = (if (m.role == Role.USER) "User: " else "Assistant: ") + t
            if (line.length + 1 > remaining) break
            lines.add(line)
            remaining -= line.length + 1
        }
        if (lines.isEmpty()) return newest
        lines.reverse()
        return "Earlier in this conversation:\n" + lines.joinToString("\n") +
            "\n\nNew message from the user (reply to this one):\n" + newest
    }


    /**
     * Phi-4 mini chat format:  <|system|>..<|end|><|user|>..<|end|><|assistant|>..<|end|>
     * The llama-android "free" complete() treats the prompt as RAW text, so without this the model
     * just continues the user's words ("Hello" -> "!\nI have a question...") and never emits <|end|>.
     * Here the template is built by hand, with real turns, ending in an open <|assistant|> header.
     */
    fun buildPhiChat(system: String, previous: List<Message>, latest: String, budgetChars: Int): String {
        val sys = "<|system|>" + system.trim() + "<|end|>"
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
                val tag = if (m.role == Role.USER) "<|user|>" else "<|assistant|>"
                val turn = tag + stripTags(t) + "<|end|>"
                if (turn.length > remaining) break
                turns.add(turn)
                remaining -= turn.length
            }
            turns.reverse()
            // template must start with a user turn
            while (turns.isNotEmpty() && !turns[0].startsWith("<|user|>")) turns.removeAt(0)
        }
        return sys + turns.joinToString("") + "<|user|>" + stripTags(newest) + "<|end|><|assistant|>"
    }

    private fun stripTags(t: String): String =
        t.replace(Regex("<\\|[a-z_]+\\|>"), "")

    /** Gemini wants alternating user/model turns that start with a user turn. */
    fun geminiTurns(
        previous: List<Message>,
        latest: Message,
        payload: AttachmentText.Payload
    ): List<GeminiClient.Turn> {
        val turns = ArrayList<GeminiClient.Turn>()
        var chars = 0
        for (m in previous.asReversed().take(24)) {
            val role = if (m.role == Role.USER) "user" else "model"
            val text = m.text.trim()
            if (text.isEmpty()) continue
            chars += text.length
            if (chars > 24_000) break
            turns.add(GeminiClient.Turn(role, text))
        }
        turns.reverse()
        while (turns.isNotEmpty() && turns[0].role != "user") turns.removeAt(0)

        val merged = ArrayList<GeminiClient.Turn>()
        for (t in turns) {
            val last = merged.lastOrNull()
            if (last != null && last.role == t.role) {
                merged[merged.size - 1] = GeminiClient.Turn(last.role, last.text + "\n\n" + t.text)
            } else {
                merged.add(t)
            }
        }
        val latestText = combineLatest(latest.text, payload.text)
        val prev = merged.lastOrNull()
        if (prev != null && prev.role == "user") {
            merged[merged.size - 1] = GeminiClient.Turn("user", prev.text + "\n\n" + latestText, payload.images)
        } else {
            merged.add(GeminiClient.Turn("user", latestText, payload.images))
        }
        return merged
    }
}
