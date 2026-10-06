package com.codeassist.ai.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpokenTrackerTest {
    private val text = "Pehla vaakya yahan hai. Doosra vaakya thoda lamba hai. Teesra aakhri vaakya hai."

    @Test fun resumesFromTheSentenceThatWasCut() {
        val t = SpokenTracker()
        val chunks = SpeechText.chunks(text, 500)
        t.reset(text, chunks)
        t.onRange(0, text.indexOf("lamba")) // cut in the middle of sentence 2
        assertEquals("Doosra vaakya thoda lamba hai. Teesra aakhri vaakya hai.", t.remainingFromSentence())
    }

    @Test fun nothingLeftWhenAlmostEverythingWasSpoken() {
        val t = SpokenTracker()
        t.reset(text, SpeechText.chunks(text, 500))
        t.onRange(0, text.length)
        assertNull(t.remainingFromSentence())
    }

    @Test fun chunkStartIsTheFallbackWithoutRangeCallbacks() {
        val t = SpokenTracker()
        val long = "Ek. ".repeat(40).trim() + " Antim vaakya yahan khatam hota hai."
        val chunks = SpeechText.chunks(long, 60)
        t.reset(long, chunks)
        t.onChunkStart(chunks.size - 1)
        assertEquals("Antim vaakya yahan khatam hota hai.", t.remainingFromSentence())
    }
}
