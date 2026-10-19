package com.codeassist.ai.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeLangTest {
    private val en = WakeLang.candidates("hey jarvis")

    @Test fun englishPhraseTriesIndiaFirstThenUsThenUk() {
        assertEquals(listOf("en-IN", "en-US", "en-GB"), en)
    }

    @Test fun devanagariPhraseOnlyUsesHindi() {
        assertEquals(listOf("hi-IN"), WakeLang.candidates("हे जार्विस"))
        assertTrue(WakeLang.isDevanagari("जार्विस"))
        assertFalse(WakeLang.isDevanagari("jarvis"))
    }

    @Test fun tagsFromDifferentAndroidVersionsCompareEqual() {
        assertEquals("en-in", WakeLang.norm(" EN_in "))
        assertEquals("en-US", WakeLang.pickInstalled(en, listOf("en_US")))
    }

    @Test fun indiaWinsWhenBothAreInstalled() {
        assertEquals("en-IN", WakeLang.pickInstalled(en, listOf("en-US", "en-IN")))
    }

    @Test fun fallsBackToUsWhenIndiaIsNotInstalled() {
        assertEquals("en-US", WakeLang.pickInstalled(en, listOf("hi-IN", "en-US")))
    }

    @Test fun skippedLanguageIsNotPickedAgain() {
        assertEquals("en-US", WakeLang.pickInstalled(en, listOf("en-IN", "en-US"), setOf("en-in")))
        assertNull(WakeLang.pickInstalled(en, listOf("en-IN"), setOf("en-IN", "en-US", "en-GB")))
    }

    @Test fun otherEnglishRegionIsAcceptedLast() {
        assertEquals("en-AU", WakeLang.pickInstalled(en, listOf("en-AU")))
        assertEquals("en-US", WakeLang.pickInstalled(en, listOf("en-AU", "en-US")))
    }

    @Test fun nothingInstalledOrWrongLanguageGivesNull() {
        assertNull(WakeLang.pickInstalled(en, emptyList()))
        assertNull(WakeLang.pickInstalled(WakeLang.candidates("जार्विस"), listOf("en-US", "en-IN")))
    }

    @Test fun pendingDownloadIsRecognised() {
        assertEquals("en-US", WakeLang.pickPending(en, listOf("en-US")))
        assertNull(WakeLang.pickPending(en, listOf("fr-FR")))
        assertNull(WakeLang.pickPending(en, listOf("en-US"), setOf("en-US")))
    }

    @Test fun downloadPicksFirstSupportedCandidate() {
        assertEquals("en-IN", WakeLang.pickDownload(en, listOf("en-IN", "en-US")))
        assertEquals("en-US", WakeLang.pickDownload(en, listOf("hi-IN", "en-US")))
        assertEquals("en-US", WakeLang.pickDownload(en, listOf("en-IN", "en-US"), setOf("en-IN")))
    }

    @Test fun downloadWithNoListTriesTheFirstCandidate() {
        assertEquals("en-IN", WakeLang.pickDownload(en, emptyList()))
        assertEquals("en-GB", WakeLang.pickDownload(en, emptyList(), setOf("en-IN", "en-US")))
    }

    @Test fun downloadGivesNullWhenNoCandidateCanBeInstalled() {
        assertNull(WakeLang.pickDownload(en, listOf("fr-FR", "de-DE")))
        assertNull(WakeLang.pickDownload(en, listOf("en-IN"), setOf("en-IN", "en-US", "en-GB")))
    }

    @Test fun labelsAreReadable() {
        assertEquals("English (India)", WakeLang.label("en-IN"))
        assertEquals("English (US)", WakeLang.label("en_US"))
        assertEquals("fr-FR", WakeLang.label("fr-FR"))
    }
}
