package com.codeassist.ai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sketch Studio stage 1: the SVG path reader ([SvgPath]) - supported commands, normalising, refusals and limits. */
class SvgPathTest {
    private fun ok(d: String): List<SvgPath.Seg> {
        val p = SvgPath.parse(d)
        assertTrue("should be accepted: $d" + (if (p is SvgPath.Parsed.Bad) " (" + p.reason + ")" else ""), p is SvgPath.Parsed.Ok)
        return (p as SvgPath.Parsed.Ok).segs
    }

    private fun bad(d: String): String {
        val p = SvgPath.parse(d)
        assertTrue("should be refused: $d", p is SvgPath.Parsed.Bad)
        return (p as SvgPath.Parsed.Bad).reason
    }

    private fun ops(segs: List<SvgPath.Seg>): String = segs.map { it.op }.joinToString("")

    @Test fun absoluteLinesAndClose() {
        val s = ok("M10 10 L50 10 L50 50 Z")
        assertEquals("MLLZ", ops(s))
        assertEquals(10f, s[0].v[0], 0.001f)
        assertEquals(50f, s[2].v[0], 0.001f)
        assertEquals(50f, s[2].v[1], 0.001f)
        assertEquals(0, s[3].v.size)
    }

    @Test fun relativeCommandsAndHorizontalVerticalBecomeAbsoluteLines() {
        val s = ok("m10 10 l40 0 v40 h-40 z")
        assertEquals("MLLLZ", ops(s))
        assertEquals(50f, s[1].v[0], 0.001f)
        assertEquals(10f, s[1].v[1], 0.001f)
        assertEquals(50f, s[2].v[0], 0.001f)
        assertEquals(50f, s[2].v[1], 0.001f)
        assertEquals(10f, s[3].v[0], 0.001f)
        assertEquals(50f, s[3].v[1], 0.001f)
    }

    @Test fun numbersAfterAMoveAreImplicitLines() {
        val s = ok("M0 0 10 10 20 0")
        assertEquals("MLL", ops(s))
        assertEquals(20f, s[2].v[0], 0.001f)
    }

    @Test fun smoothCubicReflectsTheLastControlPoint() {
        val s = ok("M10 50 C10 30 30 30 30 50 S50 70 50 50")
        assertEquals("MCC", ops(s))
        // reflection of (30,30) about the end point (30,50) is (30,70)
        assertEquals(30f, s[2].v[0], 0.001f)
        assertEquals(70f, s[2].v[1], 0.001f)
        assertEquals(50f, s[2].v[4], 0.001f)
        assertEquals(50f, s[2].v[5], 0.001f)
    }

    @Test fun smoothQuadraticReflectsTheLastControlPoint() {
        val s = ok("M10 50 Q30 10 50 50 T90 50")
        assertEquals("MQQ", ops(s))
        // reflection of (30,10) about (50,50) is (70,90)
        assertEquals(70f, s[2].v[0], 0.001f)
        assertEquals(90f, s[2].v[1], 0.001f)
        assertEquals(90f, s[2].v[2], 0.001f)
    }

    @Test fun compactNumbersWithoutSpacesAreRead() {
        val s = ok("M10,20L30-5")
        assertEquals(30f, s[1].v[0], 0.001f)
        // -5 is the edge of what is accepted and is clamped to the canvas
        assertEquals(0f, s[1].v[1], 0.001f)
    }

    @Test fun slightlyOutsideValuesAreClampedFarOutsideAreRefused() {
        assertEquals(100f, ok("M10 10 L103 10")[1].v[0], 0.001f)
        assertTrue(bad("M10 10 L500 10").contains("between 0 and 100"))
    }

    // Stage 3: arcs are read and become cubic curves (the old stage 1 test expected a refusal).
    @Test fun arcsBecomeCubicCurvesThatEndOnTheEndPoint() {
        val s = ok("M10 50 A20 20 0 0 1 50 50")
        assertEquals("MCC", ops(s))
        val last = s[s.size - 1].v
        assertEquals(50f, last[4], 0.001f)
        assertEquals(50f, last[5], 0.001f)
    }

    @Test fun aHalfCircleArcStaysOnTheCircle() {
        // centre (30,50), radius 20, sweep flag 1 = clockwise on screen: the arc goes over the top (y < 50)
        val s = ok("M10 50 A20 20 0 0 1 50 50")
        for (seg in s.drop(1)) {
            val ex = seg.v[4]
            val ey = seg.v[5]
            val dist = Math.hypot((ex - 30f).toDouble(), (ey - 50f).toDouble())
            assertEquals(20.0, dist, 0.05)
            assertTrue(ey <= 50.001f)
        }
        assertEquals(30f, s[1].v[4], 0.05f)
        assertEquals(30f, s[1].v[5], 0.05f)
    }

    @Test fun sweepFlagZeroGoesTheOtherWay() {
        val s = ok("M10 50 A20 20 0 0 0 50 50")
        assertEquals(30f, s[1].v[4], 0.05f)
        assertEquals(70f, s[1].v[5], 0.05f)
    }

    @Test fun relativeArcAndFlagsWithoutSeparators() {
        val a = ok("M10 50 a20 20 0 0 1 40 0")
        val b = ok("M10 50 a20 20 0 01 40 0")
        assertEquals(a.size, b.size)
        assertEquals(50f, a[a.size - 1].v[4], 0.001f)
    }

    @Test fun tooSmallRadiiAreScaledUpAndZeroRadiusIsALine() {
        val grown = ok("M10 50 A1 1 0 0 1 50 50")
        assertEquals("MCC", ops(grown))
        assertEquals("ML", ops(ok("M10 10 A0 5 0 0 1 40 40")))
    }

    @Test fun anArcToTheSamePointDrawsNothingAndABadFlagIsRefused() {
        assertEquals("ML", ops(ok("M10 10 A5 5 0 0 1 10 10 L30 30")))
        assertTrue(bad("M10 10 A5 5 0 2 1 20 20").contains("flag"))
        assertTrue(bad("M10 10 A5 5 0 0 1 200 20").contains("between 0 and 100"))
    }

    @Test fun otherBadPathsAreRefusedWithAReason() {
        assertTrue(bad("").contains("empty"))
        assertTrue(bad("L10 10 L20 20").contains("start with M"))
        assertTrue(bad("M10 10 X5 5").contains("unknown"))
        assertTrue(bad("M10 10 L5").contains("number"))
        assertTrue(bad("M10 10").contains("at least one line"))
        assertTrue(bad("M10 10 L20 20 Z 5 5").contains("after Z"))
    }

    @Test fun lengthAndSegmentLimits() {
        val long = "M10 10" + " L1 1".repeat(200)
        assertTrue(bad(long).contains("longer than " + SvgPath.MAX_LENGTH))
        val many = "M10 10" + " L1 1".repeat(85)
        assertTrue(bad(many).contains("more than " + SvgPath.MAX_SEGMENTS))
        // exactly the limit is fine: M + 79 lines = 80 segments
        assertEquals(80, ok("M1 1" + " L2 2".repeat(79)).size)
    }

    @Test fun toDWritesAbsoluteCommandsAndReadsBackTheSame() {
        val d = SvgPath.toD(ok("m10 10 l40 0 v40 z"))
        assertEquals("M10 10 L50 10 L50 50 Z", d)
        assertEquals(4, ok(d).size)
    }

    @Test fun fmtDropsTrailingZeros() {
        assertEquals("12", SvgPath.fmt(12f))
        assertEquals("12.5", SvgPath.fmt(12.5f))
        assertEquals("0", SvgPath.fmt(-0.001f))
    }
}
