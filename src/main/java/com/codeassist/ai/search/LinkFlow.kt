package com.codeassist.ai.search

/** What the link flow found, in the order the user should see it. */
enum class LinkSummary {
    /** Official-link request: the first card is "likely official" (domain carries the name, 2+ result pages). */
    OFFICIAL,

    /** Official-link request: candidates only, none confirmed. */
    OFFICIAL_UNCONFIRMED,

    /** Download-page request: pages with class and domain. */
    PAGES,

    /** Download-page request: only third-party mirrors or look-alike names were found. */
    PAGES_LOW_ONLY,

    /** File request: file cards that passed the rules (GitHub release files from the API, or checked direct links). */
    FILES,

    /** File request: a GitHub release exists but has no file of the type the user named. */
    FILES_NONE_OF_TYPE,

    /** File request: no file could be confirmed; the page links are given instead. */
    FILES_PAGE_ONLY,

    /** GitHub request: repo card, latest release and its files. */
    GITHUB,

    /** GitHub request: the repo exists but has no published stable release. */
    GITHUB_NO_RELEASE,

    /** GitHub request: no such repo (or none that matches the name). Candidates, if any, are cards. */
    GITHUB_NOT_FOUND,

    /** The GitHub API answered with an error (rate limit, server ...). [LinkOutcome.ghFailure] says which. */
    GITHUB_ERROR,

    /** Nothing usable in the search results. */
    NOTHING,

    /** The user named an `owner/repo` that does not exist: the caller should search and run the flow again with the results. */
    NEED_SEARCH,

    /** The user pressed Stop. */
    STOPPED
}

class LinkOutcome(
    val summary: LinkSummary,
    val cards: List<WebCard>,
    val repo: GitHubRepo? = null,
    val release: GitHubRelease? = null,
    /** Release files that fit the request but are not shown as cards. */
    val hiddenFiles: Int = 0,
    /** The file types the user named ("apk"), empty when none. */
    val wanted: List<String> = emptyList(),
    val ghFailure: SearchFailure? = null,
    val extractFailure: SearchFailure? = null,
    val extractCredits: Int = 0,
    val ghCalls: Int = 0,
    val extractRuns: Int = 0,
    val guardPasses: Int = 0,
    val guardRejects: Int = 0
)

/**
 * The network calls the link flow may make. Every one is injected (StepTimeout wrappers on the phone, canned answers in the tests). A call
 * that was stopped by the user returns any failure; the flow looks at [cancelled] after each call and ends with [LinkSummary.STOPPED].
 */
class LinkDeps(
    val repo: (String, String) -> GhOutcome<GitHubRepo>,
    val latest: (String, String) -> GhOutcome<GitHubRelease>,
    val extract: (ExtractRequest) -> SearchOutcome<TavilyExtractResponse>,
    val head: HeadTransport,
    val cancelled: () -> Boolean,
    /** The credits left for one page extract, or null when the plan allows none. */
    val extractTurn: SearchBudget.Turn?
)

/**
 * Net Search Agent, stage S3: the workflow behind "official site", "download page", "direct link" and "github" requests (audit section 3,
 * rows 3, 4, 5 and 10). Pure JVM. No model is involved: every word of the reply is built by [LinkAnswer] from the facts found here.
 *
 * Three different things never get mixed up (audit section 3): a PAGE link (search result, ranked and labelled), a FILE address
 * (an exact asset, with facts), and the DOWNLOAD itself (only after a tap, by the phone's DownloadManager, never here).
 *
 * Where a file card may come from, and nowhere else:
 *  1. a release file listed by the GitHub API (address, name, size, type, digest are the API's own fields);
 *  2. an address on a "likely official" vendor domain (found in a search snippet or in the text of the vendor page) or a Hugging Face
 *     `/resolve/` file, that passed [DownloadGuard.check].
 * A file address is never built from guesses, never taken from a model, never followed to another site.
 */
object LinkFlow {
    const val MAX_ASSET_CARDS = 6
    const val MAX_GUARD_CHECKS = 3
    const val MAX_GITHUB_REPOS = 2

    fun run(plan: SearchPlan, raw: List<TavilyResult>, direct: Pair<String, String>?, deps: LinkDeps): LinkOutcome =
        Runner(plan, raw, direct, deps).execute()

    private enum class Mode { OFFICIAL, PAGES, GITHUB, FILE }

    private class Runner(
        val plan: SearchPlan,
        val raw: List<TavilyResult>,
        val direct: Pair<String, String>?,
        val deps: LinkDeps
    ) {
        val mode: Mode = when {
            SearchKind.DIRECT_FILE in plan.kinds -> Mode.FILE
            SearchKind.GITHUB in plan.kinds -> Mode.GITHUB
            SearchKind.OFFICIAL_LINK in plan.kinds -> Mode.OFFICIAL
            else -> Mode.PAGES
        }
        val tokens: List<String> = LinkResolver.entityTokens(plan.query)
        val wanted: List<String> = LinkResolver.requestedExtensions(plan.query)

        var ghCalls = 0
        var extractRuns = 0
        var extractCredits = 0
        var passes = 0
        var rejects = 0
        var extractFailure: SearchFailure? = null

        fun outcome(
            summary: LinkSummary,
            cards: List<WebCard> = emptyList(),
            repo: GitHubRepo? = null,
            release: GitHubRelease? = null,
            hidden: Int = 0,
            ghFailure: SearchFailure? = null
        ) = LinkOutcome(
            summary, cards, repo, release, hidden, wanted, ghFailure, extractFailure, extractCredits, ghCalls, extractRuns, passes, rejects
        )

        fun stopped(): LinkOutcome = outcome(LinkSummary.STOPPED)

        fun execute(): LinkOutcome {
            if (deps.cancelled()) return stopped()
            if (direct != null && mode != Mode.OFFICIAL) {
                val r = githubFlow(direct.first, direct.second, skipFork = false)
                if (r != null) return r
                if (deps.cancelled()) return stopped()
                if (raw.isEmpty()) return outcome(LinkSummary.NEED_SEARCH)
            }
            if (raw.isEmpty()) return outcome(if (mode == Mode.GITHUB) LinkSummary.GITHUB_NOT_FOUND else LinkSummary.NOTHING)
            return when (mode) {
                Mode.OFFICIAL -> official()
                Mode.PAGES -> pages()
                Mode.GITHUB -> githubFromResults() ?: githubNotFound()
                Mode.FILE -> fileFromResults()
            }
        }

        // ---------- page cards ----------

        fun pageCard(c: Candidate, role: String, detail: String?): WebCard = WebCard(
            role = role,
            title = c.title.ifBlank { c.host },
            url = c.url,
            domain = c.host,
            badge = LinkResolver.badge(c),
            trust = c.trust.name,
            detail = detail,
            fileName = null,
            sizeBytes = null,
            mime = null,
            checksum = null,
            downloadable = false,
            runnable = false
        )

        fun pageDetail(c: Candidate): String? = when {
            c.trust == Trust.LIKELY_OFFICIAL -> "${c.results} result pages on this domain"
            c.trust == Trust.LOW -> c.reason
            else -> null
        }

        fun pageCards(r: Ranked): List<WebCard> {
            val out = ArrayList<WebCard>()
            val p = r.primary ?: return out
            out.add(pageCard(p, "primary", pageDetail(p)))
            for (a in r.alternates) out.add(pageCard(a, "alternate", pageDetail(a)))
            return out
        }

        fun official(): LinkOutcome {
            val ranked = LinkResolver.rank(raw, tokens, SearchKind.OFFICIAL_LINK)
            val p = ranked.primary ?: return outcome(LinkSummary.NOTHING)
            val s = if (p.trust == Trust.LIKELY_OFFICIAL) LinkSummary.OFFICIAL else LinkSummary.OFFICIAL_UNCONFIRMED
            return outcome(s, pageCards(ranked))
        }

        fun pages(): LinkOutcome {
            val ranked = LinkResolver.rank(raw, tokens, SearchKind.DOWNLOAD_PAGE)
            val p = ranked.primary ?: return outcome(LinkSummary.NOTHING)
            // the audit asks for the GitHub API here too: the newest tag and date of the top GitHub page, shown on its card
            var latestNote: String? = null
            val repo = LinkResolver.githubRepoOf(p.url)
            if (repo != null) {
                ghCalls++
                val lo = deps.latest(repo.first, repo.second)
                if (deps.cancelled()) return stopped()
                if (lo is GhOutcome.Ok) latestNote = "latest: " + lo.value.tag + dateSuffix(lo.value.publishedAt)
            }
            val cards = ArrayList<WebCard>()
            cards.add(pageCard(p, "primary", latestNote ?: pageDetail(p)))
            for (a in ranked.alternates) cards.add(pageCard(a, "alternate", pageDetail(a)))
            val onlyLow = ranked.all.all { it.trust == Trust.LOW }
            return outcome(if (onlyLow) LinkSummary.PAGES_LOW_ONLY else LinkSummary.PAGES, cards)
        }

        fun dateSuffix(iso: String?): String = if (iso.isNullOrBlank()) "" else " (" + LinkFmt.day(iso) + ")"

        // ---------- GitHub ----------

        fun nameMatches(repo: Pair<String, String>): Boolean {
            if (tokens.isEmpty()) return true
            val flat = (repo.first + repo.second).lowercase().filter { it.isLetterOrDigit() }
            return tokens.any { flat.contains(it) }
        }

        /** The facts of [owner]/[repo] and its latest release as cards. Null when the repo does not exist (or is a fork and [skipFork]). */
        fun githubFlow(owner: String, repoName: String, skipFork: Boolean): LinkOutcome? {
            ghCalls++
            val ro = deps.repo(owner, repoName)
            if (deps.cancelled()) return stopped()
            val repo: GitHubRepo = when (ro) {
                is GhOutcome.Ok -> ro.value
                is GhOutcome.NotFound -> return null
                is GhOutcome.Fail -> return outcome(LinkSummary.GITHUB_ERROR, ghFailure = ro.failure)
            }
            if (skipFork && repo.fork) return null
            val repoC = repoCard(repo)
            ghCalls++
            val lo = deps.latest(owner, repoName)
            if (deps.cancelled()) return stopped()
            val release: GitHubRelease = when (lo) {
                is GhOutcome.Ok -> lo.value
                is GhOutcome.NotFound -> return outcome(LinkSummary.GITHUB_NO_RELEASE, listOf(repoC), repo = repo)
                is GhOutcome.Fail -> return outcome(LinkSummary.GITHUB_ERROR, listOf(repoC), repo = repo, ghFailure = lo.failure)
            }
            val fits = release.assets.filter { a ->
                val ext = LinkResolver.extensionOf(a.name)
                ext != null && (wanted.isEmpty() || ext in wanted) && DownloadGuard.checkUrl(a.url) == null
            }
            val shown = fits.take(MAX_ASSET_CARDS)
            val hidden = fits.size - shown.size
            val relCard = releaseCard(release, repo)
            return when (mode) {
                Mode.GITHUB -> {
                    val cards = ArrayList<WebCard>()
                    cards.add(repoC)
                    cards.add(relCard)
                    cards.addAll(shown.map { assetCard(it, release) })
                    outcome(LinkSummary.GITHUB, cards, repo, release, hidden)
                }
                Mode.FILE -> {
                    val cards = ArrayList<WebCard>()
                    cards.addAll(shown.map { assetCard(it, release) })
                    cards.add(relCard)
                    val s = when {
                        shown.isNotEmpty() -> LinkSummary.FILES
                        wanted.isNotEmpty() -> LinkSummary.FILES_NONE_OF_TYPE
                        else -> LinkSummary.FILES_PAGE_ONLY
                    }
                    outcome(s, cards, repo, release, hidden)
                }
                else -> outcome(LinkSummary.PAGES, listOf(relCard, repoC), repo, release, hidden)
            }
        }

        fun githubFromResults(): LinkOutcome? {
            val repos = LinkResolver.githubRepos(raw, 6).filter { nameMatches(it) }
            var tried = 0
            for (r in repos) {
                if (tried >= MAX_GITHUB_REPOS) break
                tried++
                val out = githubFlow(r.first, r.second, skipFork = true)
                if (out != null) return out
                if (deps.cancelled()) return stopped()
            }
            return null
        }

        fun githubNotFound(): LinkOutcome {
            val ranked = LinkResolver.rank(raw, tokens, SearchKind.DOWNLOAD_PAGE)
            return outcome(LinkSummary.GITHUB_NOT_FOUND, pageCards(ranked))
        }

        fun repoCard(r: GitHubRepo): WebCard {
            val bits = ArrayList<String>()
            if (r.stars != null) bits.add(r.stars.toString() + " stars")
            if (r.license != null) bits.add(r.license)
            if (r.pushedAt != null) bits.add("last push " + LinkFmt.day(r.pushedAt))
            if (r.archived) bits.add("archived")
            if (r.fork) bits.add("fork")
            val desc = r.description?.let { LinkFmt.clean(it, 110) }
            val detail = (bits.joinToString(" · ") + (if (desc.isNullOrEmpty()) "" else "\n" + desc)).ifEmpty { null }
            return WebCard(
                "repo", r.fullName, r.htmlUrl, "github.com", if (r.fork) "GitHub (fork)" else "GitHub", Trust.KNOWN_HOST.name,
                detail, null, null, null, null, false, false
            )
        }

        fun releaseCard(rel: GitHubRelease, repo: GitHubRepo): WebCard {
            val title = repo.fullName + " " + rel.tag
            val detail = listOfNotNull(
                rel.publishedAt?.let { "published " + LinkFmt.day(it) },
                if (rel.assets.isNotEmpty()) "${rel.assets.size} files" else null,
                if (rel.prerelease) "pre-release" else null
            ).joinToString(" · ").ifEmpty { null }
            return WebCard(
                "release", title, rel.htmlUrl, "github.com", "GitHub releases", Trust.KNOWN_HOST.name,
                detail, null, null, null, null, false, false
            )
        }

        fun assetCard(a: GitHubAsset, rel: GitHubRelease): WebCard {
            val ext = LinkResolver.extensionOf(a.name) ?: ""
            val size = if (a.sizeBytes > 0) a.sizeBytes else null
            val detail = listOfNotNull(
                rel.tag,
                size?.let { LinkFmt.size(it) },
                a.contentType
            ).joinToString(" · ")
            return WebCard(
                "file", a.name, a.url, "github.com", "GitHub release file", Trust.KNOWN_HOST.name, detail,
                LinkResolver.safeFileName(a.url), size, a.contentType, a.digest, true, ext in LinkResolver.RUNNABLE
            )
        }

        // ---------- direct files outside GitHub ----------

        fun policyAllows(c: Candidate, url: String): Boolean {
            if (c.cls == LinkClass.HUGGINGFACE) return url.contains("/resolve/")
            return c.cls == LinkClass.VENDOR && c.trust == Trust.LIKELY_OFFICIAL
        }

        fun fileFromResults(): LinkOutcome {
            val gh = githubFromResults()
            if (gh != null) return gh
            if (deps.cancelled()) return stopped()

            val cands = LinkResolver.candidates(raw, tokens)
            val byUrl = LinkedHashMap<String, Candidate>()
            for (c in cands) {
                if (LinkResolver.extensionOf(c.url) != null && policyAllows(c, c.url)) byUrl.putIfAbsent(c.url, c)
            }
            val officialPages = cands.filter { it.cls == LinkClass.VENDOR && it.trust == Trust.LIKELY_OFFICIAL }
            for ((i, r) in raw.withIndex()) {
                val cand = cands.firstOrNull { it.order == i } ?: continue
                if (cand.cls != LinkClass.VENDOR || cand.trust != Trust.LIKELY_OFFICIAL) continue
                for (u in LinkResolver.findFileLinks(r.content, r.url, wanted)) byUrl.putIfAbsent(u, cand)
            }
            if (byUrl.isEmpty() && officialPages.isNotEmpty()) {
                val page = officialPages[0]
                val found = extractLinks(page)
                if (deps.cancelled()) return stopped()
                for (u in found) byUrl.putIfAbsent(u, page)
            }

            val files = ArrayList<WebCard>()
            for ((u, cand) in byUrl.entries.take(MAX_GUARD_CHECKS)) {
                if (deps.cancelled()) return stopped()
                when (val v = DownloadGuard.check(u, wanted, deps.head)) {
                    is GuardVerdict.Pass -> {
                        passes++
                        files.add(guardedCard(u, cand, v.facts))
                    }
                    is GuardVerdict.Reject -> rejects++
                }
            }
            if (files.isNotEmpty()) return outcome(LinkSummary.FILES, files)
            val pagesRanked = LinkResolver.rank(raw, tokens, SearchKind.DIRECT_FILE)
            if (pagesRanked.primary == null) return outcome(LinkSummary.NOTHING)
            return outcome(LinkSummary.FILES_PAGE_ONLY, pageCards(pagesRanked))
        }

        /** One page extract (markdown keeps the links) of the vendor page, then the file addresses written in it on the same domain. */
        fun extractLinks(page: Candidate): List<String> {
            val turn = deps.extractTurn ?: return emptyList()
            if (!turn.charge(1)) return emptyList()
            extractRuns++
            val q = ("download link " + tokens.joinToString(" ") + " " + wanted.joinToString(" ")).trim()
            val req = ExtractRequest(listOf(page.url), q, 5, false, 25, "markdown")
            val res = deps.extract(req)
            return when (res) {
                is SearchOutcome.Ok -> {
                    val used = res.value.credits
                    if (used != null) turn.settle(1, used)
                    extractCredits = turn.spent
                    val links = ArrayList<String>()
                    for (item in res.value.results) links.addAll(LinkResolver.findFileLinks(item.rawContent, page.url, wanted))
                    links.distinct()
                }
                is SearchOutcome.Fail -> {
                    if (res.failure.kind.refundsCredit()) turn.settle(1, 0)
                    extractCredits = turn.spent
                    extractFailure = res.failure
                    emptyList()
                }
            }
        }

        fun guardedCard(url: String, cand: Candidate, f: FileFacts): WebCard {
            val redirects = f.hops.size - 1
            val detail = listOfNotNull(
                f.sizeBytes?.let { LinkFmt.size(it) } ?: "size unknown",
                f.contentType,
                "checked: https" + (if (redirects > 0) ", $redirects redirect" + (if (redirects > 1) "s" else "") else "")
            ).joinToString(" · ")
            val name = LinkResolver.safeFileName(url)
            return WebCard(
                "file", name, url, cand.host, LinkResolver.badge(cand), cand.trust.name, detail,
                name, f.sizeBytes, f.contentType, null, true, f.extension in LinkResolver.RUNNABLE
            )
        }
    }
}
