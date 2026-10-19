package com.codeassist.ai.ai

import com.codeassist.ai.data.Message
import com.codeassist.ai.data.Role
import com.codeassist.ai.data.Store
import com.codeassist.ai.search.DownloadGuard
import com.codeassist.ai.search.ExtractRequest
import com.codeassist.ai.search.FailureKind
import com.codeassist.ai.search.GatherResult
import com.codeassist.ai.search.GhOutcome
import com.codeassist.ai.search.GitHubClient
import com.codeassist.ai.search.GitHubRelease
import com.codeassist.ai.search.GitHubRepo
import com.codeassist.ai.search.LinkAnswer
import com.codeassist.ai.search.LinkDeps
import com.codeassist.ai.search.LinkFlow
import com.codeassist.ai.search.LinkOutcome
import com.codeassist.ai.search.LinkResolver
import com.codeassist.ai.search.LinkSummary
import com.codeassist.ai.search.SearchBudget
import com.codeassist.ai.search.SearchFailure
import com.codeassist.ai.search.SearchFlow
import com.codeassist.ai.search.SearchKind
import com.codeassist.ai.search.SearchOutcome
import com.codeassist.ai.search.TavilyClient
import com.codeassist.ai.search.TavilyExtractResponse
import com.codeassist.ai.search.WebGate
import com.codeassist.ai.search.WebInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Net Search Agent, stage S3: the Android layer of an official-link / download-page / direct-file / GitHub request. The rules and the
 * words are in the pure `search` package ([LinkFlow], [LinkResolver], [DownloadGuard], [LinkAnswer]); this object only
 *  - tries the GitHub API first when the user wrote `owner/repo` (no search credit),
 *  - otherwise searches (the S2 search flow: budget, cache, retry, Stop),
 *  - runs every blocking call (GitHub, page extract) inside StepTimeout so Stop is seen within about 100 ms,
 *  - builds the stored message: the text written by the app, the cards in `Message.web`.
 *
 * No model is called, so nothing a web page says can steer the answer, and nothing here downloads anything: a file is fetched only when
 * the user taps Download on a card and confirms ([LinkDownloader]).
 */
object LinkRunner {
    private const val GITHUB_TIMEOUT_MS = 8_000
    private const val EXTRACT_TIMEOUT_MS = 35_000

    suspend fun run(run: ChatRunner.Run, go: WebGate.Decision.Run, progress: () -> Unit): Message {
        ConvKpi.inc("web_link_runs")
        val plan = go.plan
        val engine = Store.brainProvider
        val budget = SearchRunner.budget()
        val kindName = plan.kinds.joinToString("+") { it.name.lowercase() }
        val direct = if (SearchKind.OFFICIAL_LINK in plan.kinds) null else LinkResolver.repoFromText(plan.query)

        var gathered: GatherResult? = null
        var outcome: LinkOutcome? = null

        // 1) the user wrote owner/repo: the GitHub API answers it, no search credit
        if (direct != null) {
            run.label = "GitHub: " + direct.first + "/" + direct.second + "…"
            run.phase = run.label
            progress()
            val first = flow(run, plan, emptyList(), direct, null, null)
            if (first.summary != LinkSummary.NEED_SEARCH) outcome = first
        }

        // 2) otherwise search, then judge the results
        if (outcome == null) {
            val key = Store.tavilyKey
            if (key.isNullOrBlank()) {
                Store.tavilyKeyStatus = "invalid"
                ConvKpi.inc("web_bad_key")
                return SearchRunner.failMessage(run, engine, SearchFailure(FailureKind.BAD_KEY))
            }
            run.label = "Web: search 1/" + com.codeassist.ai.search.SearchPlanner.roundCap(plan) + "…"
            run.phase = run.label
            progress()
            val c = TavilyClient(key)
            val search = SearchFlow(
                transport = { req, ms -> SearchRunner.callTavily(c, req, ms, run) },
                budget = budget,
                cache = SearchRunner.cache
            )
            val g = withContext(Dispatchers.IO) {
                search.gather(
                    plan, go.query,
                    { n, total ->
                        run.label = "Web: search $n/$total…"
                        run.phase = run.label
                        progress()
                    },
                    { run.cancelled }
                )
            }
            SearchRunner.count(g)
            gathered = g
            if (g.stopped || run.cancelled) return stoppedMessage(run, engine)
            val failure = g.failure
            if (failure != null) SearchRunner.afterFailure(failure, budget)
            if (g.raw.isEmpty() && failure != null) return SearchRunner.failMessage(run, engine, failure)

            run.label = "Links check ho rahe hain…"
            run.phase = run.label
            progress()
            val left = plan.maxCredits - g.credits
            val turn = if (SearchKind.DIRECT_FILE in plan.kinds && left > 0) budget.startTurn(left) else null
            outcome = flow(run, plan, g.raw, null, turn, c)
        }
        val o = outcome ?: return stoppedMessage(run, engine)
        if (o.summary == LinkSummary.STOPPED || run.cancelled) return stoppedMessage(run, engine)

        // ---- counters ----
        ConvKpi.inc("web_credits", o.extractCredits.toLong())
        ConvKpi.inc("web_link_gh_calls", o.ghCalls.toLong())
        ConvKpi.inc("web_extract_runs", o.extractRuns.toLong())
        ConvKpi.inc("web_guard_pass", o.guardPasses.toLong())
        ConvKpi.inc("web_guard_reject", o.guardRejects.toLong())
        if (o.extractFailure != null) ConvKpi.inc("web_extract_failed")
        if (o.ghFailure != null && o.ghFailure.kind == FailureKind.RATE_LIMITED) ConvKpi.inc("web_link_gh_rate")
        if (o.summary == LinkSummary.GITHUB_NOT_FOUND) ConvKpi.inc("web_link_gh_notfound")
        val files = o.cards.count { it.role == "file" }
        if (files > 0) ConvKpi.inc("web_link_files", files.toLong())
        if (o.cards.isEmpty()) ConvKpi.inc("web_link_none") else ConvKpi.inc("web_link_ok")

        val credits = (gathered?.credits ?: 0) + o.extractCredits
        val secs = SearchRunner.secondsSince(run)
        run.detail = "link $kindName: ${o.summary.name.lowercase()}, ${o.cards.size} card, github ${o.ghCalls}, extract ${o.extractRuns}, " +
            "guard ${o.guardPasses} pass / ${o.guardRejects} reject"

        var caption = "Net links · " + (if (gathered != null && gathered.allFromCache && o.extractCredits == 0) "cache, 0" else credits.toString()) +
            " credit · " + secs + "s"
        if (o.ghCalls > 0) caption += " · GitHub"
        val info = WebInfo(
            kind = plan.primary.name, sources = emptyList(), citedNone = false, credits = credits,
            rounds = gathered?.rounds ?: 0, searchMs = gathered?.elapsedMs ?: 0L, composeMs = 0L,
            cached = gathered?.allFromCache ?: false, unverified = 0, linksRemoved = 0, oneSite = false, cards = o.cards
        )
        val failed = o.summary == LinkSummary.GITHUB_ERROR && o.cards.isEmpty()
        return Message(
            id = run.messageId, role = Role.AI, text = LinkAnswer.text(o), engine = engine,
            note = caption, state = if (failed) "error" else null,
            web = if (o.cards.isEmpty()) null else info.toJson()
        )
    }

    private fun stoppedMessage(run: ChatRunner.Run, engine: String): Message {
        ConvKpi.inc("web_stopped")
        // ChatRunner.stop() already ended this run and its message; this value is dropped by finish()
        return Message(id = run.messageId, role = Role.AI, text = "", engine = engine)
    }

    private suspend fun flow(
        run: ChatRunner.Run,
        plan: com.codeassist.ai.search.SearchPlan,
        raw: List<com.codeassist.ai.search.TavilyResult>,
        direct: Pair<String, String>?,
        extractTurn: SearchBudget.Turn?,
        client: TavilyClient?
    ): LinkOutcome {
        val deps = deps(run, extractTurn, client)
        return withContext(Dispatchers.IO) { LinkFlow.run(plan, raw, direct, deps) }
    }

    /** One blocking call inside StepTimeout: null when it timed out, crashed or the user pressed Stop. */
    private fun <T> guarded(run: ChatRunner.Run, timeoutMs: Int, block: () -> T): T? {
        val o = StepTimeout.call(timeoutMs = timeoutMs + 2_000L, stopped = { run.cancelled }) { block() }
        return if (o is StepTimeout.Outcome.Done) o.value else null
    }

    private fun deps(run: ChatRunner.Run, extractTurn: SearchBudget.Turn?, client: TavilyClient?): LinkDeps {
        val github = GitHubClient()
        val stopped = SearchFailure(FailureKind.TIMEOUT, null, "stopped or timed out")
        return LinkDeps(
            repo = { owner, name ->
                guarded<GhOutcome<GitHubRepo>>(run, GITHUB_TIMEOUT_MS) { github.repo(owner, name, GITHUB_TIMEOUT_MS) }
                    ?: GhOutcome.Fail(stopped)
            },
            latest = { owner, name ->
                guarded<GhOutcome<GitHubRelease>>(run, GITHUB_TIMEOUT_MS) { github.latestRelease(owner, name, GITHUB_TIMEOUT_MS) }
                    ?: GhOutcome.Fail(stopped)
            },
            extract = { req: ExtractRequest ->
                if (client == null) {
                    SearchOutcome.Fail(SearchFailure(FailureKind.BAD_KEY, null, "no key"))
                } else {
                    guarded<SearchOutcome<TavilyExtractResponse>>(run, EXTRACT_TIMEOUT_MS) { client.extract(req, EXTRACT_TIMEOUT_MS) }
                        ?: SearchOutcome.Fail(stopped)
                }
            },
            head = { url, ms -> DownloadGuard.realHead(url, ms) },
            cancelled = { run.cancelled },
            extractTurn = extractTurn
        )
    }
}
