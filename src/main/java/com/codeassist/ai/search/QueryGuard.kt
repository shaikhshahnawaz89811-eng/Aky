package com.codeassist.ai.search

import com.codeassist.ai.ai.MemoryGuard

/**
 * The last check before a question leaves the phone for a search server (audit 6.1 step 4, section 7 "Privacy").
 *
 * It reuses [MemoryGuard] but is stricter about shapes and looser about words: "best password manager" is a fine search, while
 * "my otp is 482913" or a pasted API key is not.
 *
 *  - BLOCK: a key-like token (20 or more letters and digits in one piece, or a known key prefix), a card / account / Aadhaar / PAN shape,
 *    or a secret word (otp, password, pin, cvv ...) together with digits.
 *  - STRIP: a phone-like number is removed from the query and the search goes on with the rest.
 *  - OK: everything else.
 *
 * Pure Kotlin. It cannot know a secret written in words; the query is also only sent after an explicit web request.
 */
object QueryGuard {
    sealed class Prepared {
        /** [query] is what may be sent; [stripped] says a number was removed from it. */
        class Ok(val query: String, val stripped: Boolean) : Prepared()

        class Blocked(val reason: String) : Prepared()
    }

    const val BLOCKED_MESSAGE =
        "Is sawaal mein OTP, password, card, key ya koi private number lag raha hai, isliye main ise net par nahi bhejunga."

    private val keyPrefix = Regex("(?i)\\b(?:tvly-|sk-|ghp_|gho_|hf_|AIza|AKIA|xox[bap]-)[A-Za-z0-9_\\-]{6,}")
    private val longToken = Regex("[A-Za-z0-9]{20,}")
    private val digitRun = Regex("\\d{4,}")
    private val year = Regex("(?:19|20)\\d{2}")
    private val phoneLike = Regex("\\+\\d{1,3}(?:[ \\-]?\\d{2,6}){2,5}|\\d{6,}|\\d{3,6}(?:[ \\-]\\d{3,6}){1,5}")
    private val spaces = Regex("\\s+")

    // MemoryGuard reasons that come from a number shape, not from a word
    private val shapeReasons = setOf("pan", "number", "card or account number", "aadhaar")

    fun prepare(query: String): Prepared {
        val q = query.trim()
        if (q.isEmpty()) return Prepared.Blocked("empty")
        if (keyPrefix.containsMatchIn(q)) return Prepared.Blocked("key")
        for (m in longToken.findAll(q)) {
            val t = m.value
            if (t.any { it.isDigit() } && t.any { it.isLetter() }) return Prepared.Blocked("key-like text")
        }

        val verdict = MemoryGuard.check(q)
        if (verdict.kind == MemoryGuard.Kind.NEVER) {
            if (verdict.reason in shapeReasons) return Prepared.Blocked(verdict.reason)
            if (hasSecretDigits(q)) return Prepared.Blocked(verdict.reason)
        }
        if (verdict.kind == MemoryGuard.Kind.TEMPORARY) {
            val cleaned = spaces.replace(phoneLike.replace(q, " "), " ").trim()
            if (cleaned.isEmpty()) return Prepared.Blocked("only a number")
            return Prepared.Ok(cleaned, true)
        }
        return Prepared.Ok(q, false)
    }

    /** A run of 4 or more digits that is not a year. */
    private fun hasSecretDigits(q: String): Boolean {
        for (m in digitRun.findAll(q)) {
            if (m.value.length == 4 && year.matches(m.value)) continue
            return true
        }
        return false
    }
}
