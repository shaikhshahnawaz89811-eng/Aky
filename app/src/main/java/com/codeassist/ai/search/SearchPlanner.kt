package com.codeassist.ai.search

/**
 * Net Search Agent, stage S2: who decides the next step of a search turn. Code decides, never the model (audit 5.4): a rule
 * planner cannot loop, cannot be talked into spending credits, and is tested on the JVM.
 *
 * Stage S2 answers three request kinds with ONE flow: [SearchKind.GENERAL], [SearchKind.NEWS] and [SearchKind.VALUE] (a price /
 * version / size question is a general search whose numbers must be written verbatim in a source: that check is
 * [CitationValidator]). Every other kind (links, downloads, compare, images ...) is built in a later stage and goes the normal
 * chat way until then ([SearchRunner] counts how often that happens).
 *
 * Round rules: round 1 is one basic search. A second round runs only when fewer than [MIN_DOMAINS] independent sites came
 * back, the round limit and the credit cap allow it, the deadline is not over, and the broader request is really different.
 */
object SearchPlanner {
    /** The kinds the S2 flow answers. One line to change if a kind should leave (or join) the net flow. */
    val SUPPORTED: Set<SearchKind> = setOf(SearchKind.GENERAL, SearchKind.NEWS, SearchKind.VALUE)

    /** "At least 2 independent domains agree" (audit table, row 1). */
    const val MIN_DOMAINS = 2

    /** Total time of one turn's searching (audit 5.4: question and answer 45 s). The compose call has its own time. */
    const val DEADLINE_MS = 45_000L

    const val RESULTS_PER_ROUND = 5

    /** Stage S2 acceptance: at most 2 credits a turn (the plan's own cap can be 3 for VALUE). */
    const val TURN_CREDIT_CAP = 2

    enum class StopReason { ENOUGH, ROUNDS_USED, BUDGET, DEADLINE, NOTHING_NEW }

    sealed class Next {
        class Search(val request: SearchRequest) : Next()
        class Stop(val reason: StopReason) : Next()
    }

    fun supported(plan: SearchPlan): Boolean = plan.kinds.isNotEmpty() && plan.kinds.all { it in SUPPORTED }

    fun isNews(plan: SearchPlan): Boolean = plan.primary == SearchKind.NEWS

    /** Credits one turn may spend: the plan's own cap, but never more than [TURN_CREDIT_CAP]. */
    fun creditCap(plan: SearchPlan): Int = minOf(plan.maxCredits, TURN_CREDIT_CAP)

    /** Rounds one turn may run: the plan's own limit, but never more than the credits allow at 1 credit a basic search. */
    fun roundCap(plan: SearchPlan): Int = minOf(plan.maxRounds, creditCap(plan)).coerceAtLeast(1)

    /** The first request. News with no freshness word still asks for the last week, so an old story is not served as news. */
    fun first(plan: SearchPlan, query: String): SearchRequest {
        val news = isNews(plan)
        val freshness = if (news && plan.freshness == Freshness.ANY) Freshness.WEEK else plan.freshness
        return SearchRequest(
            query = query,
            depth = "basic",
            topic = if (news) "news" else "general",
            maxResults = RESULTS_PER_ROUND,
            freshness = freshness,
            includeDomains = plan.domains
        )
    }

    /**
     * What to do after [done] finished rounds. [domains] is the number of independent sites in the evidence so far, [last] the
     * request just made. Order matters: enough evidence ends the turn before anything else is asked.
     */
    fun next(plan: SearchPlan, done: Int, domains: Int, elapsedMs: Long, canAfford: Boolean, last: SearchRequest): Next {
        if (domains >= MIN_DOMAINS) return Next.Stop(StopReason.ENOUGH)
        if (done >= roundCap(plan)) return Next.Stop(StopReason.ROUNDS_USED)
        if (elapsedMs >= DEADLINE_MS) return Next.Stop(StopReason.DEADLINE)
        if (!canAfford) return Next.Stop(StopReason.BUDGET)
        val wider = broaden(last)
        if (signature(wider) == signature(last)) return Next.Stop(StopReason.NOTHING_NEW)
        return Next.Search(wider)
    }

    /** The request of the second round: quotes, years and `site:` removed, freshness one step wider, no domain restriction. */
    fun broaden(last: SearchRequest): SearchRequest = SearchRequest(
        query = broadenQuery(last.query),
        depth = last.depth,
        topic = last.topic,
        maxResults = last.maxResults,
        freshness = widen(last.freshness),
        includeDomains = emptyList(),
        excludeDomains = last.excludeDomains
    )

    fun widen(f: Freshness): Freshness = when (f) {
        Freshness.DAY -> Freshness.WEEK
        Freshness.WEEK -> Freshness.MONTH
        Freshness.MONTH -> Freshness.ANY
        Freshness.ANY -> Freshness.ANY
    }

    private val quotes = Regex("[\"\\u201C\\u201D]")
    private val years = Regex("\\b(?:19|20)\\d{2}\\b")
    private val siteOperator = Regex("(?i)\\bsite:\\S+")
    private val spaces = Regex("\\s+")

    /** Quotes, `site:` operators and years removed. Falls back to the original when less than 3 characters would be left. */
    fun broadenQuery(q: String): String {
        var s = quotes.replace(q, " ")
        s = siteOperator.replace(s, " ")
        s = years.replace(s, " ")
        s = spaces.replace(s, " ").trim()
        return if (s.length >= 3) s else spaces.replace(q, " ").trim()
    }

    /** Two requests with the same signature would return the same results: used for "nothing new" and duplicate suppression. */
    fun signature(r: SearchRequest): String =
        SearchCache.normalizeQuery(r.query) + "|" + r.topic + "|" + r.freshness.name + "|" +
            r.includeDomains.map { it.lowercase() }.sorted().joinToString(",")
}
