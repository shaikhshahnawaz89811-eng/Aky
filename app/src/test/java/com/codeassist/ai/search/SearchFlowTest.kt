package com.codeassist.ai.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** Net search stage S2: the searching loop with a fake provider. No network, no sleeping, no model. */
class SearchFlowTest {
    private var now = 1_000_000_000L
    private val state = InMemoryBudgetState()
    private val requests = ArrayList<SearchRequest>()
    private val slept = ArrayList<Long>()
    private val progress = ArrayList<String>()

    private fun budget(cap: Int = 30) = SearchBudget(state, cap, { now }, ZoneId.of("UTC"))

    private fun newCache() = SearchCache<TavilySearchResponse>(32, 7L * 24 * 60 * 60 * 1000, { now })

    private fun hit(domain: String, n: Int = 1) = TavilyResult(
        "Story $n on $domain", "https://$domain/p$n",
        "Useful facts about the topic number $n reported by $domain today.", 0.9, "2026-10-15", emptyList()
    )

    private fun resp(vararg hits: TavilyResult, credits: Int? = null) =
        TavilySearchResponse(hits.toList(), emptyList(), null, credits)

    private fun ok(vararg hits: TavilyResult, credits: Int? = null): SearchOutcome<TavilySearchResponse> =
        SearchOutcome.Ok(resp(*hits, credits = credits))

    private fun fail(kind: FailureKind, retryAfter: Int? = null): SearchOutcome<TavilySearchResponse> =
        SearchOutcome.Fail(SearchFailure(kind, retryAfter))

    private fun plan(
        kind: SearchKind = SearchKind.GENERAL,
        f: Freshness = Freshness.ANY,
        credits: Int = 2,
        rounds: Int = 2,
        query: String = "gemma 4"
    ) = SearchPlan(listOf(kind), emptyList(), f, emptyList(), credits, rounds, false, query)

    private fun flow(
        b: SearchBudget = budget(),
        cache: SearchCache<TavilySearchResponse> = newCache(),
        transport: (SearchRequest) -> SearchOutcome<TavilySearchResponse>?
    ) = SearchFlow({ r, _ -> requests.add(r); transport(r) }, b, cache, { now }, { ms -> slept.add(ms) })

    private fun gather(f: SearchFlow, p: SearchPlan = plan(), cancelled: () -> Boolean = { false }): GatherResult =
        f.gather(p, p.query, { n, total -> progress.add("$n/$total") }, cancelled)

    private val quoted = "\"gemma 4\" release"

    // ---------- rounds ----------

    @Test fun oneRoundIsEnoughWhenTwoSitesCameBack() {
        val r = gather(flow { ok(hit("alpha.com"), hit("beta.org")) })
        assertEquals(1, r.rounds)
        assertEquals(1, requests.size)
        assertEquals(1, r.credits)
        assertEquals(SearchPlanner.StopReason.ENOUGH, r.stopReason)
        assertNull(r.failure)
        assertEquals(2, r.book.size)
        assertFalse(r.allFromCache)
        assertFalse(r.stopped)
        assertTrue(r.hasEvidence)
        assertEquals(listOf("1/2"), progress)
    }

    @Test fun aSecondBroaderRoundRunsWhenOnlyOneSiteCameBack() {
        val r = gather(flow { if (requests.size == 1) ok(hit("alpha.com")) else ok(hit("beta.org", 2)) }, plan(query = quoted))
        assertEquals(2, r.rounds)
        assertEquals(2, requests.size)
        assertEquals("gemma 4 release", requests[1].query)
        assertEquals(2, r.credits)
        assertEquals(2, r.book.independentDomainCount())
        assertTrue(r.broadened)
        assertEquals(listOf("1/2", "2/2"), progress)
    }

    @Test fun newsAsksAgainForALongerTimeRange() {
        val p = plan(SearchKind.NEWS, Freshness.DAY, query = "aaj ki khabar")
        val r = gather(flow { if (requests.size == 1) ok(hit("alpha.com")) else ok(hit("beta.org", 2)) }, p)
        assertEquals(2, r.rounds)
        assertEquals(listOf(Freshness.DAY, Freshness.WEEK), requests.map { it.freshness })
        assertEquals(listOf("news", "news"), requests.map { it.topic })
    }

    @Test fun noSecondRoundWhenItWouldAskTheSameThing() {
        val r = gather(flow { ok(hit("alpha.com")) })
        assertEquals(1, r.rounds)
        assertEquals(1, requests.size)
        assertEquals(SearchPlanner.StopReason.NOTHING_NEW, r.stopReason)
        assertEquals(1, r.credits)
    }

    @Test fun anEmptyAnswerGetsOneBroaderTry() {
        val r = gather(flow { if (requests.size == 1) ok() else ok(hit("alpha.com"), hit("beta.org", 2)) }, plan(query = quoted))
        assertEquals(2, r.rounds)
        assertEquals(2, r.book.size)
    }

    @Test fun twoEmptyAnswersGiveNoEvidenceAndNoFailure() {
        val r = gather(flow { ok() }, plan(query = quoted))
        assertEquals(2, r.rounds)
        assertFalse(r.hasEvidence)
        assertNull(r.failure)
        assertEquals(SearchPlanner.StopReason.ROUNDS_USED, r.stopReason)
    }

    // ---------- cache and credits ----------

    @Test fun aCachedAnswerCostsNothing() {
        val b = budget()
        val cache = newCache()
        gather(flow(b, cache) { ok(hit("alpha.com"), hit("beta.org")) })
        assertEquals(1, b.usedToday())
        val again = gather(flow(b, cache) { ok(hit("never.com")) })
        assertEquals(1, requests.size)
        assertTrue(again.allFromCache)
        assertEquals(0, again.credits)
        assertEquals(1, again.rounds)
        assertEquals(2, again.book.size)
        assertEquals(1, b.usedToday())
    }

    @Test fun theProviderCreditNumberReplacesTheEstimate() {
        val b = budget()
        val r = gather(flow(b) { ok(hit("alpha.com"), hit("beta.org"), credits = 2) })
        assertEquals(2, r.credits)
        assertEquals(2, b.usedToday())
    }

    @Test fun aSpentPlanStopsBeforeTheFirstCall() {
        val b = budget()
        b.markPlanExhausted()
        val r = gather(flow(b) { ok(hit("alpha.com")) })
        assertEquals(0, requests.size)
        assertEquals(0, r.rounds)
        assertFalse(r.hasEvidence)
        assertNull(r.failure)
        assertEquals(SearchPlanner.StopReason.BUDGET, r.stopReason)
    }

    @Test fun theDayCapEndsTheSearchAfterTheCreditIsUsed() {
        val b = budget(cap = 1)
        val r = gather(flow(b) { ok(hit("alpha.com")) }, plan(query = quoted))
        assertEquals(1, requests.size)
        assertEquals(1, r.rounds)
        assertEquals(1, r.credits)
        assertEquals(SearchPlanner.StopReason.BUDGET, r.stopReason)
    }

    // ---------- failures ----------

    @Test fun aRejectedKeyEndsAtOnceAndCostsNothing() {
        val b = budget()
        val r = gather(flow(b) { fail(FailureKind.BAD_KEY) })
        assertEquals(FailureKind.BAD_KEY, r.failure!!.kind)
        assertEquals(1, requests.size)
        assertEquals(0, r.rounds)
        assertEquals(0, r.credits)
        assertEquals(0, b.usedToday())
        assertTrue(slept.isEmpty())
        assertFalse(r.hasEvidence)
    }

    @Test fun aUsedUpPlanAnswerEndsAtOnce() {
        val r = gather(flow { fail(FailureKind.PLAN_LIMIT) })
        assertEquals(FailureKind.PLAN_LIMIT, r.failure!!.kind)
        assertEquals(1, requests.size)
        assertEquals(0, r.credits)
    }

    @Test fun aServerErrorIsRetriedOnce() {
        val r = gather(flow { if (requests.size == 1) fail(FailureKind.SERVER) else ok(hit("alpha.com"), hit("beta.org")) })
        assertEquals(2, requests.size)
        assertEquals(1, r.credits)
        assertNull(r.failure)
        assertEquals(1000L, slept.sum())
        assertEquals(1, r.rounds)
    }

    @Test fun twoServerErrorsGiveUpAndCostNothing() {
        val b = budget()
        val r = gather(flow(b) { fail(FailureKind.SERVER) })
        assertEquals(2, requests.size)
        assertEquals(FailureKind.SERVER, r.failure!!.kind)
        assertEquals(0, r.credits)
        assertEquals(0, b.usedToday())
    }

    @Test fun aTimeoutKeepsItsChargeBecauseTheProviderMayHaveRun() {
        val b = budget()
        val r = gather(flow(b) { fail(FailureKind.TIMEOUT) })
        assertEquals(2, requests.size)
        assertEquals(FailureKind.TIMEOUT, r.failure!!.kind)
        assertEquals(2, r.credits)
        assertEquals(2, b.usedToday())
    }

    @Test fun aRetryThatDoesNotFitTheCapReportsTheTimeout() {
        val r = gather(flow { fail(FailureKind.TIMEOUT) }, plan(credits = 1, rounds = 1))
        assertEquals(1, requests.size)
        assertEquals(FailureKind.TIMEOUT, r.failure!!.kind)
        assertEquals(1, r.credits)
    }

    @Test fun aShortRateLimitWaitIsObeyedOnce() {
        val r = gather(flow { if (requests.size == 1) fail(FailureKind.RATE_LIMITED, 3) else ok(hit("alpha.com"), hit("beta.org")) })
        assertEquals(2, requests.size)
        assertEquals(3000L, slept.sum())
        assertEquals(1, r.credits)
        assertNull(r.failure)
    }

    @Test fun aLongRateLimitWaitGivesUp() {
        val r = gather(flow { fail(FailureKind.RATE_LIMITED, 30) })
        assertEquals(1, requests.size)
        assertEquals(FailureKind.RATE_LIMITED, r.failure!!.kind)
        assertTrue(slept.isEmpty())
        assertEquals(0, r.credits)
    }

    @Test fun aMalformedAnswerIsNotRetriedAndKeepsItsCharge() {
        val r = gather(flow { fail(FailureKind.MALFORMED) })
        assertEquals(1, requests.size)
        assertEquals(FailureKind.MALFORMED, r.failure!!.kind)
        assertEquals(1, r.credits)
    }

    @Test fun offlineIsNotRetried() {
        val r = gather(flow { fail(FailureKind.OFFLINE) })
        assertEquals(1, requests.size)
        assertEquals(FailureKind.OFFLINE, r.failure!!.kind)
        assertEquals(0, r.credits)
    }

    @Test fun aFailedSecondRoundKeepsTheFirstEvidence() {
        val r = gather(flow { if (requests.size == 1) ok(hit("alpha.com")) else fail(FailureKind.BAD_REQUEST) }, plan(query = quoted))
        assertEquals(1, r.rounds)
        assertTrue(r.hasEvidence)
        assertEquals(FailureKind.BAD_REQUEST, r.failure!!.kind)
        assertFalse(r.broadened)
    }

    // ---------- stop, deadline ----------

    @Test fun stopDuringTheCallIsReportedAndNothingIsCached() {
        val cache = newCache()
        val r = gather(flow(cache = cache) { null })
        assertTrue(r.stopped)
        assertFalse(r.hasEvidence)
        assertEquals(0, cache.size())
    }

    @Test fun stopBeforeTheFirstCallMakesNoCall() {
        val r = gather(flow { ok(hit("alpha.com")) }, cancelled = { true })
        assertTrue(r.stopped)
        assertEquals(0, requests.size)
    }

    @Test fun stopBetweenRoundsKeepsWhatWasFound() {
        var stop = false
        val r = gather(flow { stop = true; ok(hit("alpha.com")) }, plan(query = quoted)) { stop }
        assertTrue(r.stopped)
        assertEquals(1, requests.size)
        assertEquals(1, r.rounds)
        assertTrue(r.hasEvidence)
    }

    @Test fun stopWhileWaitingToRetryIsSeenAtOnce() {
        var calls = 0
        val r = gather(flow { fail(FailureKind.SERVER) }) { calls++ >= 2 }
        assertTrue(r.stopped)
        assertEquals(1, requests.size)
    }

    @Test fun theDeadlineEndsTheSearching() {
        val r = gather(flow { now += 46_000L; ok(hit("alpha.com")) }, plan(query = quoted))
        assertEquals(1, r.rounds)
        assertEquals(SearchPlanner.StopReason.DEADLINE, r.stopReason)
        assertEquals(46_000L, r.elapsedMs)
    }

    // ---------- the book's own counter ----------

    @Test fun subdomainsOfOneSiteCountOnce() {
        val book = EvidenceBook()
        book.add(listOf(hit("a.example.com", 1), hit("b.example.com", 2)))
        assertEquals(1, book.independentDomainCount())
        book.add(listOf(hit("other.org", 3)))
        assertEquals(2, book.independentDomainCount())
        assertEquals(0, EvidenceBook().independentDomainCount())
    }
}
