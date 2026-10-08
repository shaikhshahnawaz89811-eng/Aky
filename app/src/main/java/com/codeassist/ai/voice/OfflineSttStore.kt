package com.codeassist.ai.voice

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
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
 * Everything about the optional offline speech model, in one place. It works like the Gemma card:
 * Import files / Download -> Load -> Unload -> Delete.
 *  - where the two files live (filesDir/stt_offline/model.int8.onnx + tokens.txt)
 *  - Import: the person picks the model (.onnx) and tokens.txt from the phone, they are copied and checked
 *  - Download (optional): a resumable download from a link ([OfflineSttUrls] turns the link into two file URLs)
 *  - Load / Unload: the person can load the model into RAM on purpose (it then stays until Unload), or it is
 *    loaded in the background on first use and dropped again after ~90 s without use
 *
 * Nothing here runs unless the user chose "Offline model" in Voice and AI (or taps a button there).
 */
object OfflineSttStore {

    enum class Phase { IDLE, DOWNLOADING, IMPORTING, ERROR }

    /** What the settings card shows. Same words as the Gemma card. */
    enum class Status { NOT_IMPORTED, DOWNLOADING, IMPORTING, UNLOADED, LOADING, LOADED, ERROR }

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

    /** True while a download or an import is running (only one at a time). */
    private val busy = AtomicBoolean(false)
    private val downloadExec: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "offline-stt-download").apply { isDaemon = true }
    }
    private val engineExec: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "offline-stt-engine").apply { isDaemon = true }
    }

    @Volatile
    private var handle: SherpaBridge.Handle? = null

    @Volatile
    private var loading = false

    /** The person pressed Load: keep the model in RAM until they press Unload (no idle timeout). */
    @Volatile
    private var pinned = false
    private val activeCaptures = AtomicInteger(0)

    // ---------- files ----------

    fun dir(ctx: Context): File = File(ctx.applicationContext.filesDir, DIR_NAME)
    fun modelFile(ctx: Context): File = File(dir(ctx), OfflineSttUrls.MODEL_NAME)
    fun tokensFile(ctx: Context): File = File(dir(ctx), OfflineSttUrls.TOKENS_NAME)

    fun installed(ctx: Context): Boolean =
        modelFile(ctx).length() >= MIN_MODEL_BYTES && tokensFile(ctx).length() >= MIN_TOKENS_BYTES

    fun installedBytes(ctx: Context): Long = modelFile(ctx).length() + tokensFile(ctx).length()

    /** Model files (or a half-finished download / import) exist on disk. */
    fun hasAnyFiles(ctx: Context): Boolean = dir(ctx).listFiles()?.isNotEmpty() == true

    /** Size of everything in the folder, also half-finished files. */
    fun filesBytes(ctx: Context): Long = dir(ctx).listFiles()?.sumOf { it.length() } ?: 0L

    /** Model installed AND the sherpa-onnx library is in the APK. */
    fun ready(ctx: Context): Boolean = installed(ctx) && SherpaBridge.isPresent()

    fun isLoaded(): Boolean = handle != null

    fun isLoading(): Boolean = loading

    fun status(ctx: Context): Status = when {
        state.phase == Phase.DOWNLOADING -> Status.DOWNLOADING
        state.phase == Phase.IMPORTING -> Status.IMPORTING
        loading -> Status.LOADING
        handle != null -> Status.LOADED
        state.phase == Phase.ERROR -> Status.ERROR
        installed(ctx) -> Status.UNLOADED
        else -> Status.NOT_IMPORTED
    }

    /** Returns a text if the delete is refused, null if it started. */
    fun requestDelete(ctx: Context): String? {
        if (busy.get()) return "Pehle chal raha kaam Cancel karo."
        if (loading || handle != null) return "Pehle Unload karo, phir Delete."
        val app = ctx.applicationContext
        pinned = false
        publish(State(Phase.IDLE, 0L, 0L, null))
        engineExec.execute {
            dir(app).listFiles()?.forEach { it.delete() }
            notifyChanged()
        }
        return null
    }

    // ---------- download / import ----------

    fun isDownloading(): Boolean = state.phase == Phase.DOWNLOADING

    fun isImporting(): Boolean = state.phase == Phase.IMPORTING

    /** Stops a running download or import. */
    fun cancelDownload() {
        cancelFlag.set(true)
    }

    fun startDownload(ctx: Context, plan: OfflineSttUrls.Plan) {
        if (!busy.compareAndSet(false, true)) return
        cancelFlag.set(false)
        pinned = false
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

                if (looksLikeWebpage(tokensPart)) {
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
                busy.set(false)
            }
        }
    }

    /**
     * Copies the picked model (.onnx) and tokens.txt into the app folder and checks them.
     * Either one may be picked alone if the other is already installed.
     */
    fun startImport(ctx: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (!busy.compareAndSet(false, true)) return
        cancelFlag.set(false)
        pinned = false
        val app = ctx.applicationContext
        publish(State(Phase.IMPORTING, 0L, 0L, null))
        downloadExec.execute {
            val dir = dir(app)
            val modelPart = File(dir, OfflineSttUrls.MODEL_NAME + ".part")
            val tokensPart = File(dir, OfflineSttUrls.TOKENS_NAME + ".part")
            try {
                val entries = ArrayList<OfflineSttImport.Entry>()
                for (u in uris) {
                    val meta = queryMeta(app, u)
                    entries.add(OfflineSttImport.Entry(meta.first, meta.second))
                }
                val pick = OfflineSttImport.classify(entries)
                val pickError = pick.error
                if (pickError != null) throw IllegalStateException(pickError)

                val modelIdx = pick.model
                val tokensIdx = pick.tokens
                var total = 0L
                if (modelIdx != null) total += entries[modelIdx].size.coerceAtLeast(0L)
                if (tokensIdx != null) total += entries[tokensIdx].size.coerceAtLeast(0L)

                release()
                dir.mkdirs()
                if (total > 0L && dir.usableSpace < total + SPACE_MARGIN) {
                    throw IllegalStateException("Phone mein jagah kam hai (chahiye ~" + (total / 1_048_576L) + " MB).")
                }

                var done = 0L
                var lastPublish = 0L
                val onBytes: (Int) -> Unit = { n ->
                    done += n.toLong()
                    val now = System.currentTimeMillis()
                    if (now - lastPublish >= 300L) {
                        lastPublish = now
                        publish(State(Phase.IMPORTING, done, total, null))
                    }
                }

                if (tokensIdx != null) {
                    copyUri(app, uris[tokensIdx], tokensPart, onBytes)
                    if (tokensPart.length() < MIN_TOKENS_BYTES) {
                        throw IllegalStateException("Tokens file bahut chhoti hai. Sahi tokens.txt chuno.")
                    }
                    if (looksLikeWebpage(tokensPart)) {
                        throw IllegalStateException("Ye tokens.txt nahi lagti (webpage / link file hai). Sahi file chuno.")
                    }
                }
                if (modelIdx != null) {
                    copyUri(app, uris[modelIdx], modelPart, onBytes)
                    if (modelPart.length() < MIN_MODEL_BYTES) {
                        throw IllegalStateException("Model file bahut chhoti hai (" + (modelPart.length() / 1024L) + " KB). Sahi .onnx file chuno.")
                    }
                    if (looksLikeWebpage(modelPart)) {
                        throw IllegalStateException("Ye model file nahi lagti (webpage / git-lfs pointer hai). Poori .onnx file chuno.")
                    }
                }

                // both pieces passed their checks: only now replace what was installed before
                if (tokensIdx != null) {
                    val target = tokensFile(app)
                    target.delete()
                    if (!tokensPart.renameTo(target)) throw IOException("tokens.txt rename nahi ho payi")
                }
                if (modelIdx != null) {
                    val target = modelFile(app)
                    target.delete()
                    if (!modelPart.renameTo(target)) throw IOException("Model file rename nahi ho payi")
                }

                val missing = ArrayList<String>()
                if (modelFile(app).length() < MIN_MODEL_BYTES) missing.add("model (.onnx)")
                if (tokensFile(app).length() < MIN_TOKENS_BYTES) missing.add("tokens.txt")
                if (missing.isEmpty()) {
                    publish(State(Phase.IDLE, 0L, 0L, null))
                } else {
                    publish(
                        State(
                            Phase.ERROR, 0L, 0L,
                            missing.joinToString(" aur ") + " bhi chahiye. Import dobara dabao aur wo file chuno."
                        )
                    )
                }
            } catch (_: Cancelled) {
                modelPart.delete()
                tokensPart.delete()
                publish(State(Phase.IDLE, 0L, 0L, null))
            } catch (e: IOException) {
                modelPart.delete()
                tokensPart.delete()
                publish(State(Phase.ERROR, 0L, 0L, "Import ruk gaya (" + (e.message ?: "file") + ")."))
            } catch (e: Exception) {
                modelPart.delete()
                tokensPart.delete()
                publish(State(Phase.ERROR, 0L, 0L, e.message ?: "Import fail ho gaya."))
            } finally {
                busy.set(false)
            }
        }
    }

    private fun queryMeta(app: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        try {
            val cursor = app.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
            )
            cursor?.use { c ->
                if (c.moveToFirst()) {
                    val nameCol = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeCol = c.getColumnIndex(OpenableColumns.SIZE)
                    if (nameCol >= 0 && !c.isNull(nameCol)) name = c.getString(nameCol)
                    if (sizeCol >= 0 && !c.isNull(sizeCol)) size = c.getLong(sizeCol)
                }
            }
        } catch (_: Exception) {
            // keep the guessed name; the size just stays unknown
        }
        return Pair(name, size)
    }

    private fun copyUri(app: Context, uri: Uri, part: File, onBytes: (Int) -> Unit) {
        val input = app.contentResolver.openInputStream(uri) ?: throw IOException("File khul nahi paayi")
        input.use { ins ->
            FileOutputStream(part, false).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (cancelFlag.get()) throw Cancelled()
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    onBytes(n)
                }
            }
        }
    }

    /** A saved error page / git-lfs pointer instead of the real file. */
    private fun looksLikeWebpage(file: File): Boolean {
        val bytes = ByteArray(300)
        val len = try {
            file.inputStream().use { it.read(bytes) }
        } catch (_: IOException) {
            -1
        }
        if (len <= 0) return false
        val head = String(bytes, 0, len, Charsets.UTF_8).lowercase()
        return head.contains("<html") || head.contains("<!doctype") || head.contains("git-lfs.github.com")
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
        notifyChanged()
    }

    private fun notifyChanged() {
        main.post { listener?.invoke() }
    }

    // ---------- the loaded model ----------

    private fun threads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    /** Runs on the engine thread only. Loads the model if it is not loaded yet. */
    private fun ensureHandle(app: Context): SherpaBridge.Handle {
        val loaded = handle
        if (loaded != null) return loaded
        loading = true
        notifyChanged()
        try {
            val created = SherpaBridge.create(
                modelFile(app).absolutePath,
                tokensFile(app).absolutePath,
                threads()
            )
            handle = created
            return created
        } finally {
            loading = false
            notifyChanged()
        }
    }

    /** Starts loading in the background (instant when already loaded). Used by the mic: not pinned. */
    fun loadAsync(ctx: Context): Future<SherpaBridge.Handle> {
        val app = ctx.applicationContext
        main.removeCallbacks(idleRelease)
        return engineExec.submit(Callable<SherpaBridge.Handle> { ensureHandle(app) })
    }

    /**
     * The Load button: loads the model now and keeps it in RAM until [requestUnload].
     * Returns a text if the action is refused, null if it started.
     */
    fun requestLoad(ctx: Context): String? {
        if (!SherpaBridge.isPresent()) return "sherpa-onnx library app mein nahi hai (app/libs mein AAR chahiye)."
        if (!installed(ctx)) return "Pehle model Import ya Download karo."
        if (busy.get()) return "Pehle chal raha kaam khatam hone do."
        if (handle != null || loading) {
            pinned = true
            main.removeCallbacks(idleRelease)
            notifyChanged()
            return null
        }
        val app = ctx.applicationContext
        pinned = true
        main.removeCallbacks(idleRelease)
        if (state.phase == Phase.ERROR) publish(State(Phase.IDLE, 0L, 0L, null))
        loading = true
        notifyChanged()
        engineExec.execute {
            try {
                ensureHandle(app)
            } catch (t: Throwable) {
                pinned = false
                publish(State(Phase.ERROR, 0L, 0L, t.message ?: "Model load nahi hua."))
            } finally {
                loading = false
                notifyChanged()
            }
        }
        return null
    }

    /** The Unload button. Returns a text if the action is refused, null if it started. */
    fun requestUnload(): String? {
        if (loading) return "Model abhi load ho raha hai, thodi der ruko."
        if (handle == null) return "Model abhi loaded nahi hai."
        if (activeCaptures.get() > 0) return "Mic abhi sun raha hai. Bolna khatam hone ke baad Unload karo."
        pinned = false
        main.removeCallbacks(idleRelease)
        release()
        return null
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
        if (!pinned) main.postDelayed(idleRelease, IDLE_RELEASE_MS)
    }

    private val idleRelease: Runnable = object : Runnable {
        override fun run() {
            if (pinned) return
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
            notifyChanged()
        }
    }
}
