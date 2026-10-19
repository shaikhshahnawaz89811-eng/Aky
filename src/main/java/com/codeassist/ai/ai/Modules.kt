package com.codeassist.ai.ai

import android.app.DownloadManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.OpenableColumns
import com.codeassist.ai.data.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Single source of truth for the on-device model card (Gemma 4 E2B Instruct, LiteRT-LM bundle, .litertlm).
 *
 *   NOT_IMPORTED --Download/Import--> DOWNLOADING/IMPORTING --> UNLOADED --Load--> LOADING --> LOADED
 *   LOADED --Unload--> UNLOADED --Delete--> NOT_IMPORTED
 *
 * The rules live here, not in the buttons: Delete is refused while loaded/loading/busy, a second
 * Load is a no-op, and Delete always returns the card to NOT_IMPORTED.
 *
 * The Phi-4 mini module was removed. If its old 2.49 GB file is still on the phone, [legacyPhiBytes]
 * reports it and [deleteLegacyPhi] frees the space (the user decides; nothing is deleted silently).
 */
object Modules {
    const val MODEL_TITLE = "Gemma 4 E2B Instruct"
    const val MODEL_QUANT = "LiteRT-LM"
    const val MODEL_SIZE_LABEL = "2.6 GB"
    const val MODEL_URL =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/" +
            "gemma-4-E2B-it.litertlm"
    private const val MODEL_FILE = "gemma-4-e2b-it.litertlm"
    private const val MODEL_PART = "gemma-4-e2b-it.litertlm.part"
    private const val MODEL_BYTES_ESTIMATE = 2_700_000_000L
    private const val MIN_VALID_BYTES = 1_500L * 1024 * 1024

    // files of the removed Phi-4 mini module
    private const val LEGACY_PHI_FILE = "phi4-mini-instruct-q4_k_m.gguf"
    private const val LEGACY_PHI_PART = "phi4-mini-instruct-q4_k_m.gguf.part"

    enum class Phase { NOT_IMPORTED, DOWNLOADING, IMPORTING, UNLOADED, LOADING, LOADED, ERROR }

    @Volatile
    var phase: Phase = Phase.NOT_IMPORTED
        private set

    @Volatile
    var done: Long = 0L
        private set

    @Volatile
    var total: Long = 0L
        private set

    /** Status / error text shown under the card. */
    @Volatile
    var message: String? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val loadGate = Mutex()
    private var job: Job? = null
    private var initialised = false
    private lateinit var app: Context

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun init(context: Context) {
        if (initialised) return
        initialised = true
        app = context.applicationContext
        Store.init(app)
        dropLegacyPhiDownload()
        val ready = modelFile()
        if (ready.exists() && ready.length() >= MIN_VALID_BYTES) {
            val p = if (LocalLlm.loaded) Phase.LOADED else Phase.UNLOADED
            setPhase(p, ready.length(), ready.length(), null)
            return
        }
        val id = Store.localDownloadId
        if (id >= 0 && downloadExists(id)) {
            setPhase(Phase.DOWNLOADING, 0, 0, "Download chal raha hai…")
            pollDownload(id)
        } else {
            Store.localDownloadId = -1
            File(modelsDir(), MODEL_PART).delete()
            setPhase(Phase.NOT_IMPORTED)
        }
    }

    /** An unfinished Phi-4 download is useless now: cancel it and remove its partial file. */
    private fun dropLegacyPhiDownload() {
        val old = Store.legacyPhiDownloadId
        if (old >= 0) {
            try {
                (app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(old)
            } catch (_: Exception) {
                // already gone
            }
            Store.legacyPhiDownloadId = -1
        }
        File(modelsDir(), LEGACY_PHI_PART).delete()
    }

    /** Size of the old Phi-4 mini file if it is still on the phone, else 0. */
    fun legacyPhiBytes(): Long {
        val f = File(modelsDir(), LEGACY_PHI_FILE)
        return if (f.exists()) f.length() else 0L
    }

    /** Deletes the old Phi-4 mini file. Returns true if it is gone afterwards. */
    fun deleteLegacyPhi(): Boolean {
        val f = File(modelsDir(), LEGACY_PHI_FILE)
        val ok = !f.exists() || f.delete()
        notifyChanged()
        return ok
    }

    // ---------- files ----------

    fun modelsDir(): File {
        val dir = app.getExternalFilesDir("models") ?: File(app.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun modelFile(): File = File(modelsDir(), MODEL_FILE)

    fun fmt(bytes: Long): String =
        if (bytes >= 1_000_000_000L) String.format(Locale.US, "%.2f GB", bytes / 1e9)
        else String.format(Locale.US, "%d MB", bytes / 1_000_000L)

    fun isMetered(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.isActiveNetworkMetered
    }

    private fun freeBytes(dir: File): Long =
        try {
            StatFs(dir.absolutePath).availableBytes
        } catch (_: Exception) {
            Long.MAX_VALUE
        }

    private fun setPhase(p: Phase, d: Long = 0L, t: Long = 0L, msg: String? = null) {
        phase = p
        done = d
        total = t
        message = msg
        notifyChanged()
    }

    private fun progress(d: Long, t: Long, msg: String?) {
        done = d
        total = t
        if (msg != null) message = msg
        notifyChanged()
    }

    private fun notifyChanged() {
        main.post { for (l in listeners) l() }
    }

    // ---------- import from a file the user already has ----------

    fun startImport(uri: Uri) {
        if (phase != Phase.NOT_IMPORTED && phase != Phase.ERROR) return
        setPhase(Phase.IMPORTING, 0, 0, "File check ho rahi hai…")
        job = scope.launch {
            val part = File(modelsDir(), MODEL_PART)
            try {
                val resolver = app.contentResolver
                val size = querySize(uri)
                // 1) reject a wrong file in milliseconds, before copying the whole file
                val name = queryName(uri)
                if (name != null && !name.endsWith(".litertlm", ignoreCase = true)) {
                    throw IOException("Ye .litertlm file nahi hai (" + name + "). gemma-4-E2B-it.litertlm file chuno.")
                }
                if (size in 1 until MIN_VALID_BYTES) {
                    throw IOException("File bahut chhoti hai (" + fmt(size) + "). Poori Gemma 4 E2B .litertlm file chuno.")
                }
                val need = if (size > 0) size else MODEL_BYTES_ESTIMATE
                if (freeBytes(modelsDir()) < need + 150L * 1024 * 1024) {
                    throw IOException("Storage kam hai: " + fmt(need) + " khaali jagah chahiye.")
                }
                // 2) copy with progress
                part.delete()
                setPhase(Phase.IMPORTING, 0, size.coerceAtLeast(0L), "Copy ho raha hai…")
                resolver.openInputStream(uri)?.use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 20)
                        var copied = 0L
                        var lastUi = 0L
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            copied += n
                            val now = System.currentTimeMillis()
                            if (now - lastUi > 250) {
                                lastUi = now
                                progress(copied, size.coerceAtLeast(0L), null)
                            }
                        }
                    }
                } ?: throw IOException("File khul nahi payi.")
                install(part)
            } catch (e: CancellationException) {
                part.delete()
                setPhase(Phase.NOT_IMPORTED)
                throw e
            } catch (e: Exception) {
                part.delete()
                setPhase(Phase.ERROR, 0, 0, e.message ?: "Import fail ho gaya.")
            }
        }
    }

    private fun querySize(uri: Uri): Long {
        return try {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
            } ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    private fun queryName(uri: Uri): String? {
        return try {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun install(part: File) {
        if (part.length() < MIN_VALID_BYTES) {
            throw IOException("File adhoori lag rahi hai (" + fmt(part.length()) + ").")
        }
        val target = modelFile()
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        setPhase(Phase.UNLOADED, target.length(), target.length(), null)
    }

    // ---------- download (Android DownloadManager: survives app close, resumes, shows a notification) ----------

    fun startDownload(allowMobileData: Boolean) {
        if (phase != Phase.NOT_IMPORTED && phase != Phase.ERROR) return
        val dir = modelsDir()
        if (freeBytes(dir) < MODEL_BYTES_ESTIMATE + 150L * 1024 * 1024) {
            setPhase(Phase.ERROR, 0, 0, "Storage kam hai: " + fmt(MODEL_BYTES_ESTIMATE) + " khaali jagah chahiye.")
            return
        }
        File(dir, MODEL_PART).delete()
        try {
            val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(MODEL_URL))
                .setTitle("$MODEL_TITLE ($MODEL_QUANT)")
                .setDescription("CodeAssistAI on-device model")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setAllowedOverMetered(allowMobileData)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(app, "models", MODEL_PART)
            val id = dm.enqueue(request)
            Store.localDownloadId = id
            setPhase(Phase.DOWNLOADING, 0, 0, "Download shuru ho raha hai…")
            pollDownload(id)
        } catch (e: Exception) {
            setPhase(Phase.ERROR, 0, 0, "Download shuru nahi hua: " + (e.message ?: "unknown error"))
        }
    }

    private fun downloadExists(id: Long): Boolean {
        return try {
            val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.query(DownloadManager.Query().setFilterById(id))?.use { it.moveToFirst() } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun pollDownload(id: Long) {
        job?.cancel()
        job = scope.launch {
            val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            while (true) {
                ensureActive()
                var exists = false
                var status = -1
                var reason = 0
                var soFar = 0L
                var totalBytes = 0L
                dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                    if (c.moveToFirst()) {
                        exists = true
                        status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        soFar = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        totalBytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    }
                }
                if (!exists) {
                    Store.localDownloadId = -1
                    File(modelsDir(), MODEL_PART).delete()
                    setPhase(Phase.NOT_IMPORTED)
                    return@launch
                }
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        Store.localDownloadId = -1
                        setPhase(Phase.IMPORTING, soFar, totalBytes, "File check ho rahi hai…")
                        val part = File(modelsDir(), MODEL_PART)
                        try {
                            install(part)
                        } catch (e: Exception) {
                            part.delete()
                            setPhase(Phase.ERROR, 0, 0, e.message ?: "Download ki file galat hai.")
                        }
                        return@launch
                    }
                    DownloadManager.STATUS_FAILED -> {
                        Store.localDownloadId = -1
                        File(modelsDir(), MODEL_PART).delete()
                        setPhase(
                            Phase.ERROR, 0, 0,
                            "Download fail hua (code $reason). Internet aur storage check karke dobara try karo."
                        )
                        return@launch
                    }
                    DownloadManager.STATUS_PAUSED -> {
                        phase = Phase.DOWNLOADING
                        progress(soFar, totalBytes, pausedText(reason))
                    }
                    else -> {
                        phase = Phase.DOWNLOADING
                        progress(soFar, totalBytes, "Download ho raha hai…")
                    }
                }
                delay(700)
            }
        }
    }

    private fun pausedText(reason: Int): String = when (reason) {
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Wi-Fi ka intezaar hai (mobile data off rakha hai)."
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Internet ka intezaar hai…"
        DownloadManager.PAUSED_WAITING_TO_RETRY -> "Dobara koshish ho rahi hai…"
        else -> "Download ruka hua hai."
    }

    // ---------- cancel ----------

    fun cancel() {
        when (phase) {
            Phase.DOWNLOADING -> {
                job?.cancel()
                val id = Store.localDownloadId
                if (id >= 0) {
                    try {
                        (app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(id)
                    } catch (_: Exception) {
                        // already gone
                    }
                }
                Store.localDownloadId = -1
                File(modelsDir(), MODEL_PART).delete()
                setPhase(Phase.NOT_IMPORTED)
            }
            Phase.IMPORTING -> job?.cancel() // the import job resets the phase itself
            else -> Unit
        }
    }

    // ---------- load / unload / delete ----------

    /** Loads the model if needed. Several callers may ask; only one load runs, extra calls are no-ops. */
    suspend fun ensureLoaded() {
        loadGate.withLock {
            val file = modelFile()
            if (LocalLlm.loaded) {
                if (phase != Phase.LOADED) setPhase(Phase.LOADED, file.length(), file.length(), null)
                return
            }
            if (!file.exists()) {
                throw LocalLlm.LoadFailure("Gemma model import nahi hua. Voice and AI mein Download ya Import karo.")
            }
            setPhase(Phase.LOADING, file.length(), file.length(), "Model RAM mein load ho raha hai…")
            try {
                LocalLlm.load(app, file)
                setPhase(Phase.LOADED, file.length(), file.length(), null)
            } catch (e: CancellationException) {
                setPhase(Phase.UNLOADED, file.length(), file.length(), null)
                throw e
            } catch (e: LocalLlm.LoadFailure) {
                setPhase(Phase.UNLOADED, file.length(), file.length(), e.message)
                throw e
            }
        }
    }

    /** Button handler: loads in the background; failures show up as the card's message. */
    fun requestLoad() {
        if (phase != Phase.UNLOADED) return
        scope.launch {
            try {
                ensureLoaded()
            } catch (_: Exception) {
                // message already set on the card
            }
        }
    }

    /** Returns an error text if the action is refused, null if it started. */
    fun requestUnload(): String? {
        if (phase != Phase.LOADED) return "Model abhi loaded nahi hai."
        if (ChatRunner.isRunning() || LocalLlm.busy) return "Reply chal raha hai. Pehle Stop karo."
        scope.launch {
            LocalLlm.unload()
            val f = modelFile()
            setPhase(Phase.UNLOADED, f.length(), f.length(), null)
        }
        return null
    }

    /** Returns an error text if the action is refused, null if it started. */
    fun requestDelete(): String? {
        if (phase == Phase.LOADED || phase == Phase.LOADING) return "Pehle Unload karo, phir Delete."
        if (phase == Phase.IMPORTING || phase == Phase.DOWNLOADING) return "Pehle chal raha kaam Cancel karo."
        scope.launch {
            val f = modelFile()
            val ok = !f.exists() || f.delete()
            File(modelsDir(), MODEL_PART).delete()
            if (ok) setPhase(Phase.NOT_IMPORTED) else setPhase(Phase.ERROR, 0, 0, "File delete nahi ho payi.")
        }
        return null
    }
}
