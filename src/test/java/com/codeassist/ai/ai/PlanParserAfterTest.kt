package com.codeassist.ai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanParserAfterTest {
    private val two = "<tool_call>\n{\"name\": \"torch_set\", \"arguments\": {\"on\": true}}\n</tool_call>\n" +
        "<tool_call>\n{\"name\": \"timer_set\", \"arguments\": {\"seconds\": 300, \"after\": \"t1\"}}\n</tool_call>"

    @Test fun afterBecomesADependency() {
        val p = PlanParser.parse(two)
        assertTrue(p is PlanParser.Parsed.Calls)
        val c = p as PlanParser.Parsed.Calls
        assertEquals(2, c.tasks.size)
        assertTrue(c.tasks[0].dependsOn.isEmpty())
        assertEquals(listOf("t1"), c.tasks[1].dependsOn)
        assertFalse(c.tasks[1].args.containsKey("after"))
        assertEquals("300", c.tasks[1].args["seconds"])
    }

    @Test fun unknownAfterIsInvalid() {
        val raw = "<tool_call>\n{\"name\": \"timer_set\", \"arguments\": {\"seconds\": 300, \"after\": \"t5\"}}\n</tool_call>"
        val p = PlanParser.parse(raw)
        assertTrue(p is PlanParser.Parsed.Invalid)
        assertTrue((p as PlanParser.Parsed.Invalid).reason.contains("unknown"))
    }

    @Test fun loopIsInvalid() {
        val raw = "<tool_call>\n{\"name\": \"torch_set\", \"arguments\": {\"on\": true, \"after\": \"t2\"}}\n</tool_call>\n" +
            "<tool_call>\n{\"name\": \"timer_set\", \"arguments\": {\"seconds\": 60, \"after\": \"t1\"}}\n</tool_call>"
        val p = PlanParser.parse(raw)
        assertTrue(p is PlanParser.Parsed.Invalid)
        assertTrue((p as PlanParser.Parsed.Invalid).reason.contains("loop"))
    }
}
