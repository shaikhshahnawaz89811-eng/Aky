package com.codeassist.ai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ProactiveTest {
    private val base = 20_000L * 86_400_000L // a midnight (UTC), time zone offset 0 in these tests

    private fun at(h: Int, m: Int = 0, day: Int = 0): Long = base + day * 86_400_000L + h * 3_600_000L + m * 60_000L

    private fun c(kind: String) = Proactive.Candidate(kind, "t", null, null)

    private val both = listOf(c(Proactive.KIND_BATTERY), c(Proactive.KIND_TORCH))

    private fun pick(cands: List<Proactive.Candidate>, st: Proactive.State, level: String, now: Long) =
        Proactive.pick(cands, st, level, now, 0)

    // ---------- the budget ----------

    @Test fun levelsMeanOneAndTwoAndNeverMoreThanTwo() {
        assertEquals(0, Proactive.perDay("Off"))
        assertEquals(1, Proactive.perDay("Low"))
        assertEquals(2, Proactive.perDay("Normal"))
        assertEquals(0, Proactive.perDay("whatever"))
        assertTrue(Proactive.LEVELS.all { Proactive.perDay(it) <= Proactive.MAX_PER_DAY })
    }

    @Test fun offShowsNothing() {
        assertEquals("off", pick(both, Proactive.State(), "Off", at(12)).why)
    }

    @Test fun nothingToSuggestIsNone() {
        assertEquals("none", pick(emptyList(), Proactive.State(), "Normal", at(12)).why)
    }

    @Test fun lowAllowsOnePerDayNormalTwo() {
        var st = Proactive.State()
        val first = pick(both, st, "Normal", at(9))
        assertNotNull(first.pick)
        st = Proactive.afterShown(st, first.pick!!, at(9), 0)
        val second = pick(both, st, "Normal", at(13))
        assertNotNull(second.pick)
        assertEquals(Proactive.KIND_TORCH, second.pick!!.kind) // battery is still cooling down
        st = Proactive.afterShown(st, second.pick!!, at(13), 0)
        assertEquals("budget", pick(both, st, "Normal", at(20)).why)
        // Low: one is enough
        val low = Proactive.afterShown(Proactive.State(), c("battery"), at(9), 0)
        assertEquals("budget", pick(both, low, "Low", at(20)).why)
    }

    @Test fun aNewDayStartsAgain() {
        var st = Proactive.State()
        st = Proactive.afterShown(st, c("battery"), at(9), 0)
        st = Proactive.afterShown(st, c("torch"), at(13), 0)
        assertEquals(2, Proactive.shownToday(st, at(20), 0))
        assertEquals(0, Proactive.shownToday(st, at(9, day = 1), 0))
        assertNotNull(pick(both, st, "Normal", at(9, day = 1)).pick)
    }

    @Test fun neverMoreThanTwoAShownCardInAnyDay() {
        for (level in listOf("Low", "Normal")) {
            val rnd = Random(42)
            var st = Proactive.State()
            val perDay = HashMap<String, Int>()
            for (step in 0 until 14 * 24 * 12) { // 14 days, every 5 minutes
                val now = base + step * 5L * 60_000L
                val cands = both.filter { rnd.nextInt(3) != 0 }
                val choice = pick(cands, st, level, now)
                val p = choice.pick
                if (p != null) {
                    st = Proactive.afterShown(st, p, now, 0)
                    val k = Proactive.dayKey(now, 0)
                    perDay[k] = (perDay[k] ?: 0) + 1
                }
            }
            assertTrue(level, perDay.isNotEmpty())
            for ((day, n) in perDay) assertTrue("$level $day shown $n", n <= Proactive.perDay(level))
        }
    }

    // ---------- the hours ----------

    @Test fun nothingAtNight() {
        for (h in listOf(22, 23, 0, 3, 7)) assertEquals("hour $h", "quiet", pick(both, Proactive.State(), "Normal", at(h)).why)
        assertNull(pick(both, Proactive.State(), "Normal", at(7, 59)).pick)
        assertNotNull(pick(both, Proactive.State(), "Normal", at(8)).pick)
        assertNotNull(pick(both, Proactive.State(), "Normal", at(21, 59)).pick)
    }

    @Test fun theLocalTimeZoneDecidesTheHourAndTheDay() {
        val ist = 19_800_000 // +5:30
        val now = at(20) // 20:00 UTC = 01:30 the next day in India
        assertEquals(1, Proactive.hourOf(now, ist))
        assertTrue(Proactive.dayKey(now, ist) != Proactive.dayKey(now, 0))
        assertEquals("quiet", Proactive.pick(both, Proactive.State(), "Normal", now, ist).why)
    }

    // ---------- gaps and cool-downs ----------

    @Test fun threeHoursBetweenTwoCards() {
        val st = Proactive.afterShown(Proactive.State(), c("battery"), at(9), 0)
        assertEquals("gap", pick(listOf(c("torch")), st, "Normal", at(11, 59)).why)
        assertNotNull(pick(listOf(c("torch")), st, "Normal", at(12)).pick)
    }

    @Test fun theSameKindWaitsSixHours() {
        val st = Proactive.afterShown(Proactive.State(), c("battery"), at(8), 0)
        assertEquals("cooldown", pick(listOf(c("battery")), st, "Normal", at(13)).why)
        assertNotNull(pick(listOf(c("battery")), st, "Normal", at(14)).pick)
    }

    // ---------- muting ----------

    @Test fun aMutedKindNeverComesBack() {
        val st = Proactive.mute(Proactive.State(), "battery")
        assertEquals("muted", pick(listOf(c("battery")), st, "Normal", at(12)).why)
        assertEquals("torch", pick(both, st, "Normal", at(12)).pick!!.kind)
        assertEquals(st, Proactive.mute(st, "battery")) // muting twice changes nothing
    }

    @Test fun settingsCanSwitchAKindOnAgain() {
        val muted = Proactive.setMuted(Proactive.State(), listOf("battery", "torch", "nonsense", "battery"))
        assertEquals(listOf("battery", "torch"), muted.muted)
        assertEquals(emptyList<String>(), Proactive.setMuted(muted, emptyList()).muted)
    }

    // ---------- candidates from real data ----------

    @Test fun lowBatteryOnlyWhenNotCharging() {
        assertNotNull(Proactive.batteryCandidate(20, false))
        assertNotNull(Proactive.batteryCandidate(5, false))
        assertNull(Proactive.batteryCandidate(21, false))
        assertNull(Proactive.batteryCandidate(10, true))
        assertNull(Proactive.batteryCandidate(-1, false))
        assertTrue(Proactive.batteryCandidate(18, false)!!.text.contains("18%"))
        assertEquals("battery_saver", Proactive.batteryCandidate(18, false)!!.actionId)
    }

    @Test fun torchLeftOnAfterTenMinutes() {
        assertNull(Proactive.torchCandidate(9 * 60_000L, "e1"))
        val t = Proactive.torchCandidate(10 * 60_000L, "e1")
        assertNotNull(t)
        assertTrue(t!!.text.contains("10 minute"))
        assertEquals("e1", t.ref)
        assertEquals("torch_off", t.actionId)
        assertTrue(Proactive.torchCandidate(95 * 60_000L, "e1")!!.text.contains("95 minute"))
    }

    // ---------- storage ----------

    @Test fun stateSurvivesPackingAndDamagedStateIsEmpty() {
        var st = Proactive.afterShown(Proactive.State(), c("battery"), at(9), 0)
        st = Proactive.mute(st, "torch")
        assertEquals(st, Proactive.unpack(Proactive.pack(st)))
        assertEquals(Proactive.State(), Proactive.unpack(null))
        assertEquals(Proactive.State(), Proactive.unpack(""))
        assertEquals(Proactive.State(), Proactive.unpack("not json"))
    }

    @Test fun anOldStateWithMissingFieldsStillWorks() {
        val st = Proactive.unpack("""{"day":"d1","shown":1}""")
        assertEquals("d1", st.day)
        assertEquals(emptyList<String>(), st.muted)
        assertFalse(st.kindLast.containsKey("battery"))
    }
}
