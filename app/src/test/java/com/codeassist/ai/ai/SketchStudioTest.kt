package com.codeassist.ai.ai

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sketch Studio stage 1: the extended "shapes" format (path / poly / ellipse, colours, background, "v":2), old documents,
 * limits, edit prompts, edit intent and the SVG export. SketchTest (the original tests) is untouched.
 */
class SketchStudioTest {
    private fun ok(raw: String): Sketch.Parsed.Ok {
        val p = Sketch.parse(raw)
        assertTrue(raw + (if (p is Sketch.Parsed.Bad) " -> " + p.reason else ""), p is Sketch.Parsed.Ok)
        return p as Sketch.Parsed.Ok
    }

    private fun bad(raw: String): String {
        val p = Sketch.parse(raw)
        assertTrue("should be refused: $raw", p is Sketch.Parsed.Bad)
        return (p as Sketch.Parsed.Bad).reason
    }

    private fun doc(vararg shapes: String, extra: String = "\"v\":2,"): String =
        "{\"kind\":\"shapes\"," + extra + "\"title\":\"T\",\"shapes\":[" + shapes.joinToString(",") + "]}"

    private val kite = "{\"t\":\"path\",\"d\":\"M50 10 L80 50 L50 90 L20 50 Z\",\"fill\":\"#FFcc00\",\"stroke\":\"#333\",\"sw\":0.6,\"op\":0.5}"

    // ---------- old documents keep working ----------

    @Test fun aDocumentSavedBeforeTheUpgradeStillReads() {
        val legacy = "{\"kind\":\"shapes\",\"title\":\"Login\",\"shapes\":[" +
            "{\"t\":\"rect\",\"x\":5,\"y\":16,\"w\":28,\"h\":14,\"l\":\"Start\"}," +
            "{\"t\":\"line\",\"x\":33,\"y\":23,\"x2\":43,\"y2\":23,\"arrow\":true}]}"
        val d = Sketch.fromJson(legacy)
        assertNotNull(d)
        assertEquals(2, d!!.shapes.size)
        assertNull(d.shapes[0].style)
        assertNull(d.background)
        assertTrue(d.shapes[1].arrow)
    }

    @Test fun withoutVersionTheOldFortyShapeLimitStaysAndTheReasonNamesTheFix() {
        val one = "{\"t\":\"circle\",\"x\":50,\"y\":50,\"r\":5}"
        val r = bad(doc(*Array(41) { one }, extra = ""))
        assertTrue(r, r.contains("40") && r.contains("\"v\":2"))
    }

    @Test fun versionTwoAllowsUpTo120Shapes() {
        val one = "{\"t\":\"circle\",\"x\":50,\"y\":50,\"r\":5}"
        assertEquals(41, ok(doc(*Array(41) { one })).drawing.shapes.size)
        assertEquals(120, ok(doc(*Array(120) { one })).drawing.shapes.size)
        assertTrue(bad(doc(*Array(121) { one })).contains("120"))
    }

    @Test fun savedJsonCarriesTheVersion() {
        val d = ok("{\"kind\":\"flowchart\",\"title\":\"x\",\"items\":[\"a\",\"b\"]}").drawing
        assertTrue(Sketch.toJson(d).contains("\"v\":2"))
    }

    // ---------- new shapes ----------

    @Test fun aPathShapeIsReadWithItsStyle() {
        val s = ok(doc(kite)).drawing.shapes[0]
        assertEquals(Sketch.Kind.PATH, s.kind)
        assertEquals(5, s.path!!.size)
        assertEquals(0xFFFFCC00.toInt(), s.style!!.fill)
        assertEquals(0xFF333333.toInt(), s.style!!.stroke)
        assertEquals(0.6f, s.style!!.strokeWidth!!, 0.001f)
        assertEquals(0.5f, s.style!!.opacity!!, 0.001f)
    }

    @Test fun aPolygonCanBeWrittenAsPairsFlatNumbersOrText() {
        for (pts in listOf("[[10,10],[50,10],[30,40]]", "[10,10,50,10,30,40]", "\"10,10 50,10 30,40\"")) {
            val s = ok(doc("{\"t\":\"poly\",\"pts\":$pts}")).drawing.shapes[0]
            assertEquals(Sketch.Kind.POLY, s.kind)
            assertEquals(6, s.points!!.size)
            assertTrue(s.closed)
            assertEquals(30f, s.points!![4], 0.001f)
        }
    }

    @Test fun anOpenPolylineNeedsOnlyTwoPointsButAClosedPolyNeedsThree() {
        val line = ok(doc("{\"t\":\"poly\",\"pts\":[[10,10],[50,50]],\"closed\":false}")).drawing.shapes[0]
        assertFalse(line.closed)
        assertTrue(bad(doc("{\"t\":\"poly\",\"pts\":[[10,10],[50,50]]}")).contains("3 to"))
        assertTrue(bad(doc("{\"t\":\"poly\",\"pts\":[[10,10,5],[50,50]]}")).contains("[x,y]"))
        assertTrue(bad(doc("{\"t\":\"poly\",\"pts\":[10,10,50]}")).contains("pairs"))
        assertTrue(bad(doc("{\"t\":\"poly\",\"pts\":[[10,10],[500,50],[5,5]]}")).contains("0 and 100"))
    }

    @Test fun anEllipseNeedsBothRadii() {
        val s = ok(doc("{\"t\":\"ellipse\",\"x\":50,\"y\":50,\"rx\":30,\"ry\":10}")).drawing.shapes[0]
        assertEquals(Sketch.Kind.ELLIPSE, s.kind)
        assertEquals(30f, s.c, 0.001f)
        assertEquals(10f, s.d, 0.001f)
        assertTrue(bad(doc("{\"t\":\"ellipse\",\"x\":50,\"y\":50,\"rx\":30}")).contains("ry"))
        assertTrue(bad(doc("{\"t\":\"ellipse\",\"x\":50,\"y\":50,\"rx\":30,\"ry\":80}")).contains("1 and 50"))
    }

    @Test fun aBadPathIsRefusedWithThePathReason() {
        val r = bad(doc("{\"t\":\"path\",\"d\":\"M10 10 A5 5 0 0 1 20 20\"}"))
        assertTrue(r, r.startsWith("path:") && r.contains("arc"))
        assertTrue(bad(doc("{\"t\":\"path\"}")).contains("'d'"))
    }

    // ---------- colours, styles, background ----------

    @Test fun coloursCanBeShortLongNamedOrNone() {
        assertEquals(0xFFFF0000.toInt(), Sketch.parseColor("#f00"))
        assertEquals(0xFF112233.toInt(), Sketch.parseColor("#112233"))
        assertEquals(0, Sketch.parseColor("none"))
        assertEquals(0, Sketch.parseColor("transparent"))
        assertNotNull(Sketch.parseColor("Blue"))
        assertNull(Sketch.parseColor("banana"))
        assertNull(Sketch.parseColor("#12"))
        assertNull(Sketch.parseColor("#gggggg"))
    }

    @Test fun badStyleValuesAreRefusedWithTheirName() {
        assertTrue(bad(doc("{\"t\":\"circle\",\"x\":5,\"y\":5,\"r\":4,\"fill\":\"banana\"}")).contains("banana"))
        assertTrue(bad(doc("{\"t\":\"circle\",\"x\":5,\"y\":5,\"r\":4,\"stroke\":\"#12\"}")).contains("stroke"))
        assertTrue(bad(doc("{\"t\":\"circle\",\"x\":5,\"y\":5,\"r\":4,\"sw\":50}")).contains("sw"))
        assertTrue(bad(doc("{\"t\":\"circle\",\"x\":5,\"y\":5,\"r\":4,\"sw\":\"thick\"}")).contains("sw"))
        assertTrue(bad(doc("{\"t\":\"circle\",\"x\":5,\"y\":5,\"r\":4,\"op\":2}")).contains("op"))
    }

    @Test fun theBackgroundIsReadAndABadOneRefused() {
        assertEquals(0xFF000000.toInt(), ok(doc("{\"t\":\"circle\",\"x\":5,\"y\":5,\"r\":4}", extra = "\"v\":2,\"bg\":\"black\",")).drawing.background)
        assertTrue(bad(doc("{\"t\":\"circle\",\"x\":5,\"y\":5,\"r\":4}", extra = "\"v\":2,\"bg\":\"nope\",")).contains("bg"))
    }

    // ---------- limits ----------

    @Test fun tooManyPathPointsInOneDrawingAreRefused() {
        val d = "M1 1" + " L2 2".repeat(79) // 80 segments
        val p = "{\"t\":\"path\",\"d\":\"$d\"}"
        assertEquals(800, ok(doc(*Array(10) { p })).drawing.shapes.sumOf { it.path!!.size })
        assertTrue(bad(doc(*Array(11) { p })).contains("too complex"))
    }

    @Test fun anAnswerLongerThanTheLimitIsRefused() {
        val raw = "{\"kind\":\"shapes\",\"v\":2,\"title\":\"" + "a".repeat(Sketch.MAX_JSON + 10) + "\",\"shapes\":[{\"t\":\"circle\",\"x\":5,\"y\":5,\"r\":4}]}"
        assertTrue(bad(raw).contains("too long"))
    }

    // ---------- save and read back ----------

    @Test fun everyNewElementSurvivesSaveAndReopen() {
        val raw = doc(
            kite,
            "{\"t\":\"poly\",\"pts\":[[10,10],[40,10],[25,30]],\"fill\":\"none\"}",
            "{\"t\":\"poly\",\"pts\":[[10,60],[40,70]],\"closed\":false,\"stroke\":\"red\"}",
            "{\"t\":\"ellipse\",\"x\":50,\"y\":50,\"rx\":20,\"ry\":8,\"l\":\"disc\"}",
            "{\"t\":\"text\",\"x\":50,\"y\":95,\"l\":\"Kite\",\"fill\":\"#FFFFFF\"}",
            extra = "\"v\":2,\"bg\":\"#102030\","
        )
        val first = ok(raw).drawing
        val back = Sketch.fromJson(Sketch.toJson(first))
        assertNotNull(back)
        assertEquals(first.shapes.size, back!!.shapes.size)
        assertEquals(0xFF102030.toInt(), back.background)
        assertEquals(SvgPath.toD(first.shapes[0].path!!), SvgPath.toD(back.shapes[0].path!!))
        assertEquals(0, back.shapes[1].style!!.fill)
        assertFalse(back.shapes[2].closed)
        assertEquals(0xFFE53935.toInt(), back.shapes[2].style!!.stroke)
        assertEquals("disc", back.shapes[3].label)
        assertEquals(0xFFFFFFFF.toInt(), back.shapes[4].style!!.fill)
        // a second round trip changes nothing
        assertEquals(Sketch.toJson(back), Sketch.toJson(Sketch.fromJson(Sketch.toJson(back))!!))
    }

    // ---------- SVG export ----------

    private fun assertWellFormed(svg: String) {
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val d = builder.parse(ByteArrayInputStream(svg.toByteArray(Charsets.UTF_8)))
        assertEquals("svg", d.documentElement.nodeName)
    }

    @Test fun svgExportIsWellFormedAndHasEveryShapeKind() {
        val raw = doc(
            kite,
            "{\"t\":\"poly\",\"pts\":[[10,10],[40,10],[25,30]]}",
            "{\"t\":\"poly\",\"pts\":[[10,60],[40,70]],\"closed\":false}",
            "{\"t\":\"ellipse\",\"x\":50,\"y\":50,\"rx\":20,\"ry\":8}",
            "{\"t\":\"circle\",\"x\":80,\"y\":20,\"r\":6}",
            "{\"t\":\"rect\",\"x\":5,\"y\":5,\"w\":20,\"h\":10,\"l\":\"A&B <x>\"}",
            "{\"t\":\"tri\",\"x\":60,\"y\":60,\"w\":20,\"h\":20}",
            "{\"t\":\"line\",\"x\":10,\"y\":90,\"x2\":90,\"y2\":90,\"arrow\":true}",
            "{\"t\":\"text\",\"x\":50,\"y\":96,\"l\":\"Kite\"}"
        )
        val svg = Sketch.toSvg(ok(raw).drawing)
        assertWellFormed(svg)
        assertTrue(svg.contains("viewBox=\"0 0 100 100\""))
        assertTrue(svg.contains("<path d=\"M50 10 L80 50 L50 90 L20 50 Z\""))
        assertTrue(svg.contains("fill=\"#FFCC00\""))
        assertTrue(svg.contains("stroke=\"#333333\""))
        assertTrue(svg.contains("<polygon points=\"10,10 40,10 25,30\""))
        assertTrue(svg.contains("<polyline points=\"10,60 40,70\""))
        assertTrue(svg.contains("<ellipse"))
        assertTrue(svg.contains("<circle"))
        assertTrue(svg.contains("A&amp;B &lt;x&gt;"))
        assertTrue(svg.contains("<text"))
    }

    @Test fun svgExportEscapesTheTitleAndUsesTheBackground() {
        val d = ok("{\"kind\":\"shapes\",\"v\":2,\"title\":\"A & \\\"B\\\"\",\"bg\":\"#102030\",\"shapes\":[{\"t\":\"circle\",\"x\":50,\"y\":50,\"r\":10}]}").drawing
        val svg = Sketch.toSvg(d)
        assertWellFormed(svg)
        assertTrue(svg.contains("<title>A &amp; &quot;B&quot;</title>"))
        assertTrue(svg.contains("fill=\"#102030\""))
    }

    @Test fun theTemplatesExportToValidSvgToo() {
        assertWellFormed(Sketch.toSvg(Sketch.flowchart("Login", listOf("a", "b", "c", "d"))))
        assertWellFormed(Sketch.toSvg(Sketch.wireframe("Chat", listOf("header:Chat", "list", "input:Type", "button:Send"))))
    }

    // ---------- editing a drawing that exists ----------

    @Test fun theEditPromptCarriesTheCurrentJsonTheRequestAndTheKeepRule() {
        val json = Sketch.toJson(ok(doc(kite)).drawing)
        val ask = Sketch.revisePrompt(json, "background dark kar do")
        assertTrue(ask.contains(json) && ask.contains("background dark kar do"))
        val system = Sketch.reviseSystemPrompt(LocalMemory.LANG_HINGLISH)
        assertTrue(system.contains("EDITING") && system.contains("exactly as it is"))
        // the editing prompt still holds the normal one (formats + detail help)
        assertTrue(system.contains("flowchart") && system.contains(Sketch.DETAIL_HELP))
        assertTrue(Sketch.systemPrompt(LocalMemory.LANG_ENGLISH).contains("ellipse"))
    }

    @Test fun aSmallDrawingFitsTheEditLimitAndABigOneDoesNot() {
        val small = Sketch.toJson(ok(doc(kite)).drawing)
        assertTrue(small.length < Sketch.MAX_REVISE_JSON)
        val one = "{\"t\":\"circle\",\"x\":50,\"y\":50,\"r\":5,\"fill\":\"#112233\"}"
        val big = Sketch.toJson(ok(doc(*Array(70) { one })).drawing)
        assertTrue(big.length > Sketch.MAX_REVISE_JSON)
    }

    @Test fun aShortChangeRightAfterADrawingIsAnEdit() {
        for (s in listOf("background dark kar do", "darwaza bada karo", "colour red karo", "title badlo", "make the roof smaller")) {
            assertTrue(s, SketchIntent.isRevision(s, true))
        }
    }

    @Test fun otherMessagesAreNotEdits() {
        // no drawing before it
        assertFalse(SketchIntent.isRevision("background dark kar do", false))
        // a question, a new drawing order, other talk, too long, code
        assertFalse(SketchIntent.isRevision("kya badlo?", true))
        assertFalse(SketchIntent.isRevision("ek sketch banao: ghar", true))
        assertFalse(SketchIntent.isRevision("kaise ho", true))
        assertFalse(SketchIntent.isRevision("badlo ".repeat(40), true))
        assertFalse(SketchIntent.isRevision("badlo ```code```", true))
        assertFalse(SketchIntent.isRevision("", true))
    }
}
