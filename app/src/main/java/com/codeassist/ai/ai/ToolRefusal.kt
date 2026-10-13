package com.codeassist.ai.ai

/**
 * Safety net for the offline brain (test chat 13 Oct 2026). When the phone tools were NOT put into the prompt and the model
 * answers a short order with "main ek AI hoon, main phone par WhatsApp open nahi kar sakta", the answer is false: the app can do
 * it. [shouldRetryWithTools] says when the reply is such a refusal, so [ChatRunner] asks once more WITH the tools.
 *
 * It is deliberately narrow. A question about ability ("kya tum WhatsApp khol sakte ho?"), a "how" / "why" question, code and
 * long messages never retry: those must not start an action the user only asked about. Pure Kotlin, JVM-tested.
 */
object ToolRefusal {
    private val splitter = Regex("[^\\p{L}\\p{M}\\p{N}]+")

    private val refusalRx = Regex(
        "(?i)(?:nahi|nahin|nhi)\\s+(?:kar|khol|chala|laga)\\s+(?:sakta|sakti|sakte)|" +
            "(?:can'?t|cannot|unable\\s+to|not\\s+able\\s+to)\\s+(?:open|launch|control|access|start)|" +
            "(?:main|mai|mein)\\s+ek\\s+ai\\s+hoon|(?:i\\s+am|i'm)\\s+(?:just\\s+)?an?\\s+ai\\b|as\\s+an\\s+ai\\b"
    )

    /** The refusal must be about the phone or an app, not about something else ("main ye kahani nahi likh sakta"). */
    private val aboutPhoneRx = Regex("(?i)phone|app\\b|apps\\b|open|khol|control|device|whatsapp")

    private val doWords = setOf(
        "karo", "kar", "do", "kardo", "kholo", "khol", "open", "chalu", "on", "start", "launch", "laga", "lagao", "band", "shuru"
    )

    /** Words that make the message a question or a code question: never retried. */
    private val notAnOrder = setOf(
        "kya", "kaise", "kyu", "kyun", "kyon", "how", "why", "what", "can", "could", "sakte", "sakta", "sakti",
        "code", "python", "kotlin", "java", "function", "script", "error", "bug", "sql", "api"
    )

    /**
     * Phone settings the app has no tool for ("wifi on karo"): the refusal is TRUE there, so it is left alone. With the tools in
     * the prompt a small model might call a wrong one instead of saying so.
     */
    private val settingWords = setOf(
        "wifi", "bluetooth", "hotspot", "data", "internet", "gps", "location", "nfc", "airplane", "flight", "silent", "dnd",
        "volume", "brightness", "sound", "saver", "display", "screen"
    )

    fun shouldRetryWithTools(userText: String, reply: String): Boolean {
        val u = userText.trim()
        if (u.isEmpty() || u.length > 80 || u.contains('\n') || u.endsWith("?")) return false
        if (u.any { it == '`' || it == '{' || it == '}' || it == ';' || it == '=' || it == '<' || it == '>' }) return false
        val tokens = u.lowercase().split(splitter).filter { it.isNotEmpty() }
        if (tokens.size < 2 || tokens.size > 8) return false
        if (tokens.any { it in notAnOrder || it in settingWords }) return false
        if (tokens.none { it in doWords }) return false
        val r = reply.trim()
        if (r.isEmpty() || r.length > 600) return false
        return refusalRx.containsMatchIn(r) && aboutPhoneRx.containsMatchIn(r)
    }
}
