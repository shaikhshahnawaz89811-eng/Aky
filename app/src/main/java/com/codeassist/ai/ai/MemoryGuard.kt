package com.codeassist.ai.ai

/**
 * Audit Sec 9.5 "never-store / never-speak list" (Phase 4A): things that must not be written to memory at all, and things
 * that are only kept for a day. Pure Kotlin, JVM-tested.
 *
 *  - NEVER: OTP, password, PIN (UPI PIN), CVV, card number, Aadhaar, PAN, bank account number, API / private keys. Not saved,
 *    not even when the user asks (a stolen phone or a shared screenshot of the Memory screen must not give these away).
 *  - TEMPORARY: a phone-like number (6 to 11 digits, or +country code). Audit "Number yaad rakhna": it goes to working memory
 *    (24 hours) unless the user says "hamesha"; the reply reads the digits back.
 *  - OK: everything else.
 *
 * A word list and number shapes cannot know everything: a secret written as words ("nau aath saat ...") passes. The
 * Memory screen therefore lets the user see and delete everything that is stored.
 */
object MemoryGuard {
    enum class Kind { OK, TEMPORARY, NEVER }

    class Verdict(val kind: Kind, val reason: String)

    const val NEVER_MESSAGE =
        "Ye main yaad nahi rakhunga: OTP, password, PIN, card number, Aadhaar / PAN, bank account jaisi cheezein kabhi save nahi hoti. " +
            "Ye aapki hifazat ke liye hai."

    private val singleWords = setOf(
        "otp", "password", "passwd", "paswd", "passcode", "cvv", "cvc", "aadhaar", "aadhar", "adhar", "a/c"
    )

    private val phrases = listOf(
        "one time password", "card number", "credit card", "debit card", "account number", "account no", "acc no",
        "bank account", "net banking", "netbanking", "internet banking", "pan card", "pan number", "pan no",
        "secret key", "api key", "private key", "seed phrase", "recovery phrase", "security code"
    )

    private val panRx = Regex("\\b[a-z]{5}[0-9]{4}[a-z]\\b")

    // +91 98765 43210 | 9876543210 | 98765 43210 | 4111 1111 1111 1111 (groups of 3 to 6 digits, so dates like "12 10 2026" never match)
    private val numberRx = Regex("\\+\\d{1,3}(?:[ \\-]?\\d{2,6}){2,5}|\\d{6,}|\\d{3,6}(?:[ \\-]\\d{3,6}){1,5}")

    fun check(text: String): Verdict {
        val lower = text.lowercase()
        val tokens = Regex("[\\p{L}\\p{N}_/]+").findAll(lower).map { it.value }.toList()
        for ((i, tok) in tokens.withIndex()) {
            if (tok in singleWords) return Verdict(Kind.NEVER, tok)
            if (tok == "pin" && tokens.getOrNull(i + 1) != "code") return Verdict(Kind.NEVER, "pin")
        }
        val padded = " " + tokens.joinToString(" ") + " "
        for (p in phrases) if (padded.contains(" $p ")) return Verdict(Kind.NEVER, p)
        if (panRx.containsMatchIn(lower)) return Verdict(Kind.NEVER, "pan")

        val postal = tokens.contains("pincode") || (tokens.contains("pin") && tokens.contains("code")) ||
            tokens.contains("zip") || tokens.contains("postal")
        var temporary = false
        for (m in numberRx.findAll(lower)) {
            val raw = m.value
            val digits = raw.count { it.isDigit() }
            val plus = raw.startsWith("+")
            when {
                plus && digits in 8..15 -> temporary = true
                plus -> return Verdict(Kind.NEVER, "number")
                digits >= 13 -> return Verdict(Kind.NEVER, "card or account number")
                digits == 12 -> {
                    val d = raw.filter { it.isDigit() }
                    if (d.startsWith("91") && d[2] in '6'..'9') temporary = true else return Verdict(Kind.NEVER, "aadhaar")
                }
                digits == 6 && postal -> Unit
                digits in 6..11 -> temporary = true
            }
        }
        return if (temporary) Verdict(Kind.TEMPORARY, "number") else Verdict(Kind.OK, "")
    }

    /** "9 8 7 6 5 4 3 2 1 0" for the first number in [text], so the user can hear it back digit by digit; null when there is none. */
    fun readback(text: String): String? {
        val m = numberRx.find(text.lowercase()) ?: return null
        return m.value.filter { it.isDigit() }.toCharArray().joinToString(" ")
    }
}
