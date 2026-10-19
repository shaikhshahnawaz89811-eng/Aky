package com.codeassist.ai.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Net search stage S3: the GitHub REST facts. Canned JSON, no network. */
class GitHubClientTest {
    private val hex = "a".repeat(64)
    private val urls = ArrayList<String>()

    private val repoJson = """
        {"name":"llama.cpp","owner":{"login":"ggml-org"},"description":"LLM inference in C/C++","stargazers_count":70000,
         "fork":false,"archived":false,"html_url":"https://github.com/ggml-org/llama.cpp",
         "license":{"spdx_id":"MIT","name":"MIT License"},"pushed_at":"2026-10-12T10:00:00Z"}
    """.trimIndent()

    private val releaseJson = """
        {"tag_name":"b1234","name":"Build b1234","draft":false,"prerelease":false,"published_at":"2026-10-10T08:00:00Z",
         "html_url":"https://github.com/ggml-org/llama.cpp/releases/tag/b1234",
         "assets":[
          {"name":"llama-b1234-bin-android.zip","size":12345678,"content_type":"application/zip","download_count":10,
           "browser_download_url":"https://github.com/ggml-org/llama.cpp/releases/download/b1234/llama-b1234-bin-android.zip","digest":"sha256:$hex"},
          {"name":"checksums.txt","size":100,"content_type":"text/plain",
           "browser_download_url":"https://github.com/ggml-org/llama.cpp/releases/download/b1234/checksums.txt","digest":"sha256:xyz"},
          {"name":"evil.zip","size":1,"browser_download_url":"https://evil.example/releases/download/x/evil.zip"},
          {"name":"elsewhere.zip","size":1,"browser_download_url":"https://github.com/ggml-org/llama.cpp/archive/elsewhere.zip"},
          {"name":"noaddress"}
         ]}
    """.trimIndent()

    private fun client(reply: GhReply, now: Long = 1000L) =
        GitHubClient({ url, _ -> urls.add(url); reply }, { now })

    private fun clientThrowing(e: IOException) = GitHubClient({ _, _ -> throw e })

    private fun failure(o: GhOutcome<*>): SearchFailure = (o as GhOutcome.Fail).failure

    // ---------- names ----------

    @Test fun ownerAndRepoNamesFollowGithubsRules() {
        assertTrue(GitHubClient.validOwner("ggml-org"))
        assertTrue(GitHubClient.validOwner("a"))
        assertTrue(GitHubClient.validOwner("a".repeat(39)))
        assertFalse(GitHubClient.validOwner("a".repeat(40)))
        assertFalse(GitHubClient.validOwner("-x"))
        assertFalse(GitHubClient.validOwner("a b"))
        assertFalse(GitHubClient.validOwner(""))
        assertTrue(GitHubClient.validRepo("llama.cpp"))
        assertFalse(GitHubClient.validRepo(".."))
        assertFalse(GitHubClient.validRepo("."))
        assertFalse(GitHubClient.validRepo("a/b"))
        assertFalse(GitHubClient.validRepo("a b"))
    }

    // ---------- parsing ----------

    @Test fun aRepoIsParsed() {
        val r = GitHubClient.parseRepo(repoJson)!!
        assertEquals("ggml-org/llama.cpp", r.fullName)
        assertEquals("LLM inference in C/C++", r.description)
        assertEquals(70000, r.stars)
        assertFalse(r.fork)
        assertFalse(r.archived)
        assertEquals("MIT", r.license)
        assertEquals("2026-10-12T10:00:00Z", r.pushedAt)
        assertEquals("https://github.com/ggml-org/llama.cpp", r.htmlUrl)
    }

    @Test fun aLicenseFallsBackToItsNameAndMayBeMissing() {
        val noAssertion = repoJson.replace("\"spdx_id\":\"MIT\"", "\"spdx_id\":\"NOASSERTION\"")
        assertEquals("MIT License", GitHubClient.parseRepo(noAssertion)!!.license)
        assertNull(GitHubClient.parseRepo(repoJson.replace(Regex("\"license\":\\{[^}]*\\}"), "\"license\":null"))!!.license)
    }

    @Test fun aBrokenRepoIsNull() {
        assertNull(GitHubClient.parseRepo("not json"))
        assertNull(GitHubClient.parseRepo("[]"))
        assertNull(GitHubClient.parseRepo("{}"))
        assertNull(GitHubClient.parseRepo(repoJson.replace("\"owner\":{\"login\":\"ggml-org\"},", "")))
        assertNull(GitHubClient.parseRepo(repoJson.replace("https://github.com/ggml-org/llama.cpp", "https://evil.example/ggml-org/llama.cpp")))
        assertNull(GitHubClient.parseRepo(repoJson.replace("\"name\":\"llama.cpp\"", "\"name\":\"..\"")))
    }

    @Test fun aReleaseKeepsOnlyFilesServedByGithubReleases() {
        val rel = GitHubClient.parseRelease(releaseJson)!!
        assertEquals("b1234", rel.tag)
        assertEquals("Build b1234", rel.name)
        assertEquals("2026-10-10T08:00:00Z", rel.publishedAt)
        assertFalse(rel.prerelease)
        assertEquals(listOf("llama-b1234-bin-android.zip", "checksums.txt"), rel.assets.map { it.name })
        val a = rel.assets[0]
        assertEquals(12345678L, a.sizeBytes)
        assertEquals("application/zip", a.contentType)
        assertEquals(10, a.downloads)
        assertEquals("sha256:$hex", a.digest)
        assertNull(rel.assets[1].digest)
    }

    @Test fun aMissingSizeIsMinusOne() {
        val json = """{"tag_name":"v1","html_url":"https://github.com/a/b/releases/tag/v1","assets":[{"name":"x.apk","browser_download_url":"https://github.com/a/b/releases/download/v1/x.apk"}]}"""
        assertEquals(-1L, GitHubClient.parseRelease(json)!!.assets[0].sizeBytes)
    }

    @Test fun aBrokenReleaseIsNull() {
        assertNull(GitHubClient.parseRelease("{"))
        assertNull(GitHubClient.parseRelease("{\"name\":\"x\"}"))
        assertNull(GitHubClient.parseRelease(releaseJson.replace("https://github.com/ggml-org/llama.cpp/releases/tag/b1234", "https://evil.example/tag")))
        assertNull(GitHubClient.parseRelease("\"just a string\""))
    }

    @Test fun aDigestIsKeptOnlyInTheExpectedShape() {
        val upper = "A".repeat(64)
        val json = """{"tag_name":"v1","html_url":"https://github.com/a/b/releases/tag/v1","assets":[{"name":"x.apk","size":1,"browser_download_url":"https://github.com/a/b/releases/download/v1/x.apk","digest":"sha256:$upper"}]}"""
        assertEquals("sha256:" + "a".repeat(64), GitHubClient.parseRelease(json)!!.assets[0].digest)
    }

    @Test fun onlyAHundredFilesAreRead() {
        val one = """{"name":"f%d.zip","size":1,"browser_download_url":"https://github.com/a/b/releases/download/v1/f%d.zip"}"""
        val assets = (1..130).joinToString(",") { String.format(one, it, it) }
        val json = """{"tag_name":"v1","html_url":"https://github.com/a/b/releases/tag/v1","assets":[$assets]}"""
        assertEquals(GitHubClient.MAX_ASSETS, GitHubClient.parseRelease(json)!!.assets.size)
    }

    // ---------- calls ----------

    @Test fun repoAndLatestReleaseUseTheRightAddresses() {
        val ok = client(GhReply(200, repoJson))
        val r = ok.repo("ggml-org", "llama.cpp") as GhOutcome.Ok
        assertEquals("ggml-org/llama.cpp", r.value.fullName)
        val l = client(GhReply(200, releaseJson)).latestRelease("ggml-org", "llama.cpp") as GhOutcome.Ok
        assertEquals("b1234", l.value.tag)
        assertEquals(
            listOf(
                "https://api.github.com/repos/ggml-org/llama.cpp",
                "https://api.github.com/repos/ggml-org/llama.cpp/releases/latest"
            ),
            urls
        )
    }

    @Test fun anInvalidNameNeverReachesTheNetwork() {
        val c = client(GhReply(200, repoJson))
        assertTrue(c.repo("bad owner", "x") is GhOutcome.NotFound)
        assertTrue(c.latestRelease("a", "../x") is GhOutcome.NotFound)
        assertTrue(urls.isEmpty())
    }

    @Test fun notFoundIsNotAnError() {
        assertTrue(client(GhReply(404, "{\"message\":\"Not Found\"}")).repo("a", "b") is GhOutcome.NotFound)
        assertTrue(client(GhReply(404, "")).latestRelease("a", "b") is GhOutcome.NotFound)
    }

    @Test fun aBadBodyIsMalformedAndNeverPartlyUsed() {
        assertEquals(FailureKind.MALFORMED, failure(client(GhReply(200, "<html>")).repo("a", "b")).kind)
        assertEquals(FailureKind.MALFORMED, failure(client(GhReply(200, "{}")).latestRelease("a", "b")).kind)
    }

    @Test fun theRateLimitIsReadFromTheHeaders() {
        val f = failure(client(GhReply(403, "{\"message\":\"x\"}", rateRemaining = 0, rateResetEpochSec = 1090L), now = 1000L).repo("a", "b"))
        assertEquals(FailureKind.RATE_LIMITED, f.kind)
        assertEquals(90, f.retryAfterSec)
    }

    @Test fun retryAfterWinsOverTheResetTime() {
        val f = failure(client(GhReply(429, "", retryAfterSec = 30, rateRemaining = 0, rateResetEpochSec = 5000L), now = 1000L).repo("a", "b"))
        assertEquals(30, f.retryAfterSec)
    }

    @Test fun aRateLimitMessageWithoutHeadersIsStillARateLimit() {
        val f = failure(client(GhReply(403, "{\"message\":\"API rate limit exceeded for 1.2.3.4\"}")).repo("a", "b"))
        assertEquals(FailureKind.RATE_LIMITED, f.kind)
        assertNull(f.retryAfterSec)
    }

    @Test fun otherForbiddenAnswersAreNotRateLimits() {
        assertEquals(FailureKind.BAD_REQUEST, failure(client(GhReply(403, "{\"message\":\"Forbidden\"}", rateRemaining = 59)).repo("a", "b")).kind)
        assertEquals(FailureKind.BAD_REQUEST, failure(client(GhReply(401, "")).repo("a", "b")).kind)
        assertEquals(FailureKind.BAD_REQUEST, failure(client(GhReply(422, "")).repo("a", "b")).kind)
    }

    @Test fun serverErrorsAreServerFailures() {
        assertEquals(FailureKind.SERVER, failure(client(GhReply(500, "")).repo("a", "b")).kind)
        assertEquals(FailureKind.SERVER, failure(client(GhReply(502, "")).latestRelease("a", "b")).kind)
    }

    @Test fun networkErrorsAreTyped() {
        assertEquals(FailureKind.OFFLINE, failure(clientThrowing(UnknownHostException("x")).repo("a", "b")).kind)
        assertEquals(FailureKind.TIMEOUT, failure(clientThrowing(SocketTimeoutException("x")).repo("a", "b")).kind)
        assertEquals(FailureKind.NETWORK, failure(clientThrowing(IOException("x")).repo("a", "b")).kind)
        assertEquals(FailureKind.MALFORMED, failure(clientThrowing(ResponseTooBig()).repo("a", "b")).kind)
    }

    @Test fun theReplyHelperKeepsItsFields() {
        val r = GhReply(200, "b", 3, 4, 5L)
        assertNotNull(r)
        assertEquals(3, r.retryAfterSec)
        assertEquals(4, r.rateRemaining)
        assertEquals(5L, r.rateResetEpochSec)
    }
}
