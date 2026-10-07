package com.codeassist.ai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSpecsTest {
    private fun task(tool: String, vararg args: Pair<String, String>) = PlanTask("t1", tool, mapOf(*args))

    @Test fun everyToolNameIsSafeForBothBrains() {
        for (s in ToolSpecs.all) assertTrue(s.name, Regex("^[a-z_]+$").matches(s.name))
    }

    @Test fun tiersComeFromTheTable() {
        assertEquals("T0", ToolSpecs.find("time_now")!!.tier)
        assertEquals("T1", ToolSpecs.find("alarm_set")!!.tier)
        assertEquals("T2", ToolSpecs.find("call_dial")!!.tier)
        assertNull(ToolSpecs.find("pay_upi"))
    }

    @Test fun validAlarmPasses() {
        assertNull(ToolSpecs.validate(task("alarm_set", "hour" to "20", "minute" to "15")))
        assertNull(ToolSpecs.validate(task("alarm_set", "hour" to "8.0", "minute" to "0", "tomorrow" to "true")))
    }

    @Test fun alarmRangesAreChecked() {
        assertNotNull(ToolSpecs.validate(task("alarm_set", "hour" to "24", "minute" to "0")))
        assertNotNull(ToolSpecs.validate(task("alarm_set", "hour" to "8", "minute" to "60")))
        assertNotNull(ToolSpecs.validate(task("alarm_set", "hour" to "eight", "minute" to "0")))
    }

    @Test fun timerRange() {
        assertNull(ToolSpecs.validate(task("timer_set", "seconds" to "600")))
        assertNotNull(ToolSpecs.validate(task("timer_set", "seconds" to "0")))
        assertNotNull(ToolSpecs.validate(task("timer_set", "seconds" to "90000")))
    }

    @Test fun torchNeedsABoolean() {
        assertNull(ToolSpecs.validate(task("torch_set", "on" to "false")))
        assertNotNull(ToolSpecs.validate(task("torch_set", "on" to "maybe")))
        assertNotNull(ToolSpecs.validate(task("torch_set")))
    }

    @Test fun dialNumberMustLookLikeANumber() {
        assertNull(ToolSpecs.validate(task("call_dial", "number" to "+91 98765-43210")))
        assertNotNull(ToolSpecs.validate(task("call_dial", "number" to "mummy")))
        assertNotNull(ToolSpecs.validate(task("call_dial", "number" to "123")))
    }

    @Test fun cleanNumberKeepsPlusAndDigits() {
        assertEquals("+919876543210", ToolSpecs.cleanNumber("+91 98765-43210"))
    }

    @Test fun planLimitsAreEnforced() {
        assertNotNull(ToolSpecs.check(emptyList()))
        val five = (1..5).map { PlanTask("t$it", "time_now", emptyMap()) }
        assertNotNull(ToolSpecs.check(five))
        assertNull(ToolSpecs.check(five.take(4)))
    }

    @Test fun qwenBlockHasTheTemplateTagsAndEveryTool() {
        val b = ToolSpecs.qwenToolsBlock()
        assertTrue(b.contains("<tools>") && b.contains("</tools>"))
        assertTrue(b.contains("<tool_call>") && b.contains("</tool_call>"))
        for (s in ToolSpecs.all) assertTrue(s.name, b.contains("\"name\":\"" + s.name + "\""))
    }

    @Test fun qwenBlockStaysSmall() {
        // the block is paid for out of a 4096-token window: growing it is a decision, not an accident
        assertTrue("block is " + ToolSpecs.qwenToolsBlock().length + " chars", ToolSpecs.qwenToolsBlock().length < 3400)
    }
}
