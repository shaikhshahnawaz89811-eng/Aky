package com.codeassist.ai.ai

import android.app.ActivityManager
import android.content.Context
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * On-device Qwen2.5 1.5B Instruct (GGUF) through the llama-android AAR (llama.cpp, CPU/NEON).
 *
 * Free-API facts that shape this class (from the library docs):
 *  - one LlamaModel is NOT thread-safe, so every call is serialised with [lock];
 *  - complete() is single-turn and returns the whole reply at once (no token streaming; streaming,
 *    grammar-constrained JSON, vision and function calling are Pro-only), so conversation history is
 *    folded into the prompt by [ChatMl.build];
 *  - sampling (temperature, top-p, top-k) is fixed when the model is loaded.
 */
object LocalLlm {
    class LoadFailure(message: String) : Exception(message)
    /** [capped] = the model ran into maxTokens instead of stopping by itself. */
    class Reply(val text: String, val tokens: Int, val tokensPerSecond: Float, val millis: Long, val capped: Boolean = false)

    private val lock = Mutex()
    private var model: LlamaModel? = null

    @Volatile
    var loaded: Boolean = false
        private set

    /** True while a native completion is running; it cannot be interrupted, only waited for. */
    @Volatile
    var busy: Boolean = false
        private set

    suspend fun load(ctx: Context, file: File, contextSize: Int, threads: Int, temperature: Float) {
        lock.withLock {
            if (loaded) return
            checkMemory(ctx, file.length(), contextSize)
            val m: LlamaModel = try {
                withContext(Dispatchers.Default) {
                    Llama.loadModel(
                        file.absolutePath,
                        LlamaConfig(
                            contextSize = contextSize,
                            threads = threads,
                            temperature = temperature
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnsatisfiedLinkError) {
                throw LoadFailure("Is device par llama library load nahi hui. 64-bit (arm64) phone chahiye.")
            } catch (e: OutOfMemoryError) {
                throw LoadFailure("Load ke dauran RAM khatam ho gayi. Baaki apps band karo ya Context size kam karo.")
            } catch (e: Throwable) {
                throw LoadFailure("Model load nahi hua: " + (e.message ?: e.javaClass.simpleName))
            }
            model = m
            loaded = true
        }
    }

    suspend fun unload() {
        lock.withLock {
            val m = model
            model = null
            loaded = false
            if (m != null) {
                withContext(Dispatchers.Default) {
                    try {
                        Llama.releaseModel(m)
                    } catch (_: Throwable) {
                        // releasing twice is documented as safe; nothing else to do
                    }
                }
            }
        }
    }

    suspend fun generate(system: String, prompt: String, maxTokens: Int): Reply {
        return lock.withLock {
            val m = model ?: throw IllegalStateException("Qwen2.5 model load nahi hai.")
            busy = true
            try {
                withContext(Dispatchers.Default) {
                    val started = System.currentTimeMillis()
                    val r = Llama.complete(
                        m,
                        prompt,
                        systemPrompt = system,
                        maxTokens = maxTokens
                    )
                    val generated = r.tokensGenerated.toInt()
                    Reply(
                        clean(r.text),
                        generated,
                        r.tokensPerSecond.toFloat(),
                        System.currentTimeMillis() - started,
                        generated >= maxTokens - 1
                    )
                }
            } finally {
                busy = false
            }
        }
    }

    private fun checkMemory(ctx: Context, modelBytes: Long, contextSize: Int) {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        // weights + KV cache + runtime buffers. Qwen2.5 1.5B: 28 layers x 2 KV heads x 128 dims x 2 (K and V)
        // x 2 bytes (f16) = 28,672 bytes per token; 400 MB covers the compute buffers and the app itself.
        val need = modelBytes + contextSize.toLong() * KV_BYTES_PER_TOKEN + 400L * 1024 * 1024
        if (info.availMem < need) {
            throw LoadFailure(
                "RAM kam hai: " + gb(info.availMem) + " free, lagbhag " + gb(need) +
                    " chahiye. Baaki apps band karke dobara Load karo ya Context size kam karo."
            )
        }
    }

    private fun gb(bytes: Long): String =
        String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0)

    private fun clean(raw: String): String = ChatMl.clean(raw)

    private const val KV_BYTES_PER_TOKEN = 28_672L
}
