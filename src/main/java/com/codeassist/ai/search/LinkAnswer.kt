package com.codeassist.ai.search

import java.util.Locale

/** Small formatting helpers for the cards. Pure. */
object LinkFmt {
    /** `2026-10-10T08:00:00Z` -> `2026-10-10`. */
    fun day(iso: String): String = iso.trim().take(10)

    /** 2.6 GB / 120 MB / 800 KB / 90 B. Decimal units, like the phone's own download screen. */
    fun size(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(Locale.US, "%.2f GB", bytes / 1e9)
        bytes >= 1_000_000L -> String.format(Locale.US, "%d MB", bytes / 1_000_000L)
        bytes >= 1_000L -> String.format(Locale.US, "%d KB", bytes / 1_000L)
        else -> "$bytes B"
    }

    private val link = Regex("(?:https?://|www\\.)\\S+")
    private val control = Regex("[\\p{Cc}\\u200B-\\u200F\\u2028-\\u202E\\u2060\\uFEFF]")
    private val spaces = Regex("\\s+")

    /** Untrusted text from the web (a repo description) made safe for a card: no link, no control or hidden characters, one line, [max] characters. */
    fun clean(text: String, max: Int): String {
        var s = link.replace(text, " ")
        s = control.replace(s, " ")
        s = spaces.replace(s, " ").trim()
        return if (s.length <= max) s else s.take(max - 1).trimEnd() + "\u2026"
    }
}

/**
 * Net Search Agent, stage S3: the words of a link answer. Every sentence here is written by the app from facts the flow found (audit 6.2:
 * "no compose call needed ... fastest, no hallucination surface"). The text never contains a web address: the addresses live only in the
 * cards, so the chat history that goes back to the model has none. Never says "safe" or "verified": a checked file is "checked: https,
 * redirects, file type", nothing more.
 */
object LinkAnswer {
    const val NOTHING_TEXT =
        "Is sawaal ke liye koi bharosemand link nahi mila, isliye main andaza nahi laga raha. " +
            "Sawaal thoda alag shabdon mein likh kar dobara try karo."

    const val GITHUB_LIMIT_TEXT =
        "GitHub ka free limit (60 request ek ghante mein) poora ho gaya. Thodi der baad dobara try karo."

    const val GITHUB_DOWN_TEXT = "GitHub se jawab nahi aaya (network ya server ki dikkat). Thodi der baad dobara try karo."

    const val CHECKED_NOTE =
        "\u201Cchecked\u201D ka matlab sirf: https, redirects aur file type ki jaanch hui. Ye file safe hone ki guarantee nahi hai."

    const val INSTALL_NOTE = "Download dabane par pehle poochunga; install tum khud karoge, main kuch auto-install nahi karta."

    const val EXTRACT_NOTE = "(Vendor page ka text padhne mein dikkat aayi, isliye wahan se file link nahi dekh paaya.)"

    fun githubErrorText(f: SearchFailure?): String =
        if (f != null && f.kind == FailureKind.RATE_LIMITED) GITHUB_LIMIT_TEXT else GITHUB_DOWN_TEXT

    private fun filesWord(n: Int, wanted: List<String>): String {
        val kind = if (wanted.isEmpty()) "" else " (." + wanted.joinToString("/.") + ")"
        return "$n file$kind"
    }

    /** The reply text for [o]. [o] must not be [LinkSummary.NEED_SEARCH] or [LinkSummary.STOPPED] (the runner never shows those). */
    fun text(o: LinkOutcome): String {
        val sb = StringBuilder()
        val first = o.cards.firstOrNull()
        when (o.summary) {
            LinkSummary.OFFICIAL -> {
                sb.append((first?.domain ?: "Ye site") + " sabse zyada official lag rahi hai (likely official: naam match karta hai aur ")
                    .append("is domain ke kai pages search mein mile). Pakka nahi hai: kholne se pehle domain ek baar dekh lo.")
                if (o.cards.size > 1) sb.append(" Neeche ").append(o.cards.size - 1).append(" aur option hain, wo \u201Cnot confirmed\u201D hain.")
            }
            LinkSummary.OFFICIAL_UNCONFIRMED ->
                sb.append("Official site pakki nahi hui: koi bhi result \u201Clikely official\u201D nahi nikla. ")
                    .append("Ye candidates mile hain, domain dekh kar khud tay karo.")
            LinkSummary.PAGES -> {
                sb.append("Download ke liye ye pages mile. Har card par class aur domain likha hai; GitHub aur official wale pehle rakhe hain. ")
                    .append("Third-party mirror \u201Clow trust\u201D hote hain.")
            }
            LinkSummary.PAGES_LOW_ONLY ->
                sb.append("Bharosemand download page nahi mila: sirf third-party mirror ya shaky naam wale pages mile (low trust). ")
                    .append("Inhe tabhi kholo jab koi aur raasta na ho.")
            LinkSummary.FILES -> {
                val files = o.cards.count { it.role == "file" }
                sb.append(filesWord(files, o.wanted)).append(" mili.")
                if (o.release != null) sb.append(" Release ").append(o.release.tag).append(": naam, size aur type GitHub API se aaye hain.")
                if (o.cards.any { it.role == "file" && it.checksum == null }) sb.append(" Checksum wahi dikhaya jo publisher ne diya, baaki mein nahi mila.")
                if (o.hiddenFiles > 0) sb.append(" Aur ").append(o.hiddenFiles).append(" file hain, release page par dekho.")
                if (o.cards.any { it.role == "file" && (it.detail ?: "").contains("checked:") }) sb.append(" ").append(CHECKED_NOTE)
                sb.append(" ").append(INSTALL_NOTE)
                if (o.cards.any { it.role == "file" && it.runnable }) sb.append(" Ye chalne wali file hai: kholne se pehle source par bharosa kar lo.")
            }
            LinkSummary.FILES_NONE_OF_TYPE ->
                sb.append("Latest release").append(o.release?.let { " (" + it.tag + ")" } ?: "").append(" mein ")
                    .append(o.wanted.joinToString("/.", ".")).append(" file nahi hai. Release page neeche hai, wahan baaki files dekh sakte ho.")
            LinkSummary.FILES_PAGE_ONLY -> {
                sb.append("Direct file link pakka nahi mila, isliye link nahi bana raha: andaza lagana galat ho sakta hai. ")
                    .append("Neeche wo page hai jahan file milni chahiye.")
                if (o.guardRejects > 0) sb.append(" ").append(o.guardRejects).append(" file link jaanch mein fail hue, isliye nahi dikhaye.")
                if (o.extractFailure != null) sb.append(" ").append(EXTRACT_NOTE)
            }
            LinkSummary.GITHUB -> {
                sb.append((o.repo?.fullName ?: "Repo") + " GitHub par mila.")
                if (o.release != null) {
                    sb.append(" Latest release: ").append(o.release.tag)
                    if (o.release.publishedAt != null) sb.append(" (").append(LinkFmt.day(o.release.publishedAt)).append(")")
                    sb.append(". Ye sab GitHub API ke fields hain.")
                }
                val files = o.cards.count { it.role == "file" }
                if (files > 0) sb.append(" ").append(filesWord(files, o.wanted)).append(" neeche hain. ").append(INSTALL_NOTE)
                if (o.hiddenFiles > 0) sb.append(" Aur ").append(o.hiddenFiles).append(" file hain, release page par dekho.")
            }
            LinkSummary.GITHUB_NO_RELEASE ->
                sb.append((o.repo?.fullName ?: "Repo") + " GitHub par hai, par isme koi published stable release nahi hai, isliye file nahi de sakta. ")
                    .append("Repo ka page neeche hai.")
            LinkSummary.GITHUB_NOT_FOUND -> {
                sb.append("GitHub par ye repo nahi mila.")
                if (o.cards.isNotEmpty()) sb.append(" Search mein ye pages aaye the (pakke nahi hain, domain dekh kar khud tay karo).")
            }
            LinkSummary.GITHUB_ERROR -> {
                sb.append(githubErrorText(o.ghFailure))
                if (o.repo != null) sb.append(" Repo ka page neeche hai.")
            }
            LinkSummary.NOTHING, LinkSummary.NEED_SEARCH, LinkSummary.STOPPED -> sb.append(NOTHING_TEXT)
        }
        return sb.toString()
    }
}
