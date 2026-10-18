package com.codeassist.ai.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchIntentTest {
    private class Case(val text: String, val prevWeb: Boolean, val forced: Boolean, val expect: List<SearchKind>?)

    /** English and Roman Hinglish, with the negatives that matter most: phone actions, code talk, own-work words, creation verbs. */
    private val cases = listOf(
        Case("gemma 4 ka latest version search karo", false, false, listOf(SearchKind.VALUE)),
        Case("kotlin coroutines ke baare me google karo", false, false, listOf(SearchKind.GENERAL)),
        Case("search for best offline speech recognition models", false, false, listOf(SearchKind.GENERAL)),
        Case("web search kar ke batao tavily pricing", false, false, listOf(SearchKind.VALUE)),
        Case("tavily ka pricing net par dekho", false, false, listOf(SearchKind.VALUE)),
        Case("who is the current ceo of openai", false, false, listOf(SearchKind.GENERAL)),
        Case("latest AI news", false, false, listOf(SearchKind.NEWS)),
        Case("aaj ki khabar batao", false, false, listOf(SearchKind.NEWS)),
        Case("ipl score today", false, false, listOf(SearchKind.NEWS)),
        Case("latest kotlin features", false, false, listOf(SearchKind.NEWS)),
        Case("trending android apps this month", false, false, listOf(SearchKind.NEWS)),
        Case("gemma official website", false, false, listOf(SearchKind.OFFICIAL_LINK)),
        Case("tavily ki official website ka link do", false, false, listOf(SearchKind.OFFICIAL_LINK)),
        Case("mujhe sherpa onnx ka official page batao", false, false, listOf(SearchKind.OFFICIAL_LINK)),
        Case("llama.cpp download link", false, false, listOf(SearchKind.DOWNLOAD_PAGE)),
        Case("gemma 4 e2b gguf download karo", false, false, listOf(SearchKind.DOWNLOAD_PAGE)),
        Case("gemma 4 ka apk kahan se milega", false, false, listOf(SearchKind.DOWNLOAD_PAGE)),
        Case("gemma apk direct link do", false, false, listOf(SearchKind.DIRECT_FILE)),
        Case("give me the direct download link of sherpa-onnx apk", false, false, listOf(SearchKind.DIRECT_FILE)),
        Case("gemma 4 vs qwen 2.5 compare karo", false, false, listOf(SearchKind.COMPARE)),
        Case("gemma ya qwen kaun sa behtar hai", false, false, listOf(SearchKind.COMPARE)),
        Case("python vs java", false, false, listOf(SearchKind.COMPARE)),
        Case("tavily vs brave vs serper", false, false, listOf(SearchKind.COMPARE)),
        Case("kaun sa phone behtar hai samsung ya iphone", false, false, listOf(SearchKind.COMPARE)),
        Case("gemma 4 vs qwen 2.5 and give download links", false, false, listOf(SearchKind.COMPARE, SearchKind.DOWNLOAD_PAGE)),
        Case("gemma ki photo dikhao", false, false, listOf(SearchKind.IMAGE)),
        Case("show me images of kotlin logo", false, false, listOf(SearchKind.IMAGE)),
        Case("kotlin tutorial youtube par dhundo", false, false, listOf(SearchKind.VIDEO)),
        Case("best android tutorial videos suggest karo", false, false, listOf(SearchKind.VIDEO)),
        Case("arxiv par speculative decoding paper dhundo", false, false, listOf(SearchKind.PAPER)),
        Case("research paper on on-device llm dikhao", false, false, listOf(SearchKind.PAPER)),
        Case("android 15 official documentation link", false, false, listOf(SearchKind.OFFICIAL_LINK, SearchKind.PAPER)),
        Case("github pe litert-lm repo dhundo", false, false, listOf(SearchKind.GITHUB)),
        Case("google-ai-edge/litert-lm latest release", false, false, listOf(SearchKind.GITHUB)),
        Case("site:huggingface.co gemma 4 e2b", false, false, listOf(SearchKind.SITE)),
        Case("kotlinlang.org pe coroutines search karo", false, false, listOf(SearchKind.SITE)),
        Case("saari pages explore karo docs.tavily.com", false, false, listOf(SearchKind.SITE_EXPLORE)),
        Case("gemma 4 par detailed report banao web se", false, false, listOf(SearchKind.REPORT)),
        Case("kotlin coroutines ki poori jaankari de do", false, false, null),
        Case("ye news sach hai kya? india ne world cup jeeta", false, false, listOf(SearchKind.FACT_CHECK)),
        Case("is it true that kotlin 2.3 is released", false, false, listOf(SearchKind.FACT_CHECK)),
        Case("iphone 16 ki price kitni hai", false, false, listOf(SearchKind.VALUE)),
        Case("kotlin ka latest version kaunsa hai", false, false, listOf(SearchKind.VALUE)),
        Case("gemma 4 model size kitna hai", false, false, listOf(SearchKind.VALUE)),
        Case("gemma 4 license kya hai", false, false, listOf(SearchKind.VALUE)),
        Case("gemma 4 e2b kab release hoga", false, false, listOf(SearchKind.VALUE)),
        Case("uska price kitna hai", true, false, listOf(SearchKind.FOLLOW_UP)),
        Case("aur batao", true, false, listOf(SearchKind.FOLLOW_UP)),
        Case("wo kab release hua", true, false, listOf(SearchKind.FOLLOW_UP)),
        Case("iska size kitna hai?", true, false, listOf(SearchKind.FOLLOW_UP)),
        Case("thanks bhai", true, false, null),
        Case("ok theek hai", true, false, null),
        Case("search karo", true, false, listOf(SearchKind.FOLLOW_UP)),
        Case("alarm laga do", false, false, null),
        Case("kal subah 6 baje alarm laga do", false, false, null),
        Case("torch on karo", false, false, null),
        Case("mausam kaisa hai", false, false, null),
        Case("whatsapp kholo", false, false, null),
        Case("battery kitni hai", false, false, null),
        Case("time kya hua hai", false, false, null),
        Case("kitne baje uthna hai", false, false, null),
        Case("kya aaj ka time batao", false, false, null),
        Case("hello", false, false, null),
        Case("mera naam shahnawaz hai", false, false, null),
        Case("mujhe python mein array sort karna hai", false, false, null),
        Case("binary search algorithm kaise kaam karta hai", false, false, null),
        Case("search bar kaise banaye", false, false, null),
        Case("ye code mein error kyu aa raha hai", false, false, null),
        Case("kotlin coroutines kya hote hain", false, false, null),
        Case("ek sketch banao: ghar", false, false, null),
        Case("latest", false, false, null),
        Case("search karo", false, false, null),
        Case("download karo", false, false, null),
        Case("ye file download karo", false, false, null),
        Case("image resize kaise kare", false, false, null),
        Case("meri photo dikhao", false, false, null),
        Case("ek photo generate karo billi ki", false, false, null),
        Case("youtube kholo", false, false, null),
        Case("github pe push kaise kare", false, false, null),
        Case("github kaise use kare", false, false, null),
        Case("google docs mein file kaise banate hain", false, false, null),
        Case("website banao mere liye", false, false, null),
        Case("array vs list compare karo", false, false, null),
        Case("yes/no question ka jawab do", false, false, null),
        Case("is app mein ek naya feature add karo", false, false, null),
        Case("latest zip me bug fix karo", false, false, null),
        Case("naya project banao", false, false, null),
        Case("gemma 4 ke baare me batao", false, false, null),
        Case("tum kaun ho", false, false, null),
        Case("is zip me kya kya hai", false, false, null),
        Case("sketch studio ka phase 2 karo", false, false, null),
        Case("gemma 4 kya hai", false, true, listOf(SearchKind.GENERAL)),
        Case("kotlin coroutines", false, true, listOf(SearchKind.GENERAL)),
        Case("alarm laga do", false, true, null),
        Case("hello", false, true, null),
        Case("thanks", false, true, null)
    )

    private fun plan(text: String, prevWeb: Boolean = false, forced: Boolean = false): SearchPlan? =
        SearchIntent.detect(text, prevWeb, forced)

    @Test fun everyPhraseIsClassifiedAsExpected() {
        val wrong = ArrayList<String>()
        for (c in cases) {
            val got = plan(c.text, c.prevWeb, c.forced)?.kinds
            if (got != c.expect) wrong.add("'" + c.text + "' prevWeb=" + c.prevWeb + " forced=" + c.forced + " expected " + c.expect + " got " + got)
        }
        assertEquals("misclassified phrases: " + wrong, 0, wrong.size)
    }

    @Test fun theTableIsBigEnoughAndHasNegatives() {
        assertTrue(cases.size >= 60)
        assertTrue(cases.count { it.expect == null } >= 25)
        // every kind has at least one positive case
        val covered = cases.mapNotNull { it.expect }.flatten().toSet()
        for (k in SearchKind.values()) assertTrue("no case for " + k, k in covered)
    }

    @Test fun noPlanAsksForMoreThanItsCreditCap() {
        for (c in cases) {
            val p = plan(c.text, c.prevWeb, c.forced) ?: continue
            assertTrue(c.text, p.maxCredits <= SearchIntent.MULTI_CREDIT_CAP)
            assertTrue(c.text, p.kinds.size in 1..SearchIntent.MAX_KINDS)
            assertTrue(c.text, p.query.isNotEmpty())
        }
    }

    // ---------- what the plan carries ----------

    @Test fun newsGetsAFreshnessWindow() {
        val a = plan("latest AI news")!!
        assertEquals(Freshness.WEEK, a.freshness)
        assertEquals(2, a.maxCredits)
        assertEquals(2, a.maxRounds)
        assertEquals(listOf("ai"), a.entities)
        assertEquals(Freshness.DAY, plan("aaj ki khabar batao")!!.freshness)
        assertEquals(Freshness.DAY, plan("ipl score today")!!.freshness)
        assertEquals(Freshness.MONTH, plan("trending android apps this month")!!.freshness)
    }

    @Test fun otherKindsDoNotRestrictFreshness() {
        assertEquals(Freshness.ANY, plan("gemma 4 ka latest version search karo")!!.freshness)
        assertEquals(Freshness.ANY, plan("google-ai-edge/litert-lm latest release")!!.freshness)
    }

    @Test fun comparisonNamesTheThingsCompared() {
        val p = plan("gemma 4 vs qwen 2.5 compare karo")!!
        assertEquals(listOf("gemma 4", "qwen 2.5"), p.entities)
        assertEquals(8, p.maxCredits)
        assertEquals(3, p.maxRounds)
        assertEquals(listOf("gemma", "qwen"), plan("gemma ya qwen kaun sa behtar hai")!!.entities)
        assertEquals(listOf("tavily", "brave", "serper"), plan("tavily vs brave vs serper")!!.entities)
    }

    @Test fun twoRequestsInOneMessageShareOneBudget() {
        val p = plan("gemma 4 vs qwen 2.5 and give download links")!!
        assertEquals(listOf(SearchKind.COMPARE, SearchKind.DOWNLOAD_PAGE), p.kinds)
        assertEquals(listOf("gemma 4", "qwen 2.5"), p.entities)
        assertEquals(10, p.maxCredits)
        assertEquals(3, p.maxRounds)
        assertTrue(p.domains.isEmpty())
    }

    @Test fun singleKindPlansCanRestrictDomains() {
        assertEquals(listOf("youtube.com", "vimeo.com"), plan("kotlin tutorial youtube par dhundo")!!.domains)
        assertEquals(listOf("arxiv.org"), plan("arxiv par speculative decoding paper dhundo")!!.domains)
        assertEquals(listOf("github.com"), plan("github pe litert-lm repo dhundo")!!.domains)
        assertEquals(listOf("huggingface.co"), plan("site:huggingface.co gemma 4 e2b")!!.domains)
        assertEquals(listOf("kotlinlang.org"), plan("kotlinlang.org pe coroutines search karo")!!.domains)
        assertTrue(plan("research paper on on-device llm dikhao")!!.domains.isEmpty())
    }

    @Test fun explicitMeansTheUserAskedForTheNetInWords() {
        assertTrue(plan("gemma 4 ka latest version search karo")!!.explicit)
        assertFalse(plan("iphone 16 ki price kitni hai")!!.explicit)
        assertTrue(plan("gemma 4 kya hai", forced = true)!!.explicit)
    }

    @Test fun siteQueryDropsTheOperatorAndEntitiesKeepOnlyTheTopic() {
        val p = plan("site:huggingface.co gemma 4 e2b")!!
        assertEquals("gemma 4 e2b", p.query)
        assertEquals(listOf("gemma 4 e2b"), p.entities)
    }

    @Test fun followUpNeedsAPreviousWebAnswer() {
        assertNull(plan("uska price kitna hai"))
        assertNull(plan("aur batao"))
        assertEquals(listOf(SearchKind.FOLLOW_UP), plan("aur batao", prevWeb = true)!!.kinds)
        assertEquals(1, plan("aur batao", prevWeb = true)!!.maxCredits)
    }

    @Test fun aNewCueAfterAWebAnswerStartsANewSearch() {
        val p = plan("tavily ki official website ka link do", prevWeb = true)
        assertNotNull(p)
        assertEquals(listOf(SearchKind.OFFICIAL_LINK), p!!.kinds)
    }

    // ---------- guards ----------

    @Test fun longPasteAndCodeAreNeverSearched() {
        val long = "latest news ".repeat(80)
        assertNull(plan(long))
        assertNull(plan("```fun main() {}``` latest news"))
        assertNull(plan("line\n".repeat(10) + "latest news"))
    }

    @Test fun theWebToggleStillSearchesALongText() {
        val long = "x ".repeat(400)
        val p = plan(long, forced = true)
        assertNotNull(p)
        assertEquals(listOf(SearchKind.GENERAL), p!!.kinds)
        assertTrue(p.explicit)
        assertTrue(p.query.length <= SearchIntent.MAX_QUERY_CHARS)
    }

    @Test fun aPastedLinkAloneIsNotASearch() {
        assertNull(plan("https://example.com is page ke baare me batao"))
        assertNull(plan("   "))
        assertNull(plan(""))
    }

    @Test fun phoneActionsNeverBecomeSearchesEvenWithTheToggle() {
        for (t in listOf("alarm laga do", "torch on karo", "mausam kaisa hai", "battery kitni hai", "whatsapp kholo")) {
            assertNull(t, plan(t))
            assertNull(t, plan(t, forced = true))
        }
    }

    // ---------- the cleaned query ----------

    @Test fun cleanQueryDropsSearchWordsAndLinks() {
        assertEquals("gemma 4 ka latest version", SearchIntent.cleanQuery("gemma 4 ka latest version search karo"))
        assertEquals("kotlin coroutines ke baare me", SearchIntent.cleanQuery("kotlin coroutines ke baare me google karo"))
        assertEquals("tavily pricing", SearchIntent.cleanQuery("please bhai tavily pricing batao"))
        assertEquals("tavily pricing", SearchIntent.cleanQuery("https://example.com/x tavily pricing"))
        assertEquals("gemma 4 e2b", SearchIntent.cleanQuery("site:huggingface.co gemma 4 e2b"))
        assertEquals("latest AI news", SearchIntent.cleanQuery("latest AI news"))
    }

    @Test fun cleanQueryNeverReturnsNothing() {
        assertEquals("karo", SearchIntent.cleanQuery("karo"))
        assertTrue(SearchIntent.cleanQuery("a ".repeat(500)).length <= SearchIntent.MAX_QUERY_CHARS)
    }

    @Test fun creditAndRoundTablesMatchTheAudit() {
        assertEquals(8, SearchIntent.creditsFor(SearchKind.COMPARE))
        assertEquals(3, SearchIntent.roundsFor(SearchKind.COMPARE))
        assertEquals(12, SearchIntent.creditsFor(SearchKind.REPORT))
        assertEquals(4, SearchIntent.roundsFor(SearchKind.REPORT))
        assertEquals(1, SearchIntent.creditsFor(SearchKind.IMAGE))
        assertEquals(1, SearchIntent.creditsFor(SearchKind.GITHUB))
        assertEquals(3, SearchIntent.creditsFor(SearchKind.DIRECT_FILE))
        assertEquals(6, SearchIntent.creditsFor(SearchKind.SITE_EXPLORE))
    }
}
