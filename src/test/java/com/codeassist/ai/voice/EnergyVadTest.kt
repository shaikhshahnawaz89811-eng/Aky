package com.codeassist.ai.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyVadTest {
    @Test fun steadyEchoBecomesTheFloorNotSpeech() {
        val vad = EnergyVad(2.5f)
        var flagged = 0
        repeat(50) { if (vad.process(400f)) flagged++ }
        assertEquals(0, flagged)
    }

    @Test fun personTalkingOverEchoIsSpeech() {
        val vad = EnergyVad(2.5f)
        repeat(50) { vad.process(400f) }
        var flagged = 0
        repeat(20) { if (vad.process(1500f)) flagged++ }
        assertEquals(20, flagged)
        assertTrue(vad.lastRatio > 2.5f)
    }

    @Test fun quietRoomSpeechIsDetected() {
        val vad = EnergyVad(2.5f)
        repeat(30) { vad.process(60f) }
        var flagged = 0
        repeat(10) { if (vad.process(800f)) flagged++ }
        assertEquals(10, flagged)
    }

    @Test fun rmsOfSilenceIsZero() {
        assertEquals(0f, EnergyVad.rms(ShortArray(320), 320), 0.0001f)
    }
}
