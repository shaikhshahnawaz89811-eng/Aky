package com.codeassist.ai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HinglishGuideTest {
    @Test fun romanHindiIsHinglishEnglishIsNot() {
        assertTrue(HinglishGuide.userWritesHinglish("Kal mujhe 8 bhaje uthna hai"))
        assertTrue(HinglishGuide.userWritesHinglish("python mein list kya hai"))
        assertTrue(HinglishGuide.userWritesHinglish("kaise ho?"))
        assertFalse(HinglishGuide.userWritesHinglish("Hello"))
        assertFalse(HinglishGuide.userWritesHinglish("Torch on"))
        assertFalse(HinglishGuide.userWritesHinglish("create 2 line python code"))
        assertFalse(HinglishGuide.userWritesHinglish("मेरा अलार्म लगा दो"))
        assertFalse(HinglishGuide.userWritesHinglish(""))
    }

    @Test fun englishAnswerToHinglishIsAMismatch() {
        val english = "I am doing great, thank you for asking. How can I help you today my friend?"
        assertFalse(HinglishGuide.replyMatchesLanguage("kaise ho aap", english))
        val hinglish = "Main theek hoon, shukriya! Aap batao, aaj main aapki kya madad kar sakta hoon, bolo kuch bhi."
        assertTrue(HinglishGuide.replyMatchesLanguage("kaise ho aap", hinglish))
    }

    @Test fun shortRepliesCodeAndEnglishQuestionsAreNeverJudged() {
        assertTrue(HinglishGuide.replyMatchesLanguage("kaise ho aap", "I am fine."))
        assertTrue(HinglishGuide.replyMatchesLanguage("python mein loop kaise likhte hain", "```python\nfor i in range(3):\n    print(i)\n```"))
        assertTrue(HinglishGuide.replyMatchesLanguage("What is a list in python?", "A list holds several values in order, and you reach them by index number."))
    }

    @Test fun fewShotStartsWithAUserTurnAndAlternates() {
        val shots = HinglishGuide.fewShot()
        assertEquals(4, shots.size)
        assertEquals(com.codeassist.ai.data.Role.USER, shots[0].role)
        assertEquals(com.codeassist.ai.data.Role.AI, shots[1].role)
    }

    @Test fun prefillIsSkippedForTheLibraryTemplate() {
        assertEquals("", HinglishGuide.prefillFor(LocalTemplate.Kind.LIB))
        assertEquals("Ji, ", HinglishGuide.prefillFor(LocalTemplate.Kind.CHATML))
    }
}
