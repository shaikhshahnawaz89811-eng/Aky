package com.codeassist.ai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionHintTest {
    @Test fun shortPhoneRequestsMatch() {
        assertTrue(ActionHint.looksLikeAction("kal subah 8 baje ka alarm laga do"))
        assertTrue(ActionHint.looksLikeAction("Time batao aur battery kitni hai"))
        assertTrue(ActionHint.looksLikeAction("torch on karo"))
        assertTrue(ActionHint.looksLikeAction("मेरा अलार्म लगा दो"))
        assertTrue(ActionHint.looksLikeAction("WhatsApp kholo"))
    }

    @Test fun wakeAndReminderPhrasesMatch() {
        assertTrue(ActionHint.looksLikeAction("Kal mujhe 8 bhaje uthna hai"))
        assertTrue(ActionHint.looksLikeAction("subah 6 baje utha dena"))
        assertTrue(ActionHint.looksLikeAction("10 minute baad yaad dilana"))
    }

    @Test fun weatherRequestsMatch() {
        assertTrue(ActionHint.looksLikeAction("Mumbai ka mausam batao"))
        assertTrue(ActionHint.looksLikeAction("what is the weather in Pune"))
        assertTrue(ActionHint.looksLikeAction("kal barish hogi kya"))
    }

    @Test fun ordinaryChatDoesNotMatch() {
        assertFalse(ActionHint.looksLikeAction("Kotlin coroutines kya hote hain?"))
        assertFalse(ActionHint.looksLikeAction("mujhe ek kahani sunao"))
        assertFalse(ActionHint.looksLikeAction(""))
    }

    @Test fun programmingQuestionsWithActionWordsDoNotMatch() {
        assertFalse(ActionHint.looksLikeAction("what is the time complexity of quicksort"))
        assertFalse(ActionHint.looksLikeAction("how do I call a function in python"))
        assertFalse(ActionHint.looksLikeAction("open the file and print it"+" {x = 1}"))
    }

    @Test fun longOrMultilineMessagesDoNotMatch() {
        assertFalse(ActionHint.looksLikeAction("alarm\nlaga do"))
        assertFalse(ActionHint.looksLikeAction("alarm " + "word ".repeat(40)))
    }

    // ---------- test chat 13 Oct 2026: "WhatsApp on karo" got no tools ----------

    private val installed = { listOf("whatsapp", "youtube", "google maps", "hdfc bank") }

    @Test fun appOnKaroNeedsAnAppName() {
        assertTrue(ActionHint.looksLikeAction("WhatsApp on karo", installed))
        assertTrue(ActionHint.looksLikeAction("youtube chalu kar do", installed))
        assertTrue(ActionHint.looksLikeAction("mera whatsapp on kar do please", installed))
        assertTrue(ActionHint.looksLikeAction("google maps on karo", installed))
        assertTrue(ActionHint.looksLikeAction("hdfc on karo", installed))
        assertTrue(ActionHint.looksLikeAction("WhatsApp on karo")) // no installed list: the well known names still count
    }

    @Test fun onAndChaluAloneAreNotActions() {
        assertFalse(ActionHint.looksLikeAction("wifi on karo", installed))
        assertFalse(ActionHint.looksLikeAction("ek kahani on karo", installed))
        assertFalse(ActionHint.looksLikeAction("on karo", installed))
        assertFalse(ActionHint.looksLikeAction("tell me about movies based on true stories", installed))
        assertFalse(ActionHint.looksLikeAction("ye gaana on hai", installed))
    }

    @Test fun theAppListIsOnlyAskedForForTheAppOnShape() {
        var asked = 0
        val spy = { asked++; listOf("whatsapp") }
        ActionHint.looksLikeAction("mujhe ek kahani sunao", spy)
        ActionHint.looksLikeAction("torch on karo", spy) // an action word decides first
        assertEquals(0, asked)
        ActionHint.looksLikeAction("hdfc on karo", spy)
        assertEquals(1, asked)
    }
}
