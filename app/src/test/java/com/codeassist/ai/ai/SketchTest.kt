package com.codeassist.ai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T4 (test chat 13 Oct 2026, mistake 6): the sketch format, its checks, the templates and the intent words. */
class SketchTest {
    private fun ok(raw: String): Sketch.Parsed.Ok {
        val p = Sketch.parse(raw)
        assertTrue(p.toString() + (if (p is Sketch.Parsed.Bad) p.reason else ""), p is Sketch.Parsed.Ok)
        return p as Sketch.Parsed.Ok
    }

    private fun bad(raw: String): String {
        val p = Sketch.parse(raw)
        assertTrue("should be refused: $raw", p is Sketch.Parsed.Bad)
        return (p as Sketch.Parsed.Bad).reason
    }

    private fun inside(d: Sketch.Drawing) {
        for (s in d.shapes) {
            assertTrue(s.a in 0f..100f && s.b in 0f..100f)
            when (s.kind) {
                Sketch.Kind.RECT, Sketch.Kind.TRI -> assertTrue(s.a + s.c <= 100.01f && s.b + s.d <= 100.01f)
                Sketch.Kind.LINE -> assertTrue(s.c in 0f..100f && s.d in 0f..100f)
                else -> Unit
            }
        }
    }

    // ---------- reading what the model wrote ----------

    @Test fun jsonIsFoundInsideFencesAndChatter() {
        val raw = "Sure!\n```json\n{\"kind\":\"flowchart\",\"title\":\"Login\",\"items\":[\"Start\",\"Check\"]}\n```\nDone."
        assertEquals("flowchart", ok(raw).kind)
        assertNull(Sketch.extractJson("no braces here"))
    }

    @Test fun aFlowchartKeepsOnlyTheLabelsTheAppDoesTheLayout() {
        val p = ok("{\"kind\":\"flowchart\",\"title\":\"Login flow\",\"items\":[\"Start\",\"Enter password\",\"Check\",\"Home\"]}")
        // title + 4 boxes + 3 arrows
        assertEquals(8, p.drawing.shapes.size)
        assertEquals(4, p.drawing.shapes.count { it.kind == Sketch.Kind.RECT })
        assertEquals(3, p.drawing.shapes.count { it.kind == Sketch.Kind.LINE && it.arrow })
        inside(p.drawing)
    }

    @Test fun aLongFlowchartSnakesSoRowChangesAreVerticalArrows() {
        val d = Sketch.flowchart("Eight", listOf("a", "b", "c", "d", "e", "f", "g", "h"))
        inside(d)
        val lines = d.shapes.filter { it.kind == Sketch.Kind.LINE }
        assertEquals(7, lines.size)
        // box 3 -> 4 and box 6 -> 7 change the row: same x at both ends
        assertEquals(lines[2].a, lines[2].c, 0.001f)
        assertEquals(lines[5].a, lines[5].c, 0.001f)
        assertTrue(lines[2].d > lines[2].b)
        // row 2 runs right to left: its first arrow points left
        assertTrue(lines[3].c < lines[3].a)
    }

    @Test fun aWireframeStacksThePartsInsideThePhoneFrame() {
        val p = ok("{\"kind\":\"wireframe\",\"title\":\"Chat\",\"items\":[\"header:Chat\",\"list\",\"input:Type here\",\"button:Send\"]}")
        inside(p.drawing)
        val rects = p.drawing.shapes.filter { it.kind == Sketch.Kind.RECT }
        assertEquals(5, rects.size)
        val frame = rects[0]
        for (r in rects.drop(1)) assertTrue(r.b >= frame.b && r.b + r.d <= frame.b + frame.d)
        assertEquals("Send", rects.last().label)
    }

    @Test fun tooManyWireframePartsAreScaledToFit() {
        val items = (1..8).map { "list:L$it" }
        val d = Sketch.wireframe("Many", items)
        inside(d)
        val rects = d.shapes.filter { it.kind == Sketch.Kind.RECT }
        assertTrue(rects.last().b + rects.last().d <= rects[0].b + rects[0].d)
    }

    @Test fun simpleShapesAreReadAndChecked() {
        val p = ok(
            "{\"kind\":\"shapes\",\"title\":\"Ghar\",\"shapes\":[" +
                "{\"t\":\"rect\",\"x\":25,\"y\":50,\"w\":50,\"h\":35,\"l\":\"deewar\"}," +
                "{\"t\":\"tri\",\"x\":20,\"y\":25,\"w\":60,\"h\":25}," +
                "{\"t\":\"circle\",\"x\":82,\"y\":15,\"r\":8}," +
                "{\"t\":\"line\",\"x\":10,\"y\":88,\"x2\":90,\"y2\":88,\"arrow\":true}," +
                "{\"t\":\"text\",\"x\":50,\"y\":96,\"l\":\"Ghar\"}]}"
        )
        assertEquals("shapes", p.kind)
        assertEquals(5, p.drawing.shapes.size)
        assertEquals(Sketch.Kind.TRI, p.drawing.shapes[1].kind)
        assertTrue(p.drawing.shapes[3].arrow)
    }

    @Test fun kindIsGuessedFromTheKeysWhenMissing() {
        assertEquals("flowchart", ok("{\"title\":\"x\",\"items\":[\"a\",\"b\"]}").kind)
        assertEquals("shapes", ok("{\"shapes\":[{\"t\":\"circle\",\"x\":50,\"y\":50,\"r\":10}]}").kind)
    }

    @Test fun wrongAnswersAreRefusedWithAReason() {
        assertTrue(bad("here is your house").contains("JSON"))
        assertTrue(bad("{not json}").contains("valid"))
        assertTrue(bad("{\"kind\":\"flowchart\",\"items\":[\"only one\"]}").contains("2 to 8"))
        assertTrue(bad("{\"kind\":\"wireframe\",\"items\":[]}").contains("wireframe"))
        assertTrue(bad("{\"title\":\"x\"}").contains("kind"))
        assertTrue(bad("{\"kind\":\"shapes\",\"shapes\":[{\"t\":\"star\",\"x\":5,\"y\":5}]}").contains("star"))
        assertTrue(bad("{\"kind\":\"shapes\",\"shapes\":[{\"t\":\"circle\",\"x\":500,\"y\":5,\"r\":4}]}").contains("0 and 100"))
        assertTrue(bad("{\"kind\":\"shapes\",\"shapes\":[{\"t\":\"rect\",\"x\":5,\"y\":5,\"w\":0,\"h\":4}]}").contains("w and h"))
        assertTrue(bad("{\"kind\":\"shapes\",\"shapes\":[{\"t\":\"text\",\"x\":5,\"y\":5}]}").contains("text"))
        assertTrue(bad("{\"kind\":\"shapes\",\"shapes\":[]}").contains("empty"))
    }

    @Test fun moreThanFortyShapesAreRefused() {
        val one = "{\"t\":\"circle\",\"x\":50,\"y\":50,\"r\":5}"
        val many = (1..41).joinToString(",") { one }
        assertTrue(bad("{\"kind\":\"shapes\",\"shapes\":[$many]}").contains("40"))
    }

    @Test fun slightlyOutsideCoordinatesAreClampedNotRefused() {
        val p = ok("{\"kind\":\"shapes\",\"shapes\":[{\"t\":\"circle\",\"x\":103,\"y\":-2,\"r\":5}]}")
        assertEquals(100f, p.drawing.shapes[0].a, 0.001f)
        assertEquals(0f, p.drawing.shapes[0].b, 0.001f)
    }

    @Test fun storedJsonReadsBackToTheSameDrawing() {
        val d = ok("{\"kind\":\"flowchart\",\"title\":\"Login\",\"items\":[\"a\",\"b\",\"c\"]}").drawing
        val back = Sketch.fromJson(Sketch.toJson(d))
        assertNotNull(back)
        assertEquals(d.shapes.size, back!!.shapes.size)
        assertEquals("Login", back.title)
        assertNull(Sketch.fromJson(null))
        assertNull(Sketch.fromJson("garbage"))
    }

    @Test fun thePromptNamesTheThreeKindsAndTheLanguage() {
        val hi = Sketch.systemPrompt(LocalMemory.LANG_HINGLISH)
        assertTrue(hi.contains("flowchart") && hi.contains("wireframe") && hi.contains("shapes"))
        assertTrue(hi.contains("Hinglish"))
        assertTrue(Sketch.systemPrompt(LocalMemory.LANG_ENGLISH).contains("in English"))
    }

    // ---------- what counts as a drawing request ----------

    @Test fun directOrdersAreDrawingRequests() {
        for (s in listOf("ek sketch banao: ghar", "login ka flowchart banao", "chat screen ka wireframe banao", "draw a house", "diagram do")) {
            assertTrue(s, SketchIntent.isRequest(s))
        }
    }

    @Test fun talkAboutTheFeatureOrOtherChatIsNot() {
        val no = listOf(
            "coding assistant me AI ko sketch banane ki skills deni hain", "sketch kya hota hai", "torch on karo",
            "REST API ka diagram kaise banate hain?", "mere app ka poora plan banao", "kal subah 6 baje alarm laga do"
        )
        for (s in no) assertFalse(s, SketchIntent.isRequest(s))
    }

    @Test fun theReportMessageGetsOneQuestionNotATrainingList() {
        val ask = "coding assistant me AI ko sketch banane ki skills deni hain"
        assertEquals(SketchIntent.CLARIFY_REPLY, SketchIntent.clarify(ask, null))
        assertTrue(SketchIntent.CLARIFY_REPLY.contains("drawing") && SketchIntent.CLARIFY_REPLY.contains("training"))
        assertNull(SketchIntent.clarify("ek sketch banao: ghar", null))
        assertNull(SketchIntent.clarify("kaise ho", null))
    }

    @Test fun afterTheQuestionNoTrainingGetsTheNoTrainingAnswer() {
        val follow = "Training nahin dena hai, sirf skills deni hain"
        assertEquals(SketchIntent.NO_TRAINING_REPLY, SketchIntent.clarify(follow, SketchIntent.CLARIFY_NOTE))
        // without the earlier question this message is not about sketches
        assertNull(SketchIntent.clarify("Training nahin dena hai", null))
    }
}
