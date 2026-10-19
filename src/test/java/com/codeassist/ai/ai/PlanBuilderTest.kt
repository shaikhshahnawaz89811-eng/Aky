package com.codeassist.ai.ai

import com.codeassist.ai.data.Message
import com.codeassist.ai.data.Role
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T3 (test chat 13 Oct 2026, mistake 4): the plan builder. The model is a fake lambda, so this runs on the JVM. */
class PlanBuilderTest {
    private fun user(t: String) = Message(role = Role.USER, text = t)
    private fun ai(t: String, note: String? = null) = Message(role = Role.AI, text = t, note = note)

    private val chat = listOf(
        user("coding assistant app banana hai jisme AI code likhe"),
        user("light theme chahiye, bada input box aur neeche output area"),
        user("settings me model size aur temperature ho, history bhi")
    )
    private val askPlan = "Ek prompt do, poora plan, dusra AI coding kar de"

    // ---------- who asks ----------

    @Test fun theReportRequestIsAPlanRequest() {
        assertTrue(PlanBuilder.isRequest(askPlan, chat))
        assertTrue(PlanBuilder.isRequest(askPlan, emptyList()))
        assertTrue(PlanBuilder.isRequest("mere app ka poora plan banao", emptyList()))
    }

    @Test fun otherPlansAndChatterAreNot() {
        val no = listOf(
            "Goa trip ka 3 din ka detailed plan banao", "kal ka plan batao", "plan banao", "mera plan kya hai",
            "prompt kya hota hai batao", "ek chhota sa plan likh do", "torch on karo"
        )
        for (s in no) {
            assertFalse(s, PlanBuilder.isRequest(s, emptyList()))
            assertFalse(s, PlanBuilder.isRequest(s, chat))
        }
    }

    @Test fun aLanguageOnlyAskNeedsSoftwareInTheChat() {
        assertTrue(PlanBuilder.isRequest("English latter me Hindi bhasha me plan do", chat))
        assertFalse(PlanBuilder.isRequest("English latter me Hindi bhasha me plan do", emptyList()))
    }

    @Test fun anotherLanguageAfterABuilderPlanIsARedoOfTheEarlierRequest() {
        val h = chat + user(askPlan) + ai("plan ...", PlanBuilder.NOTE + " · Gemma 4 E2B")
        assertEquals(askPlan, PlanBuilder.redoSource("English latter rakho or Hindi bhasha me do ye", h))
        val s = PlanBuilder.source("English latter rakho or Hindi bhasha me do ye", h)
        assertNotNull(s)
        assertTrue(s!!.redo)
        assertEquals(askPlan, s.request)
    }

    @Test fun noRedoWithoutABuilderPlanOrForAPlainLanguageChoice() {
        val h = chat + user(askPlan) + ai("plan ...", PlanBuilder.NOTE + " · Gemma 4 E2B")
        assertNull(PlanBuilder.redoSource("Hinglish me batao", h))
        assertNull(PlanBuilder.redoSource("hindi me ye do", chat + ai("kuch", "Gemma 4 E2B")))
        assertNull(PlanBuilder.redoSource("hindi me ye do", emptyList()))
    }

    // ---------- language ----------

    @Test fun englishLettersAndHindiLanguageMeanRomanHinglish() {
        assertEquals(LocalMemory.LANG_HINGLISH, PlanBuilder.language("English latter rakho or Hindi bhasha me do ye", null))
        assertEquals(LocalMemory.LANG_HINGLISH, PlanBuilder.language("hinglish me do", LocalMemory.LANG_ENGLISH))
        assertEquals(LocalMemory.LANG_ENGLISH, PlanBuilder.language("English me do", LocalMemory.LANG_HINGLISH))
        assertEquals(LocalMemory.LANG_ENGLISH, PlanBuilder.language("Hindi nahi English me do", null))
    }

    @Test fun withoutALanguageWordTheSavedChoiceWins() {
        assertEquals(LocalMemory.LANG_HINGLISH, PlanBuilder.language("write a full plan for my app", LocalMemory.LANG_HINGLISH))
        assertEquals(LocalMemory.LANG_ENGLISH, PlanBuilder.language("write a full plan for my app", null))
    }

    // ---------- the user's own lines ----------

    @Test fun decisionsAreTheUsersLinesWithoutNoise() {
        val h = chat + listOf(
            user("torch on karo"), user("ok"), user("kaise ho?"), user(askPlan),
            user("English latter rakho or Hindi bhasha me do ye"),
            user("training nahin dena hai, sirf skills deni hain")
        )
        val d = PlanBuilder.decisions(h, "")
        assertEquals(4, d.size)
        assertTrue(d[0].startsWith("coding assistant app"))
        assertTrue(d.any { it.contains("light theme") })
        assertTrue(d.any { it.contains("training nahin") })
        assertFalse(d.any { it.contains("torch") || it.contains("Hindi bhasha") })
    }

    @Test fun theRequestItselfAndErrorMessagesAreSkipped() {
        val h = listOf(user("mere app ka poora plan banao"), user("light theme aur bada input box chahiye")) +
            Message(role = Role.USER, text = "dark theme sirf raat ko chalega", state = "error")
        assertEquals(1, PlanBuilder.decisions(h, "mere app ka poora plan banao").size)
    }

    @Test fun tooManyLinesKeepTheFirstAndTheNewest() {
        val lines = (1..30).map { "decision number $it about the app screen" }
        val fit = PlanBuilder.fit(lines)
        assertTrue(fit.size <= 14)
        assertEquals(lines[0], fit[0])
        assertEquals(lines[2], fit[2])
        assertEquals(lines.last(), fit.last())
    }

    @Test fun negativeLinesAreFound() {
        val n = PlanBuilder.negatives(listOf("light theme chahiye", "training nahin dena hai", "bina internet ke chale"))
        assertEquals(2, n.size)
    }

    // ---------- cleaning and checks ----------

    private fun step(key: String) = PlanBuilder.steps.first { it.key == key }

    @Test fun tidyKeepsBulletsAndOnePassLine() {
        val raw = "Sure, here is Phase 4:\n1. Core logic likho\n* Optimization karo\n- Cache banao\n**Pass:** sab tests hare\nHope this helps!"
        assertEquals("- Core logic likho\n- Optimization karo\n- Cache banao\nPASS: sab tests hare", PlanBuilder.tidy(raw, step("phase4")))
    }

    @Test fun tidyTurnsProseIntoOneParagraph() {
        val raw = "Here is the goal:\n## Goal\nEk coding assistant app.\nDusra AI isko banayega."
        assertEquals("Ek coding assistant app. Dusra AI isko banayega.", PlanBuilder.tidy(raw, step("goal")))
    }

    @Test fun knownWrongTechClaimsAreCaught() {
        assertNotNull(PlanBuilder.techProblem("GGUF model ko TFLite me convert karo"))
        assertNotNull(PlanBuilder.techProblem("Use Llama 3 8B, a small and efficient model"))
        assertNotNull(PlanBuilder.techProblem("Syntax highlight: Code Typos library"))
        assertNull(PlanBuilder.techProblem("Run GGUF models with llama.cpp"))
        assertNull(PlanBuilder.techProblem("Gemma 2B small model on LiteRT-LM"))
        val c = PlanBuilder.dropTechLines("- Room database\n- GGUF ko TFLite me convert karo\n- Kotlin")
        assertEquals(1, c.dropped)
        assertEquals("- Room database\n- Kotlin", c.text)
    }

    @Test fun chatReferencesAreCaught() {
        assertTrue(PlanBuilder.mentionsChat("UI as discussed earlier"))
        assertTrue(PlanBuilder.mentionsChat("UI jaisa pehle bataya tha"))
        assertFalse(PlanBuilder.mentionsChat("Light theme use karo"))
    }

    @Test fun aPhaseNeedsAPassLine() {
        val ok = "- Project banao aur Gradle set karo\n- Folder ka dhaancha banao\nPASS: app bina error ke chalu ho jaye"
        assertTrue(PlanBuilder.stepProblems(step("phase1"), ok, LocalMemory.LANG_HINGLISH).isEmpty())
        val no = PlanBuilder.stepProblems(step("phase1"), "- Project banao aur Gradle set karo bhai", LocalMemory.LANG_HINGLISH)
        assertTrue("no-pass" in no)
        assertTrue("empty" in PlanBuilder.stepProblems(step("goal"), "", LocalMemory.LANG_HINGLISH))
    }

    // ---------- the whole plan ----------

    private val hing = PlanBuilder.Request(
        askPlan,
        listOf("coding assistant app banana hai jisme AI code likhe", "training nahin dena hai, sirf skills deni hain"),
        LocalMemory.LANG_HINGLISH
    )

    private fun goodAnswer(user: String): String = when {
        user.contains("Write 2 or 3 short sentences") ->
            "Ye ek coding assistant app hai jo user ko code likhne me madad karta hai. Dusra AI isko banayega."
        user.contains("List the tech stack") ->
            "- Language: Kotlin\n- UI: Android Views\n- Model: Gemma 4 E2B on LiteRT-LM\n- Storage: local files"
        user.contains("List the screens") ->
            "- Light theme rakho\n- Bada input box neeche rakho\n- Output area upar dikhao\n- Settings me model size aur temperature do"
        else -> {
            val n = Regex("Write ONLY Phase (\\d)").find(user)!!.groupValues[1]
            "- Phase $n ka pehla kaam karo aur uska code likho\n- Dusra kaam banao aur jodo\nPASS: phase $n ka test bina error ke chale"
        }
    }

    /** The trailing lambda is the fake model: (system, user) -> raw answer. */
    private fun runBuild(cancelled: () -> Boolean = { false }, gen: suspend (String, String) -> String): PlanBuilder.Result =
        runBlocking { PlanBuilder.build(hing, gen, { _, _, _, _ -> }, cancelled) }

    @Test fun aGoodRunHasAllFivePhasesAndTheUsersOwnLines() {
        val r = runBuild { _, u -> goodAnswer(u) }
        assertTrue(r.problems.toString(), r.ok)
        assertEquals(5, r.phases)
        assertEquals(8, r.calls)
        for (n in 1..5) assertTrue(r.text.contains("### Phase $n - "))
        assertTrue(r.text.contains("- coding assistant app banana hai jisme AI code likhe"))
        assertTrue(r.text.contains("- training nahin dena hai, sirf skills deni hain"))
        assertTrue(r.text.contains("Pass test: phase 5 ka test"))
        assertTrue(r.text.contains("Is plan me 5 phase hain"))
        assertFalse(PlanBuilder.mentionsChat(r.text.substringAfter("## 3. Stack")))
    }

    @Test fun englishPlansHaveEnglishHeadings() {
        val en = PlanBuilder.Request(askPlan, emptyList(), LocalMemory.LANG_ENGLISH)
        val text = PlanBuilder.assemble(en, emptyMap(), true)
        assertTrue(text.contains("### Phase 4 - Core logic and optimization"))
        assertTrue(text.contains("### Phase 5 - Testing and deployment"))
        assertTrue(text.contains("This plan has 5 phases"))
    }

    @Test fun aChatReferenceIsRetriedOnceAndThenAccepted() {
        var goalCalls = 0
        val r = runBuild { _, u ->
            if (u.contains("Write 2 or 3 short sentences")) {
                goalCalls++
                if (goalCalls == 1) "UI as discussed earlier, ye ek coding assistant app hai jo madad karta hai." else goodAnswer(u)
            } else goodAnswer(u)
        }
        assertEquals(2, goalCalls)
        assertEquals(1, r.retries)
        assertTrue(r.ok)
    }

    @Test fun aWrongTechLineIsDroppedAndCounted() {
        val r = runBuild { _, u ->
            if (u.contains("List the tech stack")) {
                "- Language: Kotlin\n- Convert GGUF to TFLite for the model\n- UI: Android Views\n- Storage: local files"
            } else goodAnswer(u)
        }
        assertEquals(1, r.techDropped)
        assertFalse(r.text.contains("TFLite"))
    }

    @Test fun aBoxTheModelCannotWriteIsReportedNeverHidden() {
        val r = runBuild { _, u -> if (u.contains("Write ONLY Phase 4")) "" else goodAnswer(u) }
        assertFalse(r.ok)
        assertEquals(5, r.phases)
        assertTrue(r.problems.any { it.contains("Phase 4") })
        assertTrue(r.text.contains("Phase 4 khaali"))
        assertTrue(r.text.contains("adhura"))
    }

    @Test fun aCancelledBuildIsMarkedIncomplete() {
        var calls = 0
        val r = runBuild({ calls >= 3 }) { _, u ->
            calls++
            goodAnswer(u)
        }
        assertFalse(r.ok)
        assertTrue(r.problems.contains("roka gaya"))
        assertTrue(r.phases < 5)
    }

    @Test fun theClosingLineNamesTheRealPhaseCountNotAClaim() {
        val text = PlanBuilder.assemble(hing, emptyMap(), true)
        assertFalse(text.lowercase().contains("covers everything"))
        assertEquals(5, Regex("(?m)^### Phase \\d").findAll(text).count())
    }
}
