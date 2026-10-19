package com.codeassist.ai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class EvalCasesTest {
    private fun assetText(): String {
        val a = File("src/main/assets/eval_multi.json")
        val b = File("app/src/main/assets/eval_multi.json")
        return (if (a.exists()) a else b).readText(Charsets.UTF_8)
    }

    private val cases: List<EvalCases.Case> by lazy { EvalCases.parse(assetText()) }

    private fun case(id: String): EvalCases.Case = cases.first { it.id == id }

    /** The calls a correct answer to [c] consists of, numbered t1, t2 ... (arguments are the ones the case checks). */
    private fun perfect(c: EvalCases.Case): List<PlanTask> =
        c.expect.mapIndexed { i, ex ->
            PlanTask("t" + (i + 1), ex.tool, ex.args, ex.after.map { "t$it" })
        }

    // ---------- the shipped set ----------

    @Test fun theShippedSetHasThirtyCasesInThreeGroups() {
        assertEquals(30, cases.size)
        assertEquals(17, cases.count { it.group == "multi" })
        assertEquals(9, cases.count { it.group == "after" })
        assertEquals(4, cases.count { it.group == "clarify" })
        assertEquals(30, cases.map { it.id }.toSet().size)
    }

    @Test fun everyExpectedPlanIsAValidPlan() {
        for (c in cases) {
            if (c.clarify) continue
            val problem = ToolSpecs.check(perfect(c))
            assertNull(c.id + ": " + problem, problem)
        }
    }

    @Test fun afterLinksPointBackwardsOnly() {
        for (c in cases) for ((i, ex) in c.expect.withIndex()) {
            for (n in ex.after) assertTrue(c.id, n >= 1 && n <= i)
        }
    }

    @Test fun everyCaseIsJudgedRightWhenAnsweredPerfectly() {
        for (c in cases) {
            val v = if (c.clarify) EvalCases.judge(c, emptyList(), "Kitne baje?") else EvalCases.judge(c, perfect(c), "")
            assertTrue(c.id + ": " + v.why, v.ok)
        }
    }

    @Test fun theSetCoversTheAppendixCases() {
        assertTrue(cases.any { it.text.startsWith("Kal Mumbai jaana hai") && it.clarify }) // Appendix A #19
        assertTrue(cases.any { c -> c.expect.size >= 3 && c.expect.none { it.after.isNotEmpty() } }) // #2: several independent
        assertTrue(cases.any { c -> c.expect.any { it.after.isNotEmpty() } })
        assertTrue(cases.any { c -> c.expect.any { it.tool == "weather_now" } })
    }

    // ---------- judging ----------

    @Test fun orderOfIndependentCallsDoesNotMatter() {
        val c = case("m01")
        val swapped = listOf(PlanTask("t1", "battery_level", emptyMap()), PlanTask("t2", "time_now", emptyMap()))
        assertTrue(EvalCases.judge(c, swapped, "").ok)
    }

    @Test fun wrongNumberIsWrong() {
        val c = case("m04")
        val wrong = listOf(
            PlanTask("t1", "alarm_set", mapOf("hour" to "9", "minute" to "0", "tomorrow" to "true")),
            PlanTask("t2", "timer_set", mapOf("seconds" to "600"))
        )
        val v = EvalCases.judge(c, wrong, "")
        assertFalse(v.ok)
        assertTrue(v.why, v.why.contains("alarm_set"))
    }

    @Test fun numbersAndBooleansAreCompared_byMeaning() {
        val c = case("m04")
        val ok = listOf(
            PlanTask("t1", "alarm_set", mapOf("hour" to "8.0", "minute" to "00", "tomorrow" to "True")),
            PlanTask("t2", "timer_set", mapOf("seconds" to "600.0"))
        )
        assertTrue(EvalCases.judge(c, ok, "").ok)
    }

    @Test fun textArgumentsIgnoreCaseAndAppNamesMayHaveExtraWords() {
        val app = listOf(PlanTask("t1", "app_open", mapOf("name" to "YouTube app")), PlanTask("t2", "battery_level", emptyMap()))
        assertTrue(EvalCases.judge(case("m13"), app, "").ok)
        val city = listOf(PlanTask("t1", "weather_now", mapOf("city" to "MUMBAI")), PlanTask("t2", "battery_level", emptyMap()))
        assertTrue(EvalCases.judge(case("m10"), city, "").ok)
        val call = listOf(PlanTask("t1", "call_dial", mapOf("number" to "98765 43210")), PlanTask("t2", "time_now", emptyMap()))
        assertTrue(EvalCases.judge(case("m14"), call, "").ok)
    }

    @Test fun aMissingAfterLinkIsWrong() {
        val c = case("d01")
        val noLink = listOf(
            PlanTask("t1", "torch_set", mapOf("on" to "true")),
            PlanTask("t2", "timer_set", mapOf("seconds" to "300"))
        )
        val v = EvalCases.judge(c, noLink, "")
        assertFalse(v.ok)
        assertTrue(v.why, v.why.contains("after"))
    }

    @Test fun anExtraAfterLinkIsWrongToo() {
        val c = case("m01")
        val linked = listOf(PlanTask("t1", "time_now", emptyMap()), PlanTask("t2", "battery_level", emptyMap(), listOf("t1")))
        assertFalse(EvalCases.judge(c, linked, "").ok)
    }

    @Test fun theLinkMustPointAtTheRightStepEvenWhenOrderIsSwapped() {
        // d01: torch first, timer after it. The brain wrote the timer first and made the torch wait for it: wrong direction.
        val c = case("d01")
        val reversed = listOf(
            PlanTask("t1", "timer_set", mapOf("seconds" to "300")),
            PlanTask("t2", "torch_set", mapOf("on" to "true"), listOf("t1"))
        )
        assertFalse(EvalCases.judge(c, reversed, "").ok)
        val rightButSwapped = listOf(
            PlanTask("t1", "timer_set", mapOf("seconds" to "300"), listOf("t2")),
            PlanTask("t2", "torch_set", mapOf("on" to "true"))
        )
        assertTrue(EvalCases.judge(c, rightButSwapped, "").ok)
    }

    @Test fun missingOrExtraCallsAreWrong() {
        val c = case("m07")
        assertFalse(EvalCases.judge(c, perfect(c).take(2), "").ok)
        assertFalse(EvalCases.judge(c, perfect(c) + PlanTask("t4", "time_now", emptyMap()), "").ok)
        assertFalse(EvalCases.judge(c, emptyList(), "ok").ok)
    }

    @Test fun anInvalidPlanIsWrongLikeInTheApp() {
        val c = case("m01")
        val bad = listOf(PlanTask("t1", "time_now", emptyMap(), listOf("t9")), PlanTask("t2", "battery_level", emptyMap()))
        val v = EvalCases.judge(c, bad, "")
        assertFalse(v.ok)
        assertTrue(v.why, v.why.contains("refused"))
    }

    @Test fun clarifyCasesWantAQuestionAndNoTool() {
        val c = case("c01")
        assertTrue(EvalCases.judge(c, emptyList(), "Petrol 8 se pehle bharwana hai ya raaste mein?").ok)
        assertFalse(EvalCases.judge(c, emptyList(), "Theek hai, ho jayega.").ok)
        assertFalse(EvalCases.judge(c, listOf(PlanTask("t1", "alarm_set", mapOf("hour" to "8", "minute" to "0"))), "Kya?").ok)
    }

    // ---------- totals and exit rule ----------

    private fun verdicts(good: Int, total: Int): List<EvalCases.Verdict> =
        (0 until total).map { if (it < good) EvalCases.Verdict(true, "") else EvalCases.Verdict(false, "x") }

    @Test fun exitRuleIs85PercentOfAFullSet() {
        val t26 = EvalCases.totals(cases, verdicts(26, 30))
        assertEquals(87, t26.percent)
        assertTrue(EvalCases.exitReached(t26))
        val t25 = EvalCases.totals(cases, verdicts(25, 30))
        assertFalse(EvalCases.exitReached(t25))
        // 85 % exactly (25.5 of 30 is not possible; 17 of 20 would be): a part-run set never counts
        val part = EvalCases.totals(cases.take(20), verdicts(20, 20))
        assertFalse(EvalCases.exitReached(part))
    }

    @Test fun groupsAreCounted() {
        val t = EvalCases.totals(cases, verdicts(30, 30))
        assertEquals(Pair(17, 17), t.byGroup["multi"])
        assertEquals(Pair(4, 4), t.byGroup["clarify"])
    }

    // ---------- bad files ----------

    @Test fun malformedFilesAreRefusedWithAReason() {
        for (bad in listOf(
            "not json",
            "[]",
            """{"cases":[{"id":"a","text":"x","expect":[{"tool":"nope"}]}]}""",
            """{"cases":[{"id":"a","text":"x","expect":[]}]}""",
            """{"cases":[{"id":"a","text":"x","clarify":true,"expect":[{"tool":"time_now"}]}]}""",
            """{"cases":[{"id":"a","text":"x","expect":[{"tool":"time_now"}]},{"id":"a","text":"y","expect":[{"tool":"time_now"}]}]}"""
        )) {
            try {
                EvalCases.parse(bad)
                fail("accepted: $bad")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message.orEmpty().isNotEmpty())
            }
        }
    }
}
