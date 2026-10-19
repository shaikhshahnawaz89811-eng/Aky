package com.codeassist.ai.search

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/** What a POST gave back. [retryAfterSec] is the Retry-After header when it was a number of seconds. */
class HttpReply(val code: Int, val body: String, val retryAfterSec: Int? = null)

/** The HTTP part, injectable so the tests run with canned replies. Throws IOException on network trouble. */
typealias HttpPost = (url: String, bearer: String, jsonBody: String, timeoutMs: Int) -> HttpReply

/** A response bigger than [TavilyClient.MAX_RESPONSE_CHARS]. Counts as a malformed answer, not as a network error. */
class ResponseTooBig : IOException("response too big")

/** One /search call (audit section 2.1). Only the options this app uses. */
class SearchRequest(
    val query: String,
    /** "basic" (1 credit) or "advanced" (2 credits). */
    val depth: String = "basic",
    /** "general", "news" or "finance". */
    val topic: String = "general",
    val maxResults: Int = 5,
    val freshness: Freshness = Freshness.ANY,
    val includeDomains: List<String> = emptyList(),
    val excludeDomains: List<String> = emptyList(),
    /** A boost for results from this country; Tavily accepts it only with topic "general". */
    val country: String? = null,
    val includeImages: Boolean = false,
    val includeImageDescriptions: Boolean = false,
    /** Tavily's own LLM answer. Off: it is an external AI claim, not a citation. */
    val includeAnswer: Boolean = false,
    /** Ask for `usage.credits` so the budget can use the real number. */
    val includeUsage: Boolean = true
)

/** One /extract call: the text of 1..20 pages, optionally reranked by [query]. */
class ExtractRequest(
    val urls: List<String>,
    val query: String? = null,
    val chunksPerSource: Int = 3,
    val advanced: Boolean = false,
    /** Seconds Tavily may spend (1..60). */
    val timeoutSec: Int = 15,
    /** "text" (default) or "markdown". Markdown keeps `[name](address)` links: the link finder of stage S3 needs them. */
    val format: String = "text"
)

/**
 * Tavily over plain HTTPS (audit section 2.1): `POST https://api.tavily.com/search` and `/extract`, key as a Bearer token.
 *
 * Pure JVM (java.net + Gson), like WeatherClient. The key is held only for the call; it is never put into an error, a log line
 * or a result. Every answer is parsed defensively: a body that is not the expected shape is a typed MALFORMED failure and is
 * never partly used. Blocking: call it through StepTimeout.call (a blocking socket read cannot be interrupted by a coroutine).
 */
class TavilyClient(private val apiKey: String, private val post: HttpPost = realPost) {

    fun search(req: SearchRequest, timeoutMs: Int = 12000): SearchOutcome<TavilySearchResponse> =
        call("/search", buildSearchBody(req), timeoutMs) { parseSearch(it) }

    fun extract(req: ExtractRequest, timeoutMs: Int = 35000): SearchOutcome<TavilyExtractResponse> =
        call("/extract", buildExtractBody(req), timeoutMs) { parseExtract(it) }

    private fun <T> call(path: String, body: String, timeoutMs: Int, parse: (String) -> T?): SearchOutcome<T> {
        if (apiKey.isBlank()) return SearchOutcome.Fail(SearchFailure(FailureKind.BAD_KEY, null, "no key"))
        val reply = try {
            post(BASE_URL + path, apiKey, body, timeoutMs)
        } catch (e: UnknownHostException) {
            return SearchOutcome.Fail(SearchFailure(FailureKind.OFFLINE, null, "unknown host"))
        } catch (e: ConnectException) {
            return SearchOutcome.Fail(SearchFailure(FailureKind.OFFLINE, null, "connect failed"))
        } catch (e: SocketTimeoutException) {
            return SearchOutcome.Fail(SearchFailure(FailureKind.TIMEOUT, null, "timeout"))
        } catch (e: ResponseTooBig) {
            return SearchOutcome.Fail(SearchFailure(FailureKind.MALFORMED, null, "response too big"))
        } catch (e: IOException) {
            return SearchOutcome.Fail(SearchFailure(FailureKind.NETWORK, null, "io error"))
        }
        return interpret(reply, parse)
    }

    private fun <T> interpret(reply: HttpReply, parse: (String) -> T?): SearchOutcome<T> {
        val c = reply.code
        if (c == 200) {
            val value = try {
                parse(reply.body)
            } catch (_: Exception) {
                null
            }
            return if (value != null) SearchOutcome.Ok(value)
            else SearchOutcome.Fail(SearchFailure(FailureKind.MALFORMED, null, "unexpected body"))
        }
        val kind = when {
            c == 401 -> FailureKind.BAD_KEY
            c == 429 -> FailureKind.RATE_LIMITED
            c == 432 || c == 433 -> FailureKind.PLAN_LIMIT
            c in 500..599 -> FailureKind.SERVER
            c in 400..499 -> FailureKind.BAD_REQUEST
            else -> FailureKind.SERVER
        }
        val retry = if (kind == FailureKind.RATE_LIMITED) reply.retryAfterSec else null
        return SearchOutcome.Fail(SearchFailure(kind, retry, "HTTP $c"))
    }

    companion object {
        const val BASE_URL = "https://api.tavily.com"
        const val MAX_RESPONSE_CHARS = 64 * 1024

        /** Longest query we send. This is this app's own limit, not a number from the Tavily docs. */
        const val MAX_QUERY_CHARS = 400
        const val MAX_RESULTS_CAP = 10
        const val MAX_EXTRACT_URLS = 5

        // ---------- request bodies ----------

        fun buildSearchBody(req: SearchRequest): String {
            val o = JsonObject()
            o.addProperty("query", req.query.trim().take(MAX_QUERY_CHARS))
            o.addProperty("search_depth", if (req.depth == "advanced") "advanced" else "basic")
            val topic = if (req.topic == "news" || req.topic == "finance") req.topic else "general"
            o.addProperty("topic", topic)
            o.addProperty("max_results", req.maxResults.coerceIn(1, MAX_RESULTS_CAP))
            val range = req.freshness.timeRange
            if (range != null) o.addProperty("time_range", range)
            if (req.includeDomains.isNotEmpty()) o.add("include_domains", strings(req.includeDomains))
            if (req.excludeDomains.isNotEmpty()) o.add("exclude_domains", strings(req.excludeDomains))
            val country = req.country
            if (country != null && topic == "general") o.addProperty("country", country)
            if (req.includeImages) {
                o.addProperty("include_images", true)
                if (req.includeImageDescriptions) o.addProperty("include_image_descriptions", true)
            }
            o.addProperty("include_answer", req.includeAnswer)
            if (req.includeUsage) o.addProperty("include_usage", true)
            return o.toString()
        }

        fun buildExtractBody(req: ExtractRequest): String {
            val o = JsonObject()
            o.add("urls", strings(req.urls.take(MAX_EXTRACT_URLS)))
            val q = req.query?.trim().orEmpty()
            if (q.isNotEmpty()) {
                o.addProperty("query", q.take(MAX_QUERY_CHARS))
                o.addProperty("chunks_per_source", req.chunksPerSource.coerceIn(1, 5))
            }
            o.addProperty("extract_depth", if (req.advanced) "advanced" else "basic")
            o.addProperty("format", if (req.format == "markdown") "markdown" else "text")
            o.addProperty("timeout", req.timeoutSec.coerceIn(1, 60))
            o.addProperty("include_usage", true)
            return o.toString()
        }

        private fun strings(list: List<String>): JsonArray {
            val a = JsonArray()
            for (s in list) a.add(s)
            return a
        }

        // ---------- parsing (pure) ----------

        private fun obj(e: JsonElement?): JsonObject? = if (e != null && e.isJsonObject) e.asJsonObject else null

        private fun arr(e: JsonElement?): JsonArray? = if (e != null && e.isJsonArray) e.asJsonArray else null

        private fun text(o: JsonObject, key: String): String? {
            val e = o.get(key) ?: return null
            if (!e.isJsonPrimitive || !e.asJsonPrimitive.isString) return null
            return e.asString
        }

        private fun num(o: JsonObject, key: String): Double? {
            val e = o.get(key) ?: return null
            if (!e.isJsonPrimitive || !e.asJsonPrimitive.isNumber) return null
            return e.asDouble
        }

        private fun credits(root: JsonObject): Int? {
            val usage = obj(root.get("usage")) ?: return null
            val c = num(usage, "credits") ?: return null
            return if (c < 0.0) null else Math.ceil(c).toInt()
        }

        private fun images(e: JsonElement?): List<TavilyImage> {
            val a = arr(e) ?: return emptyList()
            val out = ArrayList<TavilyImage>()
            for (item in a) {
                if (item.isJsonPrimitive && item.asJsonPrimitive.isString) {
                    val u = item.asString
                    if (UrlTools.isHttps(u)) out.add(TavilyImage(u, null))
                } else {
                    val o = obj(item) ?: continue
                    val u = text(o, "url") ?: continue
                    if (UrlTools.isHttps(u)) out.add(TavilyImage(u, text(o, "description")))
                }
            }
            return out
        }

        /** The answer of /search, or null when the JSON is not the expected shape (no `results` array). */
        fun parseSearch(json: String): TavilySearchResponse? {
            return try {
                val root = obj(JsonParser.parseString(json)) ?: return null
                val results = arr(root.get("results")) ?: return null
                val out = ArrayList<TavilyResult>()
                for (item in results) {
                    val o = obj(item) ?: continue
                    val url = text(o, "url") ?: continue
                    out.add(
                        TavilyResult(
                            title = text(o, "title").orEmpty(),
                            url = url,
                            content = text(o, "content").orEmpty(),
                            score = num(o, "score") ?: 0.0,
                            publishedDate = text(o, "published_date"),
                            images = images(o.get("images"))
                        )
                    )
                }
                TavilySearchResponse(out, images(root.get("images")), text(root, "answer"), credits(root))
            } catch (_: Throwable) {
                null
            }
        }

        /** The answer of /extract: pages in `results`, failures in `failed_results` (a 200 can hold both). Null when neither key is there. */
        fun parseExtract(json: String): TavilyExtractResponse? {
            return try {
                val root = obj(JsonParser.parseString(json)) ?: return null
                val results = arr(root.get("results"))
                val failed = arr(root.get("failed_results"))
                if (results == null && failed == null) return null
                val items = ArrayList<TavilyExtractItem>()
                if (results != null) {
                    for (item in results) {
                        val o = obj(item) ?: continue
                        val url = text(o, "url") ?: continue
                        val raw = text(o, "raw_content") ?: continue
                        items.add(TavilyExtractItem(url, raw))
                    }
                }
                val failedUrls = ArrayList<String>()
                if (failed != null) {
                    for (item in failed) {
                        val o = obj(item) ?: continue
                        val url = text(o, "url") ?: continue
                        failedUrls.add(url)
                    }
                }
                TavilyExtractResponse(items, failedUrls, credits(root))
            } catch (_: Throwable) {
                null
            }
        }

        // ---------- the real transport ----------

        /** HttpURLConnection POST. Reads at most [MAX_RESPONSE_CHARS] and throws [ResponseTooBig] beyond that. */
        val realPost: HttpPost = { url, bearer, jsonBody, timeoutMs ->
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = timeoutMs
                c.readTimeout = timeoutMs
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("Accept", "application/json")
                c.setRequestProperty("Authorization", "Bearer $bearer")
                c.setRequestProperty("User-Agent", "CodeAssistAI")
                c.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val body = if (stream == null) "" else readLimited(stream)
                val retry = c.getHeaderField("Retry-After")?.trim()?.toIntOrNull()
                HttpReply(code, body, retry)
            } finally {
                c.disconnect()
            }
        }

        private fun readLimited(stream: java.io.InputStream): String {
            val sb = StringBuilder()
            stream.bufferedReader(Charsets.UTF_8).use { r ->
                val buf = CharArray(4096)
                while (true) {
                    val n = r.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                    if (sb.length > MAX_RESPONSE_CHARS) throw ResponseTooBig()
                }
            }
            return sb.toString()
        }
    }
}
