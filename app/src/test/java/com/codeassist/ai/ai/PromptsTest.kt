package com.codeassist.ai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T2 (test chat 13 Oct 2026): what the prompt says about the real stack, and the user's language choice. */
class PromptsTest {
    @Test fun thePhoneBrainLineNamesGemmaAndLiteRt() {
        val s = Prompts.stackLine("local", "")
        assertTrue(s.contains("Gemma 4 E2B"))
        assertTrue(s.contains("LiteRT-LM"))
        assertTrue(s.contains("offline"))
        assertFalse(s.contains("Qwen"))
    }

    @Test fun theGeminiLineNamesTheModelAndStillKnowsTheOfflineBrain() {
        val s = Prompts.stackLine("gemini", "gemini-2.5-flash")
        assertTrue(s.contains("Google Gemini (model gemini-2.5-flash)"))
        assertTrue(s.contains("Gemma 4 E2B"))
        assertFalse(Prompts.stackLine("gemini", "  ").contains("(model"))
    }

    @Test fun theLanguageLineFollowsTheChoice() {
        assertTrue(Prompts.languageLine("hinglish").contains("Roman Hinglish"))
        assertTrue(Prompts.languageLine("hinglish").contains("even when the user writes in English"))
        assertTrue(Prompts.languageLine("english").contains("reply in English"))
        // a one-off translation request must still be answered in the other language
        assertTrue(Prompts.languageLine("hinglish").contains("asks for a translation"))
        assertTrue(Prompts.languageLine("english").contains("asks for a translation"))
        assertEquals("", Prompts.languageLine(null))
        assertEquals("", Prompts.languageLine("klingon"))
    }

    @Test fun theLanguageExampleHasNoVerbThatChangesWithGender() {
        // the Female voice rewrites only the base prompt ("sakta hoon" -> "sakti hoon"), never this line
        val line = Prompts.languageLine("hinglish")
        assertFalse(line.contains("hoon"))
        assertFalse(line.contains("unga"))
    }
}
