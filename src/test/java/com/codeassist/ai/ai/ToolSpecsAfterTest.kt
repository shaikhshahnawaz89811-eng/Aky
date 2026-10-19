package com.codeassist.ai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSpecsAfterTest {
    @Test fun callWithoutAfterIsIndependent() {
        val t = ToolSpecs.task("t1", "timer_set", mapOf("seconds" to "300"))
        assertTrue(t.dependsOn.isEmpty())
        assertEquals("300", t.args["seconds"])
    }

    @Test fun afterIsMovedOutOfTheArguments() {
        val t = ToolSpecs.task("t2", "timer_set", mapOf("seconds" to "300", "after" to "t1"))
        assertEquals(listOf("t1"), t.dependsOn)
        assertFalse(t.args.containsKey("after"))
        assertEquals("300", t.args["seconds"])
        assertNull(ToolSpecs.validate(t))
    }

    @Test fun afterSpellings() {
        assertEquals(listOf("t1"), ToolSpecs.parseAfter("T1"))
        assertEquals(listOf("t1"), ToolSpecs.parseAfter("1"))
        assertEquals(listOf("t1", "t2"), ToolSpecs.parseAfter("t1, t2"))
        assertEquals(listOf("t1", "t2"), ToolSpecs.parseAfter("t1 t2"))
        assertEquals(listOf("t1"), ToolSpecs.parseAfter("t1;t1"))
        assertTrue(ToolSpecs.parseAfter("  ").isEmpty())
        assertTrue(ToolSpecs.parseAfter(null).isEmpty())
        assertEquals(listOf("pehla"), ToolSpecs.parseAfter("pehla")) // kept as written, the graph check refuses it
    }

    @Test fun planCheckRefusesBadLinks() {
        val bad = listOf(ToolSpecs.task("t1", "timer_set", mapOf("seconds" to "60", "after" to "t7")))
        assertTrue(ToolSpecs.check(bad).orEmpty().contains("unknown"))
        val good = listOf(
            ToolSpecs.task("t1", "torch_set", mapOf("on" to "true")),
            ToolSpecs.task("t2", "timer_set", mapOf("seconds" to "300", "after" to "t1"))
        )
        assertNull(ToolSpecs.check(good))
    }

    @Test fun everyToolOffersAnOptionalAfter() {
        for (s in ToolSpecs.all) {
            val p = s.params.firstOrNull { it.name == "after" }
            assertTrue(s.name, p != null && !p.required)
        }
        assertTrue(ToolSpecs.qwenToolsBlock().contains("\"after\""))
    }

    @Test fun idempotentFlags() {
        for (name in listOf("time_now", "date_today", "battery_level", "torch_set", "app_open")) {
            assertTrue(name, ToolSpecs.find(name)?.idempotent == true)
        }
        for (name in listOf("timer_set", "alarm_set", "call_dial")) {
            assertFalse(name, ToolSpecs.find(name)?.idempotent == true)
        }
    }
}
