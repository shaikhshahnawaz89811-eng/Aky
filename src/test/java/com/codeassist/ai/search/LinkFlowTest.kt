package com.codeassist.ai.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.ZoneId

/** Net search stage S3: the link workflow with fake GitHub, fake page extract and fake HEAD answers. */
class LinkFlowTest {
    private val now = 1_000_000_000L
    private val state = InMemoryBudgetState()
    private fun budget() = SearchBudget(state, 30, { now }, ZoneId.of("UTC"))

    private class Net {
        var repoFn: (String, String) -> GhOutcome<GitHubRepo> = { _, _ -> GhOutcome.NotFound }
        var latestFn: (String, String) -> GhOutcome<GitHubRelease> = { _, _ -> GhOutcome.NotFound }
        var extractFn: (ExtractRequest) -> SearchOutcome<TavilyExtractResponse> =
            { SearchOutcome.Fail(SearchFailure(FailureKind.SERVER)) }
        val heads = HashMap<String, HeadReply>()
        var cancelled: () -> Boolean = { false }
        val log = ArrayList<String>()
        var lastExtract: ExtractRequest? = null
        fun count(prefix: String) = log.count { it.startsWith(prefix) }
    }

    private fun deps(n: Net, turn: SearchBudget.Turn? = null) = LinkDeps(
        repo = { o, r -> n.log.add("repo $o/$r"); n.repoFn(o, r) },
        latest = { o, r -> n.log.add("latest $o/$r"); n.latestFn(o, r) },
        extract = { req -> n.log.add("extract " + req.urls.joinToString()); n.lastExtract = req; n.extractFn(req) },
        head = { url, _ -> n.log.add("head $url"); n.heads[url] ?: throw IOException("no route") },
        cancelled = { n.cancelled() },
        extractTurn = turn
    )

    // ---------- fixtures ----------

    private fun repo(owner: String = "ggml-org", name: String = "llama.cpp", fork: Boolean = false, archived: Boolean = false) =
        GitHubRepo(owner, name, "LLM inference", 70000, fork, archived, "https://github.com/$owner/$name", "MIT", "2026-10-12T10:00:00Z")

    private fun asset(name: String, size: Long = 1_000_000L, ct: String? = "application/zip", digest: String? = null) =
        GitHubAsset(name, size, ct, "https://github.com/ggml-org/llama.cpp/releases/download/b1/$name", digest, 3)

    private fun release(vararg a: GitHubAsset) = GitHubRelease(
        "b1", "Build b1", "2026-10-10T08:00:00Z", false, "https://github.com/ggml-org/llama.cpp/releases/tag/b1", a.toList()
    )

    private fun plan(vararg kinds: SearchKind, query: String) =
        SearchPlan(kinds.toList(), emptyList(), Freshness.ANY, emptyList(), 2, 2, false, query)

    private fun res(url: String, title: String = "T", content: String = "") = TavilyResult(title, url, content, 0.5, null, emptyList())

    private fun fileReply(size: Long = 9_000_000L, type: String = "application/vnd.android.package-archive") =
        HeadReply(200, mapOf("content-type" to type, "content-length" to size.toString()))

    private val direct = "ggml-org" to "llama.cpp"

    private fun Net.githubOk(rel: GitHubRelease) {
        repoFn = { _, _ -> GhOutcome.Ok(repo()) }
        latestFn = { _, _ -> GhOutcome.Ok(rel) }
    }

    // ---------- GitHub, the user wrote owner/repo ----------

    @Test fun aGithubRequestGivesRepoReleaseAndFileCards() {
        val n = Net()
        n.githubOk(release(asset("llama-b1-bin-android.zip", digest = "sha256:" + "b".repeat(64)), asset("llama-b1-bin-win.zip"), asset("checksums.txt")))
        val o = LinkFlow.run(plan(SearchKind.GITHUB, query = "ggml-org/llama.cpp latest release"), emptyList(), direct, deps(n))
        assertEquals(LinkSummary.GITHUB, o.summary)
        assertEquals(listOf("repo", "release", "file", "file"), o.cards.map { it.role })
        val repoCard = o.cards[0]
        assertEquals("ggml-org/llama.cpp", repoCard.title)
        assertTrue(repoCard.detail!!.contains("70000 stars"))
        assertTrue(repoCard.detail!!.contains("MIT"))
        assertTrue(repoCard.detail!!.contains("last push 2026-10-12"))
        assertEquals("ggml-org/llama.cpp b1", o.cards[1].title)
        assertTrue(o.cards[1].detail!!.contains("published 2026-10-10"))
        val f = o.cards[2]
        assertTrue(f.downloadable)
        assertFalse(f.runnable)
        assertEquals("sha256:" + "b".repeat(64), f.checksum)
        assertEquals("application/zip", f.mime)
        assertEquals("llama-b1-bin-android.zip", f.fileName)
        assertEquals("GitHub release file", f.badge)
        assertEquals("github.com", f.domain)
        assertNull(o.cards[3].checksum)
        assertEquals(2, o.ghCalls)
        assertEquals(0, o.extractRuns)
    }

    @Test fun onlyFilesWithAKnownTypeBecomeFileCards() {
        val n = Net()
        n.githubOk(release(asset("notes.txt"), asset("app.sig"), asset("tool.apk", ct = "application/vnd.android.package-archive")))
        val o = LinkFlow.run(plan(SearchKind.GITHUB, query = "ggml-org/llama.cpp"), emptyList(), direct, deps(n))
        assertEquals(listOf("tool.apk"), o.cards.filter { it.role == "file" }.map { it.title })
        assertTrue(o.cards.first { it.role == "file" }.runnable)
    }

    @Test fun aFileRequestListsOnlyTheTypeTheUserNamed() {
        val n = Net()
        n.githubOk(release(asset("app-release.apk", ct = "application/vnd.android.package-archive"), asset("src.zip")))
        val o = LinkFlow.run(plan(SearchKind.DIRECT_FILE, query = "ggml-org/llama.cpp apk"), emptyList(), direct, deps(n))
        assertEquals(LinkSummary.FILES, o.summary)
        assertEquals(listOf("file", "release"), o.cards.map { it.role })
        assertEquals("app-release.apk", o.cards[0].title)
        assertEquals(listOf("apk"), o.wanted)
    }

    @Test fun noFileOfTheNamedTypeGivesOnlyTheReleasePage() {
        val n = Net()
        n.githubOk(release(asset("src.zip")))
        val o = LinkFlow.run(plan(SearchKind.DIRECT_FILE, query = "ggml-org/llama.cpp apk"), emptyList(), direct, deps(n))
        assertEquals(LinkSummary.FILES_NONE_OF_TYPE, o.summary)
        assertEquals(listOf("release"), o.cards.map { it.role })
    }

    @Test fun aFileRequestWithNoTypeNamedShowsTheReleaseFiles() {
        val n = Net()
        n.githubOk(release(asset("a.zip"), asset("b.zip")))
        val o = LinkFlow.run(plan(SearchKind.DIRECT_FILE, query = "ggml-org/llama.cpp"), emptyList(), direct, deps(n))
        assertEquals(LinkSummary.FILES, o.summary)
        assertEquals(2, o.cards.count { it.role == "file" })
        val none = Net()
        none.githubOk(release(asset("checksums.txt")))
        val o2 = LinkFlow.run(plan(SearchKind.DIRECT_FILE, query = "ggml-org/llama.cpp"), emptyList(), direct, deps(none))
        assertEquals(LinkSummary.FILES_PAGE_ONLY, o2.summary)
    }

    @Test fun theGithubFilePairIsAFileRequest() {
        val n = Net()
        n.githubOk(release(asset("a.zip")))
        val o = LinkFlow.run(plan(SearchKind.GITHUB, SearchKind.DIRECT_FILE, query = "ggml-org/llama.cpp zip"), emptyList(), direct, deps(n))
        assertEquals(LinkSummary.FILES, o.summary)
    }

    @Test fun onlySixFileCardsAreShownAndTheRestIsCounted() {
        val n = Net()
        n.githubOk(release(*(1..9).map { asset("f$it.zip") }.toTypedArray()))
        val o = LinkFlow.run(plan(SearchKind.GITHUB, query = "ggml-org/llama.cpp"), emptyList(), direct, deps(n))
        assertEquals(LinkFlow.MAX_ASSET_CARDS, o.cards.count { it.role == "file" })
        assertEquals(3, o.hiddenFiles)
    }

    @Test fun aRepoWithNoStableReleaseGivesTheRepoCard() {
        val n = Net()
        n.repoFn = { _, _ -> GhOutcome.Ok(repo()) }
        val o = LinkFlow.run(plan(SearchKind.GITHUB, query = "ggml-org/llama.cpp"), emptyList(), direct, deps(n))
        assertEquals(LinkSummary.GITHUB_NO_RELEASE, o.summary)
        assertEquals(listOf("repo"), o.cards.map { it.role })
        assertEquals("ggml-org/llama.cpp", o.repo!!.fullName)
    }

    @Test fun aGithubErrorOnTheRepoStopsEarlyWithTheFailure() {
        val n = Net()
        n.repoFn = { _, _ -> GhOutcome.Fail(SearchFailure(FailureKind.RATE_LIMITED, 90)) }
        val o = LinkFlow.run(plan(SearchKind.GITHUB, query = "ggml-org/llama.cpp"), emptyList(), direct, deps(n))
        assertEquals(LinkSummary.GITHUB_ERROR, o.summary)
        assertEquals(FailureKind.RATE_LIMITED, o.ghFailure!!.kind)
        assertTrue(o.cards.isEmpty())
        assertEquals(0, n.count("latest"))
    }

    @Test fun aGithubErrorOnTheReleaseKeepsTheRepoCard() {
        val n = Net()
        n.repoFn = { _, _ -> GhOutcome.Ok(repo()) }
        n.latestFn = { _, _ -> GhOutcome.Fail(SearchFailure(FailureKind.SERVER)) }
        val o = LinkFlow.run(plan(SearchKind.GITHUB, query = "ggml-org/llama.cpp"), emptyList(), direct, deps(n))
        assertEquals(LinkSummary.GITHUB_ERROR, o.summary)
        assertEquals(listOf("repo"), o.cards.map { it.role })
        assertEquals(FailureKind.SERVER, o.ghFailure!!.kind)
    }

    // ---------- GitHub, found by search ----------

    @Test fun aNamedRepoThatDoesNotExistNeedsASearch() {
        val n = Net()
        val o = LinkFlow.run(plan(SearchKind.GITHUB, query = "foo/bar tool"), emptyList(), "foo" to "bar", deps(n))
        assertEquals(LinkSummary.NEED_SEARCH, o.summary)
        assertEquals(1, o.ghCalls)
        assertEquals(0, n.count("latest"))
    }

    @Test fun afterTheSearchANamedRepoThatDoesNotExistFallsToTheResults() {
        val n = Net()
        n.repoFn = { o, r -> if (o == "foo-org") GhOutcome.Ok(repo("foo-org", "bar-tool")) else GhOutcome.NotFound }
        n.latestFn = { _, _ -> GhOutcome.Ok(release(asset("a.zip"))) }
        val o = LinkFlow.run(plan(SearchKind.GITHUB, query = "foo/bar tool"), listOf(res("https://github.com/foo-org/bar-tool")), "foo" to "bar", deps(n))
        assertEquals(LinkSummary.GITHUB, o.summary)
        assertEquals("foo-org/bar-tool", o.repo!!.fullName)
    }

    @Test fun aGithubRepoFoundInSearchResultsIsLookedUpByName() {
        val n = Net()
        n.githubOk(release(asset("a.zip")))
        val raw = listOf(res("https://github.com/ggml-org/llama.cpp"), res("https://github.com/someone/unrelated"), res("https://example.com"))
        val o = LinkFlow.run(plan(SearchKind.GITHUB, query = "llama.cpp repo"), raw, null, deps(n))
        assertEquals(LinkSummary.GITHUB, o.summary)
        assertEquals(listOf("repo ggml-org/llama.cpp", "latest ggml-org/llama.cpp"), n.log)
    }

    @Test fun aForkIsSkippedForTheRealRepo() {
        val n = Net()
        n.repoFn = { o, r -> GhOutcome.Ok(repo(o, r, fork = o == "forker")) }
        n.latestFn = { _, _ -> GhOutcome.Ok(release(asset("a.zip"))) }
        val raw = listOf(res("https://github.com/forker/llama.cpp"), res("https://github.com/ggml-org/llama.cpp"))
        val o = LinkFlow.run(plan(SearchKind.GITHUB, query = "llama.cpp repo"), raw, null, deps(n))
        assertEquals("ggml-org/llama.cpp", o.repo!!.fullName)
        assertEquals(2, n.count("repo"))
        assertEquals(1, n.count("latest"))
    }

    @Test fun noGithubRepoInTheResultsSaysNotFoundAndShowsCandidates() {
        val n = Net()
        val o = LinkFlow.run(plan(SearchKind.GITHUB, query = "llama.cpp repo"), listOf(res("https://example.com/x")), null, deps(n))
        assertEquals(LinkSummary.GITHUB_NOT_FOUND, o.summary)
        assertEquals(1, o.cards.size)
        assertEquals("not confirmed", o.cards[0].badge)
        assertEquals(0, n.log.size)
        assertEquals(LinkSummary.GITHUB_NOT_FOUND, LinkFlow.run(plan(SearchKind.GITHUB, query = "x repo"), emptyList(), null, deps(Net())).summary)
    }

    // ---------- official link ----------

    private val tavilyResults = listOf(
        res("https://tavily.com", "Tavily"), res("https://docs.tavily.com/x", "Docs"),
        res("https://github.com/tavily-ai/tavily-python", "SDK"), res("https://apkpure.com/tavily", "Mirror")
    )

    @Test fun anOfficialRequestGivesTheLikelyOfficialSiteFirst() {
        val n = Net()
        val o = LinkFlow.run(plan(SearchKind.OFFICIAL_LINK, query = "tavily official website"), tavilyResults, null, deps(n))
        assertEquals(LinkSummary.OFFICIAL, o.summary)
        assertEquals(listOf("primary", "alternate"), o.cards.map { it.role })
        assertEquals("tavily.com", o.cards[0].domain)
        assertEquals("likely official", o.cards[0].badge)
        assertEquals("LIKELY_OFFICIAL", o.cards[0].trust)
        assertEquals("2 result pages on this domain", o.cards[0].detail)
        assertEquals("GitHub", o.cards[1].badge)
        assertTrue(o.cards.none { it.downloadable })
        assertEquals(0, n.log.size)
    }

    @Test fun oneResultPageIsNotEnoughToCallASiteOfficial() {
        val o = LinkFlow.run(plan(SearchKind.OFFICIAL_LINK, query = "tavily official website"), listOf(res("https://tavily.com")), null, deps(Net()))
        assertEquals(LinkSummary.OFFICIAL_UNCONFIRMED, o.summary)
        assertEquals("not confirmed", o.cards[0].badge)
    }

    @Test fun onlyMirrorsOrNothingGivesNothingForAnOfficialRequest() {
        val p = plan(SearchKind.OFFICIAL_LINK, query = "tavily official website")
        assertEquals(LinkSummary.NOTHING, LinkFlow.run(p, listOf(res("https://apkpure.com/x")), null, deps(Net())).summary)
        assertEquals(LinkSummary.NOTHING, LinkFlow.run(p, emptyList(), null, deps(Net())).summary)
    }

    @Test fun anOfficialRequestIgnoresAWrittenRepo() {
        val n = Net()
        LinkFlow.run(plan(SearchKind.OFFICIAL_LINK, query = "tavily official website"), tavilyResults, "a" to "b", deps(n))
        assertEquals(0, n.log.size)
    }

    // ---------- download page ----------

    @Test fun aDownloadPageRequestShowsClassAndTheLatestTagOfAGithubPage() {
        val n = Net()
        n.latestFn = { _, _ -> GhOutcome.Ok(release()) }
        val raw = listOf(res("https://github.com/ggml-org/llama.cpp/releases/tag/b1"), res("https://apkpure.com/llama"))
        val o = LinkFlow.run(plan(SearchKind.DOWNLOAD_PAGE, query = "llama.cpp download link"), raw, null, deps(n))
        assertEquals(LinkSummary.PAGES, o.summary)
        assertEquals("GitHub releases", o.cards[0].badge)
        assertEquals("latest: b1 (2026-10-10)", o.cards[0].detail)
        assertEquals("low trust: third-party mirror", o.cards[1].badge)
        assertTrue(o.cards.none { it.downloadable })
        assertEquals(1, o.ghCalls)
    }

    @Test fun aGithubErrorOnADownloadPageJustLeavesTheNoteOut() {
        val n = Net()
        n.latestFn = { _, _ -> GhOutcome.Fail(SearchFailure(FailureKind.RATE_LIMITED)) }
        val o = LinkFlow.run(plan(SearchKind.DOWNLOAD_PAGE, query = "llama.cpp download link"), listOf(res("https://github.com/a/b/releases")), null, deps(n))
        assertEquals(LinkSummary.PAGES, o.summary)
        assertNull(o.cards[0].detail)
    }

    @Test fun onlyMirrorsGivePagesLowOnly() {
        val raw = listOf(res("https://apkpure.com/x"), res("https://uptodown.com/y"))
        val n = Net()
        val o = LinkFlow.run(plan(SearchKind.DOWNLOAD_PAGE, query = "tavily download link"), raw, null, deps(n))
        assertEquals(LinkSummary.PAGES_LOW_ONLY, o.summary)
        assertEquals(0, n.log.size)
    }

    @Test fun aDownloadPageRequestWithAWrittenRepoUsesTheApi() {
        val n = Net()
        n.githubOk(release(asset("a.zip")))
        val o = LinkFlow.run(plan(SearchKind.DOWNLOAD_PAGE, query = "ggml-org/llama.cpp download"), emptyList(), direct, deps(n))
        assertEquals(LinkSummary.PAGES, o.summary)
        assertEquals(listOf("release", "repo"), o.cards.map { it.role })
        assertTrue(o.cards.none { it.role == "file" })
    }

    // ---------- direct file outside GitHub ----------

    private val page = "https://tavily.com/download"
    private val apk = "https://tavily.com/dl/app-v1.apk"

    private fun vendorRaw(content: String = "") = listOf(res(page, "Download", content), res("https://docs.tavily.com/start", "Start"))

    private val fileQuery = plan(SearchKind.DIRECT_FILE, query = "tavily apk direct link")

    @Test fun aFileLinkInAVendorSnippetIsCheckedAndOffered() {
        val n = Net()
        n.heads[apk] = fileReply()
        val o = LinkFlow.run(fileQuery, vendorRaw("Get the app: $apk now"), null, deps(n))
        assertEquals(LinkSummary.FILES, o.summary)
        val f = o.cards.single()
        assertEquals("file", f.role)
        assertEquals(apk, f.url)
        assertTrue(f.downloadable)
        assertTrue(f.runnable)
        assertEquals(9_000_000L, f.sizeBytes)
        assertEquals("application/vnd.android.package-archive", f.mime)
        assertNull(f.checksum)
        assertEquals("likely official", f.badge)
        assertTrue(f.detail!!.contains("9 MB"))
        assertTrue(f.detail!!.contains("checked: https"))
        assertEquals(1, o.guardPasses)
        assertEquals(0, o.extractRuns)
    }

    @Test fun aFileThatFailsTheCheckIsNotOfferedAndThePageIsGivenInstead() {
        val n = Net()
        n.heads[apk] = HeadReply(200, mapOf("content-type" to "text/html"))
        val o = LinkFlow.run(fileQuery, vendorRaw("Get the app: $apk now"), null, deps(n))
        assertEquals(LinkSummary.FILES_PAGE_ONLY, o.summary)
        assertEquals(1, o.guardRejects)
        assertTrue(o.cards.none { it.role == "file" })
        assertEquals("primary", o.cards[0].role)
        assertEquals("tavily.com", o.cards[0].domain)
    }

    @Test fun whenNoLinkIsInTheSnippetOnePageExtractLooksForIt() {
        val n = Net()
        n.heads["https://tavily.com/dl/app.apk"] = fileReply()
        n.extractFn = {
            SearchOutcome.Ok(TavilyExtractResponse(listOf(TavilyExtractItem(page, "Install: [Android](https://tavily.com/dl/app.apk)")), emptyList(), 1))
        }
        val turn = budget().startTurn(2)
        val o = LinkFlow.run(fileQuery, vendorRaw(), null, deps(n, turn))
        assertEquals(LinkSummary.FILES, o.summary)
        assertEquals(1, o.extractRuns)
        assertEquals(1, o.extractCredits)
        assertEquals("markdown", n.lastExtract!!.format)
        assertEquals(listOf(page), n.lastExtract!!.urls)
        assertEquals(5, n.lastExtract!!.chunksPerSource)
        assertTrue(n.lastExtract!!.query!!.contains("tavily"))
    }

    @Test fun aFailedExtractGivesItsCreditBackAndTheFailureIsReported() {
        val n = Net()
        val turn = budget().startTurn(2)
        val o = LinkFlow.run(fileQuery, vendorRaw(), null, deps(n, turn))
        assertEquals(LinkSummary.FILES_PAGE_ONLY, o.summary)
        assertEquals(FailureKind.SERVER, o.extractFailure!!.kind)
        assertEquals(0, o.extractCredits)
        assertEquals(0, turn.spent)
    }

    @Test fun aTimedOutExtractKeepsItsCharge() {
        val n = Net()
        n.extractFn = { SearchOutcome.Fail(SearchFailure(FailureKind.TIMEOUT)) }
        val turn = budget().startTurn(2)
        val o = LinkFlow.run(fileQuery, vendorRaw(), null, deps(n, turn))
        assertEquals(1, o.extractCredits)
    }

    @Test fun noExtractWithoutAnExtractTurnOrWithoutAnOfficialPage() {
        val n = Net()
        val o = LinkFlow.run(fileQuery, vendorRaw(), null, deps(n, null))
        assertEquals(0, o.extractRuns)
        assertEquals(0, n.count("extract"))
        val n2 = Net()
        LinkFlow.run(fileQuery, listOf(res("https://example.org/get")), null, deps(n2, budget().startTurn(2)))
        assertEquals(0, n2.count("extract"))
    }

    @Test fun aHuggingFaceFileIsOfferedButItsModelPageIsNot() {
        val n = Net()
        val file = "https://huggingface.co/google/gemma-2b/resolve/main/gemma.gguf"
        n.heads[file] = fileReply(2_000_000_000L, "application/octet-stream")
        val raw = listOf(res(file), res("https://huggingface.co/google/gemma-2b"))
        val o = LinkFlow.run(plan(SearchKind.DIRECT_FILE, query = "gemma gguf download"), raw, null, deps(n))
        assertEquals(LinkSummary.FILES, o.summary)
        assertEquals("Hugging Face", o.cards[0].badge)
        assertEquals(listOf("head $file"), n.log)
        assertFalse(o.cards[0].runnable)
    }

    @Test fun aFileOnAnUnconfirmedSiteIsNeverCheckedOrOffered() {
        val n = Net()
        val o = LinkFlow.run(plan(SearchKind.DIRECT_FILE, query = "app apk link"), listOf(res("https://random.example/app.apk")), null, deps(n))
        assertEquals(LinkSummary.FILES_PAGE_ONLY, o.summary)
        assertEquals(0, n.count("head"))
        assertTrue(o.cards.none { it.downloadable })
    }

    @Test fun aMirrorFileIsNeverOffered() {
        val n = Net()
        val o = LinkFlow.run(plan(SearchKind.DIRECT_FILE, query = "tavily apk link"), listOf(res("https://apkpure.com/tavily/app.apk")), null, deps(n))
        assertEquals(0, n.count("head"))
        assertTrue(o.cards.none { it.downloadable })
    }

    @Test fun atMostThreeFilesAreChecked() {
        val n = Net()
        val links = (1..5).map { "https://tavily.com/dl/f$it.apk" }
        links.forEach { n.heads[it] = fileReply() }
        val o = LinkFlow.run(fileQuery, vendorRaw(links.joinToString(" ")), null, deps(n))
        assertEquals(LinkFlow.MAX_GUARD_CHECKS, n.count("head"))
        assertEquals(3, o.cards.count { it.role == "file" })
    }

    @Test fun aSearchWithNoResultsGivesNothing() {
        assertEquals(LinkSummary.NOTHING, LinkFlow.run(fileQuery, emptyList(), null, deps(Net())).summary)
    }

    // ---------- stop ----------

    @Test fun stopBeforeAnythingMakesNoCall() {
        val n = Net()
        n.cancelled = { true }
        val o = LinkFlow.run(plan(SearchKind.GITHUB, query = "ggml-org/llama.cpp"), emptyList(), direct, deps(n))
        assertEquals(LinkSummary.STOPPED, o.summary)
        assertEquals(0, n.log.size)
    }

    @Test fun stopAfterTheFirstGithubCallEndsTheFlow() {
        val n = Net()
        n.githubOk(release(asset("a.zip")))
        n.cancelled = { n.log.isNotEmpty() }
        val o = LinkFlow.run(plan(SearchKind.GITHUB, query = "ggml-org/llama.cpp"), emptyList(), direct, deps(n))
        assertEquals(LinkSummary.STOPPED, o.summary)
        assertEquals(1, n.log.size)
    }
}
