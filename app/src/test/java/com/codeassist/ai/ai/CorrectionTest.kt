package com.codeassist.ai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionTest {
    private val now = 1_000_000_000L
    private val min = 60_000L

    // ---------- kindOf ----------

    @Test fun tierZeroAlarmReplyIsCorrectable() {
        assertEquals("Alarm", Correction.kindOf("tool", "Alarm", null, "e1", now - 2 * min, now))
    }

    @Test fun plannedTimerReplyIsCorrectable() {
        assertEquals("Timer", Correction.kindOf("gemini", "Gemini · tools", "Timer", "e1", now - min, now))
    }

    @Test fun severalActionsAreNeverCorrected() {
        assertNull(Correction.kindOf("local", null, "Alarm · Torch", "e1,e2", now - min, now))
        assertNull(Correction.kindOf("local", null, "Alarm · Torch", "e1", now - min, now))
    }

    @Test fun oldOrMissingReplyIsNotCorrected() {
        assertNull(Correction.kindOf("tool", "Alarm", null, "e1", now - 31 * min, now))
        assertNull(Correction.kindOf("tool", "Alarm", null, null, now - min, now))
        assertNull(Correction.kindOf("tool", "Alarm", null, "", now - min, now))
        assertNull(Correction.kindOf("tool", "Alarm", null, "e1", now + min, now))
    }

    @Test fun otherToolsAreNotCorrected() {
        assertNull(Correction.kindOf("tool", "Torch", null, "e1", now - min, now))
        assertNull(Correction.kindOf("tool", "Call", null, "e1", now - min, now))
    }

    // ---------- alarm corrections ----------

    private fun alarm(text: String): Correction.Fix.Alarm {
        val f = Correction.parse(text, "Alarm")
        assertNotNull(text, f)
        assertTrue(text, f is Correction.Fix.Alarm)
        return f as Correction.Fix.Alarm
    }

    @Test fun nahiThenTimeWithCue() {
        val a = alarm("nahi 9 baje subah")
        assertEquals(9, a.hour)
        assertEquals(0, a.minute)
        assertEquals(false, a.tomorrow)
    }

    @Test fun eveningCue() {
        val a = alarm("Nahi, 9 baje shaam kar do")
        assertEquals(21, a.hour)
        assertEquals(0, a.minute)
    }

    @Test fun newValueIsTheOneAfterTheLastCue() {
        val a = alarm("8 baje nahi 9 baje shaam")
        assertEquals(21, a.hour)
    }

    @Test fun twentyFourHourAndMinutes() {
        val a = alarm("nahi 21:30")
        assertEquals(21, a.hour)
        assertEquals(30, a.minute)
        val b = alarm("galat tha 6:45 am")
        assertEquals(6, b.hour)
        assertEquals(45, b.minute)
    }

    @Test fun tomorrowIsKept() {
        val a = alarm("nahi kal subah 7 baje")
        assertEquals(7, a.hour)
        assertEquals(true, a.tomorrow)
    }

    @Test fun nightCue() {
        val a = alarm("nahi 8 baje raat")
        assertEquals(20, a.hour)
    }

    @Test fun bareHourWithoutCueIsEitherAmOrPm() {
        val a = alarm("nahi 9 baje")
        assertEquals(9, a.hour % 12)
        assertEquals(0, a.minute)
    }

    // ---------- timer corrections ----------

    private fun timer(text: String): Correction.Fix.Timer {
        val f = Correction.parse(text, "Timer")
        assertNotNull(text, f)
        assertTrue(text, f is Correction.Fix.Timer)
        return f as Correction.Fix.Timer
    }

    @Test fun timerLengths() {
        assertEquals(600, timer("nahi 10 minute").seconds)
        assertEquals(7200, timer("nahi 2 ghante").seconds)
        assertEquals(30, timer("nahi, 30 second").seconds)
        assertEquals(900, timer("galat 15 minute ka kar do").seconds)
    }

    // ---------- not a correction ----------

    @Test fun kindMismatchIsNotGuessed() {
        assertNull(Correction.parse("nahi 9 baje", "Timer"))
        assertNull(Correction.parse("nahi 10 minute", "Alarm"))
    }

    @Test fun noKindMeansNoCorrection() {
        assertNull(Correction.parse("nahi 9 baje", null))
        assertNull(Correction.parse("nahi 9 baje", "Torch"))
    }

    @Test fun needsACueBeforeTheValue() {
        assertNull(Correction.parse("9 baje subah", "Alarm"))
        assertNull(Correction.parse("9 baje nahi", "Alarm"))
        assertNull(Correction.parse("nahi", "Alarm"))
    }

    @Test fun longMessagesGoToTheBrain() {
        assertNull(Correction.parse("nahi mujhe lagta hai ki kal subah 9 baje ka alarm theek rahega", "Alarm"))
    }

    @Test fun ordinaryChatIsLeftAlone() {
        assertNull(Correction.parse("nahi mujhe pata", "Alarm"))
        assertNull(Correction.parse("cancel ka matlab batao", "Alarm"))
        assertNull(Correction.parse("kal ka mausam kaisa rahega", "Timer"))
    }

    // ---------- cancel ----------

    @Test fun cancelPhrases() {
        for (p in listOf(
            "rehne do", "nahi rehne do", "cancel karo", "wo alarm hata do", "arre cancel kar do please",
            "alarm cancel karo", "nahi hata do", "undo"
        )) {
            assertTrue(p, Correction.parse(p, "Alarm") is Correction.Fix.Cancel)
            assertTrue(p, Correction.parse(p, "Timer") is Correction.Fix.Cancel)
        }
    }

    @Test fun cancelNeedsAnAlarmOrTimerReply() {
        assertNull(Correction.parse("rehne do", null))
        assertNull(Correction.parse("rehne do", "Torch"))
    }

    // ---------- undo token ----------

    @Test fun alarmTimeFromToken() {
        assertEquals(Pair(8, 30), Correction.alarmTimeOfToken("alarm:CodeAssist alarm 08:30"))
        assertEquals(Pair(0, 5), Correction.alarmTimeOfToken("alarm:CodeAssist alarm 00:05"))
        assertNull(Correction.alarmTimeOfToken("timer"))
        assertNull(Correction.alarmTimeOfToken("torch:on"))
        assertNull(Correction.alarmTimeOfToken("alarm:CodeAssist alarm 25:00"))
        assertNull(Correction.alarmTimeOfToken(null))
    }
}
