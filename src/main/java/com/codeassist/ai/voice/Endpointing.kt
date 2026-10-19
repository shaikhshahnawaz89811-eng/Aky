package com.codeassist.ai.voice

/**
 * Audit PDF Sec 9.4 (endpointing for Hindi): Hindi is verb-final, so a pause often comes BEFORE the
 * verb ("kal subah 8 baje ka ... alarm laga do"). A fixed silence timeout cuts the sentence in half.
 *
 * The platform recognizer closes the utterance by itself. This object looks at the text it returned and
 * says whether the sentence looks unfinished, so the caller can keep listening for a short extra window
 * instead of sending half a sentence. Pure Kotlin, no Android types (unit tested on the JVM).
 */
object Endpointing {
    /** Extra listening time after an unfinished-looking utterance. */
    const val EXTEND_MS = 1300L
    const val EXTEND_NUMBER_MS = 1800L

    private val tails = setOf(
        // Roman Hinglish / English connectors and postpositions
        "aur", "ki", "ko", "ka", "ke", "se", "mein", "me", "par", "pe", "toh", "to", "lekin", "jo",
        "phir", "ya", "kyunki", "taaki", "jaise", "matlab", "tak", "wala", "wali", "wale", "bhi",
        "pehle", "baad", "yeh", "ye", "woh", "wo", "mera", "meri", "mere", "mujhe",
        // "on" is left out on purpose: "torch on" is a finished fast-path command (like "do" below)
        "and", "or", "the", "a", "an", "of", "in", "at", "for", "with", "that", "because",
        "but", "if", "then",
        // Devanagari
        "और", "की", "को", "का", "के", "से", "में", "पर", "तो", "लेकिन", "जो", "या", "फिर", "कि", "भी",
        "ये", "वो", "मेरा", "मेरी", "मुझे"
    )

    // "do" is left out on purpose: "alarm laga do" ends in the verb "do", not the number two.
    private val numberWords = setOf(
        "ek", "teen", "char", "chaar", "paanch", "panch", "chhe", "chhah", "saat", "aath", "nau", "das",
        "gyarah", "barah", "dedh", "dhai", "saade", "sava", "paune",
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "एक", "तीन", "चार", "पांच", "छह", "सात", "आठ", "नौ", "दस", "ग्यारह", "बारह"
    )

    /** Words that make a trailing number look like a half-said time / duration / phone number. */
    private val numberContext = setOf(
        "alarm", "timer", "reminder", "baje", "kal", "aaj", "parso", "subah", "shaam", "raat", "dopahar",
        "call", "dial", "number", "at", "set", "yaad", "minute", "minutes", "ghante", "ghanta", "hours",
        "अलार्म", "टाइमर", "बजे", "कल", "आज", "सुबह", "शाम", "रात", "कॉल", "नंबर"
    )

    private fun words(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^\\p{L}\\p{M}\\p{N} ]"), " ")
            .split(' ')
            .filter { it.isNotEmpty() }

    private fun isNumber(w: String): Boolean = w.all { it.isDigit() } || w in numberWords

    /** True when the sentence ends in a way people do not end sentences (a connector, or half a time). */
    fun isIncomplete(text: String): Boolean = extraWaitMs(text) > 0L

    /** 0 = looks finished, otherwise how long to keep listening for the rest. */
    fun extraWaitMs(text: String): Long {
        val w = words(text)
        if (w.size < 2) return 0L // one word is usually a whole command ("time", "torch")
        val last = w.last()
        if (isNumber(last) && w.dropLast(1).any { it in numberContext }) {
            // "kal subah 8" / "call 98765": more digits or "baje" may still come.
            // "timer 5 minute" ends in a unit, not a number, so it never reaches this branch.
            val digits = w.filter { tok -> tok.all { it.isDigit() } }.sumOf { it.length }
            if (digits >= 10) return 0L // a full phone number
            return EXTEND_NUMBER_MS
        }
        if (last in tails) return EXTEND_MS
        return 0L
    }
}
