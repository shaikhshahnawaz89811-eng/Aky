package com.codeassist.ai.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Net search stage S2: the rules that decide the rounds of one turn. No model, no network. */
class SearchPlannerTest {
    private fun plan(
        kind: SearchKind,
        freshness: Freshness = Freshness.ANY,
        credits: Int = 2,
        rounds: Int = 2,
        domains: List<String> = emptyList(),
        query: String = "gemma 4"
    ) = SearchPlan(listOf(kind), emptyList(), freshness, domains, credits, rounds, false, query)

    private fun req(query: String = "gemma 4", topic: String = "general", f: Freshness = Freshness.ANY, d: List<String> = emptyList()) =
        SearchRequest(query = query, topic = topic, freshness = f, includeDomains = d)

    // ---------- which plans this stage answers ----------

    @Test fun generalNewsAndValueAreSupported() {
        assertTrue(SearchPlanner.supported(plan(SearchKind.GENERAL)))
        assertTrue(SearchPlanner.supported(plan(SearchKind.NEWS)))
        assertTrue(SearchPlanner.supported(plan(SearchKind.VALUE, credits = 3)))
    }

    @Test fun otherKindsAndMixedPlansAreNotSupported() {
        for (k in SearchKind.values()) {
            if (k in SearchPlanner.SUPPORTED) continue
            assertFalse(k.name, SearchPlanner.supported(plan(k)))
        }
        val mixed = SearchPlan(listOf(SearchKind.COMPARE, SearchKind.DOWNLOAD_PAGE), emptyList(), Freshness.ANY, emptyList(), 10, 3, false, "x")
        assertFalse(SearchPlanner.supported(mixed))
        val empty = SearchPlan(emptyList(), emptyList(), Freshness.ANY, emptyList(), 1, 1, false, "x")
        assertFalse(SearchPlanner.supported(empty))
    }

    @Test fun realPhrasesRouteAsExpected() {
        val news = SearchIntent.detect("latest AI news")!!
        assertTrue(SearchPlanner.supported(news))
        assertTrue(SearchPlanner.isNews(news))
        val general = SearchIntent.detect("kotlin coroutines ke baare me google karo")!!
        assertTrue(SearchPlanner.supported(general))
        assertFalse(SearchPlanner.isNews(general))
        val value = SearchIntent.detect("gemma 4 ka latest version search karo")!!
        assertEquals(listOf(SearchKind.VALUE), value.kinds)
        assertTrue(SearchPlanner.supported(value))
        assertFalse(SearchPlanner.supported(SearchIntent.detect("gemma official website")!!))
        assertFalse(SearchPlanner.supported(SearchIntent.detect("gemma 4 vs qwen 2.5 compare karo")!!))
    }

    // ---------- caps ----------

    @Test fun aTurnNeverSpendsMoreThanTwoCredits() {
        assertEquals(2, SearchPlanner.creditCap(plan(SearchKind.GENERAL)))
        assertEquals(2, SearchPlanner.creditCap(plan(SearchKind.VALUE, credits = 3)))
        assertEquals(1, SearchPlanner.creditCap(plan(SearchKind.GENERAL, credits = 1, rounds = 1)))
    }

    @Test fun roundsFollowTheCreditCap() {
        assertEquals(2, SearchPlanner.roundCap(plan(SearchKind.GENERAL)))
        assertEquals(2, SearchPlanner.roundCap(plan(SearchKind.REPORT, credits = 12, rounds = 4)))
        assertEquals(1, SearchPlanner.roundCap(plan(SearchKind.GENERAL, credits = 1, rounds = 1)))
        assertEquals(1, SearchPlanner.roundCap(plan(SearchKind.GENERAL, credits = 0, rounds = 0)))
    }

    // ---------- the first request ----------

    @Test fun newsWithNoFreshnessWordAsksForTheLastWeek() {
        val r = SearchPlanner.first(plan(SearchKind.NEWS), "latest AI news")
        assertEquals("news", r.topic)
        assertEquals(Freshness.WEEK, r.freshness)
    }

    @Test fun newsKeepsAFreshnessTheUserSaid() {
        assertEquals(Freshness.DAY, SearchPlanner.first(plan(SearchKind.NEWS, Freshness.DAY), "aaj ki khabar").freshness)
        assertEquals(Freshness.MONTH, SearchPlanner.first(plan(SearchKind.NEWS, Freshness.MONTH), "x news").freshness)
    }

    @Test fun generalIsABasicGeneralSearchOfFiveResults() {
        val r = SearchPlanner.first(plan(SearchKind.GENERAL, domains = listOf("example.com")), "kotlin coroutines")
        assertEquals("general", r.topic)
        assertEquals("basic", r.depth)
        assertEquals(5, r.maxResults)
        assertEquals(Freshness.ANY, r.freshness)
        assertEquals(listOf("example.com"), r.includeDomains)
        assertEquals("kotlin coroutines", r.query)
        assertFalse(r.includeAnswer)
    }

    // ---------- what comes next ----------

    private fun next(
        p: SearchPlan = plan(SearchKind.GENERAL),
        done: Int = 1,
        domains: Int = 1,
        elapsed: Long = 1000,
        afford: Boolean = true,
        last: SearchRequest = req("\"gemma 4\" release 2026")
    ) = SearchPlanner.next(p, done, domains, elapsed, afford, last)

    @Test fun twoIndependentSitesEndTheTurn() {
        val n = next(domains = 2)
        assertTrue(n is SearchPlanner.Next.Stop)
        assertEquals(SearchPlanner.StopReason.ENOUGH, (n as SearchPlanner.Next.Stop).reason)
    }

    @Test fun enoughEvidenceWinsOverEveryOtherReason() {
        val n = next(done = 5, domains = 3, elapsed = 999_999, afford = false)
        assertEquals(SearchPlanner.StopReason.ENOUGH, (n as SearchPlanner.Next.Stop).reason)
    }

    @Test fun theRoundLimitStops() {
        val n = next(done = 2, domains = 1)
        assertEquals(SearchPlanner.StopReason.ROUNDS_USED, (n as SearchPlanner.Next.Stop).reason)
    }

    @Test fun theDeadlineStops() {
        val n = next(elapsed = SearchPlanner.DEADLINE_MS)
        assertEquals(SearchPlanner.StopReason.DEADLINE, (n as SearchPlanner.Next.Stop).reason)
    }

    @Test fun noMoneyStops() {
        val n = next(afford = false)
        assertEquals(SearchPlanner.StopReason.BUDGET, (n as SearchPlanner.Next.Stop).reason)
    }

    @Test fun aSecondRoundIsBroaderWhenOnlyOneSiteCameBack() {
        val n = next()
        assertTrue(n is SearchPlanner.Next.Search)
        val r = (n as SearchPlanner.Next.Search).request
        assertEquals("gemma 4 release", r.query)
        assertTrue(r.includeDomains.isEmpty())
    }

    @Test fun noSecondRoundWhenItWouldAskTheSameThing() {
        val n = next(last = req("gemma 4 release"))
        assertEquals(SearchPlanner.StopReason.NOTHING_NEW, (n as SearchPlanner.Next.Stop).reason)
    }

    @Test fun newsWidensItsFreshnessInsteadOfTheWords() {
        val n = next(p = plan(SearchKind.NEWS, Freshness.DAY), last = req("aaj ki khabar", "news", Freshness.DAY))
        val r = (n as SearchPlanner.Next.Search).request
        assertEquals("aaj ki khabar", r.query)
        assertEquals(Freshness.WEEK, r.freshness)
        assertEquals("news", r.topic)
    }

    @Test fun aSiteRestrictionIsDroppedInTheSecondRound() {
        val n = next(last = req("gemma 4", "general", Freshness.ANY, listOf("example.com")))
        val r = (n as SearchPlanner.Next.Search).request
        assertTrue(r.includeDomains.isEmpty())
    }

    // ---------- small helpers ----------

    @Test fun broadenQueryRemovesQuotesYearsAndSiteOperators() {
        assertEquals("gemma 4 release", SearchPlanner.broadenQuery("\"gemma 4\" release 2026"))
        assertEquals("gemma 4", SearchPlanner.broadenQuery("site:example.com gemma 4"))
        assertEquals("world cup winner", SearchPlanner.broadenQuery("world cup 2022 winner"))
        assertEquals("kotlin coroutines", SearchPlanner.broadenQuery("kotlin  coroutines"))
    }

    @Test fun broadenQueryKeepsTheOriginalWhenNothingUsefulIsLeft() {
        assertEquals("2026", SearchPlanner.broadenQuery("2026"))
        assertEquals("\"ab\"", SearchPlanner.broadenQuery("\"ab\""))
    }

    @Test fun freshnessWidensStepByStep() {
        assertEquals(Freshness.WEEK, SearchPlanner.widen(Freshness.DAY))
        assertEquals(Freshness.MONTH, SearchPlanner.widen(Freshness.WEEK))
        assertEquals(Freshness.ANY, SearchPlanner.widen(Freshness.MONTH))
        assertEquals(Freshness.ANY, SearchPlanner.widen(Freshness.ANY))
    }

    @Test fun signatureIgnoresCaseAndPunctuationButNotTopicFreshnessOrDomains() {
        assertEquals(SearchPlanner.signature(req("Gemma 4?")), SearchPlanner.signature(req("gemma  4")))
        assertNotEquals(SearchPlanner.signature(req("gemma 4")), SearchPlanner.signature(req("gemma 4", "news")))
        assertNotEquals(SearchPlanner.signature(req("gemma 4")), SearchPlanner.signature(req("gemma 4", f = Freshness.WEEK)))
        assertNotEquals(SearchPlanner.signature(req("gemma 4")), SearchPlanner.signature(req("gemma 4", d = listOf("a.com"))))
        assertNotNull(SearchPlanner.signature(req()))
    }
}
