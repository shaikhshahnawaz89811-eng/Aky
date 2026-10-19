package com.codeassist.ai.voice

/** Turns a chat reply (Markdown, code, links, emoji) into text that sounds right when spoken. */
object SpeechText {
    private val fenced = Regex("```[\\s\\S]*?```")
    private val inlineCode = Regex("`([^`]*)`")
    private val link = Regex("\\[([^\\]]*)]\\((?:https?://)[^)]*\\)")
    private val url = Regex("https?://\\S+")
    private val emoji = Regex("[\\u2600-\\u27BF\\uFE0F\\u200D]|[\\uD83C-\\uD83E][\\uDC00-\\uDFFF]")
    private val bullet = Regex("(?m)^\\s*(?:[-*\\u2022]|\\d+[.)])\\s+")
    private val markdown = Regex("[*_#>~|]+")
    private val spaces = Regex("\\s+")
    private val sentenceEnd = Regex("(?<=[.!?\\u0964])\\s+")

    fun clean(raw: String): String {
        var s = raw
        s = s.replace(fenced, " Code screen par hai. ")
        s = s.replace(inlineCode, "\$1")
        s = s.replace(link, "\$1")
        s = s.replace(url, " link ")
        s = s.replace(emoji, "")
        s = s.replace(bullet, "")
        s = s.replace(markdown, " ")
        return s.replace(spaces, " ").trim()
    }

    /** Splits into pieces of at most [max] characters, preferring sentence boundaries. */
    fun chunks(text: String, max: Int): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (sentence in text.split(sentenceEnd)) {
            var s = sentence.trim()
            if (s.isEmpty()) continue
            while (s.length > max) {
                var cut = s.lastIndexOf(' ', max)
                if (cut <= 0) cut = max
                if (current.isNotEmpty()) {
                    out.add(current.toString())
                    current.setLength(0)
                }
                out.add(s.substring(0, cut).trim())
                s = s.substring(cut).trim()
            }
            if (s.isEmpty()) continue
            if (current.isNotEmpty() && current.length + 1 + s.length > max) {
                out.add(current.toString())
                current.setLength(0)
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(s)
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }

    fun isStopPhrase(raw: String): Boolean {
        val t = raw.lowercase().replace(Regex("[^\\p{L}\\p{M}\\p{N} ]"), " ").replace(spaces, " ").trim()
        return t in setOf(
            "bas", "bas karo", "bas ab", "stop", "stop listening", "band karo", "ruko", "ruk jao",
            "that s all", "thats all", "exit", "बस", "बस करो", "रुको", "बंद करो", "स्टॉप"
        )
    }
}
