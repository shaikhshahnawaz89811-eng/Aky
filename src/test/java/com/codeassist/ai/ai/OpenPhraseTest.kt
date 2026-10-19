package com.codeassist.ai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenPhraseTest {
    private fun name(t: String): String? = OpenPhrase.parse(t)?.name
    private fun strict(t: String): Boolean? = OpenPhrase.parse(t)?.strict

    @Test fun theOldPhrasesStillWorkAndStayLoose() {
        assertEquals("whatsapp", name("open whatsapp"))
        assertEquals("whatsapp", name("whatsapp kholo"))
        assertEquals("whatsapp", name("whatsapp khol do"))
        assertEquals("youtube", name("youtube chalu karo"))
        assertEquals("camera", name("camera open karo"))
        assertEquals(false, strict("whatsapp kholo"))
        assertEquals(false, strict("open whatsapp"))
    }

    @Test fun switchOnPhrasesOpenTheAppAndAreStrict() {
        assertEquals("whatsapp", name("whatsapp on karo"))
        assertEquals(true, strict("whatsapp on karo"))
        assertEquals("youtube", name("youtube chalu kar do"))
        assertEquals("whatsapp", name("whatsapp ko on kar do"))
        assertEquals("instagram", name("instagram on"))
        assertEquals("youtube", name("youtube chalu"))
        assertEquals("maps", name("maps start kar do"))
    }

    @Test fun phoneSettingsAreNotApps() {
        assertNull(OpenPhrase.parse("wifi on karo"))
        assertNull(OpenPhrase.parse("bluetooth on kar do"))
        assertNull(OpenPhrase.parse("mobile data on kar do"))
        assertNull(OpenPhrase.parse("hotspot chalu kar do"))
        assertNull(OpenPhrase.parse("wifi on"))
    }

    @Test fun otherSentencesAreNotOpenRequests() {
        assertNull(OpenPhrase.parse("time batao"))
        assertNull(OpenPhrase.parse("whatsapp"))
        assertNull(OpenPhrase.parse("on karo"))
        assertNull(OpenPhrase.parse("a on karo")) // one letter is not an app name
    }
}
