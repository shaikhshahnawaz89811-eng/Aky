package com.codeassist.ai.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Net search stage S3: the checks before a Download button. The network is a map of canned replies. */
class DownloadGuardTest {
    private val calls = ArrayList<String>()

    private fun reply(code: Int, vararg h: Pair<String, String>) = HeadReply(code, h.toMap())

    private fun net(vararg routes: Pair<String, HeadReply>): HeadTransport {
        val map = routes.toMap()
        return { url, _ -> calls.add(url); map[url] ?: throw IOException("no route") }
    }

    private fun reason(v: GuardVerdict): RejectReason = (v as GuardVerdict.Reject).reason

    private val apk = "https://example.com/files/app.apk"
    private val okApk = reply(200, "content-type" to "application/vnd.android.package-archive", "content-length" to "5000")

    // ---------- address rules ----------

    @Test fun anOrdinaryHttpsAddressPasses() {
        assertNull(DownloadGuard.checkUrl(apk))
        assertNull(DownloadGuard.checkUrl("HTTPS://example.com/a.apk"))
        assertNull(DownloadGuard.checkUrl("https://example.com:443/a.apk"))
    }

    @Test fun httpIsRefused() {
        assertEquals(RejectReason.NOT_HTTPS, DownloadGuard.checkUrl("http://example.com/a.apk")!!.reason)
        assertEquals(RejectReason.NOT_HTTPS, DownloadGuard.checkUrl("ftp://example.com/a.apk")!!.reason)
    }

    @Test fun trickyHostsAreRefused() {
        val bad = listOf(
            "https://user@example.com/a.apk", "https://example.com:8443/a.apk", "https://192.168.1.5/a.apk", "https://203.0.113.9/a.apk",
            "https://[::1]/a.apk", "https://localhost/a.apk", "https://intranet/a.apk", "https://printer.local/a.apk",
            "https://xn--80ak6aa92e.com/a.apk", "https://ex\u00E4mple.com/a.apk", "not an address"
        )
        for (u in bad) {
            val r = DownloadGuard.checkUrl(u)
            assertNotNull(u, r)
            assertEquals(u, RejectReason.BAD_HOST, r!!.reason)
        }
    }

    @Test fun aVeryLongAddressIsRefused() {
        assertEquals(RejectReason.BAD_HOST, DownloadGuard.checkUrl("https://example.com/" + "a".repeat(2000) + ".apk")!!.reason)
    }

    // ---------- same site ----------

    @Test fun sameSiteAllowsOneDomainAndTheTrustedFamilies() {
        assertTrue(DownloadGuard.sameSite("https://a.example.com/x", "https://b.example.com/y"))
        assertTrue(DownloadGuard.sameSite("https://github.com/o/r/releases/download/v1/a.apk", "https://objects.githubusercontent.com/x"))
        assertTrue(DownloadGuard.sameSite("https://huggingface.co/o/m/resolve/main/f.gguf", "https://cdn-lfs.hf.co/x"))
        assertTrue(!DownloadGuard.sameSite("https://github.com/a", "https://evil.com/a"))
        assertTrue(!DownloadGuard.sameSite("https://github.com/a", "https://githubusercontent.evil.com/a"))
        assertTrue(!DownloadGuard.sameSite("https://example.com", "https://example.org"))
    }

    // ---------- small helpers ----------

    @Test fun mediaTypeDropsParametersAndCase() {
        assertEquals("application/zip", DownloadGuard.mediaType("application/zip; charset=binary"))
        assertEquals("text/html", DownloadGuard.mediaType("TEXT/HTML"))
        assertNull(DownloadGuard.mediaType(null))
        assertNull(DownloadGuard.mediaType("  "))
    }

    @Test fun typeFitsKnowsGenericAndSpecificTypes() {
        assertTrue(DownloadGuard.typeFits("apk", "application/vnd.android.package-archive"))
        assertTrue(DownloadGuard.typeFits("apk", "application/octet-stream"))
        assertTrue(!DownloadGuard.typeFits("apk", "application/pdf"))
        assertTrue(DownloadGuard.typeFits("gguf", "application/octet-stream"))
        assertTrue(!DownloadGuard.typeFits("gguf", "application/zip"))
        assertTrue(DownloadGuard.typeFits("zip", "application/x-zip-compressed"))
        assertTrue(!DownloadGuard.typeFits("zip", "image/png"))
    }

    @Test fun contentRangeGivesTheTotalSize() {
        assertEquals(12345L, DownloadGuard.totalFromContentRange("bytes 0-0/12345"))
        assertNull(DownloadGuard.totalFromContentRange("bytes 0-0/*"))
        assertNull(DownloadGuard.totalFromContentRange("bytes 0-0/0"))
        assertNull(DownloadGuard.totalFromContentRange(null))
    }

    // ---------- the check ----------

    @Test fun aPlainFilePassesWithItsFacts() {
        val v = DownloadGuard.check(apk, emptyList(), net(apk to okApk)) as GuardVerdict.Pass
        assertEquals(5000L, v.facts.sizeBytes)
        assertEquals("apk", v.facts.extension)
        assertEquals("application/vnd.android.package-archive", v.facts.contentType)
        assertEquals(apk, v.facts.finalUrl)
        assertEquals(listOf(apk), v.facts.hops)
    }

    @Test fun aRedirectOnTheSameFamilyPasses() {
        val first = "https://github.com/o/r/releases/download/v1/a.apk"
        val last = "https://objects.githubusercontent.com/x/a.apk?sig=1"
        val v = DownloadGuard.check(
            first, listOf("apk"),
            net(first to reply(302, "location" to last), last to reply(200, "content-type" to "application/octet-stream", "content-length" to "99"))
        ) as GuardVerdict.Pass
        assertEquals(2, v.facts.hops.size)
        assertEquals(last, v.facts.finalUrl)
        assertEquals(99L, v.facts.sizeBytes)
        assertEquals("apk", v.facts.extension)
    }

    @Test fun aRelativeRedirectIsResolved() {
        val other = "https://example.com/other/app.apk"
        val v = DownloadGuard.check(apk, emptyList(), net(apk to reply(301, "location" to "/other/app.apk"), other to okApk))
        assertTrue(v is GuardVerdict.Pass)
    }

    @Test fun aRedirectToAnotherSiteIsRefused() {
        val evil = "https://evil.example/app.apk"
        val v = DownloadGuard.check(apk, emptyList(), net(apk to reply(302, "location" to evil), evil to okApk))
        assertEquals(RejectReason.OFF_SITE_REDIRECT, reason(v))
        assertEquals(listOf(apk), calls)
    }

    @Test fun aRedirectToHttpOrAnIpIsRefusedBeforeItIsFollowed() {
        val http = "http://example.com/app.apk"
        assertEquals(RejectReason.NOT_HTTPS, reason(DownloadGuard.check(apk, emptyList(), net(apk to reply(302, "location" to http)))))
        val ip = "https://203.0.113.5/app.apk"
        assertEquals(RejectReason.BAD_HOST, reason(DownloadGuard.check(apk, emptyList(), net(apk to reply(302, "location" to ip)))))
        // each check fetched the first address once and never followed the bad redirect
        assertEquals(2, calls.size)
    }

    @Test fun threeRedirectsPassAndFourAreRefused() {
        val u = (0..4).map { "https://example.com/r$it/app.apk" }
        val three = DownloadGuard.check(
            u[0], emptyList(),
            net(u[0] to reply(302, "location" to u[1]), u[1] to reply(302, "location" to u[2]), u[2] to reply(302, "location" to u[3]), u[3] to okApk)
        )
        assertTrue(three is GuardVerdict.Pass)
        val four = DownloadGuard.check(
            u[0], emptyList(),
            net(
                u[0] to reply(302, "location" to u[1]), u[1] to reply(302, "location" to u[2]), u[2] to reply(302, "location" to u[3]),
                u[3] to reply(302, "location" to u[4]), u[4] to okApk
            )
        )
        assertEquals(RejectReason.TOO_MANY_REDIRECTS, reason(four))
    }

    @Test fun aRedirectWithoutAPlaceIsRefused() {
        assertEquals(RejectReason.BAD_REDIRECT, reason(DownloadGuard.check(apk, emptyList(), net(apk to reply(302)))))
    }

    @Test fun aWebPageIsNotAFile() {
        val v = DownloadGuard.check(apk, emptyList(), net(apk to reply(200, "content-type" to "text/html; charset=utf-8")))
        assertEquals(RejectReason.NOT_A_FILE, reason(v))
        assertEquals(RejectReason.NOT_A_FILE, reason(DownloadGuard.check(apk, emptyList(), net(apk to reply(200, "content-type" to "application/json")))))
    }

    @Test fun aTypeThatDoesNotFitTheEndingIsRefused() {
        val v = DownloadGuard.check(apk, emptyList(), net(apk to reply(200, "content-type" to "application/pdf", "content-length" to "10")))
        assertEquals(RejectReason.TYPE_MISMATCH, reason(v))
    }

    @Test fun aMissingTypeCannotBeChecked() {
        assertEquals(RejectReason.TYPE_UNKNOWN, reason(DownloadGuard.check(apk, emptyList(), net(apk to reply(200, "content-length" to "10")))))
    }

    @Test fun theFileTypeTheUserAskedForMustMatch() {
        val zip = "https://example.com/src.zip"
        val v = DownloadGuard.check(zip, listOf("apk"), net(zip to reply(200, "content-type" to "application/zip", "content-length" to "10")))
        assertEquals(RejectReason.TYPE_MISMATCH, reason(v))
    }

    @Test fun anAddressWithoutAFileEndingNeedsAFileNameFromTheSite() {
        val u = "https://example.com/download?id=7"
        assertEquals(RejectReason.NO_EXTENSION, reason(DownloadGuard.check(u, emptyList(), net(u to okApk))))
        val named = reply(
            200, "content-type" to "application/octet-stream", "content-length" to "10",
            "content-disposition" to "attachment; filename=\"tool-1.2.apk\""
        )
        val v = DownloadGuard.check(u, emptyList(), net(u to named)) as GuardVerdict.Pass
        assertEquals("apk", v.facts.extension)
    }

    @Test fun errorsAndBlockedChecksAreNotFiles() {
        assertEquals(RejectReason.HTTP_ERROR, reason(DownloadGuard.check(apk, emptyList(), net(apk to reply(404)))))
        assertEquals(RejectReason.HTTP_ERROR, reason(DownloadGuard.check(apk, emptyList(), net(apk to reply(500)))))
        for (code in listOf(403, 405, 429, 501)) {
            assertEquals(code.toString(), RejectReason.UNVERIFIED, reason(DownloadGuard.check(apk, emptyList(), net(apk to reply(code)))))
        }
    }

    @Test fun aNetworkErrorMeansUnverified() {
        assertEquals(RejectReason.UNVERIFIED, reason(DownloadGuard.check(apk, emptyList(), net())))
    }

    @Test fun aRangedAnswerGivesTheTotalSize() {
        val v = DownloadGuard.check(
            apk, emptyList(),
            net(apk to reply(206, "content-type" to "application/octet-stream", "content-range" to "bytes 0-0/777"))
        ) as GuardVerdict.Pass
        assertEquals(777L, v.facts.sizeBytes)
    }

    @Test fun anEmptyFileIsRefusedAndAMissingSizeIsUnknown() {
        assertEquals(
            RejectReason.EMPTY_FILE,
            reason(DownloadGuard.check(apk, emptyList(), net(apk to reply(200, "content-type" to "application/octet-stream", "content-length" to "0"))))
        )
        val v = DownloadGuard.check(apk, emptyList(), net(apk to reply(200, "content-type" to "application/octet-stream"))) as GuardVerdict.Pass
        assertNull(v.facts.sizeBytes)
    }

    @Test fun aModelFileWithAGenericTypePasses() {
        val u = "https://huggingface.co/o/m/resolve/main/model.Q4.gguf"
        val v = DownloadGuard.check(u, listOf("gguf"), net(u to reply(200, "content-type" to "application/octet-stream", "content-length" to "2500000000")))
        assertTrue(v is GuardVerdict.Pass)
    }

    @Test fun noNetworkCallIsMadeForABadAddress() {
        DownloadGuard.check("http://example.com/a.apk", emptyList(), net())
        DownloadGuard.check("https://192.168.0.1/a.apk", emptyList(), net())
        assertTrue(calls.isEmpty())
    }

    @Test fun headReplyHeadersAreCaseInsensitiveByName() {
        val r = HeadReply(200, mapOf("content-type" to "x"))
        assertEquals("x", r.header("Content-Type"))
        assertNull(r.header("location"))
    }
}
