package com.codeassist.ai.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.codeassist.ai.data.Message
import com.codeassist.ai.data.Role
import com.codeassist.ai.data.Store
import com.codeassist.ai.search.BudgetState
import com.codeassist.ai.search.EvidenceBook
import com.codeassist.ai.search.FailureKind
import com.codeassist.ai.search.GatherResult
import com.codeassist.ai.search.SearchBudget
import com.codeassist.ai.search.SearchCache
import com.codeassist.ai.search.SearchFailure
import com.codeassist.ai.search.SearchFlow
import com.codeassist.ai.search.SearchKind
import com.codeassist.ai.search.SearchOutcome
import com.codeassist.ai.search.SearchPlanner
import com.codeassist.ai.search.SearchRequest
import com.codeassist.ai.search.TavilyClient
import com.codeassist.ai.search.TavilySearchResponse
import com.codeassist.ai.search.WebAnswer
import com.codeassist.ai.search.WebGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.UnknownHostException
import java.time.LocalDate

/**
 * Net Search Agent, stage S2: the thin Android layer of the net search (audit section 4, "SearchRunner"). The rules live in the pure
 * `search` package and are tested on the JVM; this object only
 *  - fills in the gate ([decide]: settings, key, budget, connection),
 *  - runs the blocking Tavily call inside StepTimeout ([callTavily]: Stop is seen within 100 ms),
 *  - writes the answer with the brain the user chose, with NO tools in that call (web text is untrusted: audit section 7),
 *  - builds the stored message (text without links, sources in `Message.web`).
 *
 * Nothing unchecked reaches the screen or the disk: the model's text is shown only after [WebAnswer.finish] (CitationValidator).
 * While the model writes, the label counts characters instead of showing the text.
 */
object SearchRunner {
    private const val LOCAL_NAME = "Gemma 4 E2B"

    /** In-memory cache of search answers: the same question twice in one day (news) or week costs one credit. Lost with the process. */
    private val cache = SearchCache<TavilySearchResponse>()

    /** Today's credit count and the "plan used up" month live in [Store], so they survive a restart. */
    private object StoreBudgetState : BudgetState {
        override fun day(): String = Store.webCreditDay
        override fun usedToday(): Int = Store.webCreditUsed
        override fun setUsed(day: String, used: Int) {
            Store.webCreditDay = day
            Store.webCreditUsed = used
        }

        override fun exhaustedMonth(): String = Store.webExhaustedMonth
        override fun setExhaustedMonth(month: String) {
            Store.webExhaustedMonth = month
        }
    }

    fun budget(): SearchBudget = SearchBudget(StoreBudgetState, Store.webDailyCap)

    fun clearCache() = cache.clear()

    /** The settings "Test key" search costs one credit: it is counted in today's total like any other search. */
    fun countTestCredit(app: Context, actual: Int?) {
        Store.init(app)
        val turn = budget().startTurn(1)
        if (turn.charge(1) && actual != null) turn.settle(1, actual)
    }

    /** "Aaj 3 / 30 credit" for the settings card. */
    fun usageLine(): String {
        val b = budget()
        return "Aaj: " + b.usedToday() + " / " + b.dailyCap + " credit"
    }

    /** A cheap pre-check (audit 6.1 step 5). When the phone cannot tell, the call itself finds out. */
    fun isOnline(app: Context): Boolean {
        return try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    /** The gate of audit 6.1 steps 3 to 5 for one chat message. Cheap when the net search is off or the message has no web cue. */
    fun decide(app: Context, latest: Message, history: List<Message>, viaVoice: Boolean): WebGate.Decision {
        Store.init(app)
        if (!Store.webSearch) return WebGate.Decision.Skip("disabled")
        val lastReply = history.lastOrNull()
        val prevWeb = lastReply != null && lastReply.role == Role.AI && !lastReply.web.isNullOrBlank()
        val keyState = when {
            !Store.hasTavilyKey() -> WebGate.KeyState.NONE
            Store.tavilyKeyStatus == "invalid" -> WebGate.KeyState.INVALID
            else -> WebGate.KeyState.OK
        }
        val b = budget()
        val decision = WebGate.decide(
            WebGate.Inputs(
                text = latest.text,
                prevWeb = prevWeb,
                hasAttachments = latest.attachments.isNotEmpty(),
                viaVoice = viaVoice,
                enabled = true,
                key = keyState,
                planExhausted = b.isPlanExhausted(),
                remainingToday = b.remainingToday(),
                dailyCap = b.dailyCap,
                online = isOnline(app)
            )
        )
        when (decision) {
            is WebGate.Decision.Skip -> if (decision.why == "kind") ConvKpi.inc("web_skip_kind")
            is WebGate.Decision.Say -> ConvKpi.inc(decision.kpi)
            is WebGate.Decision.Run -> Unit
        }
        return decision
    }

    // ---------- the run ----------

    /**
     * One net-search turn: search, write the answer from the sources with the chosen brain, check it, build the message.
     * [prepareLocal] loads the phone model (it throws ReplyError when the model is not ready); it is called only when the phone
     * brain writes the answer, AFTER the search, so a slow model load does not delay the network call. [progress] tells the screen the
     * label changed.
     */
    suspend fun run(
        run: ChatRunner.Run,
        latest: Message,
        go: WebGate.Decision.Run,
        prepareLocal: suspend () -> Unit,
        progress: () -> Unit
    ): Message {
        ConvKpi.inc("web_runs")
        if (go.numberStripped) ConvKpi.inc("web_number_stripped")
        run.route = "web"
        val plan = go.plan
        val kind = plan.primary
        val kindName = kind.name.lowercase()
        val engineNow = Store.brainProvider

        val key = Store.tavilyKey
        if (key.isNullOrBlank()) {
            Store.tavilyKeyStatus = "invalid"
            ConvKpi.inc("web_bad_key")
            return failMessage(run, engineNow, SearchFailure(FailureKind.BAD_KEY))
        }

        run.label = "Web: search 1/" + SearchPlanner.roundCap(plan) + "…"
        run.phase = run.label
        progress()

        val b = budget()
        val client = TavilyClient(key)
        val flow = SearchFlow(
            transport = { req, ms -> callTavily(client, req, ms, run) },
            budget = b,
            cache = cache
        )
        val gathered = withContext(Dispatchers.IO) {
            flow.gather(
                plan, go.query,
                { n, total ->
                    run.label = "Web: search $n/$total…"
                    run.phase = run.label
                    progress()
                },
                { run.cancelled }
            )
        }
        count(gathered)
        if (gathered.stopped || run.cancelled) {
            ConvKpi.inc("web_stopped")
            // ChatRunner.stop() already ended this run and its message; this value is dropped by finish()
            return Message(id = run.messageId, role = Role.AI, text = "", engine = engineNow)
        }

        val failure = gathered.failure
        if (failure != null) afterFailure(failure, b)

        if (!gathered.hasEvidence) {
            if (failure != null) return failMessage(run, engineNow, failure)
            if (gathered.rounds == 0) {
                run.detail = "web $kindName: credit cap stopped the first search"
                return Message(
                    id = run.messageId, role = Role.AI, engine = engineNow, note = WebGate.NOTE + " · limit",
                    text = WebGate.capText(b.dailyCap)
                )
            }
            ConvKpi.inc("web_no_source")
            run.detail = "web $kindName: " + gathered.rounds + " round, no usable source"
            return Message(
                id = run.messageId, role = Role.AI, engine = engineNow,
                note = WebGate.NOTE + " · " + gathered.credits + " credit · " + secondsSince(run) + "s",
                text = WebAnswer.noSourceText()
            )
        }

        // ---- write the answer from the sources ----
        val question = latest.text.trim().take(500)
        val useGemini = engineNow == "gemini" && !Store.geminiKey.isNullOrBlank()
        val composeStart = System.currentTimeMillis()
        var composed: Composed? = null
        var reason = ""
        try {
            composed = if (useGemini) composeGemini(run, gathered.book, question, kind, progress)
            else composeLocal(run, gathered.book, latest.text, question, kind, prepareLocal, progress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reason = shortReason(e)
            if (useGemini && Store.automaticFallback && (LocalLlm.loaded || Modules.modelFile().isFile)) {
                ConvKpi.inc("web_compose_fallback")
                run.text = ""
                run.engine = "local"
                run.label = "Gemini nahi mila, Gemma jawab likh raha hai"
                progress()
                try {
                    composed = composeLocal(run, gathered.book, latest.text, question, kind, prepareLocal, progress)
                    reason = ""
                } catch (c: CancellationException) {
                    throw c
                } catch (e2: Exception) {
                    reason = shortReason(e2)
                }
            }
        }
        if (run.cancelled) {
            ConvKpi.inc("web_stopped")
            return Message(id = run.messageId, role = Role.AI, text = "", engine = engineNow)
        }

        val composeMs = System.currentTimeMillis() - composeStart
        val searchMs = gathered.elapsedMs
        val stats = WebAnswer.Stats(kind, gathered.credits, gathered.rounds, searchMs, composeMs, gathered.allFromCache)
        val today = LocalDate.now()

        if (composed == null || composed.text.isBlank()) {
            // the sources were found but no answer could be written: show the sources and say so (audit 8, risk of S2)
            ConvKpi.inc("web_compose_failed")
            val shown = gathered.book.pack(WebAnswer.CLOUD_TOTAL_CHARS, WebAnswer.PER_SOURCE_CHARS, 5).sources
            run.detail = "web $kindName: " + shown.size + " source, compose failed: " + reason.ifEmpty { "empty" }
            return Message(
                id = run.messageId, role = Role.AI, engine = run.engine, state = null,
                note = WebGate.NOTE + " · " + gathered.credits + " credit · " + secondsSince(run) + "s · sirf sources",
                text = WebAnswer.evidenceOnlyText(reason.ifEmpty { "model ne khaali jawab diya" }),
                web = WebAnswer.evidenceOnlyInfo(shown, stats, today).toJson()
            )
        }

        val fin = WebAnswer.finish(composed.text, composed.packed.sources, stats, today)
        ConvKpi.inc("web_ok")
        if (fin.validation.clean) ConvKpi.inc("web_clean")
        ConvKpi.inc("web_unverified", fin.info.unverified.toLong())
        ConvKpi.inc("web_links_removed", fin.info.linksRemoved.toLong())
        ConvKpi.inc("web_invalid_marker", fin.validation.count(com.codeassist.ai.search.CitationValidator.IssueKind.INVALID_MARKER).toLong())
        if (fin.info.citedNone) ConvKpi.inc("web_cited_none")
        if (composed.packed.omitted > 0 || composed.packed.trimmedChars > 0) ConvKpi.inc("web_ctx_trim")
        if (failure != null) ConvKpi.inc("web_partial")

        run.detail = "web $kindName: " + gathered.rounds + " round, " + gathered.credits + " credit, " +
            composed.packed.sources.size + " source / " + EvidenceBook.independentDomains(composed.packed.sources) +
            " site, search " + (searchMs / 100) / 10.0 + "s, answer " + (composeMs / 100) / 10.0 + "s"

        var caption = composed.caption + " · web · " + (if (gathered.allFromCache) "cache, 0" else gathered.credits.toString()) +
            " credit · " + secondsSince(run) + "s"
        if (failure != null) caption += " · adhura: " + failure.kind.name.lowercase()
        if (fin.info.unverified > 0) caption += " · " + fin.info.unverified + " line verify nahi"
        return Message(
            id = run.messageId, role = Role.AI, text = fin.text, engine = run.engine,
            note = caption, web = fin.info.toJson()
        )
    }

    // ---------- Tavily ----------

    /** One blocking call on its own thread: a timeout or a Stop press returns within about 100 ms of the limit. */
    private fun callTavily(
        client: TavilyClient, req: SearchRequest, timeoutMs: Int, run: ChatRunner.Run
    ): SearchOutcome<TavilySearchResponse>? {
        val outcome = StepTimeout.call(timeoutMs = timeoutMs + 2_000L, stopped = { run.cancelled }) {
            client.search(req, timeoutMs)
        }
        return when (outcome) {
            is StepTimeout.Outcome.Done -> outcome.value
            is StepTimeout.Outcome.TimedOut -> SearchOutcome.Fail(SearchFailure(FailureKind.TIMEOUT, null, "step timeout"))
            is StepTimeout.Outcome.Stopped -> null
            is StepTimeout.Outcome.Crashed -> SearchOutcome.Fail(SearchFailure(FailureKind.NETWORK, null, "unexpected error"))
        }
    }

    private fun count(g: GatherResult) {
        ConvKpi.inc("web_credits", g.credits.toLong())
        ConvKpi.inc("web_rounds", g.rounds.toLong())
        if (g.allFromCache) ConvKpi.inc("web_cache_hit")
        if (g.broadened) ConvKpi.inc("web_broadened")
        if (g.book.droppedInjected > 0) ConvKpi.inc("web_injected_dropped", g.book.droppedInjected.toLong())
    }

    /** The side effects of a provider failure (audit 6.4): a wrong key is remembered, a used-up plan switches the net search off for the month. */
    private fun afterFailure(f: SearchFailure, b: SearchBudget) {
        when (f.kind) {
            FailureKind.BAD_KEY -> {
                Store.tavilyKeyStatus = "invalid"
                ConvKpi.inc("web_bad_key")
            }
            FailureKind.PLAN_LIMIT -> {
                b.markPlanExhausted()
                ConvKpi.inc("web_plan_exhausted")
            }
            FailureKind.RATE_LIMITED -> ConvKpi.inc("web_rate_limited")
            FailureKind.OFFLINE -> ConvKpi.inc("web_offline")
            FailureKind.TIMEOUT -> ConvKpi.inc("web_timeouts")
            else -> ConvKpi.inc("web_failed")
        }
    }

    private fun failMessage(run: ChatRunner.Run, engine: String, f: SearchFailure): Message {
        run.detail = "web failed: " + f.kind.name.lowercase()
        return Message(
            id = run.messageId, role = Role.AI, engine = engine, state = "error",
            note = WebGate.NOTE + " · " + f.kind.name.lowercase(),
            text = f.userMessage()
        )
    }

    // ---------- writing the answer ----------

    private class Composed(val packed: EvidenceBook.Packed, val text: String, val caption: String)

    private fun secondsSince(run: ChatRunner.Run): Long = (System.currentTimeMillis() - run.startedAt) / 1000

    private fun shortReason(e: Throwable): String {
        val text = when (e) {
            is ChatRunner.ReplyError -> e.message
            is LocalLlm.LoadFailure -> e.message
            is GeminiClient.ApiError -> e.message
            is UnknownHostException -> "internet nahi mila"
            is IOException -> "network dikkat"
            else -> e.javaClass.simpleName
        }
        return (text ?: e.javaClass.simpleName).replace('\n', ' ').take(90)
    }

    /**
     * The phone model writes the answer. The evidence is packed to the window BEFORE the call (WebAnswer.evidenceChars), the history
     * is left out (the question stands alone), the call has no tools and a low temperature. One language retry like the normal chat.
     */
    private suspend fun composeLocal(
        run: ChatRunner.Run,
        book: EvidenceBook,
        userMessage: String,
        question: String,
        kind: SearchKind,
        prepareLocal: suspend () -> Unit,
        progress: () -> Unit
    ): Composed {
        prepareLocal()
        val window = LocalLlm.loadedContext
        val system = WebAnswer.system(MemoryStore.replyLanguage(), Store.replyLength)
        val taskChars = WebAnswer.instruction(kind).length + question.length + 40
        val chars = WebAnswer.evidenceChars(window, system.length, taskChars)
        val packed = book.pack(chars, WebAnswer.PER_SOURCE_CHARS, WebAnswer.maxSources(window))
        if (packed.sources.isEmpty()) throw ChatRunner.ReplyError("sources model ki window mein nahi aaye")
        val user = WebAnswer.userTurn(packed.block, question, kind)

        var counted = 0
        var lastPost = 0L
        val onDelta: (String) -> Unit = { delta ->
            counted += delta.length
            val now = System.currentTimeMillis()
            if (run.firstTokenMs == 0L) run.firstTokenMs = now - run.startedAt
            if (now - lastPost > 400) {
                lastPost = now
                run.label = "Jawab likh raha hoon (" + counted + " akshar)"
                run.phase = run.label
                progress()
            }
        }
        run.label = "Gemma jawab likh raha hai"
        run.phase = run.label
        progress()
        var reply = LocalLlm.chatSampled(
            system, emptyList(), user, emptyList(), { run.cancelled }, WebAnswer.LOCAL_TEMPERATURE, onDelta
        )
        var text = reply.text.trim()

        val remembered = MemoryStore.replyLanguage()
        val wantLang = ReplyGuard.wantedLanguage(userMessage, remembered)
        if (wantLang != null && text.isNotEmpty() && !run.cancelled && ReplyGuard.languageMismatch(userMessage, text, remembered)) {
            ConvKpi.inc("web_lang_retry")
            run.label = if (wantLang == LocalMemory.LANG_ENGLISH) "English mein dobara bana raha hoon" else "Hinglish mein dobara bana raha hoon"
            run.phase = run.label
            progress()
            val again = LocalLlm.chatSampled(
                system + "\n" + Prompts.languageLine(wantLang), emptyList(),
                user + "\n\n" + ReplyGuard.retryNote(wantLang), emptyList(), { run.cancelled },
                WebAnswer.LOCAL_TEMPERATURE, onDelta
            )
            val againText = again.text.trim()
            if (againText.isNotEmpty() && !ReplyGuard.languageMismatch(userMessage, againText, remembered)) {
                ConvKpi.inc("web_lang_retry_ok")
                text = againText
                reply = again
            } else {
                ConvKpi.inc("web_lang_retry_failed")
            }
        }
        run.tokens = reply.tokens
        run.tps = reply.tokensPerSecond
        run.engine = "local"
        val where = if (LocalLlm.backendName.isEmpty()) "" else " · " + LocalLlm.backendName.uppercase(java.util.Locale.US)
        val speed = String.format(java.util.Locale.US, "%.1f", reply.tokensPerSecond)
        return Composed(packed, text, "$LOCAL_NAME$where · ~$speed tok/s")
    }

    /** The cloud brain writes the answer from the same numbered evidence (up to 8 sources), through the plain stream: no tools. */
    private suspend fun composeGemini(
        run: ChatRunner.Run,
        book: EvidenceBook,
        question: String,
        kind: SearchKind,
        progress: () -> Unit
    ): Composed {
        val key = Store.geminiKey ?: throw ChatRunner.ReplyError("Gemini API key set nahi hai. Voice and AI mein key daalo.")
        val model = Store.geminiModel.ifBlank { GeminiClient.FALLBACK_MODEL }
        val system = WebAnswer.system(MemoryStore.replyLanguage(), Store.replyLength)
        val packed = book.pack(WebAnswer.CLOUD_TOTAL_CHARS, WebAnswer.PER_SOURCE_CHARS, WebAnswer.CLOUD_MAX_SOURCES)
        if (packed.sources.isEmpty()) throw ChatRunner.ReplyError("sources prompt mein nahi aaye")
        val user = WebAnswer.userTurn(packed.block, question, kind)
        run.label = "Gemini jawab likh raha hai"
        run.phase = run.label
        progress()
        var counted = 0
        var lastPost = 0L
        val temperature = minOf(Store.geminiTemp, WebAnswer.CLOUD_TEMPERATURE)
        val text = withContext(Dispatchers.IO) {
            GeminiClient.stream(
                key, model, system, listOf(GeminiClient.Turn("user", user)), temperature, { run.cancelled }
            ) { delta ->
                counted += delta.length
                val now = System.currentTimeMillis()
                if (run.firstTokenMs == 0L) run.firstTokenMs = now - run.startedAt
                if (now - lastPost > 400) {
                    lastPost = now
                    run.label = "Jawab likh raha hoon (" + counted + " akshar)"
                    run.phase = run.label
                    progress()
                }
            }
        }
        run.engine = "gemini"
        return Composed(packed, text.trim(), "Gemini · $model")
    }
}
