package com.codeassist.ai.service

import com.codeassist.ai.service.DrainMath.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DrainMathTest {
    private val min = 60_000L
    private fun s(uah: Long, at: Long, off: Boolean = true, charging: Boolean = false) = Sample(uah, at, off, charging)

    @Test fun cleanIntervalCounts() {
        val d = DrainMath.step(s(3_000_000, 0), s(2_990_000, 30 * min), false)
        assertNotNull(d)
        assertEquals(30 * min, d!!.ms)
        assertEquals(10_000L, d.uah)
    }

    @Test fun firstSampleHasNoPrevious() = assertNull(DrainMath.step(null, s(3_000_000, 0), false))

    @Test fun dirtyIntervalIgnored() = assertNull(DrainMath.step(s(3_000_000, 0), s(2_990_000, min), true))

    @Test fun screenOnAtEitherEndIgnored() {
        assertNull(DrainMath.step(s(3_000_000, 0, off = false), s(2_990_000, min), false))
        assertNull(DrainMath.step(s(3_000_000, 0), s(2_990_000, min, off = false), false))
    }

    @Test fun chargingAtEitherEndIgnored() {
        assertNull(DrainMath.step(s(3_000_000, 0, charging = true), s(2_990_000, min), false))
        assertNull(DrainMath.step(s(3_000_000, 0), s(3_010_000, min, charging = true), false))
    }

    @Test fun counterGoingUpIgnored() = assertNull(DrainMath.step(s(3_000_000, 0), s(3_000_500, min), false))

    @Test fun zeroUsageIsAllowed() {
        val d = DrainMath.step(s(3_000_000, 0), s(3_000_000, min), false)
        assertEquals(0L, d!!.uah)
    }

    @Test fun badTimeOrTooLongIgnored() {
        assertNull(DrainMath.step(s(3_000_000, 5 * min), s(2_990_000, 5 * min), false))
        assertNull(DrainMath.step(s(3_000_000, 5 * min), s(2_990_000, min), false))
        assertNull(DrainMath.step(s(3_000_000, 0), s(2_900_000, DrainMath.MAX_GAP_MS + 1), false))
    }

    @Test fun mahPerHour() {
        assertEquals(20.0, DrainMath.mahPerHour(10_000L, 30 * min), 1e-9)
        assertEquals(0.0, DrainMath.mahPerHour(10_000L, 0L), 1e-9)
    }
}
