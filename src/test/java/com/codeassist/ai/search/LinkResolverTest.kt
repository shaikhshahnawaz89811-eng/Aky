package com.codeassist.ai.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Net search stage S3: which link is what. Pure rules, no network. */
class LinkResolverTest {
    private fun res(url: String, title: String = "T", score: Double = 0.5, content: String = "") =
        TavilyResult(title, url, content, score, null, emptyList())

    private fun cls(url: String, tokens: List<String> = listOf("tavily"), pages: Int = 2) = LinkResolver.classify(url, tokens, pages)

    // ---------- product name words ----------

    @Test fun entityTokensKeepOnlyTheProductName() {
        assertEquals(listOf("gemma"), LinkResolver.entityTokens("gemma 4 apk download link"))
        assertEquals(listOf("llama", "cpp"), LinkResolver.entityTokens("llama.cpp releases"))
        assertEquals(listOf("tavily"), LinkResolver.entityTokens("Tavily official website"))
        assertEquals(listOf("gemma"), LinkResolver.entityTokens("2026 gemma Gemma"))
        assertTrue(LinkResolver.entityTokens("the app for android").isEmpty())
    }

    @Test fun aDomainNameMatchesTheProductNameOrNamePlusAShortWord() {
        val t = listOf("tavily")
        assertTrue(LinkResolver.labelMatchesVendor("tavily", t))
        assertTrue(LinkResolver.labelMatchesVendor("tavily-ai", t))
        assertTrue(LinkResolver.labelMatchesVendor("tavilyapp", t))
        assertFalse(LinkResolver.labelMatchesVendor("tavily-download", t))
        assertFalse(LinkResolver.labelMatchesVendor("tavilly", t))
        assertFalse(LinkResolver.labelMatchesVendor("go", listOf("go")))
        assertTrue(LinkResolver.labelMatchesVendor("llamacpp", listOf("llama", "cpp")))
        assertTrue(LinkResolver.labelMatchesVendor("gemma", listOf("llama", "gemma")))
    }

    // ---------- classes and trust ----------

    @Test fun githubPagesAreKnownHostsNotOfficial() {
        val rel = cls("https://github.com/ggml-org/llama.cpp/releases/tag/b1")
        assertEquals(LinkClass.GITHUB_RELEASE, rel.first)
        assertEquals(Trust.KNOWN_HOST, rel.second)
        assertEquals(LinkClass.GITHUB_REPO, cls("https://github.com/ggml-org/llama.cpp").first)
        assertEquals(LinkClass.UNKNOWN, cls("https://github.com/topics/llm").first)
        assertEquals(Trust.NOT_CONFIRMED, cls("https://gist.github.com/x/y").second)
    }

    @Test fun storesAndHuggingFaceAreKnownHosts() {
        assertEquals(LinkClass.HUGGINGFACE, cls("https://huggingface.co/google/gemma-2b").first)
        assertEquals(Trust.KNOWN_HOST, cls("https://huggingface.co/google/gemma-2b").second)
        assertEquals(LinkClass.STORE, cls("https://play.google.com/store/apps/details?id=x").first)
        assertEquals(LinkClass.STORE, cls("https://f-droid.org/packages/x").first)
    }

    @Test fun mirrorsAreLowTrust() {
        val c = cls("https://apkpure.com/gemma/com.x")
        assertEquals(LinkClass.MIRROR, c.first)
        assertEquals(Trust.LOW, c.second)
        assertEquals("third-party mirror", c.third)
        assertEquals(Trust.LOW, cls("https://download.cnet.com/x").second)
    }

    @Test fun userContentHostsNeverProveAName() {
        val c = cls("https://tavily.github.io/x", pages = 5)
        assertEquals(LinkClass.UNKNOWN, c.first)
        assertEquals(Trust.NOT_CONFIRMED, c.second)
        assertEquals(Trust.NOT_CONFIRMED, cls("https://tavily.vercel.app", pages = 5).second)
    }

    @Test fun aVendorDomainNeedsTwoResultPagesToBeLikelyOfficial() {
        val two = cls("https://tavily.com/docs", pages = 2)
        assertEquals(LinkClass.VENDOR, two.first)
        assertEquals(Trust.LIKELY_OFFICIAL, two.second)
        val one = cls("https://tavily.com/docs", pages = 1)
        assertEquals(LinkClass.VENDOR, one.first)
        assertEquals(Trust.NOT_CONFIRMED, one.second)
    }

    @Test fun aSuspiciousEndingStopsLikelyOfficial() {
        assertEquals(Trust.NOT_CONFIRMED, cls("https://tavily.xyz/x", pages = 3).second)
        assertEquals(Trust.LOW, cls("https://random-site.top/x").second)
    }

    @Test fun lookAlikeNamesAreLowTrust() {
        val bad = cls("https://tavily-free-download.com/x")
        assertEquals(Trust.LOW, bad.second)
        assertEquals("look-alike name", bad.third)
        assertEquals(Trust.LOW, cls("https://tavilly.com/x").second)
        assertEquals(Trust.LOW, cls("https://freetavily.com/x").second)
    }

    @Test fun anUnrelatedDomainIsJustNotConfirmed() {
        val c = cls("https://example.org/page")
        assertEquals(LinkClass.UNKNOWN, c.first)
        assertEquals(Trust.NOT_CONFIRMED, c.second)
    }

    @Test fun aNonWebAddressIsLowTrust() {
        assertEquals(Trust.LOW, cls("ftp://tavily.com/a").second)
    }

    // ---------- github ----------

    @Test fun githubRepoOfReadsOwnerAndRepo() {
        assertEquals("ggml-org" to "llama.cpp", LinkResolver.githubRepoOf("https://github.com/ggml-org/llama.cpp/releases"))
        assertEquals("a" to "b", LinkResolver.githubRepoOf("https://github.com/a/b.git"))
        assertEquals("a" to "b", LinkResolver.githubRepoOf("https://www.github.com/a/b/issues/3"))
        assertNull(LinkResolver.githubRepoOf("https://github.com/ggml-org"))
        assertNull(LinkResolver.githubRepoOf("https://github.com/orgs/foo/people"))
        assertNull(LinkResolver.githubRepoOf("https://github.com/-bad/repo"))
        assertNull(LinkResolver.githubRepoOf("https://example.com/a/b"))
    }

    @Test fun repoFromTextFindsAWrittenOwnerSlashRepo() {
        assertEquals("ggml-org" to "llama.cpp", LinkResolver.repoFromText("ggml-org/llama.cpp apk"))
        assertEquals("google-ai-edge" to "litert-lm", LinkResolver.repoFromText("google-ai-edge/litert-lm latest release"))
        assertEquals("a1" to "b2", LinkResolver.repoFromText("see a1/b2 now"))
    }

    @Test fun repoFromTextIgnoresSlashedWordsNumbersAndFiles() {
        assertNull(LinkResolver.repoFromText("android/ios apk download"))
        assertNull(LinkResolver.repoFromText("on/off switch and/or more"))
        assertNull(LinkResolver.repoFromText("24/7 support"))
        assertNull(LinkResolver.repoFromText("foo/bar.apk link"))
        assertNull(LinkResolver.repoFromText("gemma apk direct link"))
        assertNull(LinkResolver.repoFromText("see https://github.com/a/b please"))
    }

    // ---------- ranking ----------

    @Test fun anOfficialRequestPutsTheLikelyOfficialSiteFirstAndDropsMirrors() {
        val results = listOf(
            res("https://tavily.com"),
            res("https://docs.tavily.com/x"),
            res("https://apkpure.com/tavily"),
            res("https://github.com/tavily-ai/tavily-python")
        )
        val r = LinkResolver.rank(results, listOf("tavily"), SearchKind.OFFICIAL_LINK)
        assertEquals("https://tavily.com", r.primary!!.url)
        assertEquals(Trust.LIKELY_OFFICIAL, r.primary!!.trust)
        assertEquals(LinkClass.VENDOR, r.primary!!.cls)
        assertEquals(1, r.alternates.size)
        assertEquals(LinkClass.GITHUB_REPO, r.alternates[0].cls)
        assertTrue(r.all.none { it.cls == LinkClass.MIRROR })
    }

    @Test fun aSiteAppearsOnceAndAtMostTwoAlternatesFollow() {
        val results = listOf(
            res("https://tavily.com"), res("https://tavily.com/docs"), res("https://example.org/a"),
            res("https://example.net/b"), res("https://example.io/c")
        )
        val r = LinkResolver.rank(results, listOf("tavily"), SearchKind.OFFICIAL_LINK)
        assertEquals(2, r.alternates.size)
        assertEquals(3, r.all.size)
        assertEquals(3, r.all.map { it.registrable }.toSet().size)
    }

    @Test fun aDownloadRequestPutsAGithubReleaseFirstAndMirrorsLast() {
        val results = listOf(
            res("https://apkpure.com/x", score = 0.99),
            res("https://tavily.com/download"),
            res("https://tavily.com/pricing"),
            res("https://github.com/tavily-ai/app/releases/tag/v1"),
            res("https://example.org/dl")
        )
        val r = LinkResolver.rank(results, listOf("tavily"), SearchKind.DOWNLOAD_PAGE)
        assertEquals(LinkClass.GITHUB_RELEASE, r.primary!!.cls)
        assertEquals(LinkClass.VENDOR, r.alternates[0].cls)
        assertEquals(LinkClass.UNKNOWN, r.alternates[1].cls)
        assertTrue(r.all.none { it.trust == Trust.LOW })
    }

    @Test fun aDownloadRequestWithOnlyMirrorsStillShowsThemLabelledLow() {
        val r = LinkResolver.rank(listOf(res("https://apkpure.com/x"), res("https://uptodown.com/y")), listOf("tavily"), SearchKind.DOWNLOAD_PAGE)
        assertNotNull(r.primary)
        assertTrue(r.all.all { it.trust == Trust.LOW })
        assertEquals("low trust: third-party mirror", LinkResolver.badge(r.primary!!))
    }

    @Test fun anOfficialRequestWithOnlyMirrorsHasNoPrimary() {
        val r = LinkResolver.rank(listOf(res("https://apkpure.com/x")), listOf("tavily"), SearchKind.OFFICIAL_LINK)
        assertNull(r.primary)
        assertTrue(r.all.isEmpty())
    }

    @Test fun twoPagesOfOneRepoCountOnceAndTheReleasePageWins() {
        val results = listOf(res("https://github.com/o/r"), res("https://github.com/o/r/releases/tag/v1"))
        val r = LinkResolver.rank(results, listOf("r"), SearchKind.DOWNLOAD_PAGE)
        assertEquals(1, r.all.size)
        assertEquals(LinkClass.GITHUB_RELEASE, r.primary!!.cls)
    }

    @Test fun noResultsRankToNothing() {
        val r = LinkResolver.rank(emptyList(), listOf("tavily"), SearchKind.OFFICIAL_LINK)
        assertNull(r.primary)
        assertTrue(r.alternates.isEmpty())
    }

    @Test fun aHttpResultKeepsItsOwnAddress() {
        val c = LinkResolver.candidates(listOf(res("http://example.org/a")), listOf("x"))
        assertEquals("http://example.org/a", c[0].url)
    }

    private fun cand(url: String, pages: Int = 2): Candidate {
        val c = LinkResolver.classify(url, listOf("tavily"), pages)
        val host = UrlTools.host(url)
        return Candidate(url, "", host, UrlTools.registrableDomain(host), c.first, c.second, c.third, 0.0, pages, 0)
    }

    @Test fun badgesUseTheWordsShownToTheUser() {
        assertEquals("likely official", LinkResolver.badge(cand("https://tavily.com")))
        assertEquals("GitHub", LinkResolver.badge(cand("https://github.com/a/b")))
        assertEquals("GitHub releases", LinkResolver.badge(cand("https://github.com/a/b/releases")))
        assertEquals("Hugging Face", LinkResolver.badge(cand("https://huggingface.co/a/b")))
        assertEquals("app store", LinkResolver.badge(cand("https://play.google.com/x")))
        assertEquals("not confirmed", LinkResolver.badge(cand("https://example.org")))
        assertEquals("low trust: look-alike name", LinkResolver.badge(cand("https://tavilly.com")))
    }

    @Test fun githubReposListReleasePagesFirstAndSkipOtherPages() {
        val results = listOf(
            res("https://github.com/topics/llm"), res("https://github.com/a/one"),
            res("https://github.com/b/two/releases/tag/v1"), res("https://github.com/a/one/issues")
        )
        val repos = LinkResolver.githubRepos(results, 5)
        assertEquals(listOf("b" to "two", "a" to "one"), repos)
        assertEquals(1, LinkResolver.githubRepos(results, 1).size)
    }

    // ---------- file types and names ----------

    @Test fun extensionOfReadsKnownFileEndingsOnly() {
        assertEquals("apk", LinkResolver.extensionOf("https://x.com/a/app-release.APK?dl=1"))
        assertEquals("tar.gz", LinkResolver.extensionOf("model.tar.gz"))
        assertEquals("gguf", LinkResolver.extensionOf("https://x.com/f.gguf#frag"))
        assertEquals("gguf", LinkResolver.extensionOf("Gemma-2b.Q4_K_M.gguf"))
        assertNull(LinkResolver.extensionOf("a.txt"))
        assertNull(LinkResolver.extensionOf("https://x.com/download"))
        assertNull(LinkResolver.extensionOf("readme"))
    }

    @Test fun requestedExtensionsReadTheUsersWords() {
        assertEquals(listOf("apk"), LinkResolver.requestedExtensions("gemma apk direct link"))
        assertEquals(listOf("apk", "zip"), LinkResolver.requestedExtensions("zip ya APK do"))
        assertEquals(listOf("gguf"), LinkResolver.requestedExtensions("GGUF model"))
        assertTrue("tar.gz" in LinkResolver.requestedExtensions("tar.gz link"))
        assertTrue(LinkResolver.requestedExtensions("gemma official website").isEmpty())
    }

    @Test fun safeFileNameRemovesPathsAndOddCharacters() {
        assertEquals("My_App_v1.apk", LinkResolver.safeFileName("https://github.com/o/r/releases/download/v1/My%20App%20v1.apk"))
        assertEquals("passwd", LinkResolver.safeFileName("../../etc/passwd"))
        assertEquals("hidden.apk", LinkResolver.safeFileName(".hidden.apk"))
        assertEquals("download", LinkResolver.safeFileName("https://x.com/"))
        assertEquals("a_b_c.zip", LinkResolver.safeFileName("a<b>c.zip"))
    }

    @Test fun aLongFileNameKeepsItsEnding() {
        val n = LinkResolver.safeFileName("x".repeat(120) + ".apk")
        assertEquals(80, n.length)
        assertTrue(n.endsWith(".apk"))
    }

    // ---------- file links inside a page ----------

    private val page = "https://tavily.com/download"

    @Test fun findFileLinksKeepsOnlyHttpsFilesOnThePagesOwnDomain() {
        val text = "Get [Android app](https://tavily.com/dl/app-v1.apk) or the [zip](https://tavily.com/dl/src.zip). " +
            "Mirror: https://evil.example/app.apk and http://tavily.com/x.apk and https://tavily.com/page and https://cdn.tavily.com/a.apk."
        assertEquals(
            listOf("https://tavily.com/dl/app-v1.apk", "https://tavily.com/dl/src.zip", "https://cdn.tavily.com/a.apk"),
            LinkResolver.findFileLinks(text, page, emptyList())
        )
        assertEquals(
            listOf("https://tavily.com/dl/app-v1.apk", "https://cdn.tavily.com/a.apk"),
            LinkResolver.findFileLinks(text, page, listOf("apk"))
        )
    }

    @Test fun findFileLinksTrimsPunctuationAndRemovesDuplicates() {
        val text = "see https://tavily.com/a.apk. and again https://tavily.com/a.apk, ok"
        assertEquals(listOf("https://tavily.com/a.apk"), LinkResolver.findFileLinks(text, page, emptyList()))
    }

    @Test fun findFileLinksStopsAtFive() {
        val text = (1..8).joinToString(" ") { "https://tavily.com/f$it.apk" }
        assertEquals(LinkResolver.MAX_FILE_LINKS, LinkResolver.findFileLinks(text, page, emptyList()).size)
    }

    @Test fun findFileLinksNeedsAWebPage() {
        assertTrue(LinkResolver.findFileLinks("https://tavily.com/a.apk", "not a page", emptyList()).isEmpty())
    }
}
