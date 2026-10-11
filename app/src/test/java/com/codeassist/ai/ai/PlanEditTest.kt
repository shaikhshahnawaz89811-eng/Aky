package com.codeassist.ai.ai

import com.codeassist.ai.ai.TaskGraph.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanEditTest {
    private fun req(text: String): PlanEdit.Request {
        val r = PlanEdit.parse(text)
        assertNotNull(text, r)
        return r!!
    }

    // ---------- grammar ----------

    @Test fun appendixA20PetrolStepHasNoToolButIsRecognised() {
        val r = req("petrol wala kaam hata do")
        assertNull(r.tool)
        assertEquals("petrol", r.subject)
    }

    @Test fun toolWordsPickTheTool() {
        assertEquals("torch_set", req("torch wala kaam hata do").tool)
        assertEquals("call_dial", req("call wala kaam cancel karo").tool)
        assertEquals("timer_set", req("timer rehne do").tool)
        assertEquals("alarm_set", req("alarm wala hatao").tool)
        assertEquals("weather_now", req("mausam wala kaam hata do").tool)
        assertEquals("battery_level", req("battery wala kaam cancel kar do").tool)
    }

    @Test fun ordinalsChooseBetweenTwoOfTheSameTool() {
        assertEquals(2, req("doosra timer hata do").ordinal)
        assertEquals(1, req("pehla alarm cancel kar do").ordinal)
        assertEquals("timer_set", req("doosra timer hata do").tool)
        assertEquals(0, req("timer hata do").ordinal)
    }

    @Test fun negativeFormIsARemoval() {
        val r = req("torch on mat karo")
        assertEquals("torch_set", r.tool)
    }

    @Test fun messagesThatAreNotARemovalAreLeftAlone() {
        assertNull(PlanEdit.parse("kal subah 8 baje alarm laga do"))
        assertNull(PlanEdit.parse("torch on karo"))
        assertNull(PlanEdit.parse("mausam kaisa hai"))
        assertNull(PlanEdit.parse(""))
    }

    @Test fun aBareRemovePhraseHasNoSubjectSoItIsNotAPlanEdit() {
        assertNull(PlanEdit.parse("hata do"))
        assertNull(PlanEdit.parse("wo kaam hata do"))
        assertNull(PlanEdit.parse("rehne do"))
    }

    @Test fun longMessagesAreNotEdits() {
        assertNull(PlanEdit.parse("mujhe lagta hai ki wo torch wala kaam ab nahi chahiye to please use hata do na yaar"))
    }

    // ---------- finding the step ----------

    private fun plan(): TaskGraph.Plan {
        val tasks = listOf(
            PlanTask("t1", "torch_set", mapOf("on" to "true")),
            PlanTask("t2", "call_dial", mapOf("number" to "9876543210")),
            PlanTask("t3", "time_now", emptyMap())
        )
        val p = TaskGraph.build("p", "c", "m", "", tasks, 0L)
        p.node("t1")!!.state = State.COMPLETED
        p.node("t1")!!.undoEntryId = "u1"
        p.node("t2")!!.state = State.WAITING_CONFIRM
        p.node("t3")!!.state = State.COMPLETED
        p.node("t3")!!.readOnly = true
        return p
    }

    @Test fun findsTheStepByToolWord() {
        val p = plan()
        val f = PlanEdit.pick(p, req("call wala kaam hata do"))
        assertTrue(f is PlanEdit.Pick.Found)
        assertEquals("t2", (f as PlanEdit.Pick.Found).node.id)
    }

    @Test fun aNameThatIsNoToolOfThePlanIsNotFound() {
        assertTrue(PlanEdit.pick(plan(), req("petrol wala kaam hata do")) is PlanEdit.Pick.NotFound)
        assertTrue(PlanEdit.pick(plan(), req("timer wala kaam hata do")) is PlanEdit.Pick.NotFound)
    }

    @Test fun whatToDoWithEachState() {
        val p = plan()
        assertEquals(PlanEdit.Action.UNDO_DONE, PlanEdit.actionFor(p.node("t1")!!))
        assertEquals(PlanEdit.Action.CANCEL_NODE, PlanEdit.actionFor(p.node("t2")!!))
        assertEquals(PlanEdit.Action.NOTHING_TO_UNDO, PlanEdit.actionFor(p.node("t3")!!))
        p.node("t2")!!.state = State.PENDING
        assertEquals(PlanEdit.Action.CANCEL_NODE, PlanEdit.actionFor(p.node("t2")!!))
        for (s in listOf(State.FAILED, State.SKIPPED, State.CANCELLED)) {
            p.node("t2")!!.state = s
            assertEquals(PlanEdit.Action.NOT_RUN, PlanEdit.actionFor(p.node("t2")!!))
        }
        p.node("t2")!!.state = State.RUNNING
        assertEquals(PlanEdit.Action.BUSY, PlanEdit.actionFor(p.node("t2")!!))
    }

    private fun twoTimers(): TaskGraph.Plan {
        val tasks = listOf(
            PlanTask("t1", "timer_set", mapOf("seconds" to "60")),
            PlanTask("t2", "timer_set", mapOf("seconds" to "120"))
        )
        return TaskGraph.build("p", "c", "m", "", tasks, 0L)
    }

    @Test fun twoOfTheSameToolAreNeverGuessed() {
        val p = twoTimers()
        val a = PlanEdit.pick(p, req("timer hata do"))
        assertTrue(a is PlanEdit.Pick.Ambiguous)
        assertEquals(2, (a as PlanEdit.Pick.Ambiguous).count)
        val second = PlanEdit.pick(p, req("doosra timer hata do"))
        assertEquals("t2", (second as PlanEdit.Pick.Found).node.id)
        assertTrue(PlanEdit.pick(p, req("teesra timer hata do")) is PlanEdit.Pick.NotFound)
    }

    @Test fun ifOnlyOneOfTwoCanStillBeRemovedItIsThatOne() {
        val p = twoTimers()
        p.node("t1")!!.state = State.COMPLETED // no undo entry: nothing to take back
        val f = PlanEdit.pick(p, req("timer hata do"))
        assertEquals("t2", (f as PlanEdit.Pick.Found).node.id)
    }

    @Test fun listingTellsWhatThePlanHas() {
        assertEquals("Torch (ho gaya), Call (tap ka wait), Time (ho gaya)", PlanEdit.listing(plan()))
    }
}
