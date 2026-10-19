package com.codeassist.ai.ai

import com.codeassist.ai.ai.LocalTemplate.Kind
import com.codeassist.ai.data.Message
import com.codeassist.ai.data.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTemplateTest {
    private fun user(t: String) = Message(role = Role.USER, text = t)
    private fun ai(t: String) = Message(role = Role.AI, text = t)

    @Test fun kindsAreFoundByKey() {
        assertEquals(Kind.CHATML, Kind.of("chatml"))
        assertEquals(Kind.LIB, Kind.of("lib"))
        assertEquals(Kind.PLAIN, Kind.of("plain"))
        assertNull(Kind.of("auto"))
        assertNull(Kind.of(null))
        assertNull(Kind.of("zzz"))
    }

    @Test fun chatmlShapeIsTheHandBuiltPromptWithAnEmptyLibrarySystem() {
        val b = LocalTemplate.build(Kind.CHATML, "SYS", emptyList(), "Hello", 5000)
        assertEquals("", b.system)
        assertEquals(ChatMl.build("SYS", emptyList(), "Hello", 5000), b.prompt)
    }

    @Test fun libShapePassesTheSystemPromptSeparatelyAndFoldsTheHistory() {
        val first = LocalTemplate.build(Kind.LIB, "SYS", emptyList(), "Hello", 5000)
        assertEquals("SYS", first.system)
        assertEquals("Hello", first.prompt)
        val later = LocalTemplate.build(Kind.LIB, "SYS", listOf(user("a"), ai("b")), "c", 5000)
        assertTrue(later.prompt.startsWith("Earlier in this conversation:\nUser: a\nAssistant: b"))
        assertTrue(later.prompt.endsWith("New message from the user (reply to this one):\nc"))
    }

    @Test fun plainShapeEndsWithAnOpenAssistantLabel() {
        val b = LocalTemplate.build(Kind.PLAIN, "SYS", emptyList(), "Hello", 5000)
        assertEquals("", b.system)
        assertEquals("SYS\n\nUser: Hello\nAssistant:", b.prompt)
    }

    @Test fun finishOnlyCutsThePlainShape() {
        assertEquals("Theek hoon.", LocalTemplate.finish(Kind.PLAIN, "Theek hoon.\nUser: aur?"))
        assertEquals("Theek hoon.\nUser: aur?", LocalTemplate.finish(Kind.CHATML, "Theek hoon.\nUser: aur?"))
        assertEquals("Theek hoon.\nUser: aur?", LocalTemplate.finish(Kind.LIB, "Theek hoon.\nUser: aur?"))
    }

    @Test fun aShortAnswerThatStoppedByItselfPasses() {
        val p = LocalTemplate.judge(Kind.CHATML, "Hello! How can I help you today?", 9)
        assertTrue(p.stopped)
        assertTrue(p.sane)
        assertTrue(p.good)
        assertTrue(p.line().startsWith("chatml: 9 tok, ruka, saaf"))
    }

    @Test fun anAnswerThatRanIntoTheTokenLimitFails() {
        val p = LocalTemplate.judge(Kind.CHATML, "Hello! How can I help you today?", LocalTemplate.PROBE_TOKENS)
        assertFalse(p.stopped)
        assertTrue(p.sane)
        assertFalse(p.good)
    }

    @Test fun junkFails() {
        val p = LocalTemplate.judge(Kind.CHATML, "Hello \u5B57\u6837\u8BED\u8BED", 12)
        assertFalse(p.sane)
        assertFalse(p.good)
    }

    @Test fun blankFails() {
        assertFalse(LocalTemplate.judge(Kind.LIB, "", 1).good)
        assertFalse(LocalTemplate.judge(Kind.LIB, "", 0).good)
    }

    @Test fun thePlainShapeNeedsNoSelfStopBecauseItIsCutByHand() {
        val p = LocalTemplate.judge(Kind.PLAIN, "Hello! How can I help you today?", LocalTemplate.PROBE_TOKENS)
        assertFalse(p.stopped)
        assertTrue(p.good)
    }

    @Test fun aLongRamblingAnswerToHelloIsNotSane() {
        val long = "Hello! " + "I am happy to help you with anything you would like to ask me today. ".repeat(5)
        assertFalse(LocalTemplate.judge(Kind.LIB, long, 30).sane)
    }
}
