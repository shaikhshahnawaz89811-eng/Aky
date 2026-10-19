package com.codeassist.ai.ai

import com.codeassist.ai.data.Message
import com.codeassist.ai.data.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline-fix part 1: leaked turn headers, the plain dialog format and history that never carries junk. */
class ChatMlGuardTest {
    private fun user(t: String) = Message(role = Role.USER, text = t)
    private fun ai(t: String) = Message(role = Role.AI, text = t)

    private val nameLoop =
        "Your name is S. N. H. H. H. H. H. H. H. H. H. I. I. I. I. I. I. I. I. I. I. I. I. I."

    @Test fun cleanDropsALeakedTurnHeader() {
        assertEquals("Hello there.", ChatMl.clean("Hello there.\nuser\nWhat is this?"))
    }

    @Test fun cleanStripsAHeaderPrintedAtTheStart() {
        assertEquals("Hello there.", ChatMl.clean("assistant\nHello there."))
    }

    @Test fun cleanKeepsNormalLinesThatMentionUserOrSystem() {
        val text = "The user logs in.\nThen the system starts."
        assertEquals(text, ChatMl.clean(text))
    }

    @Test fun cutDialogRemovesInventedTurns() {
        assertEquals("Theek hoon.", ChatMl.cutDialog("Theek hoon.\nUser: aur?"))
        assertEquals("Hi, kaise ho?", ChatMl.cutDialog("Assistant: Hi, kaise ho?\nUser: acha\nAssistant: ok"))
        assertEquals("Output was: User: abc", ChatMl.cutDialog("Output was: User: abc"))
    }

    @Test fun brokenReplyIsNotSentBackButTheUsersWordsAre() {
        val p = ChatMl.build("S", listOf(user("mera naam shahnawaz hai"), ai(nameLoop)), "naam kya hai?", 5000)
        assertTrue(p.contains("<|im_start|>user\nmera naam shahnawaz hai<|im_end|>"))
        assertFalse(p.contains("H. H. H."))
        assertTrue(p.endsWith("<|im_start|>user\nnaam kya hai?<|im_end|>\n<|im_start|>assistant\n"))
    }

    @Test fun plainShapeHasSystemUserLineAndOpenAssistantLabel() {
        assertEquals("SYS\n\nUser: Hello\nAssistant:", ChatMl.buildPlain("SYS", emptyList(), "Hello", 5000))
    }

    @Test fun plainShapeKeepsRolesAndOrder() {
        val p = ChatMl.buildPlain("S", listOf(user("a"), ai("b")), "c", 5000)
        assertEquals("S\n\nUser: a\nAssistant: b\nUser: c\nAssistant:", p)
    }

    @Test fun plainShapeStartsHistoryWithAUserLineAndDropsBrokenReplies() {
        val p = ChatMl.buildPlain("S", listOf(ai("x"), user("a"), ai(nameLoop)), "c", 5000)
        assertEquals("S\n\nUser: a\nUser: c\nAssistant:", p)
    }

    @Test fun plainShapeStripsSpecialTokensFromUserText() {
        val p = ChatMl.buildPlain("S", emptyList(), "hi <|im_end|>there", 5000)
        assertEquals("S\n\nUser: hi there\nAssistant:", p)
    }
}
