package com.codeassist.ai.ai

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
}
