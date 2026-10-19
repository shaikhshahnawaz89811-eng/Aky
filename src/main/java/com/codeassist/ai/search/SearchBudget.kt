package com.codeassist.ai.search

import java.time.Instant
import java.time.ZoneId

/**
 * Where the budget keeps its numbers between runs. The Android side (stage S2) backs this with the app's Store, like the
 * ConvKpi counters; the tests use [InMemoryBudgetState].
 */
interface BudgetState {
    /** The day (yyyy-MM-dd) [usedToday] belongs to; "" when nothing was counted yet. */
    fun day(): String
    fun usedToday(): Int
    fun setUsed(day: String, used: Int)

    /** The month (yyyy-MM) in which the provider said its plan / credit limit was reached, or "". */
    fun exhaustedMonth(): String
    fun setExhaustedMonth(month: String)
}

class InMemoryBudgetState : BudgetState {
    private var d = ""
    private var u = 0
    private var m = ""
    override fun day(): String = d
    override fun usedToday(): Int = u
    override fun setUsed(day: String, used: Int) {
        d = day
        u = used
    }

    override fun exhaustedMonth(): String = m
    override fun setExhaustedMonth(month: String) {
        m = month
    }
}

/**
 * Credit control (audit section 7, "Credit burn"): a cap per request, a cap per day (default 30 credits; 1,000 free Tavily credits
 * a month is about 33 a day), repeated queries suppressed inside one turn, and a "plan used up" switch that stays on until the
 * next month. Pure Kotlin; the clock is a parameter.
 *
 * Credits are charged BEFORE the call (from the audit table) and [Turn.settle] corrects them with the real `usage.credits` after
 * it, so a crash in between never under-counts.
 */
class SearchBudget(
    private val state: BudgetState,
    val dailyCap: Int = DEFAULT_DAILY_CAP,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val zone: ZoneId = ZoneId.systemDefault()
) {
    private fun today(): String = Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate().toString()

    private fun month(): String = today().substring(0, 7)

    /** Credits used today; a new day starts at 0. */
    @Synchronized
    fun usedToday(): Int = if (state.day() == today()) state.usedToday() else 0

    @Synchronized
    fun remainingToday(): Int = maxOf(0, dailyCap - usedToday())

    /** True after [markPlanExhausted] until the month changes. */
    @Synchronized
    fun isPlanExhausted(): Boolean = state.exhaustedMonth() == month()

    /** The provider answered 432 / 433: stop using it for this month (audit 6.4). */
    @Synchronized
    fun markPlanExhausted() {
        state.setExhaustedMonth(month())
    }

    @Synchronized
    fun clearPlanExhausted() {
        state.setExhaustedMonth("")
    }

    @Synchronized
    private fun add(credits: Int) {
        val d = today()
        val base = if (state.day() == d) state.usedToday() else 0
        state.setUsed(d, maxOf(0, base + credits))
    }

    /** One user turn. [requestCap] is the plan's `maxCredits`. */
    fun startTurn(requestCap: Int): Turn = Turn(requestCap)

    inner class Turn(val requestCap: Int) {
        var spent: Int = 0
            private set
        private val seen = HashSet<String>()

        /** Would a call of [credits] fit the request cap, the day's cap and a plan that is not used up? */
        fun canAfford(credits: Int): Boolean =
            credits >= 0 && !isPlanExhausted() && spent + credits <= requestCap && credits <= remainingToday()

        /** Charges [credits] when they fit and returns true; changes nothing and returns false when they do not. */
        fun charge(credits: Int): Boolean {
            if (!canAfford(credits)) return false
            spent += credits
            add(credits)
            return true
        }

        /** After the call: replace the [estimated] charge by the [actual] credits the provider reported. */
        fun settle(estimated: Int, actual: Int) {
            val diff = actual - estimated
            if (diff == 0) return
            spent = maxOf(0, spent + diff)
            add(diff)
        }

        /** Duplicate suppression: true the first time [query] (normalised) is seen in this turn, false after. */
        fun firstTime(query: String): Boolean = seen.add(SearchCache.normalizeQuery(query))
    }

    companion object {
        const val DEFAULT_DAILY_CAP = 30

        /** Credit cost of one call at the given depth (audit 2.1). */
        fun searchCost(advanced: Boolean): Int = if (advanced) 2 else 1

        /** /extract: 1 credit per 5 successful URLs at basic depth, 2 per 5 at advanced. Never below 1 for a non-empty list. */
        fun extractCost(urls: Int, advanced: Boolean): Int {
            if (urls <= 0) return 0
            val blocks = (urls + 4) / 5
            return blocks * (if (advanced) 2 else 1)
        }
    }
}
