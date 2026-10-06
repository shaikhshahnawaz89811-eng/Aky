package com.codeassist.ai.voice

import com.codeassist.ai.voice.BargeInGate.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BargeInGateTest {
    private fun play(vararg parts: Pair<Int, Pair<Boolean, Float>>): List<Event> {
        val gate = BargeInGate()
        val out = ArrayList<Event>()
        for ((frames, f) in parts) {
            repeat(frames) {
                val e = gate.feed(f.first, f.second)
                if (e != Event.NONE) out.add(e)
            }
        }
        return out
    }

    private val quietSpeech = true to 3f
    private val loudSpeech = true to 6f
    private val silence = false to 1f

    @Test fun shortQuietBurstIsABackchannel() {
        assertEquals(listOf(Event.CANDIDATE, Event.REJECTED), play(15 to quietSpeech, 20 to silence))
    }

    @Test fun sustainedSpeechInterrupts() {
        assertEquals(listOf(Event.CANDIDATE, Event.VALID), play(35 to quietSpeech, 20 to silence))
    }

    @Test fun shortLoudBurstInterrupts() {
        assertEquals(listOf(Event.CANDIDATE, Event.VALID), play(18 to loudSpeech, 20 to silence))
    }

    @Test fun clickOrSpikeIsIgnored() {
        assertFalse(play(3 to loudSpeech, 20 to silence).contains(Event.VALID))
        assertEquals(emptyList<Event>(), play(3 to loudSpeech, 20 to silence))
    }

    @Test fun smallGapDoesNotSplitASentence() {
        assertEquals(
            listOf(Event.CANDIDATE, Event.VALID),
            play(20 to quietSpeech, 8 to silence, 15 to quietSpeech, 20 to silence)
        )
    }
}
