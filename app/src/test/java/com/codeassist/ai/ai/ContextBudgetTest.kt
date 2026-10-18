package com.codeassist.ai.ai

import com.codeassist.ai.data.Message
import com.codeassist.ai.data.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBudgetTest {
    private fun user(t: String) = Message(role = Role.USER, text = t)
    private fun ai(t: String) = Message(role = Role.AI, text = t)

    /** The exact text of the real failure (test chat 17 Oct 2026). */
    private val realError =
        "Status Code: 3. Message: Input token ids are too long. Exceeding the maximum number of tokens allowed: 4123 >= 4096"

    private fun longChat(turns: Int, chars: Int): List<Message> {
        val out = ArrayList<Message>()
        for (i in 0 until turns) {
            val body = "x".repeat(chars)
            out.add(if (i % 2 == 0) user(body) else ai(body))
        }
        return out
    }

    private fun promptTokens(system: String, f: ContextBudget.Fitted): Int {
        var n = ContextBudget.estimateTokens(system) + ContextBudget.PROMPT_OVERHEAD_TOKENS + ContextBudget.estimateTokens(f.userText)
        for (m in f.history) n += ContextBudget.estimateTokens(m.text) + ContextBudget.TURN_OVERHEAD_TOKENS
        return n
    }

    @Test
    fun replyRoomIsAlwaysLeft() {
        assertEquals(512, ContextBudget.replyReserve(2048))
        assertEquals(1024, ContextBudget.replyReserve(4096))
        assertEquals(2048, ContextBudget.replyReserve(8192))
        assertEquals(3072, ContextBudget.promptBudget(4096))
    }

    @Test
    fun longChatIsCutToTheWindowNewestTurnsFirst() {
        val chat = longChat(22, 1500) // the shape of the failing chat: 22 turns of long text
        val system = "s".repeat(2000)
        val f = ContextBudget.fit(4096, system, chat, "Kaise try kare?")
        assertTrue(promptTokens(system, f) <= ContextBudget.promptBudget(4096))
        assertTrue(f.history.size < chat.size)
        assertEquals(chat.size - f.history.size, f.droppedTurns)
        assertEquals(Role.USER, f.history.first().role)
        // the kept turns are the newest ones, in order
        assertEquals(chat.last().text, f.history.last().text)
    }

    @Test
    fun shortChatIsLeftAlone() {
        val chat = listOf(user("Hello"), ai("Hi! Kaise ho?"), user("Theek"), ai("Badhiya."))
        val f = ContextBudget.fit(4096, "sys", chat, "Aaj kya karein?")
        assertEquals(4, f.history.size)
        assertEquals(0, f.droppedTurns)
        assertFalse(f.trimmedUser)
        assertEquals("Aaj kya karein?", f.userText)
    }

    @Test
    fun hugeNewMessageKeepsItsEndAndDropsHistory() {
        val big = "a".repeat(40_000) + " ASAL SAWAL YAHAN HAI"
        val f = ContextBudget.fit(4096, "sys", longChat(6, 300), big)
        assertTrue(f.trimmedUser)
        assertTrue(f.userText.startsWith(ContextBudget.TRIM_MARK))
        assertTrue(f.userText.endsWith("ASAL SAWAL YAHAN HAI"))
        assertTrue(f.history.isEmpty())
        assertTrue(ContextBudget.estimateTokens(f.userText) <= ContextBudget.promptBudget(4096))
    }

    @Test
    fun biggerWindowKeepsMore() {
        val chat = longChat(22, 1500)
        val small = ContextBudget.fit(4096, "sys", chat, "ok").history.size
        val big = ContextBudget.fit(8192, "sys", chat, "ok").history.size
        assertTrue(big > small)
    }

    @Test
    fun overflowErrorIsRecognisedAndNumbersRead() {
        val o = ContextBudget.overflowOf(IllegalStateException(realError))
        assertNotNull(o)
        assertEquals(4123, o!!.actual)
        assertEquals(4096, o.limit)
    }

    @Test
    fun overflowIsFoundInsideAWrappedException() {
        val o = ContextBudget.overflowOf(RuntimeException("flow failed", IllegalStateException(realError)))
        assertNotNull(o)
        assertEquals(4123, o!!.actual)
    }

    @Test
    fun otherErrorsAreNotOverflow() {
        assertNull(ContextBudget.overflowOf(IllegalStateException("Gemma model load nahi hai.")))
        assertNull(ContextBudget.overflowOf(RuntimeException("Status Code: 13. Message: out of memory")))
    }

    @Test
    fun overflowWithoutNumbersIsStillOverflow() {
        val o = ContextBudget.overflowOf(IllegalStateException("Input token ids are too long."))
        assertNotNull(o)
        assertEquals(0, o!!.actual)
    }

    @Test
    fun retryScalesShrinkAndEndAtNoHistory() {
        val o = ContextBudget.Overflow(4123, 4096)
        val s1 = ContextBudget.nextScale(1.0, o, 4096, 0)
        assertTrue(s1 < 0.85 && s1 > 0.0)
        val s2 = ContextBudget.nextScale(s1, o, 4096, 1)
        assertEquals(s1 * 0.5, s2, 1e-9)
        assertEquals(0.0, ContextBudget.nextScale(s2, o, 4096, 2), 0.0)
        // no numbers in the error: a fixed 30% cut
        assertEquals(0.7 * 0.95, ContextBudget.nextScale(1.0, ContextBudget.Overflow(0, 0), 4096, 0), 1e-9)
    }

    @Test
    fun scaleZeroSendsNoHistory() {
        val f = ContextBudget.fit(4096, "sys", longChat(10, 400), "sawal", 0, 0.0)
        assertTrue(f.history.isEmpty())
        assertEquals("sawal", f.userText)
    }

    @Test
    fun replyThatFillsTheWindowIsFlagged() {
        val f = ContextBudget.fit(4096, "sys", longChat(22, 1500), "ok")
        assertTrue(ContextBudget.replyMayBeCut(4096, "sys", f, 0, "y".repeat(6000)))
        assertFalse(ContextBudget.replyMayBeCut(4096, "sys", f, 0, "Chhota jawab."))
    }
}
