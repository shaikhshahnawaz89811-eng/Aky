package com.codeassist.ai.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules that keep a voice conversation going (see [ConversationPolicy]); the loop that uses them needs a phone. */
class ConversationPolicyTest {

    @Test fun aContinuousConversationOutlivesThreeSilentOfflineRounds() {
        // the old rule: 3 empty rounds of 6 s (offline recognizer) = 18 s. That must not end the conversation any more.
        assertFalse(ConversationPolicy.continuousIdleExpired(nowMs = 18_000L, openedAtMs = 0L))
        assertFalse(ConversationPolicy.continuousIdleExpired(nowMs = 60_000L, openedAtMs = 0L))
    }

    @Test fun aContinuousConversationEndsAfterTwoQuietMinutes() {
        assertFalse(ConversationPolicy.continuousIdleExpired(nowMs = 119_999L, openedAtMs = 0L))
        assertTrue(ConversationPolicy.continuousIdleExpired(nowMs = 120_000L, openedAtMs = 0L))
        assertTrue(ConversationPolicy.continuousIdleExpired(nowMs = 500_000L, openedAtMs = 100_000L))
    }

    @Test fun theIdleClockIsRelativeToWhenTheMicOpened() {
        assertFalse(ConversationPolicy.continuousIdleExpired(nowMs = 1_000_000L + 60_000L, openedAtMs = 1_000_000L))
    }

    @Test fun aFalseBargeInResumesTheReplyTwiceAtMost() {
        assertTrue(ConversationPolicy.mayResumeAfterFalseBarge(resumesSoFar = 0, hasRest = true))
        assertTrue(ConversationPolicy.mayResumeAfterFalseBarge(resumesSoFar = 1, hasRest = true))
        assertFalse(ConversationPolicy.mayResumeAfterFalseBarge(resumesSoFar = 2, hasRest = true))
    }

    @Test fun nothingToResumeMeansNoResume() {
        assertFalse(ConversationPolicy.mayResumeAfterFalseBarge(resumesSoFar = 0, hasRest = false))
    }

    @Test fun afterTwoFalseAlarmsTheBargeDetectorStaysOffForThatReply() {
        assertTrue(ConversationPolicy.bargeWatchAllowed(0))
        assertTrue(ConversationPolicy.bargeWatchAllowed(1))
        assertFalse(ConversationPolicy.bargeWatchAllowed(2))
        assertFalse(ConversationPolicy.bargeWatchAllowed(3))
    }

    @Test fun theWakeSessionIsContinuousOnlyInContinuousMicMode() {
        assertTrue(ConversationPolicy.wakeSessionIsContinuous("continuous"))
        assertFalse(ConversationPolicy.wakeSessionIsContinuous("tap"))
        assertFalse(ConversationPolicy.wakeSessionIsContinuous(""))
        assertFalse(ConversationPolicy.wakeSessionIsContinuous("Continuous")) // the stored value is lower case
    }

    @Test fun theBargeWindowIsShortButLongerThanAStartUp() {
        // a real interruption has the person talking already; this only has to cover the recognizer start-up
        assertTrue(ConversationPolicy.BARGE_WINDOW_MS in 1_500L..4_000L)
        assertEquals(2, ConversationPolicy.MAX_BARGE_RESUMES)
    }
}
