package com.codeassist.ai.ai

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sketch Studio stage 3 (14 Oct 2026): gradients, transforms, groups / layers, hide, the SVG export of all of them, the layer
 * operations, the "3 option" helpers, the design kinds and the pure half of the web sandbox ([HtmlSandbox]).
 * (Arcs are tested in SvgPathTest.) The studio screen, the WebView host and the renderer need a device.
 */
class SketchStage3Test {
    private fun doc(vararg shapes: String, extra: String = ""): String =
        "{\"kind\":\"shapes\",\"v\":2,\"title\":\"T\"" + extra + ",\"shapes\":[" + shapes.joinToString(",") + "]}"

    private fun ok(raw: String): Sketch.Drawing {
        val p = Sketch.parse(raw)
        assertTrue("should be accepted: " + (if (p is Sketch.Parsed.Bad) p.reason else ""), p is Sketch.Parsed.Ok)
        return (p as Sketch.Parsed.Ok).drawing
    }

    private fun bad(raw: String): String {
        val p = Sketch.parse(raw)
        assertTrue("should be refused", p is Sketch.Parsed.Bad)
        return (p as Sketch.Parsed.Bad).reason
    }

    private val rect = "{\"t\":\"rect\",\"x\":10,\"y\":20,\"w\":30,\"h\":40,\"l\":\"wall\"}"
    private val circle = "{\"t\":\"circle\",\"x\":50,\"y\":50,\"r\":10}"
    private val sky = "{\"t\":\"group\",\"l\":\"sky\",\"shapes\":[{\"t\":\"circle\",\"x\":50,\"y\":20,\"r\":8},{\"t\":\"rect\",\"x\":0,\"y\":0,\"w\":100,\"h\":30}]}"

    private fun wellFormed(svg: String) {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        f.newDocumentBuilder().parse(ByteArrayInputStream(svg.toByteArray(Charsets.UTF_8)))
    }

    // ---------- gradients ----------

    @Test fun gradientTextIsReadInAllItsForms() {
        val a = Sketch.parseGradient("#ff0000 #0000ff")!!
        assertFalse(a.radial)
        assertEquals(90f, a.angle, 0.001f)
        assertEquals(0xFFFF0000.toInt(), a.from)
        assertEquals(0xFF0000FF.toInt(), a.to)
        assertEquals(45f, Sketch.parseGradient("#f00,#00f 45")!!.angle, 0.001f)
        assertEquals(-30f, Sketch.parseGradient("#f00 #00f -30")!!.angle, 0.001f)
        assertEquals(0xFFE53935.toInt(), Sketch.parseGradient("red>blue")!!.from)
        assertTrue(Sketch.parseGradient("#f00 #00f radial")!!.radial)
    }

    @Test fun badGradientsAreRefused() {
        for (t in listOf("", "#f00", "#f00 none", "#f00 #00f 400", "#f00 #00f abc", "#f00 #00f 10 20", "nonsense #00f")) {
            assertNull("should be refused: '$t'", Sketch.parseGradient(t))
        }
    }

    @Test fun gradientLineSpansTheBoxInTheAngleDirection() {
        val box = floatArrayOf(0f, 0f, 100f, 100f)
        val h = Sketch.Gradient(false, 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0f).line(box)
        assertEquals(0f, h[0], 0.01f)
        assertEquals(50f, h[1], 0.01f)
        assertEquals(100f, h[2], 0.01f)
        assertEquals(50f, h[3], 0.01f)
        val v = Sketch.Gradient(false, 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 90f).line(box)
        assertEquals(50f, v[0], 0.01f)
        assertEquals(0f, v[1], 0.01f)
        assertEquals(50f, v[2], 0.01f)
        assertEquals(100f, v[3], 0.01f)
        val diag = Sketch.Gradient(false, 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 45f).line(box)
        assertEquals(0f, diag[0], 0.01f)
        assertEquals(0f, diag[1], 0.01f)
        assertEquals(100f, diag[2], 0.01f)
        assertEquals(100f, diag[3], 0.01f)
        val wide = Sketch.Gradient(false, 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0f).line(floatArrayOf(10f, 20f, 50f, 30f))
        assertEquals(10f, wide[0], 0.01f)
        assertEquals(25f, wide[1], 0.01f)
        assertEquals(50f, wide[2], 0.01f)
    }

    @Test fun radialRadiusIsHalfTheLongerSide() {
        val g = Sketch.Gradient(true, 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0f)
        assertEquals(20f, g.radius(floatArrayOf(0f, 0f, 40f, 20f)), 0.001f)
        assertTrue(g.radius(floatArrayOf(5f, 5f, 5f, 5f)) >= 0.1f)
    }

    @Test fun aGradientFillSurvivesAStoreAndReadRoundTrip() {
        val d = ok(doc("{\"t\":\"rect\",\"x\":10,\"y\":10,\"w\":30,\"h\":20,\"grad\":\"#f00 #00f 45\"}"))
        val g = d.shapes[0].style!!.grad!!
        assertEquals(45f, g.angle, 0.001f)
        val json = Sketch.toJson(d)
        assertTrue(json, json.contains("\"grad\":\"#FF0000 #0000FF 45\""))
        assertEquals(json, Sketch.toJson(Sketch.fromJson(json)!!))
        val r = ok(doc("{\"t\":\"circle\",\"x\":50,\"y\":50,\"r\":20,\"grad\":\"#fff #000 radial\"}"))
        assertTrue(Sketch.toJson(r).contains("\"grad\":\"#FFFFFF #000000 radial\""))
    }

    @Test fun aBadGradientIsRefusedWithAReason() {
        assertTrue(bad(doc("{\"t\":\"rect\",\"x\":10,\"y\":10,\"w\":30,\"h\":20,\"grad\":\"#f00\"}")).contains("gradient"))
    }

    @Test fun backgroundGradient() {
        val d = ok(doc(circle, extra = ",\"bggrad\":\"#102030 #304050 0\""))
        assertEquals(0f, d.bgGrad!!.angle, 0.001f)
        assertTrue(Sketch.toJson(d).contains("\"bggrad\":\"#102030 #304050 0\""))
        assertTrue(bad(doc(circle, extra = ",\"bggrad\":\"nope\"")).contains("bggrad"))
    }

    // ---------- transforms and hide ----------

    @Test fun transformKeysAreRead() {
        val d = ok(doc("{\"t\":\"rect\",\"x\":10,\"y\":10,\"w\":20,\"h\":20,\"dx\":5,\"dy\":-3,\"rot\":30,\"sc\":1.5}"))
        val xf = d.shapes[0].xform!!
        assertEquals(5f, xf.dx, 0.001f)
        assertEquals(-3f, xf.dy, 0.001f)
        assertEquals(30f, xf.rot, 0.001f)
        assertEquals(1.5f, xf.scale, 0.001f)
        val again = Sketch.fromJson(Sketch.toJson(d))!!.shapes[0].xform!!
        assertEquals(30f, again.rot, 0.001f)
        assertEquals(1.5f, again.scale, 0.001f)
    }

    @Test fun aShapeWithoutOrWithIdentityTransformHasNone() {
        assertNull(ok(doc(rect)).shapes[0].xform)
        assertNull(ok(doc("{\"t\":\"rect\",\"x\":10,\"y\":10,\"w\":20,\"h\":20,\"dx\":0,\"sc\":1}")).shapes[0].xform)
    }

    @Test fun badTransformsAreRefused() {
        fun withKey(k: String) = doc("{\"t\":\"rect\",\"x\":10,\"y\":10,\"w\":20,\"h\":20," + k + "}")
        assertTrue(bad(withKey("\"rot\":400")).contains("rot"))
        assertTrue(bad(withKey("\"sc\":0")).contains("sc"))
        assertTrue(bad(withKey("\"sc\":9")).contains("sc"))
        assertTrue(bad(withKey("\"dx\":200")).contains("dx"))
        assertTrue(bad(withKey("\"dy\":\"x\"")).contains("dy"))
    }

    @Test fun hideIsKeptInTheFile() {
        val d = ok(doc("{\"t\":\"rect\",\"x\":10,\"y\":10,\"w\":20,\"h\":20,\"hide\":true}", circle))
        assertTrue(d.shapes[0].hidden)
        assertFalse(d.shapes[1].hidden)
        val json = Sketch.toJson(d)
        assertTrue(json.contains("\"hide\":true"))
        assertTrue(Sketch.fromJson(json)!!.shapes[0].hidden)
    }

    // ---------- groups ----------

    @Test fun aGroupHoldsShapesAndTakesTransformAndOpacity() {
        val d = ok(doc("{\"t\":\"group\",\"l\":\"sky\",\"rot\":10,\"op\":0.5,\"shapes\":[{\"t\":\"circle\",\"x\":50,\"y\":20,\"r\":8},{\"t\":\"rect\",\"x\":0,\"y\":0,\"w\":100,\"h\":30}]}", "{\"t\":\"text\",\"x\":50,\"y\":90,\"l\":\"hi\"}"))
        assertEquals(2, d.shapes.size)
        val g = d.shapes[0]
        assertEquals(Sketch.Kind.GROUP, g.kind)
        assertEquals("sky", g.label)
        assertEquals(2, g.children!!.size)
        assertEquals(10f, g.xform!!.rot, 0.001f)
        assertEquals(0.5f, g.style!!.opacity!!, 0.001f)
        assertEquals(4, Sketch.shapeCount(d))
    }

    @Test fun aGroupRoundTripChangesNothing() {
        val d = ok(doc(sky, rect))
        val json = Sketch.toJson(d)
        assertTrue(json.contains("\"t\":\"group\""))
        assertEquals(json, Sketch.toJson(Sketch.fromJson(json)!!))
        assertEquals(2, Sketch.fromJson(json)!!.shapes[0].children!!.size)
    }

    @Test fun badGroupsAreRefused() {
        assertTrue(bad(doc("{\"t\":\"group\",\"shapes\":[{\"t\":\"group\",\"shapes\":[$circle]}]}")).contains("another group"))
        assertTrue(bad(doc("{\"t\":\"group\",\"shapes\":[]}")).contains("1 to"))
        assertTrue(bad(doc("{\"t\":\"group\",\"l\":\"x\"}")).contains("shapes"))
        val many = (0 until 61).joinToString(",") { "{\"t\":\"circle\",\"x\":5,\"y\":5,\"r\":2}" }
        assertTrue(bad(doc("{\"t\":\"group\",\"shapes\":[$many]}")).contains("1 to 60"))
    }

    @Test fun groupChildrenCountTowardsTheShapeLimit() {
        val sixty = (0 until 60).joinToString(",") { "{\"t\":\"circle\",\"x\":5,\"y\":5,\"r\":2}" }
        val g = "{\"t\":\"group\",\"shapes\":[$sixty]}"
        assertTrue(bad(doc(g, g, g)).contains("at most 120"))
        assertTrue(bad(doc(g, g)).contains("at most 120")) // 2 x (1 + 60) = 122
        ok(doc(g, circle)) // 61 + 1
        // a document without "v" keeps the old limit of 40 (children included)
        val fortyOne = (0 until 41).joinToString(",") { "{\"t\":\"circle\",\"x\":5,\"y\":5,\"r\":2}" }
        val old = "{\"kind\":\"shapes\",\"title\":\"x\",\"shapes\":[{\"t\":\"group\",\"shapes\":[$fortyOne]}]}"
        assertTrue(bad(old).contains("at most 40"))
    }

    @Test fun pathPointsInsideAGroupAreCounted() {
        val d = ok(doc("{\"t\":\"group\",\"shapes\":[{\"t\":\"path\",\"d\":\"M10 10 L20 20\"}]}"))
        assertEquals(2, Sketch.pointsOf(d.shapes[0]))
    }

    // ---------- bounds and pivot ----------

    @Test fun boundsOfEveryKind() {
        val d = ok(doc(
            rect, circle,
            "{\"t\":\"ellipse\",\"x\":50,\"y\":50,\"rx\":20,\"ry\":10}",
            "{\"t\":\"line\",\"x\":10,\"y\":80,\"x2\":90,\"y2\":20}",
            "{\"t\":\"poly\",\"pts\":[[10,10],[40,10],[25,30]]}",
            "{\"t\":\"path\",\"d\":\"M10 10 L50 10 L30 40 Z\"}"
        ))
        fun b(i: Int) = Sketch.bounds(d.shapes[i])
        assertEquals(listOf(10f, 20f, 40f, 60f), b(0).toList())
        assertEquals(listOf(40f, 40f, 60f, 60f), b(1).toList())
        assertEquals(listOf(30f, 40f, 70f, 60f), b(2).toList())
        assertEquals(listOf(10f, 20f, 90f, 80f), b(3).toList())
        assertEquals(listOf(10f, 10f, 40f, 30f), b(4).toList())
        assertEquals(listOf(10f, 10f, 50f, 40f), b(5).toList())
        val p = Sketch.pivot(d.shapes[0])
        assertEquals(25f, p[0], 0.001f)
        assertEquals(40f, p[1], 0.001f)
    }

    @Test fun aGroupBoxIsTheUnionOfItsVisibleChildren() {
        val g = "{\"t\":\"group\",\"shapes\":[{\"t\":\"rect\",\"x\":0,\"y\":0,\"w\":10,\"h\":10},{\"t\":\"circle\",\"x\":50,\"y\":50,\"r\":10,\"hide\":true}]}"
        assertEquals(listOf(0f, 0f, 10f, 10f), Sketch.bounds(ok(doc(g)).shapes[0]).toList())
        val g2 = "{\"t\":\"group\",\"shapes\":[{\"t\":\"rect\",\"x\":0,\"y\":0,\"w\":10,\"h\":10},{\"t\":\"circle\",\"x\":50,\"y\":50,\"r\":10}]}"
        assertEquals(listOf(0f, 0f, 60f, 60f), Sketch.bounds(ok(doc(g2)).shapes[0]).toList())
    }

    // ---------- SVG export ----------

    @Test fun groupsAndTransformsAreWrittenAsSvgGroups() {
        val d = ok(doc(
            "{\"t\":\"group\",\"l\":\"Sky 1\",\"rot\":10,\"op\":0.5,\"shapes\":[{\"t\":\"circle\",\"x\":50,\"y\":20,\"r\":8},{\"t\":\"rect\",\"x\":0,\"y\":0,\"w\":100,\"h\":30}]}",
            "{\"t\":\"rect\",\"x\":10,\"y\":10,\"w\":20,\"h\":20,\"dx\":5,\"rot\":45,\"sc\":2}"
        ))
        val svg = Sketch.toSvg(d)
        assertTrue(svg, svg.contains("<g transform=\"rotate(10 50 15)\" opacity=\"0.5\" id=\"layer_sky_1\">"))
        assertTrue(svg, svg.contains("<g transform=\"translate(5 0) rotate(45 20 20) translate(20 20) scale(2) translate(-20 -20)\">"))
        assertEquals(2, Regex("</g>").findAll(svg).count())
        wellFormed(svg)
    }

    @Test fun gradientsGoIntoDefsInTheSameBoxAsTheScreen() {
        val d = ok(doc(
            "{\"t\":\"rect\",\"x\":10,\"y\":10,\"w\":30,\"h\":20,\"grad\":\"#ff0000 #0000ff 0\"}",
            "{\"t\":\"circle\",\"x\":50,\"y\":50,\"r\":20,\"grad\":\"#ffffff #000000 radial\"}"
        ))
        val svg = Sketch.toSvg(d)
        assertTrue(svg, svg.contains("<defs>"))
        assertTrue(svg, svg.contains("<linearGradient id=\"g1\" gradientUnits=\"userSpaceOnUse\" x1=\"10\" y1=\"20\" x2=\"40\" y2=\"20\">"))
        assertTrue(svg, svg.contains("<radialGradient id=\"g2\" gradientUnits=\"userSpaceOnUse\" cx=\"50\" cy=\"50\" r=\"20\">"))
        assertTrue(svg.contains("fill=\"url(#g1)\""))
        assertTrue(svg.contains("fill=\"url(#g2)\""))
        assertTrue(svg.contains("stop-color=\"#FF0000\""))
        wellFormed(svg)
    }

    @Test fun backgroundGradientFillsTheBackgroundRect() {
        val svg = Sketch.toSvg(ok(doc(circle, extra = ",\"bggrad\":\"#102030 #304050 90\"")))
        assertTrue(svg, svg.contains("<linearGradient id=\"g1\""))
        assertTrue(svg, svg.contains("rx=\"4\" fill=\"url(#g1)\""))
        wellFormed(svg)
    }

    @Test fun hiddenShapesAndGroupsAreNotExported() {
        val svg = Sketch.toSvg(ok(doc(
            "{\"t\":\"rect\",\"x\":10,\"y\":10,\"w\":20,\"h\":20,\"l\":\"secret\",\"hide\":true}",
            "{\"t\":\"group\",\"l\":\"hiddenlayer\",\"hide\":true,\"shapes\":[$circle]}",
            circle
        )))
        assertFalse(svg.contains("secret"))
        assertFalse(svg.contains("hiddenlayer"))
        assertFalse(svg.contains("<g"))
        assertEquals(1, Regex("<circle").findAll(svg).count())
    }

    @Test fun aPlainDrawingStillExportsWithoutDefsOrGroups() {
        val svg = Sketch.toSvg(ok(doc(rect, circle)))
        assertFalse(svg.contains("<defs>"))
        assertFalse(svg.contains("<g"))
        wellFormed(svg)
    }

    // ---------- prompts ----------

    @Test fun onlyTheStudioPromptOffersTheStage3Features() {
        val chat = Sketch.systemPrompt("en")
        val studio = Sketch.studioSystemPrompt("en")
        assertFalse(chat.contains("bggrad"))
        assertTrue(studio.contains("bggrad"))
        assertTrue(studio.contains("group"))
        assertTrue(studio.contains("A rx ry"))
        assertTrue(studio.length >= chat.length + Sketch.ADVANCED_HELP.length)
        val edit = Sketch.studioReviseSystemPrompt("en")
        assertTrue(edit.contains("EDITING"))
        assertTrue(edit.contains("bggrad"))
    }

    @Test fun anArcInASketchPathIsAccepted() {
        ok(doc("{\"t\":\"path\",\"d\":\"M10 50 A20 20 0 0 1 50 50\"}"))
    }

    // ---------- layers ----------

    private fun layered(): Sketch.Drawing = ok(doc(rect, circle, sky, extra = ",\"bg\":\"#102030\",\"bggrad\":\"#102030 #304050 90\""))

    @Test fun rowsListTheFrontLayerFirst() {
        val rows = SketchLayers.rows(layered())
        assertEquals(3, rows.size)
        assertEquals(2, rows[0].index)
        assertTrue(rows[0].isGroup)
        assertEquals("sky (2)", rows[0].title)
        assertEquals("rect \u00B7 wall", rows[2].title)
        assertEquals(0, rows[2].index)
    }

    @Test fun toggleHiddenChangesOnlyTheCopy() {
        val d = layered()
        val hidden = SketchLayers.toggleHidden(d, 0)!!
        assertTrue(hidden.shapes[0].hidden)
        assertFalse(d.shapes[0].hidden)
        assertFalse(SketchLayers.toggleHidden(hidden, 0)!!.shapes[0].hidden)
        assertNull(SketchLayers.toggleHidden(d, 9))
        assertNull(SketchLayers.toggleHidden(d, -1))
        // background colour and gradient are kept
        assertEquals(d.background, hidden.background)
        assertNotNull(hidden.bgGrad)
    }

    @Test fun layersMoveForwardAndBack() {
        val d = layered()
        val f = SketchLayers.moveForward(d, 0)!!
        assertEquals(Sketch.Kind.CIRCLE, f.shapes[0].kind)
        assertEquals(Sketch.Kind.RECT, f.shapes[1].kind)
        assertNull(SketchLayers.moveForward(d, 2))
        assertNull(SketchLayers.moveBack(d, 0))
        val b = SketchLayers.moveBack(d, 1)!!
        assertEquals(Sketch.Kind.CIRCLE, b.shapes[0].kind)
        assertEquals(Sketch.Kind.RECT, b.shapes[1].kind)
    }

    @Test fun removeKeepsAtLeastOneLayer() {
        val d = layered()
        assertEquals(2, SketchLayers.remove(d, 1)!!.shapes.size)
        assertNull(SketchLayers.remove(ok(doc(circle)), 0))
    }

    @Test fun duplicateAddsACopyInFrontAndRespectsTheLimit() {
        val d = layered()
        val c = SketchLayers.duplicate(d, 0)!!
        assertEquals(4, c.shapes.size)
        assertEquals(Sketch.Kind.RECT, c.shapes[1].kind)
        val full = ok(doc(*Array(Sketch.MAX_SHAPES_V2) { "{\"t\":\"circle\",\"x\":5,\"y\":5,\"r\":2}" }))
        assertNull(SketchLayers.duplicate(full, 0))
    }

    @Test fun onlyGroupsCanBeRenamedAndNamesAreCleaned() {
        val d = layered()
        assertEquals("My sky", SketchLayers.renameGroup(d, 2, "  My   sky ")!!.shapes[2].label)
        assertEquals(24, SketchLayers.renameGroup(d, 2, "x".repeat(60))!!.shapes[2].label.length)
        assertNull(SketchLayers.renameGroup(d, 0, "nope"))
    }

    @Test fun groupOneWrapsAPlainShapeAndRefusesAGroup() {
        val d = layered()
        val g = SketchLayers.groupOne(d, 0, "wall layer")!!
        assertEquals(Sketch.Kind.GROUP, g.shapes[0].kind)
        assertEquals("wall layer", g.shapes[0].label)
        assertEquals(Sketch.Kind.RECT, g.shapes[0].children!![0].kind)
        assertNull(SketchLayers.groupOne(d, 2, "x"))
    }

    @Test fun everyLayerResultIsStillAReadableSketch() {
        val d = layered()
        val results = listOf(
            SketchLayers.toggleHidden(d, 1), SketchLayers.moveForward(d, 0), SketchLayers.moveBack(d, 2), SketchLayers.remove(d, 0),
            SketchLayers.duplicate(d, 2), SketchLayers.renameGroup(d, 2, "a"), SketchLayers.groupOne(d, 1, "c")
        )
        for (r in results) {
            assertNotNull(r)
            assertNotNull(Sketch.fromJson(Sketch.toJson(r!!)))
        }
    }

    @Test fun allHiddenIsDetected() {
        var d = layered()
        assertFalse(SketchLayers.allHidden(d))
        for (i in d.shapes.indices) d = SketchLayers.toggleHidden(d, i)!!
        assertTrue(SketchLayers.allHidden(d))
    }

    // ---------- 3 option ----------

    @Test fun variantsHaveThreeDifferentIdeasAndLabels() {
        assertEquals(3, SketchVariants.COUNT)
        val hints = (0 until SketchVariants.COUNT).map { SketchVariants.hint(it) }
        assertEquals(3, hints.toSet().size)
        assertTrue(hints.all { it.isNotBlank() })
        assertEquals(SketchVariants.hint(2), SketchVariants.hint(99))
        assertEquals("Option 2", SketchVariants.label(1))
        val p = SketchVariants.prompt("ek ghar", 1)
        assertTrue(p.startsWith("Request: ek ghar"))
        assertTrue(p.contains(SketchVariants.hint(1)))
    }

    @Test fun sameAnswerTwiceIsShownOnce() {
        val d = ok(doc(circle))
        val json = Sketch.toJson(d)
        val list = listOf(SketchVariants.Candidate(0, d, json), SketchVariants.Candidate(1, d, json), SketchVariants.Candidate(2, ok(doc(rect)), Sketch.toJson(ok(doc(rect)))))
        val out = SketchVariants.distinct(list)
        assertEquals(2, out.size)
        assertEquals(0, out[0].index)
        assertEquals(2, out[1].index)
    }

    // ---------- design kinds ----------

    @Test fun aDesignSavedBeforeStage3IsASketch() {
        val old = SketchDesigns.Design("a", "T", "", 1, 1)
        assertEquals(SketchDesigns.KIND_SKETCH, SketchDesigns.kindOf(old))
        assertFalse(SketchDesigns.isWeb(old))
        assertEquals("", SketchDesigns.badge(old))
        assertEquals("", SketchDesigns.htmlOf(old))
        assertEquals(SketchDesigns.KIND_SKETCH, SketchDesigns.kindOf(SketchDesigns.Design("b", "T", kind = "weird")))
    }

    @Test fun webDesignsHaveAKindAndKeepTheirPageApart() {
        val web = SketchDesigns.newWebDesign("Page", SketchDesigns.KIND_HTML, 5L, "id1")
        assertTrue(SketchDesigns.isWeb(web))
        assertFalse(SketchDesigns.is3d(web))
        assertEquals("</>", SketchDesigns.badge(web))
        val three = SketchDesigns.newWebDesign("Cube", SketchDesigns.KIND_HTML3D, 5L, "id2")
        assertTrue(SketchDesigns.is3d(three))
        assertEquals("3D", SketchDesigns.badge(three))
        assertFalse(SketchDesigns.isWeb(SketchDesigns.newWebDesign("x", "bogus", 1L, "id3")))

        val next = SketchDesigns.update(listOf(web), "id1", "<div>x</div>", 9L)
        assertEquals("<div>x</div>", SketchDesigns.htmlOf(next[0]))
        assertEquals("", next[0].json)
        assertEquals(9L, next[0].updated)
        assertEquals("<div>x</div>", SketchDesigns.contentOf(next[0]))
    }

    @Test fun updatingASketchDesignStillWritesTheJson() {
        val d = SketchDesigns.Design("a", "T", "", 1, 1)
        val next = SketchDesigns.update(listOf(d), "a", "{\"x\":1}", 7L)
        assertEquals("{\"x\":1}", next[0].json)
        assertNull(next[0].html)
        assertEquals("{\"x\":1}", SketchDesigns.contentOf(next[0]))
    }

    @Test fun duplicatingAWebDesignKeepsKindAndPage() {
        val web = SketchDesigns.update(listOf(SketchDesigns.newWebDesign("Page", SketchDesigns.KIND_HTML, 1L, "id1")), "id1", "<p>hi</p>", 2L)
        val both = SketchDesigns.duplicate(web, "id1", 20L, "id2")!!
        assertEquals(2, both.size)
        assertTrue(SketchDesigns.isWeb(both[1]))
        assertEquals("<p>hi</p>", SketchDesigns.htmlOf(both[1]))
        assertEquals("Page copy", both[1].title)
    }

    // ---------- web sandbox: reading and checking ----------

    private val glass = "<!DOCTYPE html><html><head><title>Glass  login</title><style>body{background:linear-gradient(#123,#456)}" +
        ".card{backdrop-filter:blur(8px);background:rgba(255,255,255,.1)}@keyframes a{to{opacity:1}}</style></head>" +
        "<body><div class=\"card\" id=\"c\">Hello important data</div>" +
        "<script>var retrieval=function(x){return x};function tick(){requestAnimationFrame(tick)}tick();</script></body></html>"

    private fun page(raw: String): String {
        val c = HtmlSandbox.check(raw)
        assertTrue("should be accepted: " + (if (c is HtmlSandbox.Checked.Bad) c.reason else ""), c is HtmlSandbox.Checked.Ok)
        return (c as HtmlSandbox.Checked.Ok).html
    }

    private fun refused(raw: String): String {
        val c = HtmlSandbox.check(raw)
        assertTrue("should be refused: $raw", c is HtmlSandbox.Checked.Bad)
        return (c as HtmlSandbox.Checked.Bad).reason
    }

    @Test fun aNormalPageIsAcceptedAlsoInsideAFenceOrWithChatter() {
        assertEquals(glass, page(glass))
        assertEquals(glass, page("```html\n" + glass + "\n```"))
        assertEquals(glass, page("Sure, here it is:\n" + glass + "\nEnjoy!"))
        assertEquals("<div>hi</div>", page("<div>hi</div>"))
    }

    @Test fun textWithNoPageIsRefused() {
        assertTrue(refused("Sorry, I cannot do that").contains("no HTML"))
        assertTrue(refused("").contains("no HTML"))
        assertTrue(refused("<b>bold only</b>").contains("no HTML"))
    }

    @Test fun waysOutOfThePageAreRefusedWithAReason() {
        val cases = listOf(
            "<html><body><script src=\"a.js\"></script></body></html>",
            "<body><script src=\"https://x.com/a.js\"></script></body>",
            "<body><script>fetch(\"x\")</script></body>",
            "<body><script>new XMLHttpRequest()</script></body>",
            "<body><iframe></iframe></body>",
            "<head><link rel=\"stylesheet\" href=\"a.css\"></head><body><p>x</p></body>",
            "<body><form><input></form></body>",
            "<style>@import \"a.css\";</style><div>x</div>",
            "<body><script>localStorage.x=1</script></body>",
            "<body><script>eval(\"1\")</script></body>",
            "<body><img src=\"http://x.com/a.png\"></body>",
            "<body><img src=\"//x.com/a.png\"></body>",
            "<div style=\"background:url(https://x.com/a.png)\">x</div>",
            "<body><script>window.open(\"x\")</script></body>",
            "<body><script>location.href = \"x\"</script></body>",
            "<body><script>location = \"x\"</script></body>",
            "<body><script>new WebSocket(\"x\")</script></body>",
            "<body><script>\nimport x from \"y\"\n</script></body>",
            "<body><script>import(\"x\")</script></body>",
            "<body><script>document.cookie=\"a\"</script></body>",
            "<head><meta http-equiv=\"refresh\" content=\"0\"></head><body>x</body>",
            "<body><script>navigator.sendBeacon(\"x\")</script></body>",
            "<body><script>new Worker(\"x\")</script></body>"
        )
        for (c in cases) assertTrue(c, refused(c).isNotBlank())
        assertTrue(refused("<body><script>fetch(\"x\")</script></body>").contains("fetch"))
        assertTrue(refused("<body><script src=\"a.js\"></script></body>").contains("inline"))
    }

    @Test fun harmlessLookalikesAreAccepted() {
        page("<div><svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"><circle r=\"4\"/></svg></div>")
        page("<div><a href=\"#top\">go</a></div>")
        page("<body><script>window.location.reload()</script></body>")
        page("<body><script>var retrieval=1;function medieval(){}</script></body>")
        page("<body><img src=\"data:image/png;base64,AAAA\"></body>")
        page("<body><script>if(location.hash===\"x\"){}</script></body>")
        page("<body><script>var s=new THREE.Scene();var r=new THREE.WebGLRenderer({antialias:true});document.body.appendChild(r.domElement)</script></body>")
    }

    @Test fun aPageThatIsTooLongIsRefused() {
        assertTrue(refused("<div>" + "x".repeat(HtmlSandbox.MAX_HTML + 1) + "</div>").contains("too long"))
        page("<div>" + "x".repeat(1000) + "</div>")
    }

    @Test fun usesThreeOnlyForTheGlobal() {
        assertTrue(HtmlSandbox.usesThree("<script>new THREE.Scene()</script>"))
        assertFalse(HtmlSandbox.usesThree("<div>three little pigs</div>"))
    }

    // ---------- web sandbox: wrapping ----------

    @Test fun theCspComesFirstInsideTheHead() {
        val w = HtmlSandbox.wrap(glass, null)
        assertEquals(1, Regex("Content-Security-Policy").findAll(w).count())
        assertTrue(w.indexOf("Content-Security-Policy") < w.indexOf("<title>"))
        assertTrue(w.indexOf("Content-Security-Policy") < w.indexOf("<body>"))
        assertTrue(w.contains("connect-src 'none'"))
        assertTrue(w.contains("viewport"))
        assertTrue(w.endsWith("</html>"))
    }

    @Test fun pagesWithoutAHeadOrHtmlTagGetOne() {
        val bare = HtmlSandbox.wrap("<div>hi</div>", null)
        assertTrue(bare, bare.startsWith("<!DOCTYPE html><html><head><meta http-equiv=\"Content-Security-Policy\""))
        assertTrue(bare.contains("<body><div>hi</div></body></html>"))
        assertTrue(HtmlSandbox.wrap("<head><title>t</title></head><body><p>x</p></body>", null).startsWith("<head><meta http-equiv"))
        assertTrue(HtmlSandbox.wrap("<html><body><p>x</p></body></html>", null).startsWith("<html><head><meta http-equiv"))
    }

    @Test fun theThreeLibraryComesAfterTheCspAndBeforeThePage() {
        val w = HtmlSandbox.wrap(glass, "var THREE={};//</SCRIPT>")
        assertEquals(1, Regex("Content-Security-Policy").findAll(w).count())
        assertTrue(w.indexOf("Content-Security-Policy") < w.indexOf("var THREE"))
        assertTrue(w.indexOf("var THREE") < w.indexOf("<body>"))
        assertTrue(w.contains(HtmlSandbox.WEBGL_PROBE_PREFIX))
        // the library text cannot close its own script element
        assertFalse(w.contains("//</SCRIPT>"))
        assertTrue(w.contains("<\\/script"))
    }

    @Test fun anExportedPageHasNoCspAndTheLibraryOnlyWhenNeeded() {
        val plain = HtmlSandbox.forExport(glass, "var THREE={};")
        assertFalse(plain.contains("Content-Security-Policy"))
        assertFalse(plain.contains("var THREE"))
        val threePage = "<html><head></head><body><script>new THREE.Scene()</script></body></html>"
        val withLib = HtmlSandbox.forExport(threePage, "var THREE={};")
        assertFalse(withLib.contains("Content-Security-Policy"))
        assertTrue(withLib.indexOf("var THREE={}") < withLib.indexOf("new THREE.Scene"))
        assertFalse(HtmlSandbox.forExport(threePage, null).contains("var THREE"))
    }

    @Test fun theTitleOfAPageNamesAnUntitledDesign() {
        assertEquals("Glass login", HtmlSandbox.titleOf(glass))
        assertNull(HtmlSandbox.titleOf("<div>x</div>"))
        assertEquals(40, HtmlSandbox.titleOf("<title>" + "a".repeat(100) + "</title>")!!.length)
    }

    // ---------- web sandbox: prompts ----------

    @Test fun theWebPromptsStateTheRulesAndTheThreeOneSaysThreeIsLoaded() {
        val web = HtmlSandbox.systemPrompt("en", false)
        assertTrue(web.contains(HtmlSandbox.ASK_CHARS.toString()))
        assertTrue(web.contains("NOT allowed"))
        assertFalse(web.contains("ALREADY loaded"))
        val three = HtmlSandbox.systemPrompt("en", true)
        assertTrue(three.contains("THREE"))
        assertTrue(three.contains("ALREADY loaded"))
        assertTrue(HtmlSandbox.reviseSystemPrompt("en", false).contains("EDITING"))
        assertTrue(HtmlSandbox.systemPrompt(LocalMemory.LANG_HINGLISH, false).contains("Roman Hinglish"))
        assertTrue(HtmlSandbox.revisePrompt("<div>a</div>", "make it blue").contains("<div>a</div>"))
        assertNotEquals(HtmlSandbox.userPrompt("a"), HtmlSandbox.userPrompt("b"))
    }

    @Test fun theListSummaryShowsSizeAndThreeD() {
        assertEquals("khali", HtmlSandbox.summary(""))
        assertTrue(HtmlSandbox.summary("x".repeat(2500)).startsWith("2.5 KB"))
        assertTrue(HtmlSandbox.summary("<script>THREE.x</script>").endsWith("3D"))
    }
}
