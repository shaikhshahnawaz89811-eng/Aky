package com.codeassist.ai.search

import com.codeassist.ai.search.CitationValidator.IssueKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CitationValidatorTest {
    private fun ev(id: Int, text: String, date: String? = null) =
        Evidence(id, "Title $id", "https://site$id.com/p", "site$id.com", text, date, 0.5)

    private val gemma = ev(1, "Gemma 4 was released on 12 October 2026 and needs 3.2 GB of storage.")

    private fun check(model: String, vararg sources: Evidence, options: CitationValidator.Options = CitationValidator.Options()) =
        CitationValidator.validate(model, sources.toList(), options)

    @Test fun aSupportedSentenceStaysAsItIs() {
        val r = check("Gemma 4 launched on 12 October 2026 [1].", gemma)
        assertEquals("Gemma 4 launched on 12 October 2026 [1].", r.text)
        assertEquals(listOf(1), r.usedIds)
        assertTrue(r.clean)
    }

    @Test fun aNumberThatIsNotInTheCitedSourceIsFlagged() {
        val r = check("Gemma 4 needs 8 GB of storage [1].", gemma)
        assertEquals("Gemma 4 needs 8 GB of storage [1]. (unverified)", r.text)
        assertEquals(1, r.count(IssueKind.NUMBER_NOT_IN_SOURCE))
        assertTrue(r.usedIds.isEmpty())
    }

    @Test fun anUnsupportedSentenceCanBeDroppedInstead() {
        val r = check("Gemma 4 needs 8 GB of storage [1].", gemma, options = CitationValidator.Options(dropUnsupported = true))
        assertEquals("", r.text)
        assertEquals(1, r.count(IssueKind.NUMBER_NOT_IN_SOURCE))
    }

    @Test fun aNumberWithoutAnyMarkerIsFlagged() {
        val r = check("Gemma 4 launched in 2026.", gemma)
        assertEquals("Gemma 4 launched in 2026. (unverified)", r.text)
        assertEquals(1, r.count(IssueKind.UNCITED_NUMBER))
    }

    @Test fun theUnverifiedLabelCanBeChanged() {
        val r = check("Gemma 4 launched in 2026.", gemma, options = CitationValidator.Options(unverifiedLabel = "(verify nahi hua)"))
        assertEquals("Gemma 4 launched in 2026. (verify nahi hua)", r.text)
    }

    @Test fun aMarkerForAMissingSourceIsRemoved() {
        val r = check("Gemma 4 is new [7].", gemma)
        assertEquals("Gemma 4 is new.", r.text)
        assertEquals(1, r.count(IssueKind.INVALID_MARKER))
        assertTrue(r.usedIds.isEmpty())
    }

    @Test fun linksTheModelWroteAreRemoved() {
        val r = check("See [the docs](https://evil.example/x) and https://evil.example/y for more [1].", gemma)
        assertEquals("See the docs and for more [1].", r.text)
        assertFalse(r.text.contains("http"))
        assertEquals(1, r.count(IssueKind.LINK_REMOVED))
        assertEquals(listOf(1), r.usedIds)
    }

    @Test fun listMarkersAreExpanded() {
        val r = check("Gemma 4 is out [1, 2].", gemma, ev(2, "More about Gemma 4."))
        assertEquals("Gemma 4 is out [1][2].", r.text)
        assertEquals(listOf(1, 2), r.usedIds)
    }

    @Test fun aMarkerAfterTheFullStopMovesInFrontOfIt() {
        val r = check("Gemma 4 launched in 2026. [1] It runs offline.", gemma)
        assertEquals("Gemma 4 launched in 2026 [1]. It runs offline.", r.text)
        assertEquals(listOf(1), r.usedIds)
        assertTrue(r.clean)
    }

    @Test fun thousandsCommasDoNotMatter() {
        val src = ev(1, "The price is Rs 8,000 for the base model.")
        assertTrue(check("Price is 8000 rupees [1].", src).clean)
        assertTrue(check("Price is 8,000 [1].", src).clean)
    }

    @Test fun singleDigitsWithoutAUnitAreNotChecked() {
        val src = ev(1, "No numbers here at all in this source.")
        assertTrue(check("There are 3 options [1].", src).clean)
    }

    @Test fun aSingleDigitWithAUnitIsChecked() {
        val src = ev(1, "No numbers here at all in this source.")
        assertEquals(1, check("Only 5% is left [1].", src).count(IssueKind.NUMBER_NOT_IN_SOURCE))
        assertEquals(1, check("It needs 4 GB [1].", src).count(IssueKind.NUMBER_NOT_IN_SOURCE))
    }

    @Test fun sentencesWithoutNumbersNeedNoMarker() {
        val r = check("Gemma works offline.", gemma)
        assertEquals("Gemma works offline.", r.text)
        assertTrue(r.clean)
        assertTrue(r.usedIds.isEmpty())
    }

    @Test fun decimalsAreNotSplitIntoSentences() {
        val src = ev(1, "Version 2.5 is out now.")
        val r = check("Version 2.5 is out [1].", src)
        assertEquals("Version 2.5 is out [1].", r.text)
        assertTrue(r.clean)
    }

    @Test fun aNumberMustBeInTheSourceItCites() {
        val a = ev(1, "Gemma 4 has 2 billion parameters in the small model.")
        val b = ev(2, "Qwen 2.5 has 1.5 billion parameters in the small model.")
        assertTrue(check("Qwen has 1.5 billion parameters [2].", a, b).clean)
        assertEquals(1, check("Qwen has 1.5 billion parameters [1].", a, b).count(IssueKind.NUMBER_NOT_IN_SOURCE))
    }

    @Test fun numberedListLinesKeepTheirNumbers() {
        val src = ev(1, "Gemma 4 is out. Qwen is old.")
        val r = check("1. Gemma 4 is out [1]\n2. Qwen is old [1]\n10. Done", src)
        assertEquals("1. Gemma 4 is out [1]\n2. Qwen is old [1]\n10. Done", r.text)
        assertTrue(r.clean)
    }

    @Test fun usedIdsAreAscendingAndUnique() {
        val r = check("Gemma is old [2]. Qwen is new [1][2].", ev(1, "a"), ev(2, "b"))
        assertEquals(listOf(1, 2), r.usedIds)
    }

    @Test fun factNumbersReadsPricesVersionsYearsAndSkipsListNumbers() {
        assertEquals(listOf("12", "2026", "299", "2.5"), CitationValidator.factNumbers("Released 12 Oct 2026 for \$299 (v2.5)"))
        assertEquals(emptyList<String>(), CitationValidator.factNumbers("1. First item"))
        assertEquals(listOf("5"), CitationValidator.factNumbers("It has 3 modes and 5 GB"))
        assertEquals(listOf("8000"), CitationValidator.factNumbers("Costs 8,000 [1]"))
    }

    @Test fun sourcesListUsesOnlyEvidenceTheTextCites() {
        val list = listOf(ev(1, "a", "2026-10-12"), ev(2, "b"))
        assertEquals("[2] site2.com - Title 2\nhttps://site2.com/p", CitationValidator.sourcesList(list, listOf(2)))
        assertEquals("[1] site1.com - Title 1 (2026-10-12)\nhttps://site1.com/p", CitationValidator.sourcesList(list, listOf(1)))
        assertEquals("", CitationValidator.sourcesList(list, emptyList()))
    }
}
