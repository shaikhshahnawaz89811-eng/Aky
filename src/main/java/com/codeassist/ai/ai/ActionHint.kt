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
        "bhaje", "uthna", "uthana", "uthao", "utha", "jagana", "jagao", "wake", "remind", "reminder", "dilana", "dila",
        "mausam", "weather", "temperature", "barish", "मौसम",
        "अलार्म", "टाइमर", "टॉर्च", "बैटरी", "बजे", "कितने", "समय", "तारीख", "कॉल", "खोलो", "खोल"
    )

    /** Words that show the message is about programming, not about the phone. */
    private val codeWords = setOf(
        "complexity", "function", "code", "python", "kotlin", "java", "javascript", "class", "error",
        "bug", "algorithm", "array", "variable", "compile", "loop", "api", "sql", "json", "script"
    )

    private val splitter = Regex("[^\\p{L}\\p{M}\\p{N}]+")

    /**
     * "on" / "chalu" alone say nothing ("based on ..."), so they count only in the shape "<app> on karo" and only when the words
     * before them name an app (test chat 13 Oct 2026: "WhatsApp on karo" got no tools and the model said it could not open apps).
     */
    private val switchWords = setOf("on", "chalu", "चालू")
    private val tailWords = setOf("karo", "kar", "do", "de", "dena", "kardo", "please", "pls", "na", "yaar", "ji")
    private val fillers = setOf(
        "ko", "app", "mera", "mere", "meri", "phone", "me", "mein", "ek", "zara", "jara", "bhai", "yaar", "the", "a", "please", "pls"
    )

    /** Apps people name often: used as well as the installed list, so "<app> on karo" still reaches the tools (and gets an honest "nahi mila"). */
    private val wellKnownApps = setOf(
        "whatsapp", "youtube", "instagram", "facebook", "telegram", "gmail", "chrome", "maps", "spotify", "camera",
        "gallery", "snapchat", "netflix", "twitter", "paytm", "phonepe", "gpay", "zoom", "meet", "calculator", "clock"
    )

    /**
     * [apps] gives the normalized labels of the installed apps; it is only called when the message has the "<app> on karo"
     * shape, so ordinary chat never pays for the app list.
     */
    fun looksLikeAction(text: String, apps: () -> Collection<String> = { emptyList() }): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 160 || t.contains('\n')) return false
        if (t.any { it == '`' || it == '{' || it == '}' || it == ';' || it == '=' || it == '<' || it == '>' }) return false
        val tokens = t.lowercase().split(splitter).filter { it.isNotEmpty() }
        if (tokens.size > 25) return false
        if (tokens.any { it in codeWords }) return false
        if (tokens.any { it in actionWords }) return true
        return switchedAppOn(tokens, apps)
    }

    private fun switchedAppOn(tokens: List<String>, apps: () -> Collection<String>): Boolean {
        val i = tokens.indexOfLast { it in switchWords }
        if (i <= 0) return false
        if (tokens.subList(i + 1, tokens.size).any { it !in tailWords }) return false
        val head = tokens.subList(0, i).filter { it !in fillers }
        if (head.isEmpty() || head.size > 3) return false
        val joined = " " + head.joinToString(" ") + " "
        if (wellKnownApps.any { joined.contains(" $it ") }) return true
        val labels = apps().map { it.lowercase().trim() }.filter { it.isNotEmpty() }
        return labels.any { a -> joined.contains(" $a ") || head.any { w -> w.length >= 4 && a.startsWith(w) } }
    }
}
