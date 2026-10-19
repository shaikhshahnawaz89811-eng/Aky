package com.codeassist.ai.voice

/**
 * Decides whether a burst of speech heard while the assistant is talking is a real interruption.
 * Audit PDF Sec 9.1 / 9.4: a short, quiet "hmm / haan / achha" (< ~600 ms) is a backchannel and must NOT
 * stop the assistant; a deliberate interruption is either sustained or clearly louder than the echo.
 *
 * Feed one frame at a time. Pure Kotlin (JVM unit tested).
 */
class BargeInGate(
    private val frameMs: Int = 20,
    /** A loud burst this long is enough ("ruko!"). */
    private val strongMinMs: Int = 300,
    /** A quiet burst has to last this long to count; shorter ones are backchannels. */
    private val quietMinMs: Int = 600,
    /** ratio (vs noise floor) at which a burst counts as loud / deliberate */
    private val strongRatio: Float = 5f,
    private val hangoverMs: Int = 240
) {
    enum class Event { NONE, CANDIDATE, VALID, REJECTED }

    private var speechMs = 0
    private var silenceMs = 0
    private var peak = 0f
    private var candidateSent = false

    fun reset() {
        speechMs = 0
        silenceMs = 0
        peak = 0f
        candidateSent = false
    }

    fun feed(speech: Boolean, ratio: Float): Event {
        if (speech) {
            silenceMs = 0
            speechMs += frameMs
            if (ratio > peak) peak = ratio
            val need = if (peak >= strongRatio) strongMinMs else quietMinMs
            if (speechMs >= need) {
                reset()
                return Event.VALID
            }
            if (!candidateSent && speechMs >= 120) {
                candidateSent = true
                return Event.CANDIDATE
            }
            return Event.NONE
        }
        if (speechMs == 0) return Event.NONE
        silenceMs += frameMs
        if (silenceMs > hangoverMs) {
            val wasCandidate = candidateSent
            reset()
            return if (wasCandidate) Event.REJECTED else Event.NONE
        }
        return Event.NONE
    }
}
