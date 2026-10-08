package com.codeassist.ai.ai

import android.app.ActivityManager
import android.content.Context
import com.codeassist.ai.data.Message
import com.codeassist.ai.data.Role
import com.codeassist.ai.data.Store
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import com.google.ai.edge.litertlm.Message as LmMessage

/**
 * On-device Gemma 4 E2B (.litertlm) through Google's LiteRT-LM runtime (Kotlin API, GPU first, CPU fallback).
 *
 * What changed against the old Qwen/llama.cpp build:
 *  - the runtime applies the model's own chat template, so the hand-built ChatML text, the "which prompt shape works"
 *    test and the reply-repair code are no longer needed for chat;
 *  - replies stream token by token;
 *  - the model takes images directly (vision backend), no OCR detour;
 *  - history is passed as real user/model messages ([ConversationConfig.initialMessages]) on every turn. A fresh
 *    conversation per turn keeps this stateless, like before; the runtime's prefill is fast enough for that.
 *
 * One engine is not used from two places at once: every call is serialised with [lock].
 */
object LocalLlm {
    class LoadFailure(message: String) : Exception(message)

    /** [capped] is kept for old callers; tokens are estimated from the text length (the runtime does not report them). */
    class Reply(val text: String, val tokens: Int, val tokensPerSecond: Float, val millis: Long, val capped: Boolean = false)

    private val lock = Mutex()
    private var engine: Engine? = null

    @Volatile
    var loaded: Boolean = false
        private set

    /** True while a reply is being produced. */
    @Volatile
    var busy: Boolean = false
        private set

    /** "gpu" or "cpu": what the loaded engine really runs on (shown in the reply caption). */
    @Volatile
    var backendName: String = ""
        private set

    suspend fun load(ctx: Context, file: File) {
        lock.withLock {
            if (loaded) return
            checkMemory(ctx)
            val wantGpu = Store.localBackend != "cpu"
            val made: Engine = try {
                withContext(Dispatchers.Default) {
                    if (wantGpu) {
                        try {
                            start(ctx, file, true).also { backendName = "gpu" }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            // GPU (OpenCL) is missing or refused on this phone: remember it and use the CPU
                            Store.localBackend = "cpu"
                            start(ctx, file, false).also { backendName = "cpu" }
                        }
                    } else {
                        start(ctx, file, false).also { backendName = "cpu" }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                throw LoadFailure("Load ke dauran RAM khatam ho gayi. Baaki apps band karke dobara Load karo.")
            } catch (e: Throwable) {
                throw LoadFailure("Gemma load nahi hua: " + (e.message ?: e.javaClass.simpleName))
            }
            engine = made
            loaded = true
        }
    }

    private suspend fun start(ctx: Context, file: File, gpu: Boolean): Engine {
        val config = EngineConfig(
            modelPath = file.absolutePath,
            backend = if (gpu) Backend.GPU() else Backend.CPU(),
            visionBackend = if (gpu) Backend.GPU() else Backend.CPU(),
            cacheDir = ctx.cacheDir.path
        )
        val e = Engine(config)
        try {
            e.initialize()
        } catch (t: Throwable) {
            try {
                e.close()
            } catch (_: Throwable) {
                // nothing left to release
            }
            throw t
        }
        return e
    }

    suspend fun unload() {
        lock.withLock {
            val e = engine
            engine = null
            loaded = false
            backendName = ""
            if (e != null) {
                withContext(Dispatchers.Default) {
                    try {
                        e.close()
                    } catch (_: Throwable) {
                        // closing twice is harmless; nothing else to do
                    }
                }
            }
        }
    }

    /**
     * One reply. [history] is the earlier chat (user/AI turns, text only), [userText] the new message, [images] go
     * with it as pictures. [onDelta] gets each new piece of text on a background thread. [isCancelled] is polled
     * between pieces.
     */
    suspend fun chat(
        system: String,
        history: List<Message>,
        userText: String,
        images: List<GeminiClient.Image>,
        isCancelled: () -> Boolean,
        onDelta: (String) -> Unit
    ): Reply {
        return lock.withLock {
            val e = engine ?: throw IllegalStateException("Gemma model load nahi hai.")
            busy = true
            try {
                withContext(Dispatchers.Default) {
                    val started = System.currentTimeMillis()
                    val initial = ArrayList<LmMessage>()
                    for (m in history) {
                        val t = m.text.trim()
                        if (t.isEmpty()) continue
                        initial.add(if (m.role == Role.USER) LmMessage.user(t) else LmMessage.model(t))
                    }
                    val config = ConversationConfig(
                        systemInstruction = Contents.of(system),
                        initialMessages = initial,
                        samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7)
                    )
                    val parts = ArrayList<Content>()
                    for (img in images) parts.add(Content.ImageBytes(img.bytes))
                    parts.add(Content.Text(userText.ifBlank { " " }))
                    val out = StringBuilder()
                    e.createConversation(config).use { conversation ->
                        conversation.sendMessageAsync(Contents.of(*parts.toTypedArray())).collect { piece ->
                            if (!isCancelled()) {
                                val s = piece.toString()
                                if (s.isNotEmpty()) {
                                    out.append(s)
                                    onDelta(s)
                                }
                            }
                        }
                    }
                    val ms = System.currentTimeMillis() - started
                    val text = out.toString().trim()
                    val tokens = (text.length / 3).coerceAtLeast(if (text.isEmpty()) 0 else 1)
                    val tps = if (ms > 0) tokens * 1000f / ms else 0f
                    Reply(text, tokens, tps, ms)
                }
            } finally {
                busy = false
            }
        }
    }

    /** Plain one-shot call kept for the old probe code ([LocalCalibration]); [maxTokens] is ignored. */
    suspend fun generate(system: String, prompt: String, @Suppress("UNUSED_PARAMETER") maxTokens: Int): Reply =
        chat(system, emptyList(), prompt, emptyList(), { false }, { })

    private fun checkMemory(ctx: Context) {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        // Google's numbers for E2B: about 0.7 GB peak on GPU, 1.7 GB on CPU (the 2.6 GB file is memory-mapped).
        val need = 1_500L * 1024 * 1024
        if (info.availMem < need) {
            throw LoadFailure(
                "RAM kam hai: " + gb(info.availMem) + " free, lagbhag " + gb(need) +
                    " chahiye. Baaki apps band karke dobara Load karo."
            )
        }
    }

    private fun gb(bytes: Long): String =
        String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
}
