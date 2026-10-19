package com.codeassist.ai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaTest {
    @Test fun theDefaultAddsNothingToAPrompt() {
        assertEquals("", Persona.block(Persona.Spec(), "python mein list kya hai"))
        assertTrue(Persona.Spec().isDefault)
    }

    @Test fun everyChoiceAddsOneShortInstruction() {
        assertTrue(Persona.block(Persona.Spec(tone = "Professional"), "x").contains("professional"))
        assertTrue(Persona.block(Persona.Spec(tone = "Calm"), "x").contains("calm"))
        assertTrue(Persona.block(Persona.Spec(tone = "Playful"), "x").contains("playful"))
        assertTrue(Persona.block(Persona.Spec(humor = "Off"), "x").contains("Never joke"))
        assertTrue(Persona.block(Persona.Spec(humor = "Frequent"), "x").contains("light joke"))
        assertTrue(Persona.block(Persona.Spec(address = "tum"), "x").contains("tum"))
        assertTrue(Persona.block(Persona.Spec(gender = "Female"), "x").contains("karti hoon"))
        assertTrue(Persona.block(Persona.Spec(gender = "Male"), "x").contains("karta hoon"))
        assertFalse(Persona.Spec(gender = "Male").isDefault)
    }

    @Test fun theWholeStyleLineStaysSmallForTheSmallModel() {
        val b = Persona.block(Persona.Spec("Professional", "Off", "tum", "Female"), "x")
        assertTrue("style line is ${b.length} chars", b.length < 300)
        assertTrue(b.startsWith("Style: "))
    }

    @Test fun seriousMessagesGetNoJokesWhateverTheSetting() {
        for (t in listOf("ambulance bulao", "mujhe chakkar aa raha hai", "seene mein dard hai", "accident ho gaya", "mujhe suicide ke khayal aa rahe hain")) {
            assertTrue(t, Persona.serious(t))
            assertTrue(t, Persona.block(Persona.Spec(humor = "Frequent"), t).contains(Persona.SERIOUS_SENTENCE))
            // even the default persona says it, and so does a user with a system prompt of their own
            assertEquals(Persona.SERIOUS_SENTENCE, Persona.block(Persona.Spec(), t))
            assertEquals(Persona.SERIOUS_SENTENCE, Persona.block(Persona.Spec(tone = "Playful"), t, includeStyle = false))
        }
    }

    @Test fun ordinaryMessagesAreNotSerious() {
        for (t in listOf("python mein list kya hai", "kal subah 8 baje alarm laga do", "doctor ka appointment yaad rakhna", "mausam kaisa hai")) {
            assertFalse(t, Persona.serious(t))
        }
    }

    @Test fun aCustomSystemPromptKeepsItsOwnStyle() {
        assertEquals("", Persona.block(Persona.Spec(tone = "Playful", gender = "Female"), "kuch", includeStyle = false))
    }

    // ---------- fit ----------

    @Test fun femaleVoiceTurnsFirstPersonVerbsFeminine() {
        assertEquals("Soch rahi hoon", Persona.fit("Soch raha hoon", "Female"))
        assertEquals("Haan, main aapki madad kar sakti hoon.", Persona.fit("Haan, main aapki madad kar sakta hoon.", "Female"))
        assertEquals("Theek hai, abhi karti hoon.", Persona.fit("Theek hai, abhi karta hoon.", "Female"))
        assertEquals("Sun rahi hoon…", Persona.fit("Sun raha hoon…", "Female"))
        assertEquals("Bol rahi hoon", Persona.fit("Bol raha hoon", "Female"))
        assertEquals("Main bata dungi", Persona.fit("Main bata dunga", "Female"))
        assertEquals("Main kal karungi", Persona.fit("Main kal karunga", "Female"))
    }

    @Test fun capitalLettersSurvive() {
        assertEquals("SOCH RAHI HOON", Persona.fit("SOCH RAHA HOON", "Female"))
        assertEquals("Karti hoon abhi", Persona.fit("Karta hoon abhi", "Female"))
    }

    @Test fun otherGendersAndOtherWordsAreLeftAlone() {
        for (g in listOf("Auto", "Male", "")) assertEquals("Soch raha hoon", Persona.fit("Soch raha hoon", g))
        assertEquals("Main Asha hoon", Persona.fit("Main Asha hoon", "Female"))
        assertEquals("Torch on kar di.", Persona.fit("Torch on kar di.", "Female"))
        assertEquals("Alarm laga diya.", Persona.fit("Alarm laga diya.", "Female"))
        assertEquals("Tum kahan ho?", Persona.fit("Tum kahan ho?", "Female"))
        assertEquals("", Persona.fit("", "Female"))
    }

    @Test fun theDefaultPromptExampleBecomesFeminineForTheFemaleVoice() {
        val f = Persona.fit(Prompts.LOCAL_SYSTEM, "Female")
        assertTrue(f.contains("sakti hoon"))
        assertFalse(f.contains("sakta hoon"))
        assertEquals(Prompts.LOCAL_SYSTEM, Persona.fit(Prompts.LOCAL_SYSTEM, "Auto"))
    }
}
