package com.codeassist.ai.service

import com.codeassist.ai.service.HealthVerdict.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthVerdictTest {
    private val now = 1_000_000_000_000L
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun verdict(
        on: Boolean = true, flag: Boolean = true, alive: Boolean = false,
        then: Int = 7, nowBoot: Int = 7, beat: Long = now - hour
    ) = HealthVerdict.evaluate(on, flag, alive, then, nowBoot, beat, now)

    @Test fun offWhenNeverSwitchedOn() = assertEquals(Kind.OFF, verdict(on = false).kind)

    @Test fun okWhenServiceAlive() = assertEquals(Kind.OK, verdict(alive = true).kind)

    @Test fun notRunningAfterCleanStop() = assertEquals(Kind.NOT_RUNNING, verdict(flag = false).kind)

    @Test fun killedWhenFlagSetButServiceGone() {
        val r = verdict()
        assertEquals(Kind.KILLED, r.kind)
        assertEquals(hour, r.downMs)
    }

    @Test fun rebootIsNotAKill() = assertEquals(Kind.REBOOT, verdict(then = 7, nowBoot = 8).kind)

    @Test fun unknownBootCountStillCountsAsKill() {
        assertEquals(Kind.KILLED, verdict(then = -1, nowBoot = 8).kind)
        assertEquals(Kind.KILLED, verdict(then = 7, nowBoot = -1).kind)
    }

    @Test fun downTimeNeverNegativeAndZeroWithoutHeartbeat() {
        assertEquals(0L, verdict(beat = now + 5000L).downMs)
        assertEquals(0L, verdict(beat = 0L).downMs)
    }

    @Test fun killLogKeepsNewestTen() {
        var csv = ""
        for (i in 1..12) csv = HealthVerdict.appendKill(csv, i.toLong())
        val times = HealthVerdict.parseTimes(csv)
        assertEquals(10, times.size)
        assertEquals(3L, times.first())
        assertEquals(12L, times.last())
    }

    @Test fun parseIgnoresGarbage() = assertEquals(listOf(5L, 9L), HealthVerdict.parseTimes("5, x,,9"))

    @Test fun recentKillsUsesWindow() {
        val csv = listOf(now - 8 * day, now - 2 * day, now - hour).joinToString(",")
        assertEquals(2, HealthVerdict.recentKills(csv, now))
    }

    @Test fun promptNeedsTwoRecentKills() {
        assertFalse(HealthVerdict.shouldPrompt("${now - hour}", now, 0L))
        assertTrue(HealthVerdict.shouldPrompt("${now - day},${now - hour}", now, 0L))
    }

    @Test fun promptRespectsCooldown() {
        val csv = "${now - day},${now - hour}"
        assertFalse(HealthVerdict.shouldPrompt(csv, now, now - day))
        assertTrue(HealthVerdict.shouldPrompt(csv, now, now - 4 * day))
    }

    @Test fun agoWording() {
        assertEquals("abhi", HealthVerdict.ago(20_000L))
        assertEquals("12 minute pehle", HealthVerdict.ago(12 * 60_000L))
        assertEquals("3 ghante pehle", HealthVerdict.ago(3 * hour))
        assertEquals("3 din pehle", HealthVerdict.ago(3 * day))
    }
}
