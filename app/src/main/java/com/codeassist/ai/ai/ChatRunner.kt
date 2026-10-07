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

    fun isRunning(): Boolean = current != null

    /** Answers the last user message of [chatId]. Returns null if a reply is already running. */
    fun start(ctx: Context, chatId: String, viaVoice: Boolean): Run? {
        if (current != null) return null
        val app = ctx.applicationContext
        Store.init(app)
        Modules.init(app)
        ActivityLog.init(app)
        ConvKpi.init(app)
        val all = Store.messages(chatId)
        val userIndex = all.indexOfLast { it.role == Role.USER }
        if (userIndex < 0) return null
        val latest = all[userIndex]
        val history = all.subList(0, userIndex).filter { it.state == null && it.text.isNotBlank() }
        val provider = Store.brainProvider

        val tool: Tier0.Result? =
            if (latest.attachments.isEmpty()) Tier0.handle(app, latest.text) else null
        val engine = if (tool != null) "tool" else provider
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

    /** Stop button. Keeps whatever Gemini had streamed so far; a local reply cannot be partial. */
    fun stop() {
        val run = current ?: return
        run.cancelled = true
        run.job?.cancel()
        val partial = run.text.trim()
        val msg = if (partial.isNotEmpty()) {
            Message(
                id = run.messageId, role = Role.AI, text = partial,
                engine = run.engine, note = "Beech mein roka gaya"
            )
        } else {
            null
        }
        finish(run, msg)
    }

    private fun finish(run: Run, msg: Message?) {
        if (current !== run) return
        current = null
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
                    text = "$transition\n\nQwen2.5 phone mein installed nahi hai, isliye fallback nahi ho saka. " +
                        "Voice and AI mein model Download ya Import karo; agli turn par Gemini phir try hoga.",
                    state = "error", engine = "gemini", note = "$levelText · fallback unavailable"
                )
            }

            // Do not leave partial Gemini text beside the new local answer if the stream failed mid-turn.
            run.text = ""
            run.engine = "local"
            run.label = transition
            run.detail = "Gemini -> Qwen · ${level.label}"
            postProgress(run)

            return try {
                val local = runLocal(app, run, history, latest)
                if (local.state == "error") {
                    ConvKpi.inc("fallback_failed")
                    return Message(
                        id = run.messageId, role = Role.AI,
                        text = "$transition\n\n" + local.text,
                        state = "error", engine = "local",
                        note = "Fallback ${level.label} · Qwen reply rejected"
                    )
                }
                ConvKpi.inc("fallback_success")
                local.copy(
                    text = FallbackPolicy.successLine(level) + "\n\n" + local.text,
                    note = "Fallback ${level.label} · " + (local.note ?: "Qwen2.5")
                )
            } catch (fallbackError: CancellationException) {
                throw fallbackError
            } catch (fallbackError: Exception) {
                ConvKpi.inc("fallback_failed")
                val details = errorMessage(run, fallbackError).text
                Message(
                    id = run.messageId, role = Role.AI,
                    text = "$transition\n\nQwen fallback bhi nahi chal saka: $details",
                    state = "error", engine = "local",
                    note = "Fallback ${level.label} · Qwen failed"
                )
            }
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
        val system = Prompts.system(run.viaVoice) + (if (useTools) "\n" + Prompts.TOOL_RULES else "")
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
            val tasks = streamed.calls.mapIndexed { i, c -> PlanTask("t" + (i + 1), c.name, c.args) }
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
    private fun planMessage(
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
        val out = PlanExecutor.run(app, ack, tasks)
        run.detail = "tools: " + tasks.joinToString(",") { it.tool }
        val undo: String? = if (out.undoIds.isEmpty()) null else out.undoIds.joinToString(",")
        return Message(
            id = run.messageId, role = Role.AI, text = out.text, engine = engine,
            note = noteWith(caption + " · tools", skipped),
            undoId = undo, actions = out.chip, pending = out.pendingJson
        )
    }

    // ---------- Qwen2.5 1.5B (on device) ----------

    private suspend fun runLocal(app: Context, run: Run, history: List<Message>, latest: Message): Message {
        when (Modules.phase) {
            Modules.Phase.NOT_IMPORTED, Modules.Phase.ERROR, Modules.Phase.DOWNLOADING, Modules.Phase.IMPORTING ->
                throw ReplyError("Qwen2.5 abhi taiyaar nahi hai. Voice and AI mein Download ya Import karo, ya Gemini chuno.")
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

        // Which prompt shape this model + library pair really understands. Tested once per model file
        // (LocalCalibration); a forced choice in Qwen options skips the test.
        val kind = LocalCalibration.resolve { status ->
            run.label = status
            postProgress(run)
        }
        run.label = "Qwen soch raha hai"
        postProgress(run)

        // A greeting gets a short budget: if the model does not stop, little damage is done.
        val smallTalk = latest.attachments.isEmpty() && ReplyGuard.isSmallTalk(latest.text)
        var maxTokens = Prompts.maxTokensLocal(Store.replyLength)
        if (run.viaVoice && Store.replyLength != "Long") maxTokens = minOf(maxTokens, 220)
        if (smallTalk) maxTokens = minOf(maxTokens, Prompts.SMALL_TALK_TOKENS)
        val budget = Prompts.budgetChars(Store.localContext, maxTokens)
        val payload = withContext(Dispatchers.IO) {
            AttachmentText.read(
                app, latest.attachments, allowImages = false, maxChars = budget / 2,
                ocrImages = Store.localScreenshotOcr
            )
        }

        // A short question about a photo, but nothing readable came out of it: say so. A 1.5B model that only
        // sees "isko jante ho?" makes something up.
        val hasImage = latest.attachments.any { it.kind == AttachKind.IMAGE }
        if (ReplyGuard.needsImage(latest.text, hasImage, payload.text.isNotBlank())) {
            ConvKpi.inc("local_image_block")
            run.detail = "image: local model skipped"
            val how = if (Store.localScreenshotOcr) {
                "On-device OCR ko is photo mein padhne layak text nahi mila. "
            } else {
                "Photo ka text padhwana ho toh Voice and AI mein \"Qwen ke liye attached-image OCR\" on karo. "
            }
            return Message(
                id = run.messageId, role = Role.AI, engine = "local",
                text = "Ye photo offline Qwen nahi dekh sakta: ye sirf text padhta hai, isliye main andaza lagakar " +
                    "jawab nahi dunga.\n\n" + how +
                    "Photo samjhane ke liye Gemini chuno (internet chahiye) ya photo ka text yahan paste karo.",
                note = "Qwen2.5 1.5B · text-only"
            )
        }

        val system = Prompts.systemLocal(run.viaVoice)
        val userText = Prompts.combineLatest(latest.text, payload.text)
        // Tools only in the verified ChatML shape: the repair retry below needs real turn markers.
        val wantTools = kind == LocalTemplate.Kind.CHATML && latest.attachments.isEmpty() &&
            ActionHint.looksLikeAction(latest.text)
        val built: LocalTemplate.Built = if (wantTools) {
            LocalTemplate.Built(
                "",
                ChatMl.build(
                    ChatMl.withTools(system, Prompts.TOOL_RULES, ToolSpecs.qwenToolsBlock()),
                    history, userText, budget
                )
            )
        } else {
            LocalTemplate.build(kind, system, history, userText, budget)
        }
        var reply = LocalLlm.generate(built.system, built.prompt, maxTokens)
        var tokens = reply.tokens
        var millis = reply.millis

        var outcome: Message? = null
        if (wantTools && reply.text.isNotBlank()) {
            val first = PlanParser.parse(reply.text)
            var parsed: PlanParser.Parsed = first
            if (first is PlanParser.Parsed.Invalid) {
                // audit Sec 9.3: one repair retry, then a question, never a guess
                run.label = "Tool call theek kar raha hoon"
                postProgress(run)
                val note = "Your tool call was not valid: " + first.reason +
                    ". Reply again with correct <tool_call> blocks, or answer in plain text."
                reply = LocalLlm.generate("", ChatMl.appendTurn(built.prompt, reply.text, note), maxTokens)
                tokens += reply.tokens
                millis += reply.millis
                parsed = PlanParser.parse(reply.text)
            }
            val speedNow = String.format(Locale.US, "%.1f", reply.tokensPerSecond)
            val caption = "Qwen2.5 1.5B · $speedNow tok/s · ${millis / 1000}s"
            when (parsed) {
                is PlanParser.Parsed.Calls ->
                    outcome = planMessage(app, run, "local", caption, parsed.ack, parsed.tasks, payload.skipped)
                is PlanParser.Parsed.Invalid -> {
                    run.detail = "plan refused twice: " + parsed.reason
                    outcome = Message(
                        id = run.messageId, role = Role.AI, text = CLARIFY, engine = "local",
                        note = noteWith(caption, payload.skipped)
                    )
                }
                is PlanParser.Parsed.Text -> Unit
            }
        }
        run.tokens = tokens
        run.tps = reply.tokensPerSecond
        if (outcome != null) return outcome

        // Plain answer: check it, keep the clean beginning of a rambling one, ask once more for a broken one.
        val plain = if (wantTools) PlanParser.parse(reply.text) else null
        var shown = if (plain is PlanParser.Parsed.Text) plain.text else LocalTemplate.finish(kind, reply.text)
        var verdict = ReplyGuard.inspect(shown, latest.text, reply.capped, smallTalk)
        var retried = false
        if (!verdict.ok) {
            retried = true
            ConvKpi.inc("local_guard_retry")
            run.label = "Jawab saaf nahi tha, dobara soch raha hoon"
            postProgress(run)
            val retryTokens = minOf(maxTokens, 200)
            val retryBuilt = LocalTemplate.build(
                kind, Prompts.LOCAL_MINIMAL_SYSTEM, ReplyGuard.cleanHistory(history).takeLast(2), userText,
                Prompts.budgetChars(Store.localContext, retryTokens)
            )
            reply = LocalLlm.generate(retryBuilt.system, retryBuilt.prompt, retryTokens)
            tokens += reply.tokens
            millis += reply.millis
            shown = LocalTemplate.finish(kind, reply.text)
            verdict = ReplyGuard.inspect(shown, latest.text, reply.capped, smallTalk)
            run.tokens = tokens
            run.tps = reply.tokensPerSecond
        }
        if (!verdict.ok) {
            ConvKpi.inc("local_guard_fail")
            val why = verdict.problem ?: "unusable"
            run.detail = "local reply refused: $why"
            return Message(
                id = run.messageId, role = Role.AI, engine = "local", state = "error",
                text = "Offline model ne is baar saaf jawab nahi diya (ye chhota 1.5B model hai). " +
                    "Sawaal thoda chhota karke dobara poochho, ya Gemini chuno.",
                note = "Qwen2.5 1.5B · jawab reject ($why)"
            )
        }
        if (verdict.problem != null) {
            ConvKpi.inc("local_guard_trim")
            run.detail = "local reply trimmed: " + verdict.problem
        }
        val speed = String.format(Locale.US, "%.1f", reply.tokensPerSecond)
        val secs = millis / 1000
        val shape = if (retried) kind.key + " · 2. koshish" else kind.key
        return Message(
            id = run.messageId, role = Role.AI, text = verdict.text, engine = "local",
            note = noteWith("Qwen2.5 1.5B · $speed tok/s · ${secs}s · $shape", payload.skipped)
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
