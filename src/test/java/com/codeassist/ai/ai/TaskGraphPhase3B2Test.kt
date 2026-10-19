package com.codeassist.ai.ai

import com.codeassist.ai.ai.TaskGraph.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 3B-2: slow tools, Stop, and what a restart does with a network step. */
class TaskGraphPhase3B2Test {
    private fun t(id: String, tool: String, args: Map<String, String> = emptyMap(), vararg after: String) =
        PlanTask(id, tool, args, after.toList())

    private val city = mapOf("city" to "Pune")

    private fun plan(vararg tasks: PlanTask, now: Long = 1_000_000L): TaskGraph.Plan =
        TaskGraph.build("p1", "chat", "msg", "", tasks.toList(), now)

    private fun node(p: TaskGraph.Plan, id: String): TaskGraph.Node {
        val n = p.node(id)
        assertNotNull("node $id", n)
        return n!!
    }

    // ---------- the tool ----------

    @Test fun weatherToolFacts() {
        val s = ToolSpecs.find("weather_now")!!
        assertEquals("T0", s.tier)
        assertTrue(s.slow && s.readOnly && s.idempotent)
        assertTrue(TaskGraph.isSlow("weather_now"))
        assertFalse(TaskGraph.isSlow("timer_set"))
        assertEquals("Mausam", TaskGraph.label("weather_now"))
        assertEquals(TaskGraph.PClass.NOW, TaskGraph.priorityOf("weather_now"))
    }

    @Test fun onlyWeatherIsSlow() {
        assertEquals(listOf("weather_now"), ToolSpecs.all.filter { it.slow }.map { it.name })
    }

    @Test fun cityRules() {
        for (ok in listOf("Mumbai", "New Delhi", "São Paulo", "St. John's", "Pune-Nagar", "मुंबई")) {
            assertNull(ok, ToolSpecs.validate(t("t1", "weather_now", mapOf("city" to ok))))
        }
        for (bad in listOf("", "M", "123", "Mumbai; DROP", "a".repeat(41), "<b>x</b>")) {
            assertNotNull(bad, ToolSpecs.validate(t("t1", "weather_now", mapOf("city" to bad))))
        }
        assertNotNull(ToolSpecs.validate(t("t1", "weather_now")))
    }

    @Test fun weatherFitsTheSmallModelsPromptBudget() {
        val b = ToolSpecs.qwenToolsBlock()
        assertTrue(b.contains("\"name\":\"weather_now\""))
        assertTrue("block is ${b.length} chars", b.length < 3400)
    }

    // ---------- order ----------

    @Test fun quickStepsRunBeforeTheSlowOne() {
        val p = plan(t("t1", "weather_now", city), t("t2", "timer_set"), t("t3", "time_now"))
        assertEquals("t3", TaskGraph.nextReady(p, true)?.id)
        node(p, "t3").state = State.COMPLETED
        assertEquals("t2", TaskGraph.nextReady(p, true)?.id)
        node(p, "t2").state = State.COMPLETED
        assertEquals("t1", TaskGraph.nextReady(p, true)?.id)
    }

    @Test fun aSlowStepStillWaitsForItsDependency() {
        val p = plan(t("t1", "weather_now", city, "t2"), t("t2", "torch_set"))
        assertEquals("t2", TaskGraph.nextReady(p, true)?.id)
        node(p, "t2").state = State.COMPLETED
        assertEquals("t1", TaskGraph.nextReady(p, true)?.id)
    }

    @Test fun aFailedSlowStepSkipsOnlyItsDependents() {
        val p = plan(t("t1", "weather_now", city), t("t2", "torch_set", emptyMap(), "t1"), t("t3", "timer_set"))
        node(p, "t1").state = State.FAILED
        assertEquals(1, TaskGraph.propagateSkips(p))
        assertEquals(State.SKIPPED, node(p, "t2").state)
        assertEquals(State.PENDING, node(p, "t3").state)
    }

    // ---------- slow step and confirmation ----------

    @Test fun aSlowStepMayNotWaitForAConfirmation() {
        val call = mapOf("number" to "9876543210")
        val direct = listOf(t("t1", "call_dial", call), t("t2", "weather_now", city, "t1"))
        assertTrue(ToolSpecs.check(direct).orEmpty().contains("slow"))
        val viaOther = listOf(t("t1", "call_dial", call), t("t2", "torch_set", mapOf("on" to "true"), "t1"), t("t3", "weather_now", city, "t2"))
        assertNotNull(ToolSpecs.check(viaOther))
    }

    @Test fun aSlowStepNextToAConfirmationIsFine() {
        val call = mapOf("number" to "9876543210")
        assertNull(ToolSpecs.check(listOf(t("t1", "call_dial", call), t("t2", "weather_now", city))))
        // the other way round is fine too: the call waits for the weather, not the weather for the call
        assertNull(ToolSpecs.check(listOf(t("t1", "weather_now", city), t("t2", "call_dial", call, "t1"))))
    }

    // ---------- stop ----------

    private fun busyPlan(): TaskGraph.Plan {
        val p = plan(
            t("t1", "time_now"),
            t("t2", "weather_now", city),
            t("t3", "timer_set", mapOf("seconds" to "60"), "t2"),
            t("t4", "call_dial", mapOf("number" to "9876543210"))
        )
        node(p, "t1").state = State.COMPLETED
        node(p, "t1").line = "Abhi 10:00 AM hua hai."
        node(p, "t2").state = State.RUNNING
        node(p, "t4").state = State.WAITING_CONFIRM
        node(p, "t4").readback = "Dialer mein 9876543210 khol dun?"
        return p
    }

    @Test fun stopCancelsEverythingThatHasNotFinished() {
        val p = busyPlan()
        val n = TaskGraph.stop(p, 5_000_000L)
        assertEquals(3, n)
        assertEquals(State.COMPLETED, node(p, "t1").state)
        for (id in listOf("t2", "t3", "t4")) {
            assertEquals(id, State.CANCELLED, node(p, id).state)
            assertEquals("tumne roka", node(p, id).reason)
        }
        assertTrue(p.stopped)
        assertEquals(5_000_000L, p.interruptedAt)
        assertTrue(TaskGraph.isFinished(p))
        assertNull(TaskGraph.waitingNode(p))
        assertNull(node(p, "t4").readback)
    }

    @Test fun stopKeepsTheFinishedLineAndSaysWhatWasCut() {
        val p = busyPlan()
        TaskGraph.stop(p, 1L)
        val lines = TaskGraph.lines(p)
        assertEquals(4, lines.size)
        assertTrue(lines[0].ok && lines[0].text.contains("10:00"))
        assertTrue(lines[1].text.contains("beech mein roka"))
        assertTrue(lines[2].text.contains("tumne roka"))
        // a finished plan has nothing left to resume
        assertFalse(TaskGraph.needsRecovery(p))
    }

    @Test fun stopOnAFinishedPlanChangesNothing() {
        val p = plan(t("t1", "time_now"))
        node(p, "t1").state = State.COMPLETED
        assertEquals(0, TaskGraph.stop(p, 1L))
        assertEquals(State.COMPLETED, node(p, "t1").state)
    }

    @Test fun describeMarksAStoppedPlan() {
        val p = busyPlan()
        TaskGraph.stop(p, 1L)
        assertTrue(TaskGraph.describe(p).contains("STOP"))
    }

    // ---------- restart ----------

    @Test fun aRunningNetworkStepIsNeverResumed() {
        val p = plan(t("t1", "weather_now", city), t("t2", "torch_set", emptyMap(), "t1"), t("t3", "timer_set", mapOf("seconds" to "60")), now = 1_000_000L)
        node(p, "t1").state = State.RUNNING
        assertTrue(TaskGraph.needsRecovery(p))
        assertTrue(TaskGraph.recover(p, 1_030_000L))
        assertEquals(State.CANCELLED, node(p, "t1").state)
        assertTrue(node(p, "t1").line.orEmpty().contains("Dobara bolo"))
        assertEquals(State.SKIPPED, node(p, "t2").state)
        // the independent quick step of a fresh plan still runs
        assertEquals(State.PENDING, node(p, "t3").state)
        assertEquals("t3", TaskGraph.nextReady(p, true)?.id)
    }

    @Test fun aPendingNetworkStepIsDroppedToo() {
        val p = plan(t("t1", "time_now"), t("t2", "weather_now", city))
        node(p, "t1").state = State.COMPLETED
        TaskGraph.recover(p, 1_030_000L)
        assertEquals(State.CANCELLED, node(p, "t2").state)
        assertFalse(TaskGraph.needsRecovery(p))
    }

    @Test fun quickStepRulesAreUnchanged() {
        val p = plan(t("t1", "timer_set", mapOf("seconds" to "60")), t("t2", "date_today"))
        node(p, "t1").state = State.RUNNING
        node(p, "t2").state = State.RUNNING
        TaskGraph.recover(p, 1_030_000L)
        assertEquals(State.FAILED, node(p, "t1").state)
        assertTrue(node(p, "t1").unsure)
        assertEquals(State.PENDING, node(p, "t2").state)
    }
}
