package com.codeassist.ai.search

/**
 * What one turn's searching gave. [book] holds the evidence (possibly empty). [failure] is the last provider failure that ended the
 * searching, or null when it ended for another reason (enough evidence, rounds used, credit cap, deadline).
 */
class GatherResult(
    val book: EvidenceBook,
    /** Search rounds that really got an answer (a cached answer counts). */
    val rounds: Int,
    /** Credits charged this turn (after settling with the provider's own number when it gave one). */
    val credits: Int,
    /** True when at least one round came from the cache and no round went to the provider. */
    val allFromCache: Boolean,
    val failure: SearchFailure?,
    /** The user pressed Stop. */
    val stopped: Boolean,
    val stopReason: SearchPlanner.StopReason?,
    val elapsedMs: Long,
    /** The last round's query was cut or changed by the planner (a broader second round ran). */
    val broadened: Boolean,
    /** Stage S3: every result of every round in arrival order, text untouched (the evidence book scrubs links out; the link finder needs them). */
    val raw: List<TavilyResult> = emptyList()
) {
    val hasEvidence: Boolean get() = book.size > 0
}

/**
 * The searching part of one turn (audit 6.1 steps 6 to 8), with no Android class in it. The provider call is [transport], a
 * blocking function that returns null when the user pressed Stop (the Android side runs it inside StepTimeout.call). Time and
 * sleeping are injected, so the tests run instantly.
 *
 * Rules, all from the audit:
 *  - credits are charged BEFORE a call and settled with the real `usage.credits` after it ([SearchBudget.Turn]); a repeated request in
 *    one turn is never sent twice; a cached answer costs nothing;
 *  - 401 and 432 / 433 end the searching at once, 429 waits (at most [MAX_RETRY_WAIT_MS]) and tries once more, 5xx / timeout /
 *    network trouble wait [RETRY_PAUSE_MS] and try once more, a malformed or rejected answer is not retried;
 *  - what the planner decides next ([SearchPlanner.next]) is the only thing that starts another round;
 *  - a failed call that definitely did not reach the provider's billing (offline, 401, 429, 432 / 433, 4xx, 5xx) gives its credit back;
 *    a timeout or a network error keeps the charge (the provider may have run the search).
 */
class SearchFlow(
    private val transport: (SearchRequest, Int) -> SearchOutcome<TavilySearchResponse>?,
    private val budget: SearchBudget,
    private val cache: SearchCache<TavilySearchResponse>,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val sleep: (Long) -> Unit = { ms -> Thread.sleep(ms) }
) {
    companion object {
        const val SEARCH_TIMEOUT_MS = 12_000
        const val RETRY_PAUSE_MS = 1_000L
        const val MAX_RETRY_WAIT_MS = 10_000L
        private const val SLICE_MS = 100L
    }

    /**
     * Runs the rounds for [plan] with the already guarded [query]. [onRound] gets (round number, rounds allowed) just before each
     * provider call. [cancelled] is polled between calls and while waiting.
     */
    fun gather(
        plan: SearchPlan,
        query: String,
        onRound: (Int, Int) -> Unit,
        cancelled: () -> Boolean
    ): GatherResult {
        val started = clock()
        val turn = budget.startTurn(SearchPlanner.creditCap(plan))
        val book = EvidenceBook()
        val kind = plan.primary
        val roundLimit = SearchPlanner.roundCap(plan)
        var request = SearchPlanner.first(plan, query)
        var done = 0
        var calls = 0
        var hits = 0
        var failure: SearchFailure? = null
        var stopped = false
        var reason: SearchPlanner.StopReason? = null
        var broadened = false
        val raw = ArrayList<TavilyResult>()

        while (true) {
            if (cancelled()) {
                stopped = true
                break
            }
            if (!turn.firstTime(SearchPlanner.signature(request))) {
                reason = SearchPlanner.StopReason.NOTHING_NEW
                break
            }
            val key = cacheKey(request, kind)
            var response: TavilySearchResponse? = cache.get(key)
            if (response != null) {
                hits++
            } else {
                onRound(done + 1, roundLimit)
                val got = call(request, turn, cancelled)
                calls++
                when (got) {
                    is Called.Stop -> {
                        stopped = true
                        break
                    }
                    is Called.Denied -> {
                        reason = SearchPlanner.StopReason.BUDGET
                        break
                    }
                    is Called.Failed -> {
                        failure = got.failure
                        break
                    }
                    is Called.Got -> {
                        response = got.response
                        cache.put(key, got.response)
                    }
                }
            }
            if (response != null) {
                book.add(response.results)
                raw.addAll(response.results)
            }
            done++

            val next = SearchPlanner.next(
                plan, done, book.independentDomainCount(), clock() - started, turn.canAfford(1), request
            )
            if (next is SearchPlanner.Next.Stop) {
                reason = next.reason
                break
            }
            if (next is SearchPlanner.Next.Search) {
                request = next.request
                broadened = true
            }
        }
        return GatherResult(
            book = book,
            rounds = done,
            credits = turn.spent,
            allFromCache = calls == 0 && hits > 0,
            failure = failure,
            stopped = stopped,
            stopReason = reason,
            elapsedMs = clock() - started,
            broadened = broadened && done > 1,
            raw = raw
        )
    }

    private fun cacheKey(r: SearchRequest, kind: SearchKind): String =
        SearchCache.keyFor(r.query, kind, clock()) + "|" + r.topic + "|" + r.freshness.name + "|" +
            r.includeDomains.map { it.lowercase() }.sorted().joinToString(",")

    private sealed class Called {
        class Got(val response: TavilySearchResponse) : Called()
        class Failed(val failure: SearchFailure) : Called()
        object Denied : Called()
        object Stop : Called()
    }

    /** One provider call with its single retry. Charges before, settles after. */
    private fun call(request: SearchRequest, turn: SearchBudget.Turn, cancelled: () -> Boolean): Called {
        val cost = SearchBudget.searchCost(request.depth == "advanced")
        var attempt = 0
        var lastFailure: SearchFailure? = null
        while (true) {
            // a retry that no longer fits the credit cap reports the failure that made it necessary, not a budget stop
            if (!turn.charge(cost)) return if (lastFailure != null) Called.Failed(lastFailure) else Called.Denied
            val outcome = transport(request, SEARCH_TIMEOUT_MS) ?: return Called.Stop
            when (outcome) {
                is SearchOutcome.Ok -> {
                    val actual = outcome.value.credits
                    if (actual != null) turn.settle(cost, actual)
                    return Called.Got(outcome.value)
                }
                is SearchOutcome.Fail -> {
                    val f = outcome.failure
                    lastFailure = f
                    if (f.kind.refundsCredit()) turn.settle(cost, 0)
                    if (attempt >= 1 || !f.retryable) return Called.Failed(f)
                    val wait = if (f.kind == FailureKind.RATE_LIMITED) {
                        val asked = (f.retryAfterSec ?: 2) * 1000L
                        if (asked > MAX_RETRY_WAIT_MS) return Called.Failed(f)
                        asked
                    } else {
                        RETRY_PAUSE_MS
                    }
                    if (!pause(wait, cancelled)) return Called.Stop
                    attempt++
                }
            }
        }
    }

    /** Waits [ms] in short slices; false when the user pressed Stop meanwhile. */
    private fun pause(ms: Long, cancelled: () -> Boolean): Boolean {
        var left = ms
        while (left > 0) {
            if (cancelled()) return false
            val slice = minOf(SLICE_MS, left)
            sleep(slice)
            left -= slice
        }
        return !cancelled()
    }
}

/** A failure that did not reach the provider's billing: the credit charged before the call is given back. A timeout may have run the search, so it keeps its charge. */
fun FailureKind.refundsCredit(): Boolean = when (this) {
    FailureKind.OFFLINE, FailureKind.BAD_KEY, FailureKind.RATE_LIMITED, FailureKind.PLAN_LIMIT,
    FailureKind.BAD_REQUEST, FailureKind.SERVER -> true
    FailureKind.NETWORK, FailureKind.TIMEOUT, FailureKind.MALFORMED -> false
}
