package com.codeassist.ai.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class SearchBudgetTest {
    private val dayMs = 24L * 60 * 60 * 1000
    private var now = Instant.parse("2026-10-17T10:00:00Z").toEpochMilli()

    private fun budget(state: BudgetState = InMemoryBudgetState(), cap: Int = 30) =
        SearchBudget(state, cap, { now }, ZoneOffset.UTC)

    @Test fun aTurnCannotSpendMoreThanItsRequestCap() {
        val b = budget()
        val turn = b.startTurn(2)
        assertTrue(turn.charge(1))
        assertTrue(turn.charge(1))
        assertFalse(turn.charge(1))
        assertEquals(2, turn.spent)
        assertEquals(2, b.usedToday())
    }

    @Test fun theDayCapStopsEveryTurn() {
        val b = budget(cap = 3)
        val first = b.startTurn(5)
        assertTrue(first.charge(2))
        assertEquals(1, b.remainingToday())
        assertFalse(first.charge(2))
        assertTrue(first.charge(1))
        assertEquals(0, b.remainingToday())
        assertFalse(b.startTurn(5).charge(1))
    }

    @Test fun aNewDayStartsAtZero() {
        val b = budget(cap = 5)
        assertTrue(b.startTurn(5).charge(5))
        assertEquals(0, b.remainingToday())
        now += dayMs
        assertEquals(0, b.usedToday())
        assertEquals(5, b.remainingToday())
        assertTrue(b.startTurn(5).charge(2))
        assertEquals(2, b.usedToday())
    }

    @Test fun theCountSurvivesANewBudgetObject() {
        val state = InMemoryBudgetState()
        assertTrue(budget(state).startTurn(5).charge(3))
        assertEquals(3, budget(state).usedToday())
    }

    @Test fun settleReplacesTheEstimateWithTheRealCredits() {
        val b = budget()
        val turn = b.startTurn(10)
        assertTrue(turn.charge(2))
        turn.settle(2, 1)
        assertEquals(1, turn.spent)
        assertEquals(1, b.usedToday())
        turn.settle(1, 3)
        assertEquals(3, turn.spent)
        assertEquals(3, b.usedToday())
        turn.settle(3, 3)
        assertEquals(3, b.usedToday())
    }

    @Test fun anExhaustedPlanBlocksUntilNextMonth() {
        val b = budget()
        assertFalse(b.isPlanExhausted())
        b.markPlanExhausted()
        assertTrue(b.isPlanExhausted())
        assertFalse(b.startTurn(5).canAfford(1))
        assertFalse(b.startTurn(5).charge(1))
        now += 20 * dayMs
        assertFalse(b.isPlanExhausted())
        assertTrue(b.startTurn(5).canAfford(1))
    }

    @Test fun theExhaustedFlagCanBeClearedByHand() {
        val b = budget()
        b.markPlanExhausted()
        b.clearPlanExhausted()
        assertFalse(b.isPlanExhausted())
    }

    @Test fun repeatedQueriesInOneTurnAreSuppressed() {
        val turn = budget().startTurn(5)
        assertTrue(turn.firstTime("Gemma 4?"))
        assertFalse(turn.firstTime("gemma  4"))
        assertTrue(turn.firstTime("qwen"))
    }

    @Test fun aNegativeChargeIsRefused() {
        assertFalse(budget().startTurn(5).canAfford(-1))
    }

    @Test fun costsFollowTheTavilyTable() {
        assertEquals(1, SearchBudget.searchCost(false))
        assertEquals(2, SearchBudget.searchCost(true))
        assertEquals(0, SearchBudget.extractCost(0, false))
        assertEquals(1, SearchBudget.extractCost(1, false))
        assertEquals(1, SearchBudget.extractCost(5, false))
        assertEquals(2, SearchBudget.extractCost(6, false))
        assertEquals(4, SearchBudget.extractCost(6, true))
    }

    @Test fun theDefaultDayCapIsThirty() {
        assertEquals(30, SearchBudget.DEFAULT_DAILY_CAP)
        assertEquals(30, SearchBudget(InMemoryBudgetState(), clock = { now }, zone = ZoneOffset.UTC).remainingToday())
    }
}
