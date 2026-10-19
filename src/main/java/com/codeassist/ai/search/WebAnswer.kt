package com.codeassist.ai.search

import com.codeassist.ai.ai.ContextBudget
import com.codeassist.ai.ai.Prompts
import java.time.LocalDate

/**
 * Net Search Agent, stage S2: everything between "the evidence is packed" and "the message is stored", with no Android class and
 * no model in it (the model call itself lives in SearchRunner), so each rule is tested on the JVM.
 *
 *  - [system] and [userTurn]: what the model reads. The sources come first, the question comes LAST (if anything is ever cut,
 *    ContextBudget keeps the end of the turn); the system text is [Prompts.WEB_RULES] plus the user's language choice.
 *  - [evidenceChars] / [maxSources]: how much evidence fits the model's window (audit 5.3), sized BEFORE the call so
 *    ContextBudget.fit() never has to cut it.
 *  - [finish]: runs [CitationValidator], adds the notes the APP writes (one site only, how fresh the newest source is) and builds
 *    the [WebInfo]. The stored text has no link and no evidence; the model never writes the sources.
 */
object WebAnswer {
    const val PER_SOURCE_CHARS = 900

    /** Evidence for the cloud brain: its window is large, so up to 8 sources (audit 5.3 rule 7). */
    const val CLOUD_MAX_SOURCES = 8
    const val CLOUD_TOTAL_CHARS = 9_000

    /** The sampler of the phone model for a web answer (audit 5.3 rule 5: grounded summarising wants a low temperature). */
    const val LOCAL_TEMPERATURE = 0.25
    const val CLOUD_TEMPERATURE = 0.3f

    /** Never pack less than this (a source with less room than this is not worth a slot). */
    private const val MIN_EVIDENCE_CHARS = 600

    /** Tokens kept back for chat-template markers and the estimator's error (audit 5.3: measure in S2, estimates here). */
    private const val SAFETY_TOKENS = 48

    const val UNVERIFIED_LABEL = "(verify nahi hua)"

    const val ONE_SITE_NOTE = "Note: sirf ek hi website se source mila, isliye ise pakka mat maano."

    const val FLAGGED_NOTE = "Note: jin lines ke aage (verify nahi hua) likha hai, unhe sources se khud milao."

    const val NO_SOURCE_TEXT =
        "Is sawaal ka bharosemand source nahi mila, isliye main kuch andaza nahi laga raha. " +
            "Sawaal thoda alag shabdon mein likh kar dobara try karo."

    /** The system prompt of a web answer: the rules, plus the language the user chose ("Hinglish me batao"). */
    fun system(replyLanguage: String?, replyLength: String): String {
        val sb = StringBuilder(Prompts.WEB_RULES)
        val lang = Prompts.languageLine(replyLanguage)
        if (lang.isNotEmpty()) sb.append("\n").append(lang)
        when (replyLength) {
            "Short" -> sb.append("\nKeep the answer to 2 or 3 sentences.")
            "Long" -> sb.append("\nYou may write up to 10 sentences.")
        }
        return sb.toString()
    }

    /** The one-line task that follows the sources. News and value questions get a rule about dates. */
    fun instruction(kind: SearchKind): String {
        val base = "Answer the question below using only the sources above, and put the source number after every fact."
        return when (kind) {
            SearchKind.NEWS -> "$base Write short bullets, each with its date when the source header shows one."
            SearchKind.VALUE -> "$base Give the exact value as the source writes it, with the date shown in the source header."
            else -> base
        }
    }

    /** Sources first, task, then the question (last, so a cut can only ever take the start of the sources). */
    fun userTurn(block: String, question: String, kind: SearchKind): String =
        block + "\n\n" + instruction(kind) + "\n\nQuestion: " + question.trim()

    /** How many sources the phone model's window can hold: 2 at 2048, 4 at 4096, 6 at 6144, 8 above. */
    fun maxSources(contextTokens: Int): Int = when {
        contextTokens <= 2048 -> 2
        contextTokens <= 4096 -> 4
        contextTokens <= 6144 -> 6
        else -> CLOUD_MAX_SOURCES
    }

    /**
     * Characters of evidence (headers included) that fit the phone model's window next to [systemChars] of system text and
     * [turnChars] of task plus question. Uses the same pessimistic estimator as ContextBudget (2.6 characters a token).
     */
    fun evidenceChars(contextTokens: Int, systemChars: Int, turnChars: Int): Int {
        val room = ContextBudget.promptBudget(contextTokens) -
            ContextBudget.tokensForChars(systemChars) -
            ContextBudget.tokensForChars(turnChars) -
            ContextBudget.PROMPT_OVERHEAD_TOKENS - ContextBudget.TURN_OVERHEAD_TOKENS - SAFETY_TOKENS
        val chars = (room * ContextBudget.CHARS_PER_TOKEN).toInt()
        return maxOf(MIN_EVIDENCE_CHARS, chars)
    }

    class Finished(val text: String, val info: WebInfo, val validation: CitationValidator.Result)

    /** What the turn did, for the caption and the stored info. */
    class Stats(
        val kind: SearchKind,
        val credits: Int,
        val rounds: Int,
        val searchMs: Long,
        val composeMs: Long,
        val cached: Boolean
    )

    /**
     * Checks [modelText] against the packed [sources] and builds the final text and info. An empty result text stays empty (the
     * caller treats it as "the model said nothing"). [today] is a parameter so the freshness lines are testable.
     */
    fun finish(modelText: String, sources: List<Evidence>, stats: Stats, today: LocalDate): Finished {
        val checked = CitationValidator.validate(
            modelText, sources, CitationValidator.Options(dropUnsupported = false, unverifiedLabel = UNVERIFIED_LABEL)
        )
        val shown = pickShown(sources, checked.usedIds)
        val oneSite = EvidenceBook.independentDomains(sources) < 2

        val notes = ArrayList<String>()
        if (checked.text.isNotBlank()) {
            if (oneSite) notes.add(ONE_SITE_NOTE)
            if (stats.kind == SearchKind.NEWS) {
                val line = newestLine(shown.ifEmpty { sources }, today)
                if (line != null) notes.add(line)
            }
            if (checked.count(CitationValidator.IssueKind.UNCITED_NUMBER) + checked.count(CitationValidator.IssueKind.NUMBER_NOT_IN_SOURCE) > 0) {
                notes.add(FLAGGED_NOTE)
            }
        }
        val text = if (notes.isEmpty()) checked.text else checked.text + "\n\n" + notes.joinToString("\n")

        val info = WebInfo(
            kind = stats.kind.name,
            sources = shown.map { toWebSource(it, today) },
            citedNone = checked.usedIds.isEmpty() && sources.isNotEmpty(),
            credits = stats.credits,
            rounds = stats.rounds,
            searchMs = stats.searchMs,
            composeMs = stats.composeMs,
            cached = stats.cached,
            unverified = checked.count(CitationValidator.IssueKind.UNCITED_NUMBER) +
                checked.count(CitationValidator.IssueKind.NUMBER_NOT_IN_SOURCE),
            linksRemoved = checked.count(CitationValidator.IssueKind.LINK_REMOVED),
            oneSite = oneSite
        )
        return Finished(text, info, checked)
    }

    /** The sources to show under the answer: the cited ones, or all packed ones when the answer cited none. */
    fun pickShown(sources: List<Evidence>, usedIds: List<Int>): List<Evidence> {
        if (usedIds.isEmpty()) return sources
        return sources.filter { it.id in usedIds }
    }

    fun toWebSource(e: Evidence, today: LocalDate): WebSource =
        WebSource(e.id, e.title, e.url, e.domain, e.date, EvidenceBook.isOld(e.date, today))

    /**
     * "Sabse naya source: 2026-10-16 (1 din pehle)." written by the app from the source dates, or a warning when every dated source
     * is older than 30 days, or null when no source has a date. Never the model's words.
     */
    fun newestLine(sources: List<Evidence>, today: LocalDate): String? {
        var best: String? = null
        for (e in sources) {
            val d = e.date ?: continue
            if (best == null || d > best) best = d
        }
        val newest = best ?: return null
        val age = EvidenceBook.ageDays(newest, today) ?: return null
        if (age > EvidenceBook.OLD_AFTER_DAYS) {
            return "Dhyan do: sabse naya source bhi $age din purana hai ($newest). Ye ab purani khabar ho sakti hai."
        }
        val ago = when {
            age <= 0L -> "aaj"
            age == 1L -> "1 din pehle"
            else -> "$age din pehle"
        }
        return "Sabse naya source: $newest ($ago)."
    }

    /** The reply when the search ran but nothing usable came back (not an error: the question just found no source). */
    fun noSourceText(): String = NO_SOURCE_TEXT

    /** The reply when the answer could not be written but sources were found: the sources are still shown as chips. */
    fun evidenceOnlyText(reason: String): String =
        "Jawab likhne mein dikkat aayi ($reason), lekin ye sources mile hain. Neeche se khol kar padh lo."

    /** Sources of a packed block for the evidence-only reply. */
    fun evidenceOnlyInfo(sources: List<Evidence>, stats: Stats, today: LocalDate): WebInfo = WebInfo(
        kind = stats.kind.name,
        sources = sources.map { toWebSource(it, today) },
        citedNone = true,
        credits = stats.credits,
        rounds = stats.rounds,
        searchMs = stats.searchMs,
        composeMs = stats.composeMs,
        cached = stats.cached,
        unverified = 0,
        linksRemoved = 0,
        oneSite = EvidenceBook.independentDomains(sources) < 2
    )
}
