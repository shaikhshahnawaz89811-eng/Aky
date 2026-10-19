package com.codeassist.ai.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Net search stage S2: the gate of audit 6.1 steps 3 to 5. The router and QueryGuard are the real ones; the phone is a form. */
class WebGateTest {
    private fun inp(
        text: String,
        prevWeb: Boolean = false,
        attachments: Boolean = false,
        voice: Boolean = false,
        enabled: Boolean = true,
        key: WebGate.KeyState = WebGate.KeyState.OK,
        exhausted: Boolean = false,
        remaining: Int = 30,
        cap: Int = 30,
        online: Boolean = true
    ) = WebGate.Inputs(
        text = text, prevWeb = prevWeb, hasAttachments = attachments, viaVoice = voice, enabled = enabled, key = key,
        planExhausted = exhausted, remainingToday = remaining, dailyCap = cap, online = online
    )

    private fun skip(d: WebGate.Decision): String = (d as WebGate.Decision.Skip).why

    private val news = "latest AI news"
    private val explicit = "kotlin coroutines ke baare me google karo"
    private val soft = "who is the current ceo of openai"

    // ---------- when nothing happens ----------

    @Test fun anOrdinaryMessageIsNotSearched() {
        assertEquals("no_intent", skip(WebGate.decide(inp("hello"))))
        assertEquals("no_intent", skip(WebGate.decide(inp("alarm laga do"))))
        assertEquals("no_intent", skip(WebGate.decide(inp("ye code mein error kyu aa raha hai"))))
    }

    @Test fun theSwitchOffMeansNoSearchAtAll() {
        assertEquals("disabled", skip(WebGate.decide(inp(explicit, enabled = false))))
    }

    @Test fun attachmentsAndVoiceNeverSearch() {
        assertEquals("attachments", skip(WebGate.decide(inp(explicit, attachments = true))))
        assertEquals("voice", skip(WebGate.decide(inp(explicit, voice = true))))
    }

    @Test fun kindsOfLaterStagesGoTheNormalWay() {
        assertEquals("kind", skip(WebGate.decide(inp("gemma 4 vs qwen 2.5 compare karo"))))
        assertEquals("kind", skip(WebGate.decide(inp("uska price kitna hai", prevWeb = true))))
        // a link kind mixed with another kind is a multi-output request: a later stage
        assertEquals("kind", skip(WebGate.decide(inp("android 15 official documentation link"))))
    }

    // ---------- stage S3: link requests ----------

    @Test fun linkRequestsRun() {
        for (text in listOf("gemma official website", "llama.cpp download link", "gemma apk direct link do", "github pe litert-lm repo dhundo")) {
            val d = WebGate.decide(inp(text)) as WebGate.Decision.Run
            assertTrue(text, SearchPlanner.isLink(d.plan))
        }
    }

    @Test fun aLinkRequestWithNoKeyGetsTheLineEvenWithoutASearchWord() {
        val d = WebGate.decide(inp("gemma official website", key = WebGate.KeyState.NONE)) as WebGate.Decision.Say
        assertEquals(WebGate.NO_KEY_TEXT, d.text)
        assertEquals("web_no_key", d.kpi)
    }

    @Test fun aLinkRequestWithAWrongKeyGetsTheLine() {
        val d = WebGate.decide(inp("llama.cpp download link", key = WebGate.KeyState.INVALID)) as WebGate.Decision.Say
        assertEquals("web_bad_key", d.kpi)
    }

    @Test fun aLinkRequestOfflineSaysSoInsteadOfInventingAnAddress() {
        val d = WebGate.decide(inp("gemma official website", online = false)) as WebGate.Decision.Say
        assertEquals(WebGate.OFFLINE_TEXT, d.text)
        assertTrue(d.error)
    }

    @Test fun aLinkRequestWithAUsedUpPlanOrCapSaysSo() {
        assertEquals("web_plan_exhausted", (WebGate.decide(inp("gemma apk direct link do", exhausted = true)) as WebGate.Decision.Say).kpi)
        assertEquals("web_cap_reached", (WebGate.decide(inp("gemma apk direct link do", remaining = 0)) as WebGate.Decision.Say).kpi)
    }

    // ---------- when it runs ----------

    @Test fun newsRuns() {
        val d = WebGate.decide(inp(news)) as WebGate.Decision.Run
        assertEquals(SearchKind.NEWS, d.plan.primary)
        assertEquals("latest AI news", d.query)
        assertFalse(d.numberStripped)
    }

    @Test fun anExplicitGeneralQuestionRuns() {
        val d = WebGate.decide(inp(explicit)) as WebGate.Decision.Run
        assertEquals(listOf(SearchKind.GENERAL), d.plan.kinds)
        assertTrue(d.plan.explicit)
        assertEquals("kotlin coroutines ke baare me", d.query)
    }

    @Test fun aValueQuestionRuns() {
        val d = WebGate.decide(inp("gemma 4 ka latest version search karo")) as WebGate.Decision.Run
        assertEquals(listOf(SearchKind.VALUE), d.plan.kinds)
    }

    @Test fun aSoftCueRunsWhenEverythingIsReady() {
        assertTrue(WebGate.decide(inp(soft)) is WebGate.Decision.Run)
    }

    // ---------- key ----------

    @Test fun noKeyAndAnExplicitAskGivesOneLine() {
        val d = WebGate.decide(inp(explicit, key = WebGate.KeyState.NONE)) as WebGate.Decision.Say
        assertEquals(WebGate.NO_KEY_TEXT, d.text)
        assertEquals("web_no_key", d.kpi)
        assertFalse(d.error)
    }

    @Test fun noKeyAndASoftCueIsJustNormalChat() {
        assertEquals("no_key", skip(WebGate.decide(inp(soft, key = WebGate.KeyState.NONE))))
        assertEquals("no_key", skip(WebGate.decide(inp(news, key = WebGate.KeyState.NONE))))
    }

    @Test fun aWrongKeyAndAnExplicitAskSaysSo() {
        val d = WebGate.decide(inp(explicit, key = WebGate.KeyState.INVALID)) as WebGate.Decision.Say
        assertTrue(d.text.contains("key galat"))
        assertTrue(d.error)
        assertEquals("web_bad_key", d.kpi)
        assertEquals("no_key", skip(WebGate.decide(inp(soft, key = WebGate.KeyState.INVALID))))
    }

    // ---------- plan, daily cap, connection ----------

    @Test fun aUsedUpPlanSaysSoForExplicitAndNewsButNotForASoftCue() {
        assertEquals("web_plan_exhausted", (WebGate.decide(inp(explicit, exhausted = true)) as WebGate.Decision.Say).kpi)
        assertEquals("web_plan_exhausted", (WebGate.decide(inp(news, exhausted = true)) as WebGate.Decision.Say).kpi)
        assertEquals("unavailable", skip(WebGate.decide(inp(soft, exhausted = true))))
    }

    @Test fun theDailyCapSaysSoAndNamesTheLimit() {
        val d = WebGate.decide(inp(explicit, remaining = 0, cap = 15)) as WebGate.Decision.Say
        assertEquals("web_cap_reached", d.kpi)
        assertTrue(d.text.contains("(15)"))
        assertFalse(d.error)
        assertEquals("unavailable", skip(WebGate.decide(inp(soft, remaining = 0))))
    }

    @Test fun exhaustedPlanComesBeforeTheDailyCap() {
        val d = WebGate.decide(inp(explicit, exhausted = true, remaining = 0)) as WebGate.Decision.Say
        assertEquals("web_plan_exhausted", d.kpi)
    }

    @Test fun offlineNewsRefusesToAnswerFromMemory() {
        val d = WebGate.decide(inp(news, online = false)) as WebGate.Decision.Say
        assertTrue(d.text.startsWith(WebGate.OFFLINE_TEXT))
        assertTrue(d.text.contains("yaad se nahi"))
        assertTrue(d.error)
        assertEquals("web_offline", d.kpi)
    }

    @Test fun offlineExplicitGeneralIsOneShortLine() {
        val d = WebGate.decide(inp(explicit, online = false)) as WebGate.Decision.Say
        assertEquals(WebGate.OFFLINE_TEXT, d.text)
    }

    @Test fun offlineSoftCueFallsBackToNormalChat() {
        assertEquals("unavailable", skip(WebGate.decide(inp(soft, online = false))))
    }

    // ---------- private text ----------

    @Test fun aKeyInTheQuestionIsNeverSentAndAnExplicitAskSaysSo() {
        val d = WebGate.decide(inp("kotlin tvly-ABCDEFGHIJKL search karo")) as WebGate.Decision.Say
        assertEquals(QueryGuard.BLOCKED_MESSAGE, d.text)
        assertEquals("web_blocked_query", d.kpi)
    }

    @Test fun aKeyInASoftQuestionJustSkipsTheNet() {
        assertEquals("blocked", skip(WebGate.decide(inp("latest tvly-ABCDEFGHIJKL news"))))
    }

    // ---------- the one-line texts ----------

    @Test fun theLinesNameWhereToGo() {
        assertTrue(WebGate.NO_KEY_TEXT.contains("Settings"))
        assertTrue(WebGate.NO_KEY_TEXT.contains("Tavily"))
        assertTrue(WebGate.EXHAUSTED_TEXT.contains("mahine"))
        assertTrue(WebGate.capText(30).contains("30"))
    }
}
