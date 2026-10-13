package com.codeassist.ai.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.codeassist.ai.data.AttachKind
import com.codeassist.ai.data.Message
import com.codeassist.ai.data.Role
import com.codeassist.ai.data.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.UUID

/**
 * Runs one AI reply at a time, independent of any screen. The finished reply is written to the
 * chat store from here, so it is not lost if the user leaves the chat while it is being produced.
 * A screen attaches as [listener] to show progress and the result.
 */
object ChatRunner {
    class ReplyError(message: String) : Exception(message)

    class Run(val chatId: String, val messageId: String, @Volatile var engine: String, val viaVoice: Boolean) {
        @Volatile var text: String = ""
        @Volatile var label: String = "Soch raha hoon"
        @Volatile var phase: String = "Soch raha hoon"
        @Volatile var route: String = if (engine == "tool") "tier0" else "brain"
        val startedAt: Long = System.currentTimeMillis()
        @Volatile var cancelled: Boolean = false
        @Volatile var firstTokenMs: Long = 0L
        @Volatile var loadMs: Long = 0L
        @Volatile var tokens: Int = 0
        @Volatile var tps: Float = 0f
        /** Short note for the trace: which tools the brain called, or why a plan was refused. */
        @Volatile var detail: String = ""

        /** Phase 3B-2: the stored plan this run is executing (set by the task engine), so Stop can end it. */
        @Volatile var planId: String? = null
        var job: Job? = null
    }

    interface Listener {
        /** Live text (Gemini streams) or a new phase label. Main thread. */
        fun onProgress(run: Run)

        /** [message] is already saved; null means the run ended with nothing to show (stopped early). */
        fun onFinished(run: Run, message: Message?)
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    var current: Run? = null
        private set

    @Volatile
    var listener: Listener? = null

    /** Application context of the running reply, kept for [stop] (the Stop button has no context of its own). */
    private var appCtx: Context? = null

    fun isRunning(): Boolean = current != null

    /** Answers the last user message of [chatId]. Returns null if a reply is already running. */
    fun start(ctx: Context, chatId: String, viaVoice: Boolean): Run? {
        if (current != null) return null
        val app = ctx.applicationContext
        appCtx = app
        Store.init(app)
        Modules.init(app)
        ActivityLog.init(app)
        ConvKpi.init(app)
        MemoryStore.init(app)
        val all = Store.messages(chatId)
        val userIndex = all.indexOfLast { it.role == Role.USER }
        if (userIndex < 0) return null
        val latest = all[userIndex]
        val history = all.subList(0, userIndex).filter { it.state == null && it.text.isNotBlank() }
        val provider = Store.brainProvider

        val tool: Tier0.Result? = quickReply(app, latest, history)
        // T2: a heavy plan / roadmap / prompt-writing question goes to Gemini even when the phone brain is chosen
        // (Settings switch, a saved Gemini key is needed; see FallbackPolicy.escalateHeavyPlan)
        // T3: "ek prompt do, poora plan, dusra AI coding kar de" on the phone brain: the app writes the plan box by box with Gemma
        // (PlanBuilder). It comes BEFORE the heavy-plan hand-over to Gemini: the Gemini path is untested, the builder is offline.
        val planSrc: PlanBuilder.Source? =
            if (tool == null && !viaVoice && provider == "local" && Store.planBuilder && latest.attachments.isEmpty()) {
                PlanBuilder.source(latest.text, history)
            } else {
                null
            }
        // T4: "ek sketch banao: ghar" on the phone brain: Gemma writes one small JSON, the app checks it and draws it (Sketch)
        val sketchReq = tool == null && planSrc == null && !viaVoice && provider == "local" &&
            latest.attachments.isEmpty() && SketchIntent.isRequest(latest.text)
        val heavy = tool == null && planSrc == null && !sketchReq && FallbackPolicy.escalateHeavyPlan(
            latest.text, provider, !Store.geminiKey.isNullOrBlank(), Store.heavyPlanToGemini
        )
        val engine = if (tool != null) "tool" else if (planSrc != null || sketchReq) "local" else if (heavy) "gemini" else provider
        val run = Run(chatId, UUID.randomUUID().toString(), engine, viaVoice)
        current = run

        run.job = scope.launch {
            val ticker = launch {
                while (isActive) {
                    delay(1000)
                    if (run.text.isEmpty() && current === run) {
                        val secs = (System.currentTimeMillis() - run.startedAt) / 1000
                        run.phase = run.label + " · " + secs + "s"
                        listener?.onProgress(run)
                    }
                }
            }
            var result: Message?
            try {
                result = when {
                    tool != null -> {
                        val entry = ActivityLog.add(tool.title, tool.tier, tool.reply, tool.undo)
                        Message(
                            id = run.messageId, role = Role.AI, text = tool.reply,
                            engine = "tool", note = tool.title,
                            undoId = if (tool.undo != null) entry.id else null
                        )
                    }
                    planSrc != null -> runPlanBuild(app, run, history, latest, planSrc)
                    sketchReq -> runSketch(run, latest)
                    heavy -> runHeavyPlan(app, run, history, latest)
                    provider == "gemini" -> runGeminiWithFallback(app, run, history, latest)
                    else -> runLocal(app, run, history, latest)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                result = errorMessage(run, e)
            } finally {
                ticker.cancel()
            }
            finish(run, result)
        }
        return run
    }

    /**
     * Answers that need no model at all, in this order: a correction of the last alarm / timer ("nahi, 9 baje", Phase 3B),
     * taking one step away from the plan just shown ("petrol wala kaam hata do", Phase 3C), a bare "off" after a torch reply, a memory command
     * ("yaad rakho ...", "bhool jao"), then the Tier-0 phone actions. When none matches, the message is read for
     * facts the user states about themselves ("mera naam ... hai") and the brain answers.
     */
    private fun quickReply(app: Context, latest: Message, history: List<Message>): Tier0.Result? {
        if (latest.attachments.isNotEmpty()) return null
        val lastAi = history.lastOrNull { it.role == Role.AI }
        val lastTool: String? = if (lastAi != null && lastAi.engine == "tool") lastAi.note else null
        val quick = CorrectionRunner.tryFix(app, latest.text, lastAi)
            ?: PlanEditRunner.tryEdit(app, latest.text, lastAi)
            ?: Tier0.followUp(app, latest.text, lastTool)
            ?: sketchClarify(latest.text, lastTool)
            ?: MemoryStore.command(latest.text)
            ?: Tier0.handle(app, latest.text)
        if (quick == null) MemoryStore.autoLearn(latest.text)
        return quick
    }

    /** T4: talk about giving the AI a sketch skill gets one fixed question (no model call, no training list). */
    private fun sketchClarify(text: String, lastTool: String?): Tier0.Result? {
        val reply = SketchIntent.clarify(text, lastTool) ?: return null
        ConvKpi.inc("sk_clarify")
        val title = if (reply == SketchIntent.CLARIFY_REPLY) SketchIntent.CLARIFY_NOTE else "Sketch"
        return Tier0.Result(title, reply)
    }

    private fun isActionReply(m: Message): Boolean =
        m.engine == "tool" || m.undoId != null || m.actions != null || m.pending != null

    /**
     * The history the offline model may see: every phone-action exchange is left out. A reply like "Torch off kar
     * di." is not a conversation, and a 1.5B model that finds three of them in its prompt answers the next,
     * unrelated question with the same line (this is what "Kal mujhe 8 baje uthna hai" -> "Torch off kar di." was).
     */
    private fun localHistory(history: List<Message>): List<Message> {
        val out = ArrayList<Message>(history.size)
        var i = 0
        while (i < history.size) {
            val m = history[i]
            val next = if (i + 1 < history.size) history[i + 1] else null
            if (m.role == Role.USER && next != null && next.role == Role.AI && isActionReply(next)) {
                i += 2
                continue
            }
            if (m.role == Role.AI && isActionReply(m)) {
                i++
                continue
            }
            out.add(m)
            i++
        }
        return out
    }

    /** Stop button. Keeps whatever Gemini had streamed so far; a local reply cannot be partial. */
    fun stop() {
        val run = current ?: return
        run.cancelled = true
        // Phase 3B-2: a plan that is running is stopped first (steps not finished are cancelled, the engine sees the flag),
        // and its reply so far becomes the message, so what already happened and its Undo chip are not lost.
        val planId = run.planId
        val app = appCtx
        val planOut: PlanExecutor.Outcome? = if (planId != null && app != null) PlanExecutor.stop(app, planId) else null
        run.job?.cancel()
        val partial = run.text.trim()
        val msg = if (planOut != null) {
            val undo: String? = if (planOut.undoIds.isEmpty()) null else planOut.undoIds.joinToString(",")
            Message(
                id = run.messageId, role = Role.AI, text = planOut.text, engine = run.engine,
                note = "Beech mein roka gaya", undoId = undo, actions = planOut.chip, pending = planOut.pendingJson
            )
        } else if (partial.isNotEmpty()) {
            Message(
                id = run.messageId, role = Role.AI, text = partial,
                engine = run.engine, note = "Beech mein roka gaya"
            )
        } else {
            null
        }
        finish(run, msg)
    }

    private fun finish(run: Run, original: Message?) {
        if (current !== run) return
        current = null
        // Phase 4B: sentences the app wrote itself (phone-action and plan replies) follow the "Bolne ka lehja" setting;
        // what a model wrote is left as it is (the model is told the form in its prompt)
        val msg = if (original != null && (original.engine == "tool" || run.planId != null)) {
            original.copy(text = Persona.fit(original.text, Store.personaGender))
        } else {
            original
        }
        record(run, msg)
        if (msg != null) persist(run.chatId, msg)
        listener?.onFinished(run, msg)
    }

    private fun record(run: Run, msg: Message?) {
        val status = when {
            run.cancelled -> "stopped"
            msg?.state == "error" -> "error"
            else -> "ok"
        }
        Trace.add(
            Trace.Turn(
                time = System.currentTimeMillis(),
                engine = run.engine,
                route = run.route,
                totalMs = System.currentTimeMillis() - run.startedAt,
                firstTokenMs = run.firstTokenMs,
                loadMs = run.loadMs,
                tokens = run.tokens,
                tokPerSec = run.tps,
                status = status,
                detail = if (status == "error") (msg?.text ?: "").take(90) else run.detail
            )
        )
    }

    private fun persist(chatId: String, msg: Message) {
        val list = Store.messages(chatId)
        list.add(msg)
        Store.saveMessages(chatId, list)
        val meta = Store.chats().firstOrNull { it.id == chatId }
        if (meta != null) {
            meta.snippet = msg.text.replace('\n', ' ').take(80)
            meta.time = System.currentTimeMillis()
            Store.updateChat(meta)
        }
    }

    private fun postProgress(run: Run) {
        main.post {
            if (current === run) listener?.onProgress(run)
        }
    }

    // ---------- Gemini ----------

    /**
     * B2: route only transient cloud failures to an already-installed local model. The selected
     * provider is not changed, so the next user turn tries Gemini again automatically.
     */
    private suspend fun runGeminiWithFallback(
        app: Context, run: Run, history: List<Message>, latest: Message
    ): Message {
        try {
            val result = runGemini(app, run, history, latest)
            ConvKpi.inc("degrade_l0_full")
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!Store.automaticFallback) throw e
            val level = if (e is GeminiClient.ApiError) {
                FallbackPolicy.forHttpStatus(e.http)
            } else {
                FallbackPolicy.forNetworkError(e)
            } ?: throw e

            ConvKpi.inc("degrade_${level.key}")
            val transition = FallbackPolicy.transitionLine(level)
            run.route = "fallback-${level.key}"
            if (!LocalLlm.loaded && !Modules.modelFile().isFile) {
                ConvKpi.inc("fallback_unavailable")
                run.detail = "Gemini -> ${level.label}; local model not installed"
                val levelText = if (level == FallbackPolicy.Level.L2_OFFLINE) "L2 offline" else "L1 degraded"
                return Message(
                    id = run.messageId, role = Role.AI,
                    text = "$transition\n\nGemma 4 phone mein installed nahi hai, isliye fallback nahi ho saka. " +
                        "Voice and AI mein model Download ya Import karo; agli turn par Gemini phir try hoga.",
                    state = "error", engine = "gemini", note = "$levelText · fallback unavailable"
                )
            }

            // Do not leave partial Gemini text beside the new local answer if the stream failed mid-turn.
            run.text = ""
            run.engine = "local"
            run.label = transition
            run.detail = "Gemini -> Gemma · ${level.label}"
            postProgress(run)

            return try {
                val local = runLocal(app, run, history, latest)
                if (local.state == "error") {
                    ConvKpi.inc("fallback_failed")
                    return Message(
                        id = run.messageId, role = Role.AI,
                        text = "$transition\n\n" + local.text,
                        state = "error", engine = "local",
                        note = "Fallback ${level.label} · Gemma reply rejected"
                    )
                }
                ConvKpi.inc("fallback_success")
                local.copy(
                    text = FallbackPolicy.successLine(level) + "\n\n" + local.text,
                    note = "Fallback ${level.label} · " + (local.note ?: "Gemma 4")
                )
            } catch (fallbackError: CancellationException) {
                throw fallbackError
            } catch (fallbackError: Exception) {
                ConvKpi.inc("fallback_failed")
                val details = errorMessage(run, fallbackError).text
                Message(
                    id = run.messageId, role = Role.AI,
                    text = "$transition\n\nGemma fallback bhi nahi chal saka: $details",
                    state = "error", engine = "local",
                    note = "Fallback ${level.label} · Gemma failed"
                )
            }
        }
    }

    /**
     * T2: the phone brain is chosen but the question is a heavy plan / roadmap / prompt request: Gemini answers this turn.
     * The user chose the phone brain, so ANY Gemini failure (no internet, bad key, quota) hands the turn back to Gemma instead
     * of showing an error; [Store.automaticFallback] does not matter here. The caption of the reply says what happened.
     */
    private suspend fun runHeavyPlan(app: Context, run: Run, history: List<Message>, latest: Message): Message {
        ConvKpi.inc("heavy_to_gemini")
        run.route = "heavy-gemini"
        try {
            val m = runGemini(app, run, history, latest)
            run.detail = "heavy plan -> Gemini"
            return m.copy(note = (m.note ?: "Gemini") + FallbackPolicy.HEAVY_NOTE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ConvKpi.inc("heavy_gemini_failed")
            run.text = ""
            run.engine = "local"
            run.route = "heavy-gemini-failed"
            run.detail = "heavy plan: Gemini failed, Gemma answered"
            val local = runLocal(app, run, history, latest)
            return local.copy(note = (local.note ?: LOCAL_NAME) + FallbackPolicy.HEAVY_FAILED_NOTE)
        }
    }

    private suspend fun runGemini(app: Context, run: Run, history: List<Message>, latest: Message): Message {
        val key = Store.geminiKey
            ?: throw ReplyError("Gemini API key set nahi hai. Voice and AI mein key daalo.")
        val model = Store.geminiModel.ifBlank { GeminiClient.FALLBACK_MODEL }
        run.label = "Gemini soch raha hai"
        postProgress(run)

        val payload = withContext(Dispatchers.IO) {
            AttachmentText.read(app, latest.attachments, true, 60_000)
        }
        val turns = Prompts.geminiTurns(history, latest, payload)
        // Tools only for a plain message: text from an attached file is untrusted and must never trigger an action
        // (audit Sec 11.3, prompt injection).
        val useTools = latest.attachments.isEmpty()
        val system = Prompts.system(run.viaVoice, latest.text) + (if (useTools) "\n" + Prompts.TOOL_RULES else "")
        var lastPost = 0L
        val streamed = withContext(Dispatchers.IO) {
            GeminiClient.streamWithToolsTimeouts(
                key, model, system, turns, Store.geminiTemp,
                if (useTools) ToolSpecs.all else emptyList(),
                connectTimeoutMs = 10_000,
                readTimeoutMs = if (Store.automaticFallback) 18_000 else 90_000,
                isCancelled = { run.cancelled }
            ) { delta ->
                if (run.firstTokenMs == 0L) run.firstTokenMs = System.currentTimeMillis() - run.startedAt
                run.text = run.text + delta
                val now = System.currentTimeMillis()
                if (now - lastPost > 90) {
                    lastPost = now
                    postProgress(run)
                }
            }
        }
        if (streamed.calls.isNotEmpty()) {
            val tasks = streamed.calls.mapIndexed { i, c -> ToolSpecs.task("t" + (i + 1), c.name, c.args) }
            return planMessage(app, run, "gemini", "Gemini · $model", streamed.text, tasks, payload.skipped)
        }
        return Message(
            id = run.messageId, role = Role.AI, text = streamed.text.trim(), engine = "gemini",
            note = noteWith("Gemini · $model", payload.skipped)
        )
    }

    private const val CLARIFY =
        "Samajh nahi paya ki phone par kya karna hai. Ek baar saaf bolo, jaise: \"kal subah 8 baje ka alarm laga do\"."

    /** Checks the plan, runs it behind the policy gate and turns the result into one reply message. */
    private suspend fun planMessage(
        app: Context, run: Run, engine: String, caption: String, ack: String,
        tasks: List<PlanTask>, skipped: List<String>
    ): Message {
        val problem = ToolSpecs.check(tasks)
        if (problem != null) {
            run.detail = "plan refused: " + problem
            return Message(
                id = run.messageId, role = Role.AI, text = CLARIFY, engine = engine,
                note = noteWith(caption, skipped)
            )
        }
        run.label = "Phone action chal raha hai"
        postProgress(run)
        val monitor = object : TaskEngine.Monitor {
            override fun onPlan(planId: String) {
                run.planId = planId
            }

            override fun onProgress(line: String) {
                run.label = line
                run.phase = line
                postProgress(run)
            }
        }
        val out = PlanExecutor.run(app, ack, tasks, run.chatId, run.messageId, monitor)
        run.detail = "tools: " + tasks.joinToString(",") { it.tool }
        val undo: String? = if (out.undoIds.isEmpty()) null else out.undoIds.joinToString(",")
        return Message(
            id = run.messageId, role = Role.AI, text = out.text, engine = engine,
            note = noteWith(caption + " · tools", skipped),
            undoId = undo, actions = out.chip, pending = out.pendingJson
        )
    }

    // ---------- Gemma 4 E2B (on device, LiteRT-LM) ----------

    private const val LOCAL_NAME = "Gemma 4 E2B"
    private const val LOCAL_TURNS = 24
    private const val LOCAL_HISTORY_CHARS = 16_000

    /**
     * The chat as real user/model messages for the runtime: newest turns first up to a size limit, same-role
     * neighbours merged (a failed turn can leave two user messages in a row), starting with a user turn and ending
     * with a model turn (the new message follows).
     */
    private fun recentTurns(chat: List<Message>): List<Message> {
        val picked = ArrayList<Message>()
        var chars = 0
        for (m in chat.asReversed().take(LOCAL_TURNS)) {
            val t = m.text.trim()
            if (t.isEmpty()) continue
            val cut = if (t.length > 1500) t.take(1500) + "..." else t
            chars += cut.length
            if (chars > LOCAL_HISTORY_CHARS) break
            picked.add(m.copy(text = cut))
        }
        picked.reverse()
        while (picked.isNotEmpty() && picked[0].role != Role.USER) picked.removeAt(0)
        val merged = ArrayList<Message>()
        for (m in picked) {
            val last = if (merged.isEmpty()) null else merged[merged.size - 1]
            if (last != null && last.role == m.role) {
                merged[merged.size - 1] = last.copy(text = last.text + "\n\n" + m.text)
            } else {
                merged.add(m)
            }
        }
        while (merged.isNotEmpty() && merged[merged.size - 1].role == Role.USER) merged.removeAt(merged.size - 1)
        return merged
    }

    /** The model must be installed and loaded before any local call: shared by [runLocal] and [runPlanBuild]. */
    private suspend fun prepareLocal(run: Run) {
        when (Modules.phase) {
            Modules.Phase.NOT_IMPORTED, Modules.Phase.ERROR, Modules.Phase.DOWNLOADING, Modules.Phase.IMPORTING ->
                throw ReplyError("Gemma 4 abhi taiyaar nahi hai. Voice and AI mein Download ya Import karo, ya Gemini chuno.")
            else -> Unit
        }
        if (!LocalLlm.loaded) {
            run.label = "Model RAM mein load ho raha hai"
            postProgress(run)
            val loadStart = System.currentTimeMillis()
            Modules.ensureLoaded()
            run.loadMs = System.currentTimeMillis() - loadStart
        }
        if (LocalLlm.busy) {
            run.label = "Pichla reply khatam ho raha hai"
            postProgress(run)
        }
        run.label = "Gemma soch raha hai"
        postProgress(run)
    }

    /**
     * T3: the plan builder. The app fixes the headings and the 5 phases, copies the user's own chat lines into the plan, asks
     * Gemma for one small box at a time and checks every box ([PlanBuilder.build]). Offline only: Gemini is not used here.
     * Takes minutes on a phone (about 8 to 16 short generations); the plan grows on screen box by box.
     */
    private suspend fun runPlanBuild(
        app: Context, run: Run, history: List<Message>, latest: Message, src: PlanBuilder.Source
    ): Message {
        ConvKpi.inc("pb_runs")
        if (src.redo) ConvKpi.inc("pb_redo")
        run.route = "plan-builder"
        prepareLocal(run)
        val lang = PlanBuilder.language(latest.text, MemoryStore.replyLanguage())
        val req = PlanBuilder.Request(src.request, PlanBuilder.decisions(history, src.request), lang)
        val started = System.currentTimeMillis()
        val result = PlanBuilder.build(
            req,
            { system, user -> LocalLlm.chat(system, emptyList(), user, emptyList(), { run.cancelled }) { }.text },
            { n, total, key, partial ->
                run.label = "Plan: hissa $n / $total (" + key + ")"
                run.phase = run.label
                if (partial.isNotEmpty()) {
                    if (run.firstTokenMs == 0L) run.firstTokenMs = System.currentTimeMillis() - run.startedAt
                    run.text = partial
                }
                postProgress(run)
            },
            { run.cancelled }
        )
        val secs = (System.currentTimeMillis() - started) / 1000
        ConvKpi.inc(if (result.ok) "pb_ok" else "pb_incomplete")
        ConvKpi.inc("pb_retries", result.retries.toLong())
        ConvKpi.inc("pb_tech_dropped", result.techDropped.toLong())
        run.detail = "plan builder " + result.phases + "/" + PlanBuilder.PHASES + " phase, " + result.calls + " call" +
            (if (result.ok) "" else ", adhura: " + result.problems.joinToString("; "))
        val where = if (LocalLlm.backendName.isEmpty()) "" else " · " + LocalLlm.backendName.uppercase(Locale.US)
        val caption = PlanBuilder.NOTE + " · " + LOCAL_NAME + where + " · " + result.phases + "/" + PlanBuilder.PHASES +
            " phase · " + result.calls + " call · " + secs + "s" + (if (result.ok) "" else " · adhura")
        return Message(id = run.messageId, role = Role.AI, text = result.text, engine = "local", note = caption)
    }

    /**
     * T4: one Gemma call that must answer with ONE small JSON object ([Sketch.systemPrompt]); a wrong answer gets one retry with
     * the reason. The checked drawing is stored in [Message.sketch] and drawn by SketchView. Offline only.
     */
    private suspend fun runSketch(run: Run, latest: Message): Message {
        ConvKpi.inc("sk_runs")
        run.route = "sketch"
        prepareLocal(run)
        run.label = "Sketch bana raha hoon"
        postProgress(run)
        val lang = PlanBuilder.language(latest.text, MemoryStore.replyLanguage())
        val system = Sketch.systemPrompt(lang)
        val ask = Sketch.userPrompt(latest.text)
        val first = LocalLlm.chat(system, emptyList(), ask, emptyList(), { run.cancelled }) { }
        var millis = first.millis
        var parsed: Sketch.Parsed = Sketch.parse(first.text)
        var retried = false
        val bad = parsed
        if (bad is Sketch.Parsed.Bad && !run.cancelled) {
            ConvKpi.inc("sk_retry")
            retried = true
            run.label = "Sketch theek kar raha hoon"
            postProgress(run)
            val again = LocalLlm.chat(system, emptyList(), ask + Sketch.retryNote(bad.reason), emptyList(), { run.cancelled }) { }
            millis += again.millis
            parsed = Sketch.parse(again.text)
        }
        val where = if (LocalLlm.backendName.isEmpty()) "" else " · " + LocalLlm.backendName.uppercase(Locale.US)
        val caption = "Sketch · $LOCAL_NAME$where · ${millis / 1000}s" + (if (retried) " · dobara" else "")
        val result = parsed
        return when (result) {
            is Sketch.Parsed.Ok -> {
                ConvKpi.inc("sk_ok")
                run.detail = "sketch " + result.kind + ", " + result.drawing.shapes.size + " shapes"
                Message(
                    id = run.messageId, role = Role.AI, engine = "local", note = caption,
                    text = result.drawing.title + " (" + Sketch.kindLabel(result.kind) + ")",
                    sketch = Sketch.toJson(result.drawing)
                )
            }
            is Sketch.Parsed.Bad -> {
                ConvKpi.inc("sk_failed")
                run.detail = "sketch refused: " + result.reason
                Message(
                    id = run.messageId, role = Role.AI, engine = "local", note = caption,
                    text = "Sketch nahi ban paya (" + result.reason + "). Thoda saaf likho, jaise: " +
                        "\"sketch banao: ghar\" ya \"login ka flowchart banao\"."
                )
            }
        }
    }

    private suspend fun runLocal(app: Context, run: Run, history: List<Message>, latest: Message): Message {
        prepareLocal(run)

        // Images go to the model as pictures (Gemma 4 is multimodal); text files are inlined as for Gemini.
        val payload = withContext(Dispatchers.IO) {
            AttachmentText.read(app, latest.attachments, true, 40_000)
        }
        val userText = Prompts.combineLatest(latest.text, payload.text)
        val past = recentTurns(localHistory(history))

        // Phone tools only for a plain, short, action-looking message (same rule as before; a few hundred tokens).
        // "<app> on karo" counts too, when the words name an installed app (test chat 13 Oct 2026: "WhatsApp on karo").
        var wantTools = latest.attachments.isEmpty() && ActionHint.looksLikeAction(latest.text) { Tier0.installedAppNames(app) }
        var system = Prompts.systemLocal(run.viaVoice, latest.text)
        if (wantTools) {
            system += "\n" + Prompts.TOOL_RULES + "\n" + Prompts.LOCAL_TOOL_HINT + "\n\n" + ToolSpecs.qwenToolsBlock()
        }

        var lastPost = 0L
        var reply = LocalLlm.chat(system, past, userText, payload.images, { run.cancelled }) { delta ->
            // a tool call is parsed first, so its raw "<tool_call>" text is never shown while it is written
            if (!wantTools) {
                if (run.firstTokenMs == 0L) run.firstTokenMs = System.currentTimeMillis() - run.startedAt
                run.text = run.text + delta
                val now = System.currentTimeMillis()
                if (now - lastPost > 90) {
                    lastPost = now
                    postProgress(run)
                }
            }
        }
        // The tools were off and the model said "main ek AI hoon, phone par ye nahi kar sakta" to a short order: that is false.
        // Ask once more WITH the tools (only for orders, never for questions about ability: see ToolRefusal).
        var extraMs = 0L
        if (!wantTools && latest.attachments.isEmpty() && !run.cancelled &&
            ToolRefusal.shouldRetryWithTools(latest.text, reply.text)
        ) {
            ConvKpi.inc("local_refusal_retry")
            run.label = "Phone tools ke saath dobara try kar raha hoon"
            run.text = ""
            postProgress(run)
            wantTools = true
            system += "\n" + Prompts.TOOL_RULES + "\n" + Prompts.LOCAL_TOOL_HINT + "\n\n" + ToolSpecs.qwenToolsBlock()
            extraMs = reply.millis
            reply = LocalLlm.chat(system, past, userText, payload.images, { run.cancelled }) { }
        }
        run.tokens = reply.tokens
        run.tps = reply.tokensPerSecond
        val speed = String.format(Locale.US, "%.1f", reply.tokensPerSecond)
        val where = if (LocalLlm.backendName.isEmpty()) "" else " · " + LocalLlm.backendName.uppercase(Locale.US)
        var caption = "$LOCAL_NAME$where · ~$speed tok/s · ${(reply.millis + extraMs) / 1000}s"

        var finalText = reply.text.trim()
        if (wantTools && finalText.isNotEmpty()) {
            var parsed: PlanParser.Parsed = PlanParser.parse(finalText)
            if (parsed is PlanParser.Parsed.Invalid) {
                // audit Sec 9.3: one repair retry, then a question, never a guess
                run.label = "Tool call theek kar raha hoon"
                postProgress(run)
                val note = "Your tool call was not valid: " + parsed.reason +
                    ". Reply again with correct <tool_call> blocks, or answer in plain text."
                val withAnswer = past +
                    Message(role = Role.USER, text = userText) +
                    Message(role = Role.AI, text = finalText)
                val again = LocalLlm.chat(system, withAnswer, note, emptyList(), { run.cancelled }) { }
                caption = "$LOCAL_NAME$where · ${(reply.millis + extraMs + again.millis) / 1000}s"
                parsed = PlanParser.parse(again.text)
            }
            when (parsed) {
                is PlanParser.Parsed.Calls ->
                    return planMessage(app, run, "local", caption, parsed.ack, parsed.tasks, payload.skipped)
                is PlanParser.Parsed.Invalid -> {
                    run.detail = "plan refused twice: " + parsed.reason
                    return Message(
                        id = run.messageId, role = Role.AI, text = CLARIFY, engine = "local",
                        note = noteWith(caption, payload.skipped)
                    )
                }
                is PlanParser.Parsed.Text -> finalText = parsed.text.trim()
            }
        }
        // T2 (test chat 13 Oct 2026): the user wants Hinglish (said so, or writes it) and Gemma answered in English. Ask ONCE more,
        // with the rule repeated in the user turn. A retry that is still in the wrong language is dropped: the first answer stays.
        // Not for tool turns, code, or short replies (ReplyGuard.languageMismatch never judges those).
        val remembered = MemoryStore.replyLanguage()
        val wantLang = ReplyGuard.wantedLanguage(latest.text, remembered)
        if (wantLang != null && !wantTools && finalText.isNotEmpty() && !run.cancelled &&
            ReplyGuard.languageMismatch(latest.text, finalText, remembered)
        ) {
            ConvKpi.inc("local_lang_retry")
            run.label = if (wantLang == LocalMemory.LANG_ENGLISH) "English mein dobara bana raha hoon" else "Hinglish mein dobara bana raha hoon"
            run.text = ""
            postProgress(run)
            val langSystem = system + "\n" + Prompts.languageLine(wantLang)
            val again = LocalLlm.chat(
                langSystem, past, userText + "\n\n" + ReplyGuard.retryNote(wantLang), payload.images, { run.cancelled }
            ) { delta ->
                if (run.firstTokenMs == 0L) run.firstTokenMs = System.currentTimeMillis() - run.startedAt
                run.text = run.text + delta
                val now = System.currentTimeMillis()
                if (now - lastPost > 90) {
                    lastPost = now
                    postProgress(run)
                }
            }
            val againText = again.text.trim()
            if (againText.isNotEmpty() && !ReplyGuard.languageMismatch(latest.text, againText, remembered)) {
                ConvKpi.inc("local_lang_retry_ok")
                finalText = againText
                caption += " · bhasha retry +" + (again.millis / 1000) + "s"
            } else {
                ConvKpi.inc("local_lang_retry_failed")
                caption += " · bhasha retry ke baad bhi nahi badli"
            }
            run.detail = "language retry"
        }
        if (finalText.isEmpty()) {
            run.detail = "local reply empty"
            return Message(
                id = run.messageId, role = Role.AI, engine = "local", state = "error",
                text = "Gemma ne is baar khaali jawab diya. Dobara try karo.",
                note = caption
            )
        }
        return Message(
            id = run.messageId, role = Role.AI, text = finalText, engine = "local",
            note = noteWith(caption, payload.skipped)
        )
    }

    private fun noteWith(base: String, skipped: List<String>): String =
        if (skipped.isEmpty()) base else base + "\nNahi bheja: " + skipped.joinToString("; ")

    private fun errorMessage(run: Run, e: Throwable): Message {
        val text: String = when (e) {
            is ReplyError -> e.message ?: "Reply nahi ban paya."
            is LocalLlm.LoadFailure -> e.message ?: "Model load nahi hua."
            is GeminiClient.ApiError -> e.message ?: "Gemini error."
            is UnknownHostException -> "Internet nahi mil raha. Connection check karo."
            is SocketTimeoutException -> "Gemini ka jawab time par nahi aaya. Dobara try karo."
            is IOException -> "Network error: " + (e.message ?: "connection toot gaya")
            else -> "Reply nahi ban paya: " + (e.message ?: e.javaClass.simpleName)
        }
        return Message(id = run.messageId, role = Role.AI, text = text, state = "error", engine = run.engine)
    }
}
