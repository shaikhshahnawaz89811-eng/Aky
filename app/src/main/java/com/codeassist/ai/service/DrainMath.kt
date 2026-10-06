package com.codeassist.ai.service

/**
 * Pure arithmetic of the battery drain meter (audit PDF Sec 12.1 "Idle battery drain", Phase 2 exit
 * "battery budget met"). Pure Kotlin so it is covered by JVM unit tests.
 *
 * Only intervals that are clean are counted: screen off at both ends, no charger at both ends, nothing
 * that changes the picture in between (screen turned on, charger plugged in), and a sane length. The
 * battery's charge counter is read in micro-ampere-hours.
 */
object DrainMath {
    /** Longest interval accepted: a phone in deep sleep can go hours between two samples. */
    const val MAX_GAP_MS = 4L * 3600 * 1000

    class Sample(val uah: Long, val atMs: Long, val screenOff: Boolean, val charging: Boolean)

    class Delta(val ms: Long, val uah: Long)

    /** @return the clean drain between [prev] and [now], or null when the interval must be ignored. */
    fun step(prev: Sample?, now: Sample, dirty: Boolean): Delta? {
        if (prev == null || dirty) return null
        if (!prev.screenOff || !now.screenOff || prev.charging || now.charging) return null
        val dt = now.atMs - prev.atMs
        if (dt <= 0L || dt > MAX_GAP_MS) return null
        val used = prev.uah - now.uah
        if (used < 0L) return null // the counter went up: recalibration or a charger we missed
        return Delta(dt, used)
    }

    fun mahPerHour(uah: Long, ms: Long): Double =
        if (ms <= 0L) 0.0 else uah / 1000.0 / (ms / 3_600_000.0)
}
