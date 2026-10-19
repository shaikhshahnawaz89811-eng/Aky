package com.codeassist.ai.voice

/**
 * The few numbers and yes/no rules that decide when a voice conversation goes on and when it ends. They sit here, not
 * inside [VoiceController] (which needs a phone), so the JVM tests can pin them down.
 *
 * Why they exist (16 Oct 2026, "wake word + offline STT + Gemma, continuous talk, stopping in between"):
 *  - a continuous conversation used to end after 3 empty listening rounds: about 18 s with the offline recognizer (6 s per
 *    round), so a person who was thinking was cut off ([CONTINUOUS_IDLE_MS] is the replacement: 2 minutes of nobody speaking);
 *  - a conversation opened by the wake phrase was always a short "follow-up window" conversation, never a continuous one,
 *    even with mic mode "continuous" ([wakeSessionIsContinuous]);
 *  - when the barge-in detector stopped the assistant because of its own echo or a noise, the rest of the reply was thrown
 *    away ([mayResumeAfterFalseBarge], [BARGE_WINDOW_MS]).
 */
object ConversationPolicy {
    /** Continuous mode: how long the mic may stay open with nobody speaking before the conversation ends by itself. */
    const val CONTINUOUS_IDLE_MS = 120_000L

    /**
     * After the assistant was stopped by the barge-in detector: how long to wait for the first word. A real interruption
     * means the person is already talking, so a quiet window means echo or noise and the reply goes on.
     */
    const val BARGE_WINDOW_MS = 2_500L

    /** A reply is resumed this many times after a false barge-in; then barge-in stays off for the rest of that reply. */
    const val MAX_BARGE_RESUMES = 2

    /** True when the mic has been open [CONTINUOUS_IDLE_MS] or longer since [openedAtMs] (both from the same monotonic clock). */
    fun continuousIdleExpired(nowMs: Long, openedAtMs: Long): Boolean = nowMs - openedAtMs >= CONTINUOUS_IDLE_MS

    /** After an interruption with no speech: say the rest of the reply when there is a rest and the false alarms are not used up. */
    fun mayResumeAfterFalseBarge(resumesSoFar: Int, hasRest: Boolean): Boolean = hasRest && resumesSoFar < MAX_BARGE_RESUMES

    /** False once this reply has had [MAX_BARGE_RESUMES] false alarms: the detector is not started again for it. */
    fun bargeWatchAllowed(resumesSoFar: Int): Boolean = resumesSoFar < MAX_BARGE_RESUMES

    /** The conversation that the wake phrase opens is continuous exactly when the mic mode setting is "continuous". */
    fun wakeSessionIsContinuous(micMode: String): Boolean = micMode == "continuous"
}
