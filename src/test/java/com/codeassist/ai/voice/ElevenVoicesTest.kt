package com.codeassist.ai.voice

import com.codeassist.ai.ai.ElevenLabsClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevenVoicesTest {
    @Test fun saveAndLoadKeepsEveryField() {
        val list = listOf(
            ElevenLabsClient.Voice("id1", "Rachel", "female", "american"),
            ElevenLabsClient.Voice("id2", "George", "male", "")
        )
        val back = ElevenLabsClient.decodeVoices(ElevenLabsClient.encodeVoices(list))
        assertEquals(2, back.size)
        assertEquals("id1", back[0].id)
        assertEquals("Rachel", back[0].name)
        assertEquals("female", back[0].gender)
        assertEquals("american", back[0].accent)
        assertEquals("male", back[1].gender)
        assertEquals("", back[1].accent)
    }

    @Test fun tabsAndNewlinesInANameCannotBreakTheList() {
        val list = listOf(ElevenLabsClient.Voice("id1", "Odd\tName\nHere", "male", ""))
        val back = ElevenLabsClient.decodeVoices(ElevenLabsClient.encodeVoices(list))
        assertEquals(1, back.size)
        assertEquals("Odd Name Here", back[0].name)
    }

    @Test fun unknownGenderBecomesOtherAndBadLinesAreDropped() {
        val raw = "a\tAlex\tNeutral\t\nbroken line\n\tNoId\tmale\t\nb\tBen\tMALE\tbritish"
        val back = ElevenLabsClient.decodeVoices(raw)
        assertEquals(2, back.size)
        assertEquals("other", back[0].gender)
        assertEquals("male", back[1].gender)
    }

    @Test fun emptySavedListGivesNoVoices() {
        assertTrue(ElevenLabsClient.decodeVoices("").isEmpty())
    }

    @Test fun labelShowsNameAndKind() {
        assertEquals("Rachel · Female", ElevenLabsClient.Voice("i", "Rachel", "female", "").label())
        assertEquals("Ben · Male · british", ElevenLabsClient.Voice("i", "Ben", "male", "british").label())
    }

    @Test fun fallbackHasOneVoiceOfEachKind() {
        val kinds = ElevenLabsClient.FALLBACK_VOICES.map { it.gender }.toSet()
        assertEquals(setOf("female", "male"), kinds)
    }
}
