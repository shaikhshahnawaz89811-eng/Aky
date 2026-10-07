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

    /** Added to the system prompt of both brains when tools are offered (audit Sec 9.3 rules). */
    const val TOOL_RULES =
        "Phone actions: call a tool only when the user clearly asks for that action now (alarm, timer, torch, " +
            "battery, time, date, open an app, dial a number). For every other message answer normally and call " +
            "no tool. Never invent a phone number or an app name. If something needed is missing, ask ONE short " +
            "question instead of guessing. The app shows the tool results to the user, so do not describe them."

    /**
     * System prompt for the on-device 1.5B model: short, plain English instructions (a small model follows these
     * better than a long Hinglish text). The language rule is spelled out with an example; for a Hinglish message the
     * prompt also carries two example exchanges (see [HinglishGuide.fewShot]). What the user told the app about
     * themselves is added by [systemLocal]. A system prompt the user edited in Settings is used instead.
     */
    const val LOCAL_SYSTEM =
        "You are CodeAssist AI, a friendly assistant inside an Android app, talking to an Indian user. " +
            "LANGUAGE: if the user writes Hinglish (Hindi in English letters), you MUST answer in simple Roman Hinglish, " +
            "for example \"Haan, main aapki madad kar sakta hoon.\" Never answer a Hinglish message in English. " +
            "If the user writes English, answer in English. If the user writes Devanagari, answer in Devanagari. " +
            "Keep answers short and clear; put code in Markdown code blocks. " +
            "Use the earlier messages of this chat and the facts you know about the user when they matter. " +
            "Never invent facts: if you do not know, say so. Stop when the answer is complete."

    /** Added to the tool rules for the offline model only: three worked examples, because a 1.5B model copies them well. */
    const val LOCAL_TOOL_HINT =
        "Examples: \"kal subah 6 baje utha dena\" -> alarm_set {hour 6, minute 0, tomorrow true}. " +
            "\"10 minute baad yaad dilana\" -> timer_set {seconds 600}. " +
            "\"torch jalao\" -> torch_set {on true}. \"battery kitni hai\" -> battery_level."

    /** Used for the one retry after a rejected reply: as little text as possible for the model to trip over. */
    const val LOCAL_MINIMAL_SYSTEM =
        "You are a helpful assistant. Answer briefly in the user's language. " +
            "If the user writes Hinglish (Hindi in English letters), answer in simple Roman Hinglish."

    /** A greeting needs a greeting back, not 384 tokens: bounds the damage if the model does not stop. */
    const val SMALL_TALK_TOKENS = 90

    fun system(viaVoice: Boolean): String {
        val sb = StringBuilder(Store.systemPrompt.trim())
        when (Store.replyLength) {
            "Short" -> sb.append("\nKeep every answer very short.")
            "Long" -> sb.append("\nGive detailed, step-by-step answers when that helps.")
        }
        if (viaVoice) sb.append("\n").append(VOICE_HINT)
        return sb.toString()
    }

    fun systemLocal(viaVoice: Boolean): String {
        val sb = StringBuilder(if (Store.systemPromptIsCustom) Store.systemPrompt.trim() else LOCAL_SYSTEM)
        val facts = LocalMemory.block(Store.userFacts)
        if (facts.isNotEmpty()) sb.append("\n").append(facts)
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
     * only when it alone exceeds the budget). Failed and broken replies are left out.
     */
    fun buildLocal(previous: List<Message>, latest: String, budgetChars: Int): String {
        val newest = if (latest.length > budgetChars) {
            "[...earlier part trimmed...]\n" + latest.takeLast(budgetChars)
        } else {
            latest
        }
        var remaining = budgetChars - newest.length
        val usable = ReplyGuard.cleanHistory(previous)
        if (usable.isEmpty() || remaining < 240) return newest

        val lines = ArrayList<String>()
        for (m in usable.asReversed()) {
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
