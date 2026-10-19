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

/** What a GET gave back. The two rate-limit numbers come from the `x-ratelimit-remaining` / `x-ratelimit-reset` headers when present. */
class GhReply(
    val code: Int,
    val body: String,
    val retryAfterSec: Int? = null,
    val rateRemaining: Int? = null,
    val rateResetEpochSec: Long? = null
)

/** The HTTP part, injectable so the tests run with canned replies. Throws IOException on network trouble. */
typealias GhGet = (url: String, timeoutMs: Int) -> GhReply

/** One file of a release. [url] is the `browser_download_url` of the API: https, on github.com, under `/releases/download/`. */
class GitHubAsset(
    val name: String,
    val sizeBytes: Long,
    val contentType: String?,
    val url: String,
    /** `sha256:<hex>` when GitHub recorded one for the upload (older uploads have none), else null. The publisher's number, not ours. */
    val digest: String?,
    val downloads: Int?
)

class GitHubRelease(
    val tag: String,
    val name: String?,
    /** ISO time from the API, e.g. 2026-10-10T08:00:00Z. */
    val publishedAt: String?,
    val prerelease: Boolean,
    val htmlUrl: String,
    val assets: List<GitHubAsset>
)

class GitHubRepo(
    val owner: String,
    val name: String,
    val description: String?,
    val stars: Int?,
    val fork: Boolean,
    val archived: Boolean,
    val htmlUrl: String,
    val license: String?,
    val pushedAt: String?
) {
    val fullName: String get() = "$owner/$name"
}

sealed class GhOutcome<out T> {
    class Ok<out T>(val value: T) : GhOutcome<T>()

    /** 404: no such repo, or (for the latest release) no published stable release. */
    object NotFound : GhOutcome<Nothing>()

    class Fail(val failure: SearchFailure) : GhOutcome<Nothing>()
}

/**
 * Net Search Agent, stage S3: the facts of a GitHub repo and its latest release straight from the official REST API (audit section 2.2:
 * structured, no scraping, no key for light use: 60 requests an hour without a token). Fields of the API are authoritative: the tag, the
 * date, the file names and sizes shown to the user are these, never a model's words.
 *
 * Endpoints: `GET /repos/{owner}/{repo}` and `GET /repos/{owner}/{repo}/releases/latest` (the newest published release that is neither a
 * draft nor a pre-release). Owner and repo names are checked against GitHub's own name rules before they go into an address. Pure JVM
 * (java.net + Gson); an answer that is not the expected shape is a typed MALFORMED failure and is never partly used. Blocking: call it
 * through StepTimeout.call.
 */
class GitHubClient(
    private val get: GhGet = realGet,
    private val nowSec: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    fun repo(owner: String, repo: String, timeoutMs: Int = TIMEOUT_MS): GhOutcome<GitHubRepo> {
        if (!validOwner(owner) || !validRepo(repo)) return GhOutcome.NotFound
        return call("$BASE_URL/repos/$owner/$repo", timeoutMs) { parseRepo(it) }
    }

    fun latestRelease(owner: String, repo: String, timeoutMs: Int = TIMEOUT_MS): GhOutcome<GitHubRelease> {
        if (!validOwner(owner) || !validRepo(repo)) return GhOutcome.NotFound
        return call("$BASE_URL/repos/$owner/$repo/releases/latest", timeoutMs) { parseRelease(it) }
    }

    private fun <T> call(url: String, timeoutMs: Int, parse: (String) -> T?): GhOutcome<T> {
        val reply = try {
            get(url, timeoutMs)
        } catch (e: UnknownHostException) {
            return GhOutcome.Fail(SearchFailure(FailureKind.OFFLINE, null, "unknown host"))
        } catch (e: ConnectException) {
            return GhOutcome.Fail(SearchFailure(FailureKind.OFFLINE, null, "connect failed"))
        } catch (e: SocketTimeoutException) {
            return GhOutcome.Fail(SearchFailure(FailureKind.TIMEOUT, null, "timeout"))
        } catch (e: ResponseTooBig) {
            return GhOutcome.Fail(SearchFailure(FailureKind.MALFORMED, null, "response too big"))
        } catch (e: IOException) {
            return GhOutcome.Fail(SearchFailure(FailureKind.NETWORK, null, "io error"))
        }
        val c = reply.code
        if (c == 200) {
            val value = try {
                parse(reply.body)
            } catch (_: Exception) {
                null
            }
            return if (value != null) GhOutcome.Ok(value)
            else GhOutcome.Fail(SearchFailure(FailureKind.MALFORMED, null, "unexpected body"))
        }
        if (c == 404) return GhOutcome.NotFound
        if ((c == 403 || c == 429) && isRateLimit(reply)) {
            val wait = reply.retryAfterSec
                ?: reply.rateResetEpochSec?.let { (it - nowSec()).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt() }
            return GhOutcome.Fail(SearchFailure(FailureKind.RATE_LIMITED, wait, "github rate limit"))
        }
        val kind = if (c in 500..599) FailureKind.SERVER else FailureKind.BAD_REQUEST
        return GhOutcome.Fail(SearchFailure(kind, null, "HTTP $c"))
    }

    private fun isRateLimit(r: GhReply): Boolean =
        r.rateRemaining == 0 || r.code == 429 || r.body.contains("rate limit", ignoreCase = true)

    companion object {
        const val BASE_URL = "https://api.github.com"
        const val TIMEOUT_MS = 8_000

        /** A release with many files (llama.cpp has dozens) plus its notes is large: 600 KB, then it counts as malformed. */
        const val MAX_RESPONSE_CHARS = 600 * 1024
        const val MAX_ASSETS = 100

        private val ownerRx = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$")
        private val repoRx = Regex("^[A-Za-z0-9_.-]{1,100}$")
        private val digestRx = Regex("^sha256:[0-9a-fA-F]{64}$")

        fun validOwner(s: String): Boolean = ownerRx.matches(s)

        fun validRepo(s: String): Boolean = repoRx.matches(s) && s != "." && s != ".."

        // ---------- parsing (pure) ----------

        private fun obj(e: JsonElement?): JsonObject? = if (e != null && e.isJsonObject) e.asJsonObject else null

        private fun arr(e: JsonElement?): JsonArray? = if (e != null && e.isJsonArray) e.asJsonArray else null

        private fun text(o: JsonObject, key: String): String? {
            val e = o.get(key) ?: return null
            if (!e.isJsonPrimitive || !e.asJsonPrimitive.isString) return null
            return e.asString
        }

        private fun long(o: JsonObject, key: String): Long? {
            val e = o.get(key) ?: return null
            if (!e.isJsonPrimitive || !e.asJsonPrimitive.isNumber) return null
            return e.asLong
        }

        private fun bool(o: JsonObject, key: String): Boolean {
            val e = o.get(key) ?: return false
            return e.isJsonPrimitive && e.asJsonPrimitive.isBoolean && e.asBoolean
        }

        private fun isGithubPage(url: String): Boolean = UrlTools.isHttps(url) && UrlTools.host(url) == "github.com"

        /** The repo facts, or null when the JSON is not a repo object (no name, no owner, no github.com page). */
        fun parseRepo(json: String): GitHubRepo? {
            return try {
                val o = obj(JsonParser.parseString(json)) ?: return null
                val name = text(o, "name") ?: return null
                val owner = text(obj(o.get("owner")) ?: return null, "login") ?: return null
                val html = text(o, "html_url") ?: return null
                if (!isGithubPage(html) || !validOwner(owner) || !validRepo(name)) return null
                val lic = obj(o.get("license"))
                val licenseName = if (lic == null) null else (text(lic, "spdx_id")?.takeIf { it != "NOASSERTION" } ?: text(lic, "name"))
                GitHubRepo(
                    owner = owner,
                    name = name,
                    description = text(o, "description"),
                    stars = long(o, "stargazers_count")?.toInt(),
                    fork = bool(o, "fork"),
                    archived = bool(o, "archived"),
                    htmlUrl = html,
                    license = licenseName,
                    pushedAt = text(o, "pushed_at")
                )
            } catch (_: Throwable) {
                null
            }
        }

        /**
         * The release facts, or null when the JSON is not a release object (no tag, no github.com page). A file whose download address is not
         * an https github.com `/releases/download/` address is left out: only addresses the API itself gave and that point where GitHub
         * serves release files are ever offered.
         */
        fun parseRelease(json: String): GitHubRelease? {
            return try {
                val o = obj(JsonParser.parseString(json)) ?: return null
                val tag = text(o, "tag_name") ?: return null
                val html = text(o, "html_url") ?: return null
                if (!isGithubPage(html)) return null
                val assets = ArrayList<GitHubAsset>()
                val list = arr(o.get("assets"))
                if (list != null) {
                    for (item in list) {
                        if (assets.size >= MAX_ASSETS) break
                        val a = obj(item) ?: continue
                        val name = text(a, "name") ?: continue
                        val url = text(a, "browser_download_url") ?: continue
                        if (!isGithubPage(url) || !url.contains("/releases/download/")) continue
                        val digest = text(a, "digest")?.takeIf { digestRx.matches(it) }
                        assets.add(
                            GitHubAsset(
                                name = name,
                                sizeBytes = long(a, "size") ?: -1L,
                                contentType = text(a, "content_type"),
                                url = url,
                                digest = digest?.lowercase(),
                                downloads = long(a, "download_count")?.toInt()
                            )
                        )
                    }
                }
                GitHubRelease(
                    tag = tag,
                    name = text(o, "name"),
                    publishedAt = text(o, "published_at"),
                    prerelease = bool(o, "prerelease"),
                    htmlUrl = html,
                    assets = assets
                )
            } catch (_: Throwable) {
                null
            }
        }

        // ---------- the real transport ----------

        /** HttpURLConnection GET with the headers GitHub asks for. Reads at most [MAX_RESPONSE_CHARS], else throws [ResponseTooBig]. */
        val realGet: GhGet = { url, timeoutMs ->
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = timeoutMs
                c.readTimeout = timeoutMs
                c.requestMethod = "GET"
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                c.setRequestProperty("User-Agent", "CodeAssistAI")
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val body = if (stream == null) "" else readLimited(stream)
                GhReply(
                    code = code,
                    body = body,
                    retryAfterSec = c.getHeaderField("Retry-After")?.trim()?.toIntOrNull(),
                    rateRemaining = c.getHeaderField("x-ratelimit-remaining")?.trim()?.toIntOrNull(),
                    rateResetEpochSec = c.getHeaderField("x-ratelimit-reset")?.trim()?.toLongOrNull()
                )
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
