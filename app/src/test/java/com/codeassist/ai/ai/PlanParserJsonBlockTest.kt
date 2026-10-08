package com.codeassist.ai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanParserJsonBlockTest {
    @Test fun jsonCodeBlockWithToolCallKeyIsAccepted() {
        val raw = "Aaj ki tareekh:\n\n```json\n{\n\"tool_call\": [{\"name\": \"date_today\", \"arguments\": {} }] }\n}\n```"
        val p = PlanParser.parse(raw)
        assertTrue(p is PlanParser.Parsed.Calls)
        p as PlanParser.Parsed.Calls
        assertEquals("date_today", p.tasks[0].tool)
        assertEquals("", p.ack)
    }

    @Test fun nameStoredUnderAnotherKeyIsFound() {
        val raw = "```json\n{\"tool_call\": [{\"1\": \"date_today\", \"arguments\": {}}] }\n```"
        val p = PlanParser.parse(raw)
        assertTrue(p is PlanParser.Parsed.Calls)
    }

    @Test fun ordinaryCodeBlockIsLeftAlone() {
        val p = PlanParser.parse("```json\n{\"a\": 1}\n```")
        assertTrue(p is PlanParser.Parsed.Text)
    }
}
