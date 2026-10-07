package com.codeassist.ai.ai

/**
 * Cheap check on the user's words: does this message look like a phone action? Used for the Qwen brain only.
 * The tool prompt costs ~1k tokens of a 4096 window and a 1.5B model is quick to call a tool by mistake, so
 * the tools are only put into the prompt when the message is short, has no code and mentions an action word.
 * Gemini always gets the tools (it decides well and a few declarations cost nothing).
 */
object ActionHint {
    private val actionWords = setOf(
        "alarm", "alaram", "timer", "torch", "flashlight", "flash", "battery", "charge", "charging",
        "time", "samay", "baje", "bje", "kitne", "date", "tarikh", "tareekh", "call", "dial",
        "open", "kholo", "khol", "launch", "laga", "lagao",
        "अलार्म", "टाइमर", "टॉर्च", "बैटरी", "बजे", "कितने", "समय", "तारीख", "कॉल", "खोलो", "खोल"
    )

    /** Words that show the message is about programming, not about the phone. */
    private val codeWords = setOf(
        "complexity", "function", "code", "python", "kotlin", "java", "javascript", "class", "error",
        "bug", "algorithm", "array", "variable", "compile", "loop", "api", "sql", "json", "script"
    )

    private val splitter = Regex("[^\\p{L}\\p{M}\\p{N}]+")

    fun looksLikeAction(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 160 || t.contains('\n')) return false
        if (t.any { it == '`' || it == '{' || it == '}' || it == ';' || it == '=' || it == '<' || it == '>' }) return false
        val tokens = t.lowercase().split(splitter).filter { it.isNotEmpty() }
        if (tokens.size > 25) return false
        if (tokens.any { it in codeWords }) return false
        return tokens.any { it in actionWords }
    }
}
