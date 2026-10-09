package com.codeassist.ai.voice

import com.codeassist.ai.voice.WakeMatcher.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeMatcherTest {
    private val phrase = "hey code assist"

    @Test fun fullPhraseMatchesAtEveryLevel() {
        for (level in Level.values()) {
            assertTrue(level.name, WakeMatcher.match("hey code assist", phrase, level).matched)
        }
    }

    @Test fun commandAfterThePhraseIsReturned() {
        val r = WakeMatcher.match("Hey, code assist! time batao", phrase, Level.NORMAL)
        assertTrue(r.matched)
        assertEquals("time batao", r.remainder)
    }

    @Test fun greetingIsNeverRequired() {
        assertTrue(WakeMatcher.match("code assist", phrase, Level.STRICT).matched)
        assertTrue(WakeMatcher.match("code assist", phrase, Level.NORMAL).matched)
    }

    @Test fun clippedStartOnlyPassesAtNormalOrLoose() {
        assertTrue(WakeMatcher.match("assist", phrase, Level.NORMAL).matched)
        assertFalse(WakeMatcher.match("assist", phrase, Level.STRICT).matched)
    }

    @Test fun glueAndPrefixSpellingsAreAccepted() {
        val glued = WakeMatcher.match("hey codeassist what time is it", phrase, Level.NORMAL)
        assertTrue(glued.matched)
        assertEquals("what time is it", glued.remainder)
        assertTrue(WakeMatcher.match("hey code assistant", phrase, Level.NORMAL).matched)
        assertFalse(WakeMatcher.match("assistance please", phrase, Level.NORMAL).matched)
    }

    @Test fun ordinaryTalkDoesNotWake() {
        assertFalse(WakeMatcher.match("good morning everyone", phrase, Level.NORMAL).matched)
        assertFalse(WakeMatcher.match("good morning everyone", phrase, Level.LOOSE).matched)
        assertFalse(WakeMatcher.match("the cat sat", phrase, Level.NORMAL).matched)
        assertFalse(WakeMatcher.match("code", phrase, Level.NORMAL).matched)
    }

    @Test fun looseAcceptsOneKeyWord() {
        val r = WakeMatcher.match("please assist me", phrase, Level.LOOSE)
        assertTrue(r.matched)
        assertEquals("me", r.remainder)
        assertFalse(WakeMatcher.match("please assist me", phrase, Level.STRICT).matched)
    }

    @Test fun looseTakesTheLastKeyWordForTheRemainder() {
        val r = WakeMatcher.match("code assist time batao", phrase, Level.LOOSE)
        assertTrue(r.matched)
        assertEquals("time batao", r.remainder)
    }

    @Test fun alternativesAreAllChecked() {
        val r = WakeMatcher.matchAny(listOf("hey good assistant", "hey code assist now"), phrase, Level.NORMAL)
        assertTrue(r.matched)
        assertEquals("now", r.remainder)
    }

    @Test fun emptyInputNeverMatches() {
        assertFalse(WakeMatcher.match("", phrase, Level.LOOSE).matched)
        assertFalse(WakeMatcher.match("hello", "", Level.LOOSE).matched)
    }

    @Test fun levelNamesMap() {
        assertEquals(Level.STRICT, WakeMatcher.levelOf("strict"))
        assertEquals(Level.LOOSE, WakeMatcher.levelOf("loose"))
        assertEquals(Level.NORMAL, WakeMatcher.levelOf("anything"))
    }

    @Test fun phraseWarnings() {
        assertNull(WakeMatcher.phraseWarning("hey code assist"))
        assertNotNull(WakeMatcher.phraseWarning("Rani"))
        assertNotNull(WakeMatcher.phraseWarning("ok google"))
        assertNotNull(WakeMatcher.phraseWarning("go"))
        assertNotNull(WakeMatcher.phraseWarning("  "))
    }

    // ---- the default phrase is "hey jarvis" ----
    private val jarvis = "hey jarvis"

    @Test fun jarvisMatchesWithOrWithoutTheGreeting() {
        for (level in Level.values()) {
            assertTrue(level.name, WakeMatcher.match("hey jarvis", jarvis, level).matched)
            assertTrue(level.name, WakeMatcher.match("jarvis", jarvis, level).matched)
        }
    }

    @Test fun jarvisCommandAfterThePhraseIsReturned() {
        val r = WakeMatcher.match("Hey Jarvis! what time is it", jarvis, Level.NORMAL)
        assertTrue(r.matched)
        assertEquals("what time is it", r.remainder)
    }

    @Test fun jarvisSplitAndSlightlyOffSpellingsAreAccepted() {
        assertTrue(WakeMatcher.match("hey jar vis", jarvis, Level.NORMAL).matched)
        assertTrue(WakeMatcher.match("garvis", jarvis, Level.NORMAL).matched)
    }

    @Test fun jarvisIgnoresOrdinaryTalk() {
        assertFalse(WakeMatcher.match("good morning everyone", jarvis, Level.NORMAL).matched)
        assertFalse(WakeMatcher.match("good morning everyone", jarvis, Level.LOOSE).matched)
        assertFalse(WakeMatcher.match("harvest season is here", jarvis, Level.NORMAL).matched)
        assertFalse(WakeMatcher.match("customer service", jarvis, Level.NORMAL).matched)
    }

    @Test fun jarvisPhraseHasNoWarning() {
        assertNull(WakeMatcher.phraseWarning("hey jarvis"))
        assertNotNull(WakeMatcher.phraseWarning("jarvis")) // 2 syllables only: the editor still warns
    }
}
