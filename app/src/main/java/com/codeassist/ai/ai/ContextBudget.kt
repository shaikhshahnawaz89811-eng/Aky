package com.codeassist.ai.ai

import com.codeassist.ai.data.Message
import com.codeassist.ai.data.Role
import java.util.Locale

/**
 * Keeps the prompt of the offline model inside its context window.
 *
 * Why this exists (test chat 17 Oct 2026): the runtime counts input AND output tokens against one limit
 * (EngineConfig.maxNumTokens, 4096 here). The old code trimmed history by CHARACTERS (16,000, about 5,500 tokens of Hinglish),
 * so a long chat overflowed ("Input token ids are too long ... 4123 >= 4096"), every retry rebuilt the same prompt, and the
 * replies just before the overflow were cut because the prompt had eaten the room for the answer.
 *
 * What it does, with no Android code in it (so it can be unit-tested on the JVM):
 *  - [fit] keeps the newest turns that fit into "context minus room for the reply";
 *  - [overflowOf] recognises the runtime's overflow error and reads its two numbers, so [nextScale] can shrink the prompt by the
 *    real measured amount instead of guessing again.
 */
object ContextBudget {
    /**
     * Characters per token used for the estimate. Measured on the test chat: Hinglish prose is about 3.5 chars per token, error
     * text with digits about 2.7, so 2.6 over-estimates on purpose: too small a prompt is harmless, too big a one fails.
     */
    const val CHARS_PER_TOKEN = 2.6

    /** Room that is always left for the answer, at least this many tokens (a quarter of the window for bigger windows). */
    const val MIN_REPLY_TOKENS = 512

    /** Chat-template markers around every turn. */
    const val TURN_OVERHEAD_TOKENS = 8

    /** Template markers around the whole prompt. */
    const val PROMPT_OVERHEAD_TOKENS = 24

    /** Guess for one picture (the runtime does not tell); a wrong guess is caught by the overflow retry. */
    const val IMAGE_TOKENS = 300

    /** The newest user message is never cut below this many characters. */
    const val MIN_LATEST_CHARS = 700

    const val TRIM_MARK = "[...earlier part trimmed...]\n"

    const val TOO_LONG_TEXT =
        "Ye message ya attachment model ke context mein nahi aa raha. Chhota karke bhejo, ya Voice and AI mein " +
            "Context size badhao (phir Unload aur Load)."

    /** What the runtime said when a prompt was too long: [actual] tokens against [limit]; both 0 when the text had no numbers. */
    class Overflow(val actual: Int, val limit: Int)

    /** The prompt after fitting. [droppedTurns] older turns did not fit; [trimmedUser] means the newest message was cut. */
    class Fitted(val history: List<Message>, val userText: String, val droppedTurns: Int, val trimmedUser: Boolean)

    private val overflowNumbers = Regex("(\\d+)\\s*>=\\s*(\\d+)")

    fun estimateTokens(text: String): Int = tokensForChars(text.length)

    fun tokensForChars(chars: Int): Int = Math.ceil(chars / CHARS_PER_TOKEN).toInt()

    /** Tokens kept free for the reply. */
    fun replyReserve(contextTokens: Int): Int = maxOf(MIN_REPLY_TOKENS, contextTokens / 4)

    /** Tokens the whole prompt (system + history + new message + pictures) may use. */
    fun promptBudget(contextTokens: Int): Int = maxOf(256, contextTokens - replyReserve(contextTokens))

    /**
     * Picks what goes into the prompt. [scale] below 1.0 shrinks the budget (used after an overflow); 0.0 means "no history, newest
     * message cut to its minimum". The newest message always survives (its head is cut first, as in the old prompt builders), then
     * the newest history turns that still fit, never starting with an AI turn.
     */
    fun fit(
        contextTokens: Int,
        system: String,
        history: List<Message>,
        userText: String,
        imageCount: Int = 0,
        scale: Double = 1.0
    ): Fitted {
        val budget = (promptBudget(contextTokens) * scale.coerceIn(0.0, 1.0)).toInt()
        val fixed = estimateTokens(system) + imageCount * IMAGE_TOKENS + PROMPT_OVERHEAD_TOKENS
        val minLatest = tokensForChars(MIN_LATEST_CHARS)
        var room = maxOf(budget - fixed, minLatest)

        var user = userText
        var trimmed = false
        val userTokens = estimateTokens(user)
        if (userTokens > room) {
            val keep = maxOf((room * CHARS_PER_TOKEN).toInt() - TRIM_MARK.length, MIN_LATEST_CHARS)
            user = TRIM_MARK + user.takeLast(keep)
            trimmed = true
            room = 0
        } else {
            room -= userTokens
        }

        val picked = ArrayList<Message>()
        var left = room
        for (m in history.asReversed()) {
            if (scale <= 0.0) break
            if (m.text.isBlank()) continue
            val cost = estimateTokens(m.text) + TURN_OVERHEAD_TOKENS
            if (cost > left) break
            picked.add(m)
            left -= cost
        }
        picked.reverse()
        while (picked.isNotEmpty() && picked[0].role != Role.USER) picked.removeAt(0)
        return Fitted(picked, user, history.size - picked.size, trimmed)
    }

    /** True when [message] is the runtime's "prompt does not fit" error. */
    fun isOverflowText(message: String): Boolean {
        val l = message.lowercase(Locale.ROOT)
        return l.contains("input token ids are too long") || l.contains("exceeding the maximum number of tokens")
    }

    /** The overflow behind [t] (also when it is wrapped in another exception), or null for any other error. */
    fun overflowOf(t: Throwable): Overflow? {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 6) {
            val msg = cur.message
            if (msg != null && isOverflowText(msg)) {
                val m = overflowNumbers.find(msg)
                val actual = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val limit = m?.groupValues?.get(2)?.toIntOrNull() ?: 0
                return Overflow(actual, limit)
            }
            cur = cur.cause
            depth++
        }
        return null
    }

    /**
     * The [scale] for the next try after overflow number [attempt] (0 = first failure). First failure: shrink by what the runtime
     * measured (allowed tokens / real tokens), at least 15%. Second: half again. Third: no history at all.
     */
    fun nextScale(scale: Double, overflow: Overflow, contextTokens: Int, attempt: Int): Double {
        if (attempt >= 2) return 0.0
        if (attempt == 1) return scale * 0.5
        val limit = if (overflow.limit > 0) overflow.limit else contextTokens
        val ratio = if (overflow.actual > 0) promptBudget(limit).toDouble() / overflow.actual else 0.7
        return scale * minOf(ratio, 0.85) * 0.95
    }

    /**
     * True when prompt and reply together come so close to the window that the reply may have been cut by the limit. Both sides are
     * over-estimated, so this can say "maybe" for a long reply that was in fact complete; it is only used for a caption.
     */
    fun replyMayBeCut(contextTokens: Int, system: String, fit: Fitted, imageCount: Int, replyText: String): Boolean {
        var prompt = estimateTokens(system) + imageCount * IMAGE_TOKENS + PROMPT_OVERHEAD_TOKENS + estimateTokens(fit.userText)
        for (m in fit.history) prompt += estimateTokens(m.text) + TURN_OVERHEAD_TOKENS
        return prompt + estimateTokens(replyText) >= contextTokens - 48
    }
}
