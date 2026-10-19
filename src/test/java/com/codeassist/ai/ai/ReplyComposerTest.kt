package com.codeassist.ai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyComposerTest {
    private fun line(t: String, ok: Boolean = true, readOnly: Boolean = false) = ReplyComposer.Line(t, ok, readOnly)

    @Test fun readOnlyFirstThenActionsThenErrors() {
        val r = ReplyComposer.results(
            "Theek hai.",
            listOf(
                line("Alarm set."),
                line("Torch nahi mili.", ok = false),
                line("Abhi 10:00 AM.", readOnly = true),
                line("Battery 80%.", readOnly = true)
            )
        )
        assertEquals("Abhi 10:00 AM. Battery 80%. Alarm set. Torch nahi mili.", r)
    }

    @Test fun ackIsUsedOnlyWhenNothingElseWasProduced() {
        assertEquals("Theek hai.", ReplyComposer.results("  Theek hai. ", emptyList()))
    }

    @Test fun pendingQuestionGoesLastWithTheTapHint() {
        val t = ReplyComposer.withPending("Alarm set.", "Dialer mein 98765 khol dun?")
        assertTrue(t.startsWith("Alarm set. Dialer mein 98765 khol dun?"))
        assertTrue(t.endsWith(ReplyComposer.CONFIRM_HINT))
        assertTrue("a spoken reply must not end in a question mark", !t.trimEnd().endsWith("?"))
    }

    @Test fun noPendingLeavesTheTextAlone() {
        assertEquals("Alarm set.", ReplyComposer.withPending("Alarm set.", null))
    }

    @Test fun pendingAloneHasNoLeadingSpace() {
        assertEquals("Khol dun? " + ReplyComposer.CONFIRM_HINT, ReplyComposer.withPending("", "Khol dun?"))
    }
}
