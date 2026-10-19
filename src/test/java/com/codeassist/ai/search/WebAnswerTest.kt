package com.codeassist.ai.search

import com.codeassist.ai.ai.LocalMemory
import com.codeassist.ai.ai.Prompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Net search stage S2: prompt text, evidence sizing and the checked final answer. No model: the model's text is a string in the test. */
class WebAnswerTest {
    private val today = LocalDate.of(2026, 10, 17)

    private fun ev(id: Int, domain: String, text: String, date: String? = "2026-10-16", title: String = "Title $id") =
        Evidence(id, title, "https://$domain/p$id", domain, text, date, 0.9)

    private fun stats(kind: SearchKind = SearchKind.GENERAL) = WebAnswer.Stats(kind, 1, 1, 1800, 9200, false)

    private val twoSites = listOf(
        ev(1, "alpha.com", "Kotlin 2.3 was released with a new compiler. The download is 2.6 GB."),
        ev(2, "beta.org", "The Kotlin 2.3 release brings a faster build.", "2026-09-01")
    )

    // ---------- the prompt ----------

    @Test fun webRulesAreShortAndForbidLinksAndMemory() {
        val r = Prompts.WEB_RULES
        assertTrue(r.length < 800)
        assertTrue(r.contains("<web_data>"))
        assertTrue(r.contains("ONLY facts"))
        assertTrue(r.contains("square brackets"))
        assertTrue(r.contains("untrusted"))
        assertTrue(r.contains("Do not write web addresses"))
        assertFalse(r.contains("http"))
    }

    @Test fun theSystemTextCarriesTheUsersLanguageChoice() {
        val plain = WebAnswer.system(null, "Balanced")
        assertEquals(Prompts.WEB_RULES, plain)
        val hinglish = WebAnswer.system(LocalMemory.LANG_HINGLISH, "Balanced")
        assertTrue(hinglish.startsWith(Prompts.WEB_RULES))
        assertTrue(hinglish.contains("Roman Hinglish"))
        val english = WebAnswer.system(LocalMemory.LANG_ENGLISH, "Balanced")
        assertTrue(english.contains("reply in English"))
    }

    @Test fun replyLengthChangesOnlyTheLastLine() {
        assertTrue(WebAnswer.system(null, "Short").endsWith("2 or 3 sentences."))
        assertTrue(WebAnswer.system(null, "Long").endsWith("up to 10 sentences."))
        assertEquals(Prompts.WEB_RULES, WebAnswer.system(null, "Balanced"))
    }

    @Test fun theQuestionComesLastAndTheSourcesFirst() {
        val turn = WebAnswer.userTurn("<web_data>\n[1] x\n</web_data>", "  kotlin 2.3 kab aaya?  ", SearchKind.GENERAL)
        assertTrue(turn.startsWith("<web_data>"))
        assertTrue(turn.endsWith("Question: kotlin 2.3 kab aaya?"))
        assertTrue(turn.indexOf("</web_data>") < turn.indexOf("Answer the question"))
    }

    @Test fun newsAndValueGetADateRule() {
        assertTrue(WebAnswer.instruction(SearchKind.NEWS).contains("bullets"))
        assertTrue(WebAnswer.instruction(SearchKind.VALUE).contains("exact value"))
        assertFalse(WebAnswer.instruction(SearchKind.GENERAL).contains("bullets"))
    }

    // ---------- sizing ----------

    @Test fun theNumberOfSourcesFollowsTheWindow() {
        assertEquals(2, WebAnswer.maxSources(2048))
        assertEquals(4, WebAnswer.maxSources(4096))
        assertEquals(6, WebAnswer.maxSources(6144))
        assertEquals(8, WebAnswer.maxSources(8192))
    }

    @Test fun fourSourcesFitTheDefaultWindow() {
        val system = WebAnswer.system(LocalMemory.LANG_HINGLISH, "Balanced")
        val chars = WebAnswer.evidenceChars(4096, system.length, WebAnswer.instruction(SearchKind.NEWS).length + 500 + 40)
        // 4 sources of 900 characters with a header of about 100 each
        assertTrue("got $chars", chars >= 4 * (WebAnswer.PER_SOURCE_CHARS + 100))
        assertTrue("got $chars", chars < 8000)
    }

    @Test fun aSmallWindowGetsLessEvidence() {
        val a = WebAnswer.evidenceChars(2048, 1000, 400)
        val b = WebAnswer.evidenceChars(4096, 1000, 400)
        val c = WebAnswer.evidenceChars(8192, 1000, 400)
        assertTrue(a < b && b < c)
        assertTrue(a >= 600)
    }

    @Test fun evidenceNeverGetsLessThanTheMinimum() {
        assertEquals(600, WebAnswer.evidenceChars(2048, 9000, 2000))
    }

    @Test fun packedEvidenceStaysInsideTheBudgetItWasGiven() {
        val book = EvidenceBook()
        val hits = (1..6).map { i ->
            TavilyResult("Story $i about topic", "https://site$i.com/p", "word ".repeat(400), 1.0 - i / 10.0, "2026-10-15", emptyList())
        }
        book.add(hits)
        val system = WebAnswer.system(null, "Balanced")
        val turnChars = WebAnswer.instruction(SearchKind.GENERAL).length + 300
        val budget = WebAnswer.evidenceChars(4096, system.length, turnChars)
        val packed = book.pack(budget, WebAnswer.PER_SOURCE_CHARS, WebAnswer.maxSources(4096))
        assertEquals(4, packed.sources.size)
        assertTrue(packed.block.length <= budget + 40)
        // system + task + evidence must fit the promptBudget of the window, so ContextBudget never has to cut the evidence
        val totalTokens = com.codeassist.ai.ai.ContextBudget.estimateTokens(system + packed.block) +
            com.codeassist.ai.ai.ContextBudget.tokensForChars(turnChars) + com.codeassist.ai.ai.ContextBudget.PROMPT_OVERHEAD_TOKENS
        assertTrue("tokens $totalTokens", totalTokens <= com.codeassist.ai.ai.ContextBudget.promptBudget(4096))
    }

    // ---------- the checked answer ----------

    @Test fun aCitedAnswerKeepsItsTextAndShowsOnlyTheCitedSources() {
        val f = WebAnswer.finish("Kotlin 2.3 aa gaya hai [1].", twoSites, stats(), today)
        assertEquals(listOf(1), f.validation.usedIds)
        assertEquals(listOf(1), f.info.sources.map { it.id })
        assertFalse(f.info.citedNone)
        assertEquals(0, f.info.unverified)
        assertEquals(0, f.info.linksRemoved)
        assertFalse(f.info.oneSite)
        assertEquals("Kotlin 2.3 aa gaya hai [1].", f.text)
    }

    @Test fun anAnswerWithNoMarkerShowsAllPackedSourcesAndSaysSo() {
        val f = WebAnswer.finish("Haan, naya version aa gaya hai.", twoSites, stats(), today)
        assertTrue(f.info.citedNone)
        assertEquals(listOf(1, 2), f.info.sources.map { it.id })
    }

    @Test fun aNumberThatIsNotInTheSourceIsFlaggedAndExplained() {
        val f = WebAnswer.finish("Download 9.9 GB ka hai [1].", twoSites, stats(), today)
        assertEquals(1, f.info.unverified)
        assertTrue(f.text.contains(WebAnswer.UNVERIFIED_LABEL))
        assertTrue(f.text.endsWith(WebAnswer.FLAGGED_NOTE))
    }

    @Test fun aNumberWithNoMarkerIsFlagged() {
        val f = WebAnswer.finish("Size 2.6 GB hai.", twoSites, stats(), today)
        assertEquals(1, f.info.unverified)
        assertTrue(f.text.contains(WebAnswer.UNVERIFIED_LABEL))
    }

    @Test fun aLinkTheModelWroteIsRemovedAndCounted() {
        val f = WebAnswer.finish("Dekho https://evil.example/x yahan [1].", twoSites, stats(), today)
        assertEquals(1, f.info.linksRemoved)
        assertFalse(f.text.contains("evil.example"))
        assertFalse(f.text.contains("http"))
    }

    @Test fun aMarkerForAMissingSourceIsDropped() {
        val f = WebAnswer.finish("Compiler naya hai [7].", twoSites, stats(), today)
        assertFalse(f.text.contains("[7]"))
        assertTrue(f.info.citedNone)
        assertEquals(1, f.validation.count(CitationValidator.IssueKind.INVALID_MARKER))
    }

    @Test fun oneSiteOnlyAddsTheAppsOwnWarning() {
        val one = listOf(ev(1, "alpha.com", "Kotlin 2.3 was released today."), ev(2, "www.alpha.com", "More about Kotlin 2.3 here."))
        val f = WebAnswer.finish("Kotlin 2.3 aa gaya [1].", one, stats(), today)
        assertTrue(f.info.oneSite)
        assertTrue(f.text.endsWith(WebAnswer.ONE_SITE_NOTE))
    }

    @Test fun twoSitesGetNoWarning() {
        val f = WebAnswer.finish("Kotlin 2.3 aa gaya [1][2].", twoSites, stats(), today)
        assertFalse(f.text.contains("Note:"))
    }

    @Test fun newsGetsAFreshnessLineWrittenByTheApp() {
        val f = WebAnswer.finish("Compiler naya hai [1].", twoSites, stats(SearchKind.NEWS), today)
        assertTrue(f.text.contains("Sabse naya source: 2026-10-16 (1 din pehle)."))
    }

    @Test fun anEmptyModelTextStaysEmptyWithoutNotes() {
        val f = WebAnswer.finish("   ", twoSites, stats(SearchKind.NEWS), today)
        assertEquals("", f.text)
    }

    @Test fun theInfoCarriesTheCostOfTheTurn() {
        val f = WebAnswer.finish("Compiler naya hai [1].", twoSites, WebAnswer.Stats(SearchKind.GENERAL, 2, 2, 3000, 8000, true), today)
        assertEquals(2, f.info.credits)
        assertEquals(2, f.info.rounds)
        assertEquals(3000L, f.info.searchMs)
        assertEquals(8000L, f.info.composeMs)
        assertTrue(f.info.cached)
        assertEquals("GENERAL", f.info.kind)
    }

    // ---------- freshness helpers ----------

    @Test fun newestLineSaysHowOldTheNewestSourceIs() {
        assertEquals("Sabse naya source: 2026-10-17 (aaj).", WebAnswer.newestLine(listOf(ev(1, "a.com", "t", "2026-10-17")), today))
        assertEquals("Sabse naya source: 2026-10-16 (1 din pehle).", WebAnswer.newestLine(twoSites, today))
        assertEquals("Sabse naya source: 2026-10-07 (10 din pehle).", WebAnswer.newestLine(listOf(ev(1, "a.com", "t", "2026-10-07")), today))
    }

    @Test fun newestLineWarnsWhenEverySourceIsOld() {
        val line = WebAnswer.newestLine(listOf(ev(1, "a.com", "t", "2026-08-01")), today)!!
        assertTrue(line.contains("77 din"))
        assertTrue(line.startsWith("Dhyan do"))
    }

    @Test fun newestLineIsNullWithoutDates() {
        assertNull(WebAnswer.newestLine(listOf(ev(1, "a.com", "t", null)), today))
        assertNull(WebAnswer.newestLine(emptyList(), today))
    }

    @Test fun oldSourcesAreMarkedOld() {
        assertTrue(WebAnswer.toWebSource(ev(1, "a.com", "t", "2026-08-01"), today).old)
        assertFalse(WebAnswer.toWebSource(ev(1, "a.com", "t", "2026-10-10"), today).old)
        assertFalse(WebAnswer.toWebSource(ev(1, "a.com", "t", null), today).old)
    }

    // ---------- the fallbacks ----------

    @Test fun evidenceOnlyInfoShowsEverySourceAndCitesNone() {
        val info = WebAnswer.evidenceOnlyInfo(twoSites, stats(), today)
        assertTrue(info.citedNone)
        assertEquals(2, info.sources.size)
        assertTrue(WebAnswer.evidenceOnlyText("timeout").contains("timeout"))
        assertTrue(WebAnswer.noSourceText().contains("source nahi mila"))
    }
}
