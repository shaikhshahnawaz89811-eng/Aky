package com.codeassist.ai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanParserTest {
    private fun calls(raw: String): PlanParser.Parsed.Calls {
        val p = PlanParser.parse(raw)
        assertTrue("expected Calls but was " + p.javaClass.simpleName, p is PlanParser.Parsed.Calls)
        return p as PlanParser.Parsed.Calls
    }

    private fun invalid(raw: String): PlanParser.Parsed.Invalid {
        val p = PlanParser.parse(raw)
        assertTrue("expected Invalid but was " + p.javaClass.simpleName, p is PlanParser.Parsed.Invalid)
        return p as PlanParser.Parsed.Invalid
    }

    @Test fun plainAnswerIsText() {
        val p = PlanParser.parse("Kotlin ek JVM language hai.")
        assertTrue(p is PlanParser.Parsed.Text)
        assertEquals("Kotlin ek JVM language hai.", (p as PlanParser.Parsed.Text).text)
    }

    @Test fun oneAlarmCall() {
        val c = calls("<tool_call>\n{\"name\": \"alarm_set\", \"arguments\": {\"hour\": 8, \"minute\": 0}}\n</tool_call>")
        assertEquals(1, c.tasks.size)
        assertEquals("t1", c.tasks[0].id)
        assertEquals("alarm_set", c.tasks[0].tool)
        assertEquals("8", c.tasks[0].args["hour"])
        assertEquals("0", c.tasks[0].args["minute"])
    }

    @Test fun twoCallsAndSpokenWordsAround() {
        val raw = "Theek hai.\n<tool_call>\n{\"name\": \"time_now\", \"arguments\": {}}\n</tool_call>\n" +
            "<tool_call>\n{\"name\": \"torch_set\", \"arguments\": {\"on\": true}}\n</tool_call>"
        val c = calls(raw)
        assertEquals(listOf("time_now", "torch_set"), c.tasks.map { it.tool })
        assertEquals(listOf("t1", "t2"), c.tasks.map { it.id })
        assertEquals("true", c.tasks[1].args["on"])
        assertEquals("Theek hai.", c.ack)
    }

    @Test fun jsonInsideCodeFenceIsAccepted() {
        val c = calls("<tool_call>\n```json\n{\"name\": \"battery_level\", \"arguments\": {}}\n```\n</tool_call>")
        assertEquals("battery_level", c.tasks[0].tool)
    }

    @Test fun missingClosingTagStillParsesWhenJsonIsComplete() {
        val c = calls("<tool_call>\n{\"name\": \"timer_set\", \"arguments\": {\"seconds\": 300}}")
        assertEquals("300", c.tasks[0].args["seconds"])
    }

    @Test fun argumentsWrittenAsAJsonString() {
        val c = calls("<tool_call>{\"name\": \"alarm_set\", \"arguments\": \"{\\\"hour\\\": 21, \\\"minute\\\": 30}\"}</tool_call>")
        assertEquals("21", c.tasks[0].args["hour"])
        assertEquals("30", c.tasks[0].args["minute"])
    }

    @Test fun brokenJsonIsInvalid() {
        val i = invalid("<tool_call>{\"name\": \"alarm_set\", \"arguments\": {hour: }</tool_call>")
        assertTrue(i.reason.contains("JSON"))
    }

    @Test fun unknownToolIsInvalid() {
        val i = invalid("<tool_call>{\"name\": \"send_sms\", \"arguments\": {\"to\": \"1\"}}</tool_call>")
        assertTrue(i.reason.contains("unknown tool"))
    }

    @Test fun missingRequiredArgumentIsInvalid() {
        val i = invalid("<tool_call>{\"name\": \"alarm_set\", \"arguments\": {\"hour\": 8}}</tool_call>")
        assertTrue(i.reason.contains("minute"))
    }

    @Test fun outOfRangeHourIsInvalid() {
        val i = invalid("<tool_call>{\"name\": \"alarm_set\", \"arguments\": {\"hour\": 25, \"minute\": 0}}</tool_call>")
        assertTrue(i.reason.contains("hour"))
    }

    @Test fun tooManyCallsIsInvalid() {
        val one = "<tool_call>{\"name\": \"time_now\", \"arguments\": {}}</tool_call>"
        val i = invalid(one.repeat(5))
        assertTrue(i.reason.contains("too many"))
    }

    @Test fun specialTokensAfterTheCallAreIgnored() {
        val c = calls("<tool_call>{\"name\": \"date_today\", \"arguments\": {}}</tool_call><|im_end|>")
        assertEquals("date_today", c.tasks[0].tool)
    }
}
