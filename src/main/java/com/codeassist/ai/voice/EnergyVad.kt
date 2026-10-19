package com.codeassist.ai.voice

import kotlin.math.sqrt

/**
 * Small adaptive energy VAD (audit PDF Sec 9.1 "VAD", first version). It is NOT a neural VAD: it only
 * has to tell "someone started talking over the assistant" apart from the echo / room noise level it has
 * been tracking. Feed one RMS value per 20 ms frame. Pure Kotlin (JVM unit tested).
 *
 * The noise floor follows the signal slowly while it is quiet and barely moves while speech is on, so a
 * constant echo of the assistant's own voice becomes the "floor" and a person talking rises above it.
 */
class EnergyVad(var ratio: Float = 2.5f) {
    companion object {
        const val ABS_MIN_RMS = 220f // about -43 dBFS; below this nothing counts as speech
        const val MIN_FLOOR = 18f

        fun rms(buf: ShortArray, n: Int): Float {
            if (n <= 0) return 0f
            var sum = 0.0
            for (i in 0 until n) {
                val s = buf[i].toDouble()
                sum += s * s
            }
            return sqrt(sum / n).toFloat()
        }
    }

    var noiseFloor: Float = -1f
        private set

    /** rms / noise floor of the last frame (1.0 = same as the floor). */
    var lastRatio: Float = 0f
        private set

    /** Returns true when this frame counts as speech. */
    fun process(rms: Float): Boolean {
        if (noiseFloor < 0f) noiseFloor = rms.coerceAtLeast(MIN_FLOOR)
        val threshold = maxOf(noiseFloor * ratio, ABS_MIN_RMS)
        val speech = rms > threshold
        lastRatio = rms / noiseFloor.coerceAtLeast(MIN_FLOOR)
        val rate = if (speech) 0.002f else 0.05f
        noiseFloor = (noiseFloor + (rms - noiseFloor) * rate).coerceAtLeast(MIN_FLOOR)
        return speech
    }

    /** Keeps learning the floor without ever reporting speech (used for the first moments of playback). */
    fun warm(rms: Float) {
        if (noiseFloor < 0f) noiseFloor = rms.coerceAtLeast(MIN_FLOOR)
        noiseFloor = (noiseFloor + (rms - noiseFloor) * 0.2f).coerceAtLeast(MIN_FLOOR)
    }
}
