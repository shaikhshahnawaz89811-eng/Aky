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
 * On-device Phi-4 mini (GGUF) through the llama-android AAR (llama.cpp, CPU/NEON).
 *
 * Free-API facts that shape this class (from the library docs):
 *  - one LlamaModel is NOT thread-safe, so every call is serialised with [lock];
 *  - complete() is single-turn and returns the whole reply at once (no token streaming),
 *    so conversation history is folded into the prompt by [Prompts.buildLocal];
 *  - sampling (temperature, top-p, top-k) is fixed when the model is loaded.
 */
object LocalPhi {
    class LoadFailure(message: String) : Exception(message)
    class Reply(val text: String, val tokens: Int, val tokensPerSecond: Float, val millis: Long)

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
            val m = model ?: throw IllegalStateException("Phi-4 mini load nahi hai.")
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
                    Reply(
                        clean(r.text),
                        r.tokensGenerated.toInt(),
                        r.tokensPerSecond.toFloat(),
                        System.currentTimeMillis() - started
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
        // weights + KV cache (~128 KB per token for Phi-4 mini) + runtime buffers
        val need = modelBytes + contextSize.toLong() * 131_072L + 350L * 1024 * 1024
        if (info.availMem < need) {
            throw LoadFailure(
                "RAM kam hai: " + gb(info.availMem) + " free, lagbhag " + gb(need) +
                    " chahiye. Baaki apps band karke dobara Load karo."
            )
        }
    }

    private fun gb(bytes: Long): String =
        String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0)

    private fun clean(raw: String): String {
        var t = raw
        // cut at the first chat marker (if the library leaves special tokens in the text)
        val cut = listOf("<|end|>", "<|user|>", "<|system|>", "<|endoftext|>", "<|assistant|>")
            .map { t.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()
        if (cut != null) t = t.substring(0, cut)
        // safety net: if the model still starts writing the next turn, drop it
        val runOn = Regex("\\n\\s*(User|Human|Assistant|Question):").find(t)
        if (runOn != null && runOn.range.first > 0) t = t.substring(0, runOn.range.first)
        return t.trim()
    }
}
