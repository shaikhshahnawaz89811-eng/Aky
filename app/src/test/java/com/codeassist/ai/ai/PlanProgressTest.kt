package com.codeassist.ai.ai

import com.codeassist.ai.ai.TaskGraph.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanProgressTest {
    private fun plan(vararg tools: String): TaskGraph.Plan =
        TaskGraph.build("p", "c", "m", "", tools.mapIndexed { i, t -> PlanTask("t" + (i + 1), t, emptyMap()) }, 0L)

    @Test fun lineCountsFinishedStepsPlusTheRunningOne() {
        val p = plan("time_now", "timer_set", "weather_now")
        p.nodes[0].state = State.COMPLETED
        p.nodes[1].state = State.FAILED
        p.nodes[2].state = State.RUNNING
        assertEquals("Kaam 3/3 chal raha hai: Mausam", PlanProgress.line(p, p.nodes[2]))
    }

    @Test fun firstStepIsNumberOne() {
        val p = plan("weather_now", "timer_set", "time_now")
        p.nodes[0].state = State.RUNNING
        assertEquals("Kaam 1/3 chal raha hai: Mausam", PlanProgress.line(p, p.nodes[0]))
    }

    @Test fun aSingleStepHasNoCounter() {
        val p = plan("weather_now")
        assertEquals("Mausam chal raha hai", PlanProgress.line(p, p.nodes[0]))
    }

    @Test fun positionNeverPassesTheTotal() {
        val p = plan("time_now", "weather_now")
        p.nodes[0].state = State.COMPLETED
        p.nodes[1].state = State.COMPLETED
        assertEquals("Kaam 2/2 chal raha hai: Mausam", PlanProgress.line(p, p.nodes[1]))
    }

    @Test fun nudgeRules() {
        assertFalse(PlanProgress.shouldNudge(3999, 0, 0))
        assertTrue(PlanProgress.shouldNudge(4000, 0, 0))
        assertFalse(PlanProgress.shouldNudge(5000, 1, 1000))
        assertTrue(PlanProgress.shouldNudge(11000, 1, 6500))
        assertFalse(PlanProgress.shouldNudge(20000, 2, 99999))
    }

    @Test fun slowLineNamesTheStepAndTheSeconds() {
        val p = plan("weather_now")
        val line = PlanProgress.slowLine(p.nodes[0], 4300)
        assertTrue(line, line.contains("Mausam") && line.contains("4 s"))
    }

    @Test fun eightSecondLimitFitsAtMostOneNudge() {
        // the timeout is 8 s, the first nudge comes at 4 s and the gap is 6 s: only one line can ever be shown
        var sent = 0
        var last = 0L
        var t = 0L
        while (t < TaskGraph.STEP_TIMEOUT_MS) {
            if (PlanProgress.shouldNudge(t, sent, t - last)) {
                sent++
                last = t
            }
            t += 100
        }
        assertEquals(1, sent)
    }
}
