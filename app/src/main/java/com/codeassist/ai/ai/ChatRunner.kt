package com.codeassist.ai.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
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

    class Run(val chatId: String, val messageId: String, val engine: String, val viaVoice: Boolean) {
        @Volatile var text: String = ""
        @Volatile var label: String = "Soch raha hoon"
        @Volatile var phase: String = "Soch raha hoon"
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
                    provider == "gemini" -> runGemini(app, run, history, latest)
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
                route = if (run.engine == "tool") "tier0" else "brain",
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
            GeminiClient.streamWithTools(
                key, model, system, turns, Store.geminiTemp,
                if (useTools) ToolSpecs.all else emptyList(), { run.cancelled }
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
        run.label = "Qwen soch raha hai"
        postProgress(run)

        var maxTokens = Prompts.maxTokensLocal(Store.replyLength)
        if (run.viaVoice && Store.replyLength != "Long") maxTokens = minOf(maxTokens, 220)
        val budget = Prompts.budgetChars(Store.localContext, maxTokens)
        val payload = withContext(Dispatchers.IO) {
            AttachmentText.read(app, latest.attachments, false, budget / 2)
        }
        val system = Prompts.system(run.viaVoice)
        // Tools go into the prompt only for a short plain message that mentions a phone action (see ActionHint).
        val wantTools = latest.attachments.isEmpty() && ActionHint.looksLikeAction(latest.text)
        val prompt = if (wantTools) {
            ChatMl.build(
                ChatMl.withTools(system, Prompts.TOOL_RULES, ToolSpecs.qwenToolsBlock()),
                history, Prompts.combineLatest(latest.text, payload.text), budget
            )
        } else {
            ChatMl.build(
                system, history, Prompts.combineLatest(latest.text, payload.text), budget - system.length
            )
        }
        // system prompt is already inside [prompt]; pass "" so the library does not add a second one
        var reply = LocalLlm.generate("", prompt, maxTokens)
        if (reply.text.isBlank()) {
            throw ReplyError("Model ne khaali reply di. Dobara try karo ya Reply length badlao.")
        }
        var tokens = reply.tokens
        var millis = reply.millis

        var outcome: Message? = null
        if (wantTools) {
            val first = PlanParser.parse(reply.text)
            var parsed: PlanParser.Parsed = first
            if (first is PlanParser.Parsed.Invalid) {
                // audit Sec 9.3: one repair retry, then a question, never a guess
                run.label = "Tool call theek kar raha hoon"
                postProgress(run)
                val note = "Your tool call was not valid: " + first.reason +
                    ". Reply again with correct <tool_call> blocks, or answer in plain text."
                reply = LocalLlm.generate("", ChatMl.appendTurn(prompt, reply.text, note), maxTokens)
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

        val plain = if (wantTools) PlanParser.parse(reply.text) else null
        val shown = if (plain is PlanParser.Parsed.Text) plain.text else reply.text
        if (shown.isBlank()) {
            throw ReplyError("Model ne khaali reply di. Dobara try karo ya Reply length badlao.")
        }
        val speed = String.format(Locale.US, "%.1f", reply.tokensPerSecond)
        val secs = millis / 1000
        return Message(
            id = run.messageId, role = Role.AI, text = shown, engine = "local",
            note = noteWith("Qwen2.5 1.5B · $speed tok/s · ${secs}s", payload.skipped)
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
