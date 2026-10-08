package com.codeassist.ai.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Everything about the optional offline speech model, in one place:
 *  - where the two files live (filesDir/stt_offline/model.int8.onnx + tokens.txt)
 *  - a resumable download from a link the user can change ([OfflineSttUrls] turns the link into two file URLs)
 *  - the loaded model: loaded in the background on first use, dropped again after ~90 s without use
 *
 * Nothing here runs unless the user chose "Offline model" in Voice and AI (or taps Download there).
 */
object OfflineSttStore {

    enum class Phase { IDLE, DOWNLOADING, ERROR }

    class State(val phase: Phase, val done: Long, val total: Long, val message: String?)

    private const val DIR_NAME = "stt_offline"
    private const val MIN_MODEL_BYTES = 5_000_000L
    private const val MIN_TOKENS_BYTES = 200L
    private const val IDLE_RELEASE_MS = 90_000L
    private const val SPACE_MARGIN = 64L * 1024L * 1024L

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var state: State = State(Phase.IDLE, 0L, 0L, null)
        private set

    /** Set by the settings screen. Always called on the main thread. */
    var listener: (() -> Unit)? = null

    private class Cancelled : RuntimeException()

    private val cancelFlag = AtomicBoolean(false)
    private val downloading = AtomicBoolean(false)
    private val downloadExec: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "offline-stt-download").apply { isDaemon = true }
    }
    private val engineExec: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "offline-stt-engine").apply { isDaemon = true }
    }

    @Volatile
    private var handle: SherpaBridge.Handle? = null
    private val activeCaptures = AtomicInteger(0)

    // ---------- files ----------

    fun dir(ctx: Context): File = File(ctx.applicationContext.filesDir, DIR_NAME)
    fun modelFile(ctx: Context): File = File(dir(ctx), OfflineSttUrls.MODEL_NAME)
    fun tokensFile(ctx: Context): File = File(dir(ctx), OfflineSttUrls.TOKENS_NAME)

    fun installed(ctx: Context): Boolean =
        modelFile(ctx).length() >= MIN_MODEL_BYTES && tokensFile(ctx).length() >= MIN_TOKENS_BYTES

    fun installedBytes(ctx: Context): Long = modelFile(ctx).length() + tokensFile(ctx).length()

    /** Model files (or a half-finished download) exist on disk. */
    fun hasAnyFiles(ctx: Context): Boolean = dir(ctx).listFiles()?.isNotEmpty() == true

    /** Model installed AND the sherpa-onnx library is in the APK. */
    fun ready(ctx: Context): Boolean = installed(ctx) && SherpaBridge.isPresent()

    fun delete(ctx: Context) {
        cancelFlag.set(true)
        release()
        // queued behind release() on the same single thread, so the model is closed before its file goes away
        engineExec.execute {
            dir(ctx).listFiles()?.forEach { it.delete() }
        }
        if (!downloading.get()) publish(State(Phase.IDLE, 0L, 0L, null))
    }

    // ---------- download ----------

    fun isDownloading(): Boolean = downloading.get()

    fun cancelDownload() {
        cancelFlag.set(true)
    }

    fun startDownload(ctx: Context, plan: OfflineSttUrls.Plan) {
        if (!downloading.compareAndSet(false, true)) return
        cancelFlag.set(false)
        val app = ctx.applicationContext
        publish(State(Phase.DOWNLOADING, 0L, 0L, null))
        downloadExec.execute {
            try {
                release()
                val dir = dir(app)
                dir.mkdirs()

                val tokensPart = File(dir, OfflineSttUrls.TOKENS_NAME + ".part")
                fetch(plan.tokensUrl, tokensPart, dir, 1L) { _, _ -> }

                val modelPart = File(dir, OfflineSttUrls.MODEL_NAME + ".part")
                var lastPublish = 0L
                fetch(plan.modelUrl, modelPart, dir, MIN_MODEL_BYTES) { done, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastPublish >= 300L) {
                        lastPublish = now
                        publish(State(Phase.DOWNLOADING, done, total, null))
                    }
                }

                val headBytes = ByteArray(300)
                val headLen = tokensPart.inputStream().use { it.read(headBytes) }
                val head = String(headBytes, 0, headLen.coerceAtLeast(0), Charsets.UTF_8).lowercase()
                if (head.contains("<html") || head.contains("<!doctype")) {
                    tokensPart.delete()
                    modelPart.delete()
                    throw IllegalStateException("tokens.txt ki jagah webpage mila. Link folder ka ya direct file ka do.")
                }

                val tokens = tokensFile(app)
                val model = modelFile(app)
                tokens.delete()
                model.delete()
                if (!tokensPart.renameTo(tokens) || !modelPart.renameTo(model)) {
                    throw IOException("Files rename nahi ho payi")
                }
                publish(State(Phase.IDLE, 0L, 0L, null))
            } catch (_: Cancelled) {
                // partial files stay on disk, so the next Download resumes from there
                publish(State(Phase.IDLE, 0L, 0L, null))
            } catch (e: IOException) {
                publish(
                    State(
                        Phase.ERROR, 0L, 0L,
                        "Download ruk gaya (" + (e.message ?: "network") + "). Dobara dabao, wahin se resume hoga."
                    )
                )
            } catch (e: Exception) {
                publish(State(Phase.ERROR, 0L, 0L, e.message ?: "Download fail ho gaya."))
            } finally {
                downloading.set(false)
            }
        }
    }

    private fun fetch(url: String, part: File, dir: File, minBytes: Long, progress: (Long, Long) -> Unit) {
        var existing = if (part.exists()) part.length() else 0L
        var attempt = 0
        while (true) {
            attempt++
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 20_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", "CodeAssistAI/2.0 (Android)")
                if (existing > 0L) conn.setRequestProperty("Range", "bytes=$existing-")
                val code = conn.responseCode
                if (code == 416 && attempt == 1) {
                    // the partial file does not fit this link any more: start again from zero
                    part.delete()
                    existing = 0L
                    continue
                }
                if (code != 200 && code != 206) {
                    throw IllegalStateException("Server ne HTTP $code diya. Link galat ho sakta hai ya file wahan nahi hai.")
                }
                val type = (conn.contentType ?: "").lowercase()
                if (type.startsWith("text/html")) {
                    throw IllegalStateException("Link se webpage aaya, model file nahi. Folder ka link ya direct .onnx link do.")
                }
                val resumed = code == 206 && existing > 0L
                val len = conn.contentLengthLong
                val total = if (len > 0L) len + (if (resumed) existing else 0L) else 0L
                val needed = if (total > 0L) total - (if (resumed) existing else 0L) else 0L
                if (needed > 0L && dir.usableSpace < needed + SPACE_MARGIN) {
                    throw IllegalStateException("Phone mein jagah kam hai (chahiye ~" + (needed / 1_048_576L) + " MB).")
                }

                var done = if (resumed) existing else 0L
                FileOutputStream(part, resumed).use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelFlag.get()) throw Cancelled()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            progress(done, total)
                        }
                    }
                }
                if (part.length() < minBytes) {
                    part.delete()
                    throw IllegalStateException("Download ki hui file bahut chhoti hai. Link check karo.")
                }
                return
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun publish(s: State) {
        state = s
        main.post { listener?.invoke() }
    }

    // ---------- the loaded model ----------

    private fun threads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    /** Starts loading in the background (instant when already loaded). */
    fun loadAsync(ctx: Context): Future<SherpaBridge.Handle> {
        val app = ctx.applicationContext
        main.removeCallbacks(idleRelease)
        return engineExec.submit(Callable<SherpaBridge.Handle> {
            val loaded = handle
            if (loaded != null) {
                loaded
            } else {
                val created = SherpaBridge.create(
                    modelFile(app).absolutePath,
                    tokensFile(app).absolutePath,
                    threads()
                )
                handle = created
                created
            }
        })
    }

    /** Runs the model on 16 kHz mono float samples. Blocks: call from a worker thread. Throws a readable message. */
    fun transcribe(ctx: Context, samples: FloatArray): String {
        var lastError: Throwable? = null
        for (attempt in 0 until 2) {
            try {
                val h = loadAsync(ctx).get()
                return h.decode(samples, 16_000)
            } catch (e: SherpaBridge.Released) {
                lastError = e // unloaded in between: load again and retry once
            } catch (e: ExecutionException) {
                throw (e.cause ?: e)
            }
        }
        throw (lastError ?: IllegalStateException("Offline model chal nahi paya"))
    }

    fun captureStarted() {
        activeCaptures.incrementAndGet()
        main.removeCallbacks(idleRelease)
    }

    fun captureEnded() {
        activeCaptures.decrementAndGet()
        main.removeCallbacks(idleRelease)
        main.postDelayed(idleRelease, IDLE_RELEASE_MS)
    }

    private val idleRelease: Runnable = object : Runnable {
        override fun run() {
            if (activeCaptures.get() > 0) {
                main.postDelayed(this, IDLE_RELEASE_MS)
            } else {
                release()
            }
        }
    }

    /** Frees the model's RAM (about 150 to 600 MB while loaded). The next use loads it again. */
    fun release() {
        engineExec.execute {
            val h = handle
            handle = null
            try {
                h?.release()
            } catch (_: Throwable) {
                // nothing to do
            }
        }
    }
}
