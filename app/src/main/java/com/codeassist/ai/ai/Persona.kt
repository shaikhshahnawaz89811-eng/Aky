package com.codeassist.ai.ai

/**
 * Audit Sec 9.7 "Persona spec" and Sec 9.10 (Phase 4B): how the assistant sounds, as four small settings instead of a free
 * prompt: tone, humor, how it addresses the user (aap / tum) and the grammatical gender of its own verbs (Hindi verbs change:
 * "karta hoon" / "karti hoon", which the audit found mixed in the old plan). Pure Kotlin, JVM-tested.
 *
 * The default (Friendly, Light, aap, Auto) adds nothing to a prompt, so a user who never opens these settings gets exactly the
 * prompts of before and the small model's context is untouched. A non-default choice adds one short "Style:" line.
 *
 * The audit's hard rule is kept: in a serious message (emergency, pain, accident, self-harm words) there are no jokes, whatever
 * the humor setting says ("serious / safety = auto 0"). That sentence is added even when the user has their own system prompt.
 */
object Persona {
    val TONES = listOf("Friendly", "Professional", "Calm", "Playful")
    val HUMORS = listOf("Off", "Light", "Frequent")
    val ADDRESS = listOf("aap", "tum")
    val GENDERS = listOf("Auto", "Female", "Male")

    const val DEFAULT_TONE = "Friendly"
    const val DEFAULT_HUMOR = "Light"
    const val DEFAULT_ADDRESS = "aap"
    const val DEFAULT_GENDER = "Auto"

    class Spec(
        val tone: String = DEFAULT_TONE,
        val humor: String = DEFAULT_HUMOR,
        val address: String = DEFAULT_ADDRESS,
        val gender: String = DEFAULT_GENDER
    ) {
        val isDefault: Boolean
            get() = tone == DEFAULT_TONE && humor == DEFAULT_HUMOR && address == DEFAULT_ADDRESS && gender == DEFAULT_GENDER
    }

    const val SERIOUS_SENTENCE = "This message is serious: no jokes, calm and caring tone."

    private val seriousWords = setOf(
        "ambulance", "emergency", "chakkar", "dard", "khoon", "accident", "hospital", "bimar", "bimari", "maut", "marna",
        "suicide", "aatmahatya", "khudkushi", "police", "aag", "fire", "saans", "behosh", "zehar", "poison", "overdose",
        "heart", "attack", "abuse"
    )

    /** True for a message about an emergency, pain, accident or self-harm: humor is switched off for that answer. */
    fun serious(text: String): Boolean {
        val words = Regex("[\\p{L}]+").findAll(text.lowercase()).map { it.value }
        return words.any { it in seriousWords }
    }

    /**
     * The persona part of a system prompt, or "" when there is nothing to add. [includeStyle] = false (the user wrote their own
     * system prompt) leaves only the serious-message sentence.
     */
    fun block(spec: Spec, userText: String, includeStyle: Boolean = true): String {
        val parts = ArrayList<String>()
        if (includeStyle) {
            when (spec.tone) {
                "Professional" -> parts.add("Tone: professional and concise, no slang.")
                "Calm" -> parts.add("Tone: calm and gentle, short sentences.")
                "Playful" -> parts.add("Tone: warm, playful and upbeat.")
            }
            when (spec.humor) {
                "Off" -> parts.add("Never joke.")
                "Frequent" -> parts.add("Add a light joke when it fits.")
            }
            if (spec.address == "tum") parts.add("Address the user as \"tum\" (informal), not \"aap\".")
            when (spec.gender) {
                "Female" -> parts.add("Speak as a woman: use feminine verb forms (karti hoon, rahi hoon, sakti hoon, karungi).")
                "Male" -> parts.add("Speak as a man: use masculine verb forms (karta hoon, raha hoon, sakta hoon, karunga).")
            }
        }
        val line = if (parts.isEmpty()) "" else "Style: " + parts.joinToString(" ")
        val serious = if (serious(userText)) SERIOUS_SENTENCE else ""
        return listOf(line, serious).filter { it.isNotEmpty() }.joinToString("\n")
    }

    // ---------- the app's own sentences ----------

    private const val NOT_LETTER_BEFORE = "(?<![\\p{L}\\p{M}])"
    private const val NOT_LETTER_AFTER = "(?![\\p{L}\\p{M}])"

    private val maleParticiples = listOf(
        "raha", "karta", "sakta", "chahta", "jaata", "jata", "hota", "deta", "leta", "dikhata", "batata", "bolta", "dekhta",
        "sunta", "samajhta", "padhta", "likhta", "milta", "lagata", "rakhta", "aata", "chalata", "bhejta", "dhundta"
    )

    private val participleRx = Regex(
        NOT_LETTER_BEFORE + "(" + maleParticiples.joinToString("|") + ")(\\s+)(hoon|hu|hun)" + NOT_LETTER_AFTER,
        RegexOption.IGNORE_CASE
    )

    private val futureRx = Regex(NOT_LETTER_BEFORE + "(\\p{L}+)unga" + NOT_LETTER_AFTER, RegexOption.IGNORE_CASE)

    /**
     * Sentences the app writes itself ("Soch raha hoon", "Theek hai, abhi karta hoon") are written in the masculine form. For the
     * Female voice the first-person verbs are turned into feminine ones. Only a closed list of verbs before "hoon" and words ending
     * in "-unga" are touched, so a name such as "Asha hoon" or an object-agreeing "kar diya" stays as it is.
     * Model answers are not edited: the model is told the form in [block].
     */
    fun fit(text: String, gender: String): String {
        if (gender != "Female" || text.isEmpty()) return text
        var out = participleRx.replace(text) { m ->
            val verb = m.groupValues[1]
            val last = verb.last()
            verb.dropLast(1) + (if (last.isUpperCase()) "I" else "i") + m.groupValues[2] + m.groupValues[3]
        }
        out = futureRx.replace(out) { m ->
            val word = m.value
            word.dropLast(1) + (if (word.last().isUpperCase()) "I" else "i")
        }
        return out
    }
}
