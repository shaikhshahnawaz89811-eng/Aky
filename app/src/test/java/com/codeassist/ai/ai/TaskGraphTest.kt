package com.codeassist.ai.ai

import com.codeassist.ai.ai.TaskGraph.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskGraphTest {
    private fun t(id: String, tool: String, vararg after: String): PlanTask = PlanTask(id, tool, emptyMap(), after.toList())

    private fun plan(vararg tasks: PlanTask, now: Long = 1_000_000L): TaskGraph.Plan =
        TaskGraph.build("p1", "chat", "msg", "", tasks.toList(), now)

    private fun node(p: TaskGraph.Plan, id: String): TaskGraph.Node {
        val n = p.node(id)
        assertNotNull("node $id", n)
        return n!!
    }

    // ---------- validation ----------

    @Test fun chainIsValid() {
        assertNull(TaskGraph.validate(listOf(t("t1", "torch_set"), t("t2", "timer_set", "t1"))))
    }

    @Test fun unknownDependencyIsRefused() {
        val r = TaskGraph.validate(listOf(t("t1", "torch_set", "t9")))
        assertTrue(r.orEmpty().contains("unknown"))
    }

    @Test fun selfDependencyIsRefused() {
        assertTrue(TaskGraph.validate(listOf(t("t1", "torch_set", "t1"))).orEmpty().contains("itself"))
    }

    @Test fun duplicateIdIsRefused() {
        assertTrue(TaskGraph.validate(listOf(t("t1", "time_now"), t("t1", "date_today"))).orEmpty().contains("duplicate"))
    }

    @Test fun loopsAreRefused() {
        val two = listOf(t("t1", "time_now", "t2"), t("t2", "date_today", "t1"))
        assertTrue(TaskGraph.validate(two).orEmpty().contains("loop"))
        val three = listOf(t("t1", "time_now", "t3"), t("t2", "date_today", "t1"), t("t3", "battery_level", "t2"))
        assertTrue(TaskGraph.validate(three).orEmpty().contains("loop"))
    }

    // ---------- running order ----------

    @Test fun priorityClassesByTool() {
        assertEquals(TaskGraph.PClass.NOW, TaskGraph.priorityOf("time_now"))
        assertEquals(TaskGraph.PClass.NOW, TaskGraph.priorityOf("torch_set"))
        assertEquals(TaskGraph.PClass.NOW, TaskGraph.priorityOf("call_dial"))
        assertEquals(TaskGraph.PClass.SOON, TaskGraph.priorityOf("alarm_set"))
        assertEquals(TaskGraph.PClass.SOON, TaskGraph.priorityOf("timer_set"))
    }

    @Test fun nowRunsBeforeSoonThenWrittenOrder() {
        val p = plan(t("t1", "alarm_set"), t("t2", "time_now"), t("t3", "timer_set"), t("t4", "battery_level"))
        assertEquals("t2", TaskGraph.nextReady(p, true)?.id)
        node(p, "t2").state = State.COMPLETED
        assertEquals("t4", TaskGraph.nextReady(p, true)?.id)
        node(p, "t4").state = State.COMPLETED
        assertEquals("t1", TaskGraph.nextReady(p, true)?.id)
        node(p, "t1").state = State.COMPLETED
        assertEquals("t3", TaskGraph.nextReady(p, true)?.id)
    }

    @Test fun dependencyBeatsPriority() {
        // the alarm (SOON) has to wait for the torch (NOW) only because of the link, and the time (NOW) does not wait
        val p = plan(t("t1", "alarm_set", "t2"), t("t2", "torch_set"))
        assertEquals("t2", TaskGraph.nextReady(p, true)?.id)
        node(p, "t2").state = State.RUNNING
        assertNull(TaskGraph.nextReady(p, true))
        node(p, "t2").state = State.COMPLETED
        assertEquals("t1", TaskGraph.nextReady(p, true)?.id)
    }

    @Test fun confirmNodeCanBeHeldBack() {
        val p = plan(t("t1", "call_dial"))
        assertNull(TaskGraph.nextReady(p, false))
        assertEquals("t1", TaskGraph.nextReady(p, true)?.id)
    }

    @Test fun onlyOneConfirmationWaitsAtATime() {
        val p = plan(t("t1", "call_dial"), t("t2", "call_dial"))
        node(p, "t1").state = State.WAITING_CONFIRM
        assertEquals("t1", TaskGraph.waitingNode(p)?.id)
        assertNull(TaskGraph.nextReady(p, TaskGraph.waitingNode(p) == null))
    }

    // ---------- partial failure ----------

    @Test fun failureSkipsOnlyTheDependents() {
        val p = plan(t("t1", "timer_set"), t("t2", "torch_set", "t1"), t("t3", "battery_level", "t2"), t("t4", "time_now"))
        node(p, "t1").state = State.FAILED
        assertEquals(2, TaskGraph.propagateSkips(p))
        assertEquals(State.SKIPPED, node(p, "t2").state)
        assertEquals(State.SKIPPED, node(p, "t3").state)
        assertEquals(State.PENDING, node(p, "t4").state)
        assertTrue(node(p, "t2").reason.orEmpty().contains("t1"))
        assertTrue(node(p, "t3").reason.orEmpty().contains("t2"))
        assertEquals(0, TaskGraph.propagateSkips(p))
    }

    @Test fun waitingNodeBlocksItsDependentsWithoutSkippingThem() {
        val p = plan(t("t1", "call_dial"), t("t2", "torch_set", "t1"), t("t3", "time_now"))
        node(p, "t1").state = State.WAITING_CONFIRM
        assertEquals("t3", TaskGraph.nextReady(p, false)?.id)
        node(p, "t3").state = State.COMPLETED
        assertNull(TaskGraph.nextReady(p, false))
        assertEquals(0, TaskGraph.propagateSkips(p))
        assertEquals(State.PENDING, node(p, "t2").state)
        // the user says Nahi: the dependent is skipped
        node(p, "t1").state = State.CANCELLED
        assertEquals(1, TaskGraph.propagateSkips(p))
        assertEquals(State.SKIPPED, node(p, "t2").state)
    }

    @Test fun countsAndFinished() {
        val p = plan(t("t1", "time_now"), t("t2", "date_today"), t("t3", "battery_level"), t("t4", "torch_set"))
        node(p, "t1").state = State.COMPLETED
        node(p, "t2").state = State.FAILED
        node(p, "t3").state = State.SKIPPED
        node(p, "t4").state = State.WAITING_CONFIRM
        val c = TaskGraph.counts(p)
        assertEquals(1, c.done)
        assertEquals(1, c.failed)
        assertEquals(1, c.skipped)
        assertEquals(1, c.waiting)
        assertFalse(TaskGraph.isFinished(p))
        node(p, "t4").state = State.CANCELLED
        assertTrue(TaskGraph.isFinished(p))
    }

    // ---------- crash recovery ----------

    @Test fun freshPlanRecovery() {
        val now = 1_000_000L
        val p = plan(
            t("t1", "date_today"), t("t2", "timer_set"), t("t3", "torch_set", "t2"), t("t4", "battery_level"),
            now = now - 1_000L
        )
        node(p, "t1").state = State.RUNNING
        node(p, "t2").state = State.RUNNING
        assertTrue(TaskGraph.needsRecovery(p))
        assertTrue(TaskGraph.recover(p, now))
        assertEquals(State.PENDING, node(p, "t1").state)      // idempotent: runs again
        assertEquals(State.FAILED, node(p, "t2").state)       // not idempotent: never run twice
        assertTrue(node(p, "t2").unsure)
        assertEquals(State.SKIPPED, node(p, "t3").state)      // depends on the unsure timer
        assertEquals(State.PENDING, node(p, "t4").state)
    }

    @Test fun stalePlanIsNotRunLate() {
        val now = 1_000_000L
        val p = plan(t("t1", "date_today"), t("t2", "timer_set"), t("t3", "battery_level"), now = now - 5 * 60 * 1000L)
        node(p, "t1").state = State.RUNNING
        node(p, "t2").state = State.RUNNING
        assertTrue(TaskGraph.recover(p, now))
        assertEquals(State.CANCELLED, node(p, "t1").state)
        assertEquals(State.FAILED, node(p, "t2").state)
        assertTrue(node(p, "t2").unsure)
        assertEquals(State.CANCELLED, node(p, "t3").state)
    }

    @Test fun needsRecoveryRules() {
        val done = plan(t("t1", "time_now"))
        node(done, "t1").state = State.COMPLETED
        assertFalse(TaskGraph.needsRecovery(done))

        val waitingOnly = plan(t("t1", "call_dial"), t("t2", "torch_set", "t1"))
        node(waitingOnly, "t1").state = State.WAITING_CONFIRM
        assertFalse(TaskGraph.needsRecovery(waitingOnly))

        val waitingPlusFree = plan(t("t1", "call_dial"), t("t2", "time_now"))
        node(waitingPlusFree, "t1").state = State.WAITING_CONFIRM
        assertTrue(TaskGraph.needsRecovery(waitingPlusFree))

        val neverStarted = plan(t("t1", "time_now"))
        assertTrue(TaskGraph.needsRecovery(neverStarted))
    }

    // ---------- reply ----------

    @Test fun linesAndSummary() {
        val p = plan(t("t1", "time_now"), t("t2", "alarm_set"), t("t3", "torch_set", "t2"))
        val n1 = node(p, "t1")
        n1.state = State.COMPLETED
        n1.line = "Abhi 10:00 AM baje hain."
        n1.readOnly = true
        val n2 = node(p, "t2")
        n2.state = State.FAILED
        n2.line = "Clock app nahi mili."
        val n3 = node(p, "t3")
        n3.state = State.SKIPPED
        n3.reason = "pehle wala kaam (t2) nahi hua"

        val lines = TaskGraph.lines(p)
        assertEquals(3, lines.size)
        assertTrue(lines[0].ok && lines[0].readOnly)
        assertFalse(lines[1].ok)
        assertEquals("Torch chhod diya, kyunki pehle wala kaam (t2) nahi hua.", lines[2].text)
        assertEquals(
            "1 kaam ho gaya, 1 nahi hua, 1 chhod diya. Chaho toh nahi hue kaam dobara bolo.",
            TaskGraph.summaryLine(p)
        )
    }

    @Test fun noSummaryWhenAllWorkedOrSingleTask() {
        val ok = plan(t("t1", "time_now"), t("t2", "date_today"))
        node(ok, "t1").state = State.COMPLETED
        node(ok, "t2").state = State.COMPLETED
        assertNull(TaskGraph.summaryLine(ok))
        val single = plan(t("t1", "timer_set"))
        node(single, "t1").state = State.FAILED
        assertNull(TaskGraph.summaryLine(single))
    }

    // ---------- the kill-process test plan ----------

    @Test fun debugSeedRecoversToTheExpectedStates() {
        val now = 5_000_000L
        val p = TaskGraph.debugSeed("chat", "msg", now)
        assertTrue(p.debug)
        assertTrue(TaskGraph.needsRecovery(p))
        assertTrue(TaskGraph.recover(p, now + 1_000L))
        // what the real engine does next: run every ready node
        while (true) {
            val n = TaskGraph.nextReady(p, true) ?: break
            n.attempts = n.attempts + 1
            n.state = State.COMPLETED
            TaskGraph.propagateSkips(p)
        }
        assertEquals(State.COMPLETED, node(p, "t1").state)
        assertEquals(State.FAILED, node(p, "t2").state)
        assertEquals(State.COMPLETED, node(p, "t3").state)
        assertEquals(State.SKIPPED, node(p, "t4").state)
        assertEquals(State.COMPLETED, node(p, "t5").state)
        assertEquals(2, node(p, "t5").attempts)
        assertTrue(TaskGraph.debugOk(p))
    }

    @Test fun debugCheckFailsOnAWrongOutcome() {
        val p = TaskGraph.debugSeed("chat", "msg", 5_000_000L)
        assertFalse(TaskGraph.debugOk(p)) // nothing recovered yet
    }

    @Test fun describeShowsStatesAndLinks() {
        val p = TaskGraph.debugSeed("chat", "msg", 5_000_000L)
        val text = TaskGraph.describe(p)
        assertTrue(text.contains("TEST"))
        assertTrue(text.contains("t4 torch_set PENDING (after t2)"))
        assertTrue(text.contains("t2 timer_set RUNNING"))
    }
}
