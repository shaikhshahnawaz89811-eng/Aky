package com.codeassist.ai.search

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class TavilyClientTest {
    private val searchJson = """
        {"query":"gemma","answer":"Tavily says hi",
         "images":[{"url":"https://img.example.com/a.png","description":"a picture"},"https://img.example.com/b.png","http://insecure.example.com/c.png"],
         "results":[
           {"title":"Gemma 4 released","url":"https://example.com/gemma","content":"Gemma 4 was released.","score":0.91,
            "published_date":"2026-10-12","images":["https://img.example.com/r1.png"]},
           {"title":"No score","url":"https://other.org/x","content":"Some text"},
           {"title":"No url","content":"skip me"},
           "not an object"
         ],
         "usage":{"credits":2},"response_time":1.2}
    """.trimIndent()

    private val extractJson = """
        {"results":[{"url":"https://example.com/a","raw_content":"Page text A"},{"url":"https://example.com/b"}],
         "failed_results":[{"url":"https://example.com/c","error":"timeout"},{"error":"no url"}],
         "usage":{"credits":1}}
    """.trimIndent()

    /** A fake transport that remembers what it was asked and answers with a canned reply or an exception. */
    private class Spy(private val reply: HttpReply?, private val error: Exception? = null) {
        var url = ""
        var bearer = ""
        var body = ""
        var timeout = 0
        var calls = 0
        val post: HttpPost = { u, b, bd, t ->
            calls++
            url = u
            bearer = b
            body = bd
            timeout = t
            if (error != null) throw error
            reply!!
        }
    }

    private fun client(spy: Spy, key: String = "tvly-secret-123") = TavilyClient(key, spy.post)

    private fun body(req: SearchRequest): JsonObject = JsonParser.parseString(TavilyClient.buildSearchBody(req)).asJsonObject

    private fun failure(outcome: SearchOutcome<*>): SearchFailure {
        assertTrue("expected a failure", outcome is SearchOutcome.Fail)
        return (outcome as SearchOutcome.Fail).failure
    }

    // ---------- request bodies ----------

    @Test fun defaultSearchBodyIsTheCheapestHonestOne() {
        val b = body(SearchRequest("  gemma 4  "))
        assertEquals("gemma 4", b.get("query").asString)
        assertEquals("basic", b.get("search_depth").asString)
        assertEquals("general", b.get("topic").asString)
        assertEquals(5, b.get("max_results").asInt)
        assertFalse(b.get("include_answer").asBoolean)
        assertTrue(b.get("include_usage").asBoolean)
        for (k in listOf("time_range", "include_domains", "exclude_domains", "country", "include_images", "include_image_descriptions")) {
            assertFalse(k, b.has(k))
        }
    }

    @Test fun everyOptionReachesTheBody() {
        val b = body(
            SearchRequest(
                "q", depth = "advanced", topic = "news", maxResults = 50, freshness = Freshness.WEEK,
                includeDomains = listOf("github.com"), excludeDomains = listOf("spam.com"), country = "india",
                includeImages = true, includeImageDescriptions = true, includeAnswer = true, includeUsage = false
            )
        )
        assertEquals("advanced", b.get("search_depth").asString)
        assertEquals("news", b.get("topic").asString)
        assertEquals(TavilyClient.MAX_RESULTS_CAP, b.get("max_results").asInt)
        assertEquals("week", b.get("time_range").asString)
        assertEquals("github.com", b.get("include_domains").asJsonArray.get(0).asString)
        assertEquals("spam.com", b.get("exclude_domains").asJsonArray.get(0).asString)
        assertFalse("country needs topic general", b.has("country"))
        assertTrue(b.get("include_images").asBoolean)
        assertTrue(b.get("include_image_descriptions").asBoolean)
        assertTrue(b.get("include_answer").asBoolean)
        assertFalse(b.has("include_usage"))
    }

    @Test fun countryIsSentForGeneralSearchOnly() {
        assertEquals("india", body(SearchRequest("q", country = "india")).get("country").asString)
    }

    @Test fun unknownDepthAndTopicFallBackToTheDefaults() {
        val b = body(SearchRequest("q", depth = "bogus", topic = "bogus", maxResults = 0))
        assertEquals("basic", b.get("search_depth").asString)
        assertEquals("general", b.get("topic").asString)
        assertEquals(1, b.get("max_results").asInt)
    }

    @Test fun imageDescriptionsNeedImages() {
        val b = body(SearchRequest("q", includeImages = false, includeImageDescriptions = true))
        assertFalse(b.has("include_images"))
        assertFalse(b.has("include_image_descriptions"))
    }

    @Test fun aLongQueryIsCut() {
        assertEquals(TavilyClient.MAX_QUERY_CHARS, body(SearchRequest("a".repeat(500))).get("query").asString.length)
    }

    @Test fun extractBodyIsCappedAndOnlyAsksForChunksWithAQuery() {
        val urls = (1..6).map { "https://example.com/$it" }
        val b = JsonParser.parseString(
            TavilyClient.buildExtractBody(ExtractRequest(urls, query = "specs", chunksPerSource = 9, advanced = true, timeoutSec = 100))
        ).asJsonObject
        assertEquals(TavilyClient.MAX_EXTRACT_URLS, b.get("urls").asJsonArray.size())
        assertEquals("specs", b.get("query").asString)
        assertEquals(5, b.get("chunks_per_source").asInt)
        assertEquals("advanced", b.get("extract_depth").asString)
        assertEquals("text", b.get("format").asString)
        assertEquals(60, b.get("timeout").asInt)
        assertTrue(b.get("include_usage").asBoolean)

        val plain = JsonParser.parseString(TavilyClient.buildExtractBody(ExtractRequest(urls.take(2)))).asJsonObject
        assertFalse(plain.has("query"))
        assertFalse(plain.has("chunks_per_source"))
        assertEquals("basic", plain.get("extract_depth").asString)
    }

    // ---------- parsing ----------

    @Test fun searchAnswerIsParsed() {
        val r = TavilyClient.parseSearch(searchJson)
        assertNotNull(r)
        assertEquals(2, r!!.results.size)
        val first = r.results[0]
        assertEquals("Gemma 4 released", first.title)
        assertEquals("https://example.com/gemma", first.url)
        assertEquals("Gemma 4 was released.", first.content)
        assertEquals(0.91, first.score, 0.0001)
        assertEquals("2026-10-12", first.publishedDate)
        assertEquals(listOf("https://img.example.com/r1.png"), first.images.map { it.url })
        val second = r.results[1]
        assertEquals(0.0, second.score, 0.0001)
        assertNull(second.publishedDate)
        assertTrue(second.images.isEmpty())
        assertEquals("Tavily says hi", r.answer)
        assertEquals(2, r.credits)
    }

    @Test fun onlyHttpsTopLevelImagesAreKept() {
        val r = TavilyClient.parseSearch(searchJson)!!
        assertEquals(listOf("https://img.example.com/a.png", "https://img.example.com/b.png"), r.images.map { it.url })
        assertEquals("a picture", r.images[0].description)
        assertNull(r.images[1].description)
    }

    @Test fun emptyResultsAreAValidAnswer() {
        val r = TavilyClient.parseSearch("""{"results":[]}""")
        assertNotNull(r)
        assertTrue(r!!.results.isEmpty())
        assertNull(r.answer)
        assertNull(r.credits)
    }

    @Test fun malformedSearchAnswersGiveNull() {
        assertNull(TavilyClient.parseSearch("not json"))
        assertNull(TavilyClient.parseSearch("<html>502</html>"))
        assertNull(TavilyClient.parseSearch("[]"))
        assertNull(TavilyClient.parseSearch("{}"))
        assertNull(TavilyClient.parseSearch("""{"results":"x"}"""))
        assertNull(TavilyClient.parseSearch(""))
    }

    @Test fun creditsAreRoundedUpAndNeverNegative() {
        assertEquals(2, TavilyClient.parseSearch("""{"results":[],"usage":{"credits":1.2}}""")!!.credits)
        assertNull(TavilyClient.parseSearch("""{"results":[],"usage":{"credits":-1}}""")!!.credits)
        assertNull(TavilyClient.parseSearch("""{"results":[],"usage":{"credits":"two"}}""")!!.credits)
    }

    @Test fun extractCanAskForMarkdownSoLinksSurvive() {
        val body = JsonParser.parseString(
            TavilyClient.buildExtractBody(ExtractRequest(listOf("https://example.com/a"), query = "download link", chunksPerSource = 5, format = "markdown"))
        ).asJsonObject
        assertEquals("markdown", body.get("format").asString)
        // anything else is plain text
        val odd = JsonParser.parseString(TavilyClient.buildExtractBody(ExtractRequest(listOf("https://example.com/a"), format = "html"))).asJsonObject
        assertEquals("text", odd.get("format").asString)
    }

    @Test fun extractAnswerHoldsPagesAndFailures() {
        val r = TavilyClient.parseExtract(extractJson)
        assertNotNull(r)
        assertEquals(1, r!!.results.size)
        assertEquals("https://example.com/a", r.results[0].url)
        assertEquals("Page text A", r.results[0].rawContent)
        assertEquals(listOf("https://example.com/c"), r.failedUrls)
        assertEquals(1, r.credits)
    }

    @Test fun extractNeedsOneOfItsTwoLists() {
        assertNotNull(TavilyClient.parseExtract("""{"failed_results":[]}"""))
        assertNotNull(TavilyClient.parseExtract("""{"results":[]}"""))
        assertNull(TavilyClient.parseExtract("""{"foo":1}"""))
        assertNull(TavilyClient.parseExtract("nope"))
    }

    // ---------- calls ----------

    @Test fun aSearchGoesToTheRightPlaceWithTheKeyAsBearer() {
        val spy = Spy(HttpReply(200, searchJson))
        val out = client(spy).search(SearchRequest("gemma"), 5000)
        assertTrue(out is SearchOutcome.Ok)
        assertEquals(2, (out as SearchOutcome.Ok<TavilySearchResponse>).value.results.size)
        assertEquals("https://api.tavily.com/search", spy.url)
        assertEquals("tvly-secret-123", spy.bearer)
        assertEquals(5000, spy.timeout)
        assertTrue(spy.body.contains("\"query\":\"gemma\""))
        assertFalse("the key is only in the header", spy.body.contains("tvly-secret"))
    }

    @Test fun anExtractGoesToExtractWithALongerDefaultTimeout() {
        val spy = Spy(HttpReply(200, extractJson))
        val out = client(spy).extract(ExtractRequest(listOf("https://example.com/a")))
        assertTrue(out is SearchOutcome.Ok)
        assertEquals("https://api.tavily.com/extract", spy.url)
        assertEquals(35000, spy.timeout)
    }

    @Test fun httpStatusesBecomeTypedFailures() {
        val expected = mapOf(
            401 to FailureKind.BAD_KEY, 429 to FailureKind.RATE_LIMITED, 432 to FailureKind.PLAN_LIMIT, 433 to FailureKind.PLAN_LIMIT,
            400 to FailureKind.BAD_REQUEST, 422 to FailureKind.BAD_REQUEST, 404 to FailureKind.BAD_REQUEST,
            500 to FailureKind.SERVER, 503 to FailureKind.SERVER, 302 to FailureKind.SERVER
        )
        for ((code, kind) in expected) {
            val f = failure(client(Spy(HttpReply(code, "{}"))).search(SearchRequest("q")))
            assertEquals("HTTP $code", kind, f.kind)
        }
    }

    @Test fun retryAfterIsKeptOnlyForRateLimits() {
        assertEquals(7, failure(client(Spy(HttpReply(429, "", 7))).search(SearchRequest("q"))).retryAfterSec)
        assertNull(failure(client(Spy(HttpReply(500, "", 9))).search(SearchRequest("q"))).retryAfterSec)
    }

    @Test fun aWrongShapedOkAnswerIsMalformedNeverPartlyUsed() {
        for (text in listOf("<html>", "{}", "[]", "")) {
            assertEquals(text, FailureKind.MALFORMED, failure(client(Spy(HttpReply(200, text))).search(SearchRequest("q"))).kind)
        }
        assertEquals(FailureKind.MALFORMED, failure(client(Spy(HttpReply(200, "{}"))).extract(ExtractRequest(listOf("https://a.com")))).kind)
    }

    @Test fun networkProblemsAreTyped() {
        val cases = listOf<Pair<Exception, FailureKind>>(
            UnknownHostException("h") to FailureKind.OFFLINE,
            ConnectException("c") to FailureKind.OFFLINE,
            SocketTimeoutException("t") to FailureKind.TIMEOUT,
            IOException("x") to FailureKind.NETWORK,
            ResponseTooBig() to FailureKind.MALFORMED
        )
        for ((error, kind) in cases) {
            assertEquals(error.javaClass.simpleName, kind, failure(client(Spy(null, error)).search(SearchRequest("q"))).kind)
        }
    }

    @Test fun noKeyMeansNoCall() {
        val spy = Spy(HttpReply(200, searchJson))
        assertEquals(FailureKind.BAD_KEY, failure(TavilyClient("  ", spy.post).search(SearchRequest("q"))).kind)
        assertEquals(0, spy.calls)
    }

    @Test fun failuresNeverCarryTheKey() {
        val replies = listOf(
            Spy(HttpReply(401, "tvly-secret-123 is wrong")),
            Spy(HttpReply(500, "")),
            Spy(null, IOException("tvly-secret-123")),
            Spy(null, UnknownHostException("tvly-secret-123"))
        )
        for (spy in replies) {
            val f = failure(client(spy).search(SearchRequest("q")))
            assertFalse(f.detail, f.detail.contains("secret"))
            assertFalse(f.userMessage().contains("secret"))
        }
    }

    @Test fun retryableKindsAreTheTransientOnes() {
        val retryable = setOf(FailureKind.RATE_LIMITED, FailureKind.SERVER, FailureKind.TIMEOUT, FailureKind.NETWORK)
        for (k in FailureKind.values()) {
            assertEquals(k.name, k in retryable, SearchFailure(k).retryable)
            assertTrue(k.name, SearchFailure(k).userMessage().isNotEmpty())
        }
    }
}
