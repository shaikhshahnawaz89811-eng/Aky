package com.codeassist.ai.ai

import com.codeassist.ai.data.Message
import com.codeassist.ai.data.Role

/**
 * Keeps the offline model in the user's language. Pure Kotlin, unit-tested on the JVM.
 *
 * Gemini follows "answer in Roman Hinglish" from one line of instruction. A 1.5B model does not: it understands
 * Hinglish but answers in English, because most of what it read was English. Telling it harder does little; showing
 * it works. So for a Hinglish message the offline prompt starts with two short example exchanges (real chat turns,
 * the first thing dropped when the window is full), and the finished reply is checked: an English-only answer to a
 * Hinglish question is asked for once more, with a "Ji, " already written for the model to continue from.
 */
object HinglishGuide {
    /** Words that are Hindi only; common English words that are also Hinglish ("to", "do", "main", "hi") are left out. */
    private val markers = setOf(
        "hai", "hain", "hoon", "hu", "hun", "tha", "thi", "hoga", "hogi", "raha", "rahi", "rahe", "hua", "hui",
        "kya", "kaise", "kaisa", "kaisi", "kaun", "kyun", "kyu", "kahan", "kab", "kitna", "kitne", "kitni",
        "mujhe", "mera", "meri", "mere", "tumhara", "tumhari", "aap", "aapka", "aapki", "tum", "hum", "humein",
        "nahi", "nahin", "haan", "ji", "accha", "achha", "theek", "thik", "bahut", "bohot", "kuch", "koi", "sab",
        "karo", "karna", "karke", "kar", "kijiye", "batao", "bata", "bataiye", "dena", "dijiye", "lena", "chahiye",
        "kal", "aaj", "abhi", "baad", "pehle", "phir", "fir", "aur", "bhi", "lekin", "magar", "isko", "usko", "isse",
        "yeh", "woh", "ye", "wo", "mein", "ko", "ka", "ki", "ke", "se", "par", "sakta", "sakti", "sakte", "sakata",
        "baje", "bhaje", "uthna", "kholo", "lagao", "laga", "dikhao", "samjhao", "likho", "sunao", "banao",
        "sikhao", "sikhana", "banana", "batana", "samjhana", "dikhana", "sunana", "chahta", "chahti", "padhao", "padho"
    )

    private val splitter = Regex("[^\\p{L}\\p{M}\\p{N}']+")

    /** The text split into lowercase words. */
    private fun words(text: String): List<String> =
        text.lowercase().split(splitter).filter { it.isNotEmpty() }

    private fun score(ws: List<String>): Int = ws.count { it in markers }

    private fun hasDevanagari(text: String): Boolean = text.any { it in '\u0900'..'\u097F' }

    /** True when [text] is Hindi written in English letters (not English, not Devanagari). */
    fun userWritesHinglish(text: String): Boolean {
        if (hasDevanagari(text)) return false
        val ws = words(text)
        if (ws.isEmpty()) return false
        val s = score(ws)
        return s >= 2 || (s >= 1 && ws.size <= 3)
    }

    /**
     * False when the user wrote Hinglish and the reply is long enough to judge but has no Hindi word at all.
     * Code answers are never judged: code is English by nature.
     */
    fun replyMatchesLanguage(userText: String, reply: String): Boolean {
        if (!userWritesHinglish(userText)) return true
        if (reply.contains("```")) return true
        if (hasDevanagari(reply)) return true
        val ws = words(reply)
        if (ws.size < 8) return true
        return score(ws) >= maxOf(1, ws.size / 30)
    }

    /** Two short exchanges that show the tone and the script. Placed before the real history. */
    fun fewShot(): List<Message> = listOf(
        Message(role = Role.USER, text = "kaise ho? kya kar rahe ho"),
        Message(
            role = Role.AI,
            text = "Main theek hoon, shukriya! Aap batao, aaj main aapki kya madad kar sakta hoon?"
        ),
        Message(role = Role.USER, text = "mujhe chai banana sikhao"),
        Message(
            role = Role.AI,
            text = "Zaroor! Pehle paani ubaalo, phir usmein chai patti aur adrak daalo. " +
                "Do minute baad doodh aur cheeni milao, ek ubaal aane do aur chhaan lo. Chai taiyaar hai!"
        )
    )

    /** For the one language retry. Short and blunt, with an example of the exact style. */
    const val RETRY_SYSTEM =
        "You are a friendly assistant. Reply ONLY in Roman Hinglish: Hindi written in English letters, " +
            "for example \"Haan, main aapki madad kar sakta hoon.\" Never reply in English. Keep it short."

    /** Start of the assistant turn that is already written, so the model continues in Hinglish. Empty for LIB. */
    fun prefillFor(kind: LocalTemplate.Kind): String =
        if (kind == LocalTemplate.Kind.LIB) "" else "Ji, "
}
