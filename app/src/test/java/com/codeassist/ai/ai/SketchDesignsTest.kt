package com.codeassist.ai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sketch Studio stage 2: the pure logic of the studio screen. Designs list operations, undo / redo history, zoom / pan maths and
 * export file names. The screen (SketchStudioActivity), SketchView touch handling and the file export need a device.
 */
class SketchDesignsTest {
    private fun d(id: String, title: String = "T", json: String = "", t: Long = 0L) = SketchDesigns.Design(id, title, json, t, t)

    private val house = "{\"kind\":\"shapes\",\"v\":2,\"title\":\"House\",\"shapes\":[{\"t\":\"rect\",\"x\":20,\"y\":40,\"w\":60,\"h\":40}]}"

    // ---------- titles and file names ----------

    @Test fun blankTitleBecomesUntitled() {
        assertEquals(SketchDesigns.UNTITLED, SketchDesigns.cleanTitle(null))
        assertEquals(SketchDesigns.UNTITLED, SketchDesigns.cleanTitle("   \n\t "))
    }

    @Test fun titleIsOneLineAndCut() {
        assertEquals("a b c", SketchDesigns.cleanTitle("  a \n b\t c "))
        val long = "x".repeat(100)
        assertEquals(SketchDesigns.MAX_TITLE, SketchDesigns.cleanTitle(long).length)
    }

    @Test fun fileNamesAreSafe() {
        assertEquals("house.svg", SketchDesigns.fileName("House", "svg"))
        assertEquals("login_flow.png", SketchDesigns.fileName("Login / Flow!!", ".PNG"))
        assertEquals("sketch.svg", SketchDesigns.fileName("", "svg"))
        assertEquals("sketch.svg", SketchDesigns.fileName("!!!", "svg"))
        assertEquals("sketch.png", SketchDesigns.fileName(null, "png"))
        assertTrue(SketchDesigns.fileName("a".repeat(80), "svg").length <= 34)
        assertFalse(SketchDesigns.fileName("../../etc", "svg").contains("/"))
    }

    // ---------- list operations ----------

    @Test fun addKeepsOrderAndRefusesDuplicatesAndFullList() {
        val one = SketchDesigns.add(emptyList(), d("a"))
        assertNotNull(one)
        assertEquals(1, one!!.size)
        assertNull("same id twice", SketchDesigns.add(one, d("a")))
        var list: List<SketchDesigns.Design> = emptyList()
        for (i in 0 until SketchDesigns.MAX_DESIGNS) list = SketchDesigns.add(list, d("id$i"))!!
        assertEquals(SketchDesigns.MAX_DESIGNS, list.size)
        assertNull("list is full", SketchDesigns.add(list, d("one-more")))
    }

    @Test fun updateChangesJsonAndTimeOnlyOfThatDesign() {
        val list = listOf(d("a", "A", "", 1), d("b", "B", "", 1))
        val next = SketchDesigns.update(list, "b", house, 99)
        assertEquals("", next[0].json)
        assertEquals(1L, next[0].updated)
        assertEquals(house, next[1].json)
        assertEquals(99L, next[1].updated)
        assertEquals("B", next[1].title)
        assertEquals(list, SketchDesigns.update(list, "zzz", house, 99))
    }

    @Test fun renameCleansTheTitle() {
        val list = listOf(d("a", "Old"))
        assertEquals("New name", SketchDesigns.rename(list, "a", "  New   name ", 5)[0].title)
        assertEquals(SketchDesigns.UNTITLED, SketchDesigns.rename(list, "a", "  ", 5)[0].title)
    }

    @Test fun deleteRemovesOnlyThatDesign() {
        val list = listOf(d("a"), d("b"), d("c"))
        assertEquals(listOf("a", "c"), SketchDesigns.delete(list, "b").map { it.id })
        assertEquals(3, SketchDesigns.delete(list, "nope").size)
    }

    @Test fun duplicateMakesACopyWithNewIdAndTitle() {
        val list = listOf(d("a", "House", house, 1))
        val next = SketchDesigns.duplicate(list, "a", 50, "a2")!!
        assertEquals(2, next.size)
        val copy = next.first { it.id == "a2" }
        assertEquals("House copy", copy.title)
        assertEquals(house, copy.json)
        assertEquals(50L, copy.created)
        assertEquals(50L, copy.updated)
        // the second copy gets a number
        val third = SketchDesigns.duplicate(next, "a", 60, "a3")!!
        assertEquals("House copy 2", third.first { it.id == "a3" }.title)
    }

    @Test fun duplicateOfUnknownOrInFullListIsNull() {
        assertNull(SketchDesigns.duplicate(listOf(d("a")), "zzz", 1, "x"))
        var list: List<SketchDesigns.Design> = emptyList()
        for (i in 0 until SketchDesigns.MAX_DESIGNS) list = SketchDesigns.add(list, d("id$i"))!!
        assertNull(SketchDesigns.duplicate(list, "id0", 1, "x"))
    }

    @Test fun copyTitleFitsTheLimit() {
        val long = "y".repeat(SketchDesigns.MAX_TITLE)
        val t = SketchDesigns.copyTitle(listOf(d("a", long)), long)
        assertTrue(t.length <= SketchDesigns.MAX_TITLE)
        assertTrue(t.endsWith(" copy"))
        assertNotEquals(long, t)
    }

    @Test fun sortedShowsNewestChangeFirst() {
        val list = listOf(d("old", t = 1), d("new", t = 9), d("mid", t = 5))
        assertEquals(listOf("new", "mid", "old"), SketchDesigns.sorted(list).map { it.id })
    }

    @Test fun findAndReadable() {
        val list = listOf(d("a", json = house))
        assertNotNull(SketchDesigns.find(list, "a"))
        assertNull(SketchDesigns.find(list, "b"))
        assertNull(SketchDesigns.find(list, null))
        assertTrue(SketchDesigns.readable(""))
        assertTrue(SketchDesigns.readable(house))
        assertFalse(SketchDesigns.readable("{not json"))
    }

    @Test fun aDrawingKeptAsADesignReadsBackUnchanged() {
        val drawing = Sketch.fromJson(house)!!
        val json = Sketch.toJson(drawing)
        val design = SketchDesigns.newDesign(drawing.title, json, 7, "id1")
        assertEquals("House", design.title)
        assertEquals(json, design.json)
        assertEquals(1, Sketch.fromJson(design.json)!!.shapes.size)
    }

    // ---------- undo / redo ----------

    @Test fun historyStartsAtTheOpenedState() {
        val h = SketchHistory("")
        assertEquals("", h.current)
        assertFalse(h.canUndo)
        assertFalse(h.canRedo)
        assertNull(h.undo())
        assertNull(h.redo())
    }

    @Test fun undoAndRedoWalkTheStates() {
        val h = SketchHistory("")
        assertTrue(h.push("A"))
        assertTrue(h.push("B"))
        assertEquals("B", h.current)
        assertEquals("A", h.undo())
        assertEquals("", h.undo())
        assertNull(h.undo())
        assertEquals("A", h.redo())
        assertEquals("B", h.redo())
        assertNull(h.redo())
    }

    @Test fun pushAfterUndoDropsTheRedoTail() {
        val h = SketchHistory("")
        h.push("A")
        h.push("B")
        h.undo()
        assertTrue(h.canRedo)
        h.push("C")
        assertFalse(h.canRedo)
        assertEquals("C", h.current)
        assertEquals("A", h.undo())
        assertEquals(3, h.size)
    }

    @Test fun pushOfTheSameStateChangesNothing() {
        val h = SketchHistory("A")
        assertFalse(h.push("A"))
        assertEquals(1, h.size)
    }

    @Test fun historyForgetsTheOldestStateAtTheLimit() {
        val h = SketchHistory("s0", limit = 3)
        h.push("s1")
        h.push("s2")
        h.push("s3")
        assertEquals(3, h.size)
        assertEquals("s3", h.current)
        assertEquals("s2", h.undo())
        assertEquals("s1", h.undo())
        assertNull("s0 was forgotten", h.undo())
    }

    // ---------- zoom and pan ----------

    private val side = 400f

    @Test fun startsUnzoomed() {
        val x = ViewXform()
        assertTrue(x.isIdentity)
        assertEquals(1f, x.scale, 0f)
    }

    @Test fun zoomKeepsThePointUnderTheFingerStill() {
        val x = ViewXform()
        // the canvas point under the finger before and after must be the same
        val fx = 300f
        val fy = 100f
        val beforeX = x.toCanvas(fx, x.tx)
        val beforeY = x.toCanvas(fy, x.ty)
        x.zoomAt(2f, fx, fy, side)
        assertEquals(2f, x.scale, 0.0001f)
        assertEquals(beforeX, x.toCanvas(fx, x.tx), 0.01f)
        assertEquals(beforeY, x.toCanvas(fy, x.ty), 0.01f)
    }

    @Test fun scaleStaysBetweenOneAndEight() {
        val x = ViewXform()
        x.zoomAt(100f, 200f, 200f, side)
        assertEquals(ViewXform.MAX_SCALE, x.scale, 0f)
        x.zoomAt(0.0001f, 200f, 200f, side)
        assertEquals(ViewXform.MIN_SCALE, x.scale, 0f)
        assertTrue("back at 1x there is nothing to move", x.isIdentity)
    }

    @Test fun badFactorsAreIgnored() {
        val x = ViewXform()
        x.zoomAt(Float.NaN, 10f, 10f, side)
        x.zoomAt(0f, 10f, 10f, side)
        x.zoomAt(-2f, 10f, 10f, side)
        x.zoomAt(2f, 10f, 10f, 0f)
        assertTrue(x.isIdentity)
    }

    @Test fun cannotPanAnUnzoomedPicture() {
        val x = ViewXform()
        x.pan(50f, -70f, side)
        assertTrue(x.isIdentity)
    }

    @Test fun panIsClampedSoThePictureAlwaysCoversTheView() {
        val x = ViewXform()
        x.zoomAt(2f, 0f, 0f, side)
        x.pan(500f, 500f, side)
        assertEquals(0f, x.tx, 0f)
        assertEquals(0f, x.ty, 0f)
        x.pan(-5000f, -5000f, side)
        assertEquals(side - side * 2f, x.tx, 0.001f)
        assertEquals(side - side * 2f, x.ty, 0.001f)
    }

    @Test fun zoomingOutPullsThePictureBackIntoPlace() {
        val x = ViewXform()
        x.zoomAt(4f, 400f, 400f, side)
        x.pan(-5000f, -5000f, side)
        x.zoomAt(0.5f, 0f, 0f, side)
        assertEquals(2f, x.scale, 0.0001f)
        assertTrue(x.tx >= side - side * 2f - 0.001f && x.tx <= 0f)
        assertTrue(x.ty >= side - side * 2f - 0.001f && x.ty <= 0f)
    }

    @Test fun resetGoesBackToTheStart() {
        val x = ViewXform()
        x.zoomAt(3f, 120f, 80f, side)
        x.pan(-30f, -10f, side)
        assertFalse(x.isIdentity)
        x.reset()
        assertTrue(x.isIdentity)
    }
}
