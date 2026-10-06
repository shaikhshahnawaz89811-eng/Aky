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
                    tool != null -> Message(
                        id = run.messageId, role = Role.AI, text = tool.reply,
                        engine = "tool", note = tool.title
                    )
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
        if (msg != null) persist(run.chatId, msg)
        listener?.onFinished(run, msg)
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
        val system = Prompts.system(run.viaVoice)
        var lastPost = 0L
        val full = withContext(Dispatchers.IO) {
            GeminiClient.stream(key, model, system, turns, Store.geminiTemp, { run.cancelled }) { delta ->
                run.text = run.text + delta
                val now = System.currentTimeMillis()
                if (now - lastPost > 90) {
                    lastPost = now
                    postProgress(run)
                }
            }
        }
        return Message(
            id = run.messageId, role = Role.AI, text = full.trim(), engine = "gemini",
            note = noteWith("Gemini · $model", payload.skipped)
        )
    }

    // ---------- Phi-4 mini (on device) ----------

    private suspend fun runLocal(app: Context, run: Run, history: List<Message>, latest: Message): Message {
        when (Modules.phase) {
            Modules.Phase.NOT_IMPORTED, Modules.Phase.ERROR, Modules.Phase.DOWNLOADING, Modules.Phase.IMPORTING ->
                throw ReplyError("Phi-4 mini abhi taiyaar nahi hai. Voice and AI mein Download ya Import karo, ya Gemini chuno.")
            else -> Unit
        }
        if (!LocalPhi.loaded) {
            run.label = "Model RAM mein load ho raha hai"
            postProgress(run)
            Modules.ensureLoaded()
        }
        if (LocalPhi.busy) {
            run.label = "Pichla reply khatam ho raha hai"
            postProgress(run)
        }
        run.label = "Phi-4 soch raha hai"
        postProgress(run)

        var maxTokens = Prompts.maxTokensLocal(Store.replyLength)
        if (run.viaVoice && Store.replyLength != "Long") maxTokens = minOf(maxTokens, 220)
        val budget = Prompts.budgetChars(Store.phiContext, maxTokens)
        val payload = withContext(Dispatchers.IO) {
            AttachmentText.read(app, latest.attachments, false, budget / 2)
        }
        val system = Prompts.system(run.viaVoice)
        val prompt = Prompts.buildPhiChat(
            system, history, Prompts.combineLatest(latest.text, payload.text), budget - system.length
        )
        // system prompt is already inside [prompt]; pass "" so the library does not add a second one
        val reply = LocalPhi.generate("", prompt, maxTokens)
        if (reply.text.isBlank()) {
            throw ReplyError("Model ne khaali reply di. Dobara try karo ya Reply length badlao.")
        }
        val speed = String.format(Locale.US, "%.1f", reply.tokensPerSecond)
        val secs = reply.millis / 1000
        return Message(
            id = run.messageId, role = Role.AI, text = reply.text, engine = "phi4",
            note = noteWith("Phi-4 mini · $speed tok/s · ${secs}s", payload.skipped)
        )
    }

    private fun noteWith(base: String, skipped: List<String>): String =
        if (skipped.isEmpty()) base else base + "\nNahi bheja: " + skipped.joinToString("; ")

    private fun errorMessage(run: Run, e: Throwable): Message {
        val text: String = when (e) {
            is ReplyError -> e.message ?: "Reply nahi ban paya."
            is LocalPhi.LoadFailure -> e.message ?: "Model load nahi hua."
            is GeminiClient.ApiError -> e.message ?: "Gemini error."
            is UnknownHostException -> "Internet nahi mil raha. Connection check karo."
            is SocketTimeoutException -> "Gemini ka jawab time par nahi aaya. Dobara try karo."
            is IOException -> "Network error: " + (e.message ?: "connection toot gaya")
            else -> "Reply nahi ban paya: " + (e.message ?: e.javaClass.simpleName)
        }
        return Message(id = run.messageId, role = Role.AI, text = text, state = "error", engine = run.engine)
    }
}
