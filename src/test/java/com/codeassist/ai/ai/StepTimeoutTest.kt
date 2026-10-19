package com.codeassist.ai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepTimeoutTest {
    @Test fun quickWorkIsDone() {
        val r = StepTimeout.call(2000) { 42 }
        assertTrue(r is StepTimeout.Outcome.Done<*>)
        assertEquals(42, (r as StepTimeout.Outcome.Done<*>).value)
    }

    @Test fun slowWorkTimesOutAtTheLimitNotWhenTheWorkEnds() {
        val started = System.currentTimeMillis()
        val r = StepTimeout.call<Int>(200) {
            Thread.sleep(5000)
            1
        }
        val took = System.currentTimeMillis() - started
        assertTrue(r === StepTimeout.Outcome.TimedOut)
        assertTrue("took $took ms", took in 150..1500)
    }

    @Test fun stopEndsTheWaitQuickly() {
        val started = System.currentTimeMillis()
        val r = StepTimeout.call<Int>(8000, pollMs = 50, stopped = { System.currentTimeMillis() - started > 120 }) {
            Thread.sleep(5000)
            1
        }
        val took = System.currentTimeMillis() - started
        assertTrue(r === StepTimeout.Outcome.Stopped)
        assertTrue("took $took ms", took < 1500)
    }

    @Test fun aCrashInTheWorkIsReportedNotThrown() {
        val r = StepTimeout.call<Int>(2000) { throw IllegalStateException("boom") }
        assertTrue(r is StepTimeout.Outcome.Crashed)
        assertEquals("boom", (r as StepTimeout.Outcome.Crashed).error.message)
    }

    @Test fun tickReportsTheWaitedTime() {
        val ticks = ArrayList<Long>()
        val r = StepTimeout.call<Int>(400, pollMs = 50, onTick = { ticks.add(it) }) {
            Thread.sleep(5000)
            1
        }
        assertTrue(r === StepTimeout.Outcome.TimedOut)
        assertTrue("ticks ${ticks.size}", ticks.size >= 3)
        for (i in 1 until ticks.size) assertTrue(ticks[i] >= ticks[i - 1])
    }

    @Test fun workThatFinishesBeforeTheFirstTickNeedsNoTick() {
        var ticked = false
        val r = StepTimeout.call(2000, onTick = { ticked = true }) { "ok" }
        assertTrue(r is StepTimeout.Outcome.Done<*>)
        assertTrue(!ticked)
    }
}
