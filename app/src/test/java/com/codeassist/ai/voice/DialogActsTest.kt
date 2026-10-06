package com.codeassist.ai.voice

import com.codeassist.ai.voice.DialogActs.Act
import org.junit.Assert.assertEquals
import org.junit.Test

class DialogActsTest {
    private fun ctx(
        pending: Boolean = false, resume: Boolean = false, repeat: Boolean = false, session: Boolean = true
    ) = DialogActs.Context(pending, resume, repeat, session)

    @Test fun nodInsideSessionIsIgnored() {
        assertEquals(Act.BACKCHANNEL, DialogActs.classify("hmm", ctx()))
        assertEquals(Act.BACKCHANNEL, DialogActs.classify("Haan!", ctx()))
        assertEquals(Act.BACKCHANNEL, DialogActs.classify("achha", ctx()))
    }

    @Test fun sameWordAfterTapIsACommand() {
        assertEquals(Act.COMMAND, DialogActs.classify("haan", ctx(session = false)))
    }

    @Test fun yesNoNeedAPendingQuestion() {
        assertEquals(Act.CONFIRM_YES, DialogActs.classify("haan", ctx(pending = true)))
        assertEquals(Act.CONFIRM_NO, DialogActs.classify("nahi", ctx(pending = true)))
        assertEquals(Act.COMMAND, DialogActs.classify("nahi", ctx()))
    }

    @Test fun stopAndGoodbyeEndTheSession() {
        assertEquals(Act.STOP, DialogActs.classify("bas karo", ctx()))
        assertEquals(Act.GOODBYE, DialogActs.classify("bye bye", ctx()))
    }

    @Test fun resumeAndRepeatOnlyWhenAvailable() {
        assertEquals(Act.RESUME, DialogActs.classify("aage batao", ctx(resume = true)))
        assertEquals(Act.COMMAND, DialogActs.classify("aage batao", ctx(resume = false)))
        assertEquals(Act.REPEAT, DialogActs.classify("dobara bolo", ctx(repeat = true)))
        assertEquals(Act.COMMAND, DialogActs.classify("dobara bolo", ctx(repeat = false)))
    }

    @Test fun longSentencesAreAlwaysCommands() {
        assertEquals(Act.COMMAND, DialogActs.classify("haan to kal ka alarm laga do", ctx(pending = true)))
    }
}
