package com.codeassist.ai.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import com.codeassist.ai.ai.ConvKpi
import com.codeassist.ai.data.Store

/**
 * Stand-in wake-word engine: a cheap energy detector keeps the mic open (no speech recognition, no network),
 * and only when somebody starts talking does it hand a short burst to the ON-DEVICE speech recognizer
 * (API 33+, see [WakeSupport]) and look for the wake phrase in the transcript ([WakeMatcher]).
 *
 * This is NOT a real keyword spotter, so its limits are real (they are listed in BUILD_NOTES.md):
 *  - the recognizer starts a moment after the voice is heard, so the first word of the phrase is often lost
 *    (the matcher is built for that: the greeting is optional, NORMAL accepts the last key word);
 *  - every burst of speech in the room costs one short recognizer run, so a storm guard limits the runs
 *    (6 per minute, then a 30 s rest);
 *  - the recognizer may play its start beep on some phones.
 * The audio is only measured or recognized on the device; nothing is recorded, stored or sent.
 *
 * [standalone] = used by the wake test screen: it never pauses for the shared "busy" flag and never
 * registers itself as the service's gate.
 */
class VadGatedWakeWord(
    context: Context,
    private val listener: WakeWordEngine.Listener,
    private val standalone: Boolean = false
) : WakeWordEngine, WakeCoordinator.Gate {

    companion object {
        private const val RATE = 16000
        private const val FRAME = 320 // 20 ms
        private const val WARMUP_MS = 600
        private const val TRIGGER_MS = 160
        private const val RESUME_DELAY_MS = 800L
        private const val COOLDOWN_MS = 1200L
        private const val RETRY_OPEN_MS = 10_000L
        private const val RECOGNIZER_MAX_MS = 6000L
        private const val PARTIAL_GRACE_MS = 1500L
        private const val STORM_WINDOW_MS = 60_000L
        private const val STORM_MAX = 6
        private const val STORM_BACKOFF_MS = 30_000L
        private const val FLUSH_MS = 300_000L
        private const val POST_WAKE_REST_MS = 3000L
    }

    private class MonitorRun {
        @Volatile
        var alive = true
        var triggered = false
        var openFailed = false
    }

    private val app: Context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var started = false
    private var busy = false
    private var monitorRun: MonitorRun? = null
    private var monitorThread: Thread? = null

    private var recognizer: SpeechRecognizer? = null
    private var recognizerActive = false
    private var matched: WakeMatcher.Result? = null
    private var matchedHeard = ""

    private val attempts = ArrayList<Long>()
    private var backoffUntil = 0L

    // ---------- WakeWordEngine ----------

    override fun start() {
        if (started) return
        started = true
        if (!standalone) {
            WakeCoordinator.gate = this
            busy = WakeCoordinator.isBusy()
        }
        setStatus("Shuru ho raha hai")
        scheduleMonitor(0L)
    }

    override fun stop() {
        started = false
        main.removeCallbacksAndMessages(null)
        stopMonitor(300L)
        destroyRecognizer()
        if (WakeCoordinator.gate === this) WakeCoordinator.gate = null
        setStatus("Band")
    }

    // ---------- WakeCoordinator.Gate ----------

    override fun onBusyChanged(busy: Boolean) {
        if (standalone) return
        this.busy = busy
        if (!started) return
        if (busy) {
            main.removeCallbacksAndMessages(null)
            stopMonitor(200L)
            finishRecognizer()
            setStatus("Pause: voice chal rahi hai")
        } else {
            scheduleMonitor(RESUME_DELAY_MS)
        }
    }

    // ---------- monitor (cheap energy detector) ----------

    private val monitorRunnable = Runnable { startMonitor() }

    private fun scheduleMonitor(delayMs: Long) {
        main.removeCallbacks(monitorRunnable)
        main.postDelayed(monitorRunnable, delayMs)
    }

    private fun startMonitor() {
        if (!started || busy || recognizerActive || monitorRun != null) return
        val now = SystemClock.elapsedRealtime()
        if (now < backoffUntil) {
            setStatus("Shor zyada: thodi der rest")
            scheduleMonitor(backoffUntil - now + 50L)
            return
        }
        val run = MonitorRun()
        monitorRun = run
        val t = Thread({ monitorLoop(run) }, "wake-vad")
        monitorThread = t
        setStatus("Sun raha hai")
        t.start()
    }

    private fun stopMonitor(joinMs: Long) {
        monitorRun?.alive = false
        monitorRun = null
        val t = monitorThread ?: return
        monitorThread = null
        if (t !== Thread.currentThread()) {
            try {
                t.join(joinMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun monitorLoop(run: MonitorRun) {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            run.openFailed = true
            finishRun(run)
            return
        }
        val size = maxOf(minBuf, FRAME * 2 * 8)
        var record: AudioRecord? = null
        var ns: NoiseSuppressor? = null
        val begun = SystemClock.elapsedRealtime()
        var flushed = begun
        try {
            record = open(MediaRecorder.AudioSource.VOICE_RECOGNITION, size)
                ?: open(MediaRecorder.AudioSource.MIC, size)
            if (record == null) {
                run.openFailed = true
            } else {
                try {
                    if (NoiseSuppressor.isAvailable()) {
                        ns = NoiseSuppressor.create(record.audioSessionId)
                        ns?.setEnabled(true)
                    }
                } catch (_: Throwable) {
                    // optional
                }
                val vad = EnergyVad(3.0f)
                record.startRecording()
                val buf = ShortArray(FRAME)
                var warmMs = 0
                var speechMs = 0
                while (run.alive) {
                    var got = 0
                    while (got < FRAME && run.alive) {
                        val n = record.read(buf, got, FRAME - got)
                        if (n <= 0) {
                            run.alive = false
                            run.openFailed = true
                            break
                        }
                        got += n
                    }
                    if (!run.alive || got < FRAME) break
                    val level = EnergyVad.rms(buf, FRAME)
                    if (warmMs < WARMUP_MS) {
                        vad.warm(level)
                        warmMs += 20
                        continue
                    }
                    val speech = vad.process(level)
                    speechMs = if (speech) speechMs + 20 else 0
                    if (speechMs >= TRIGGER_MS) {
                        run.triggered = true
                        break
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - flushed >= FLUSH_MS) {
                        if (!standalone) ConvKpi.inc("wake_listen_ms", now - flushed)
                        flushed = now
                    }
                }
            }
        } catch (_: SecurityException) {
            main.post { fatal("Mic permission nahi hai") }
        } catch (e: Throwable) {
            run.openFailed = true
        } finally {
            try {
                ns?.release()
            } catch (_: Throwable) {
            }
            try {
                record?.stop()
            } catch (_: Throwable) {
            }
            try {
                record?.release()
            } catch (_: Throwable) {
            }
            if (!standalone) ConvKpi.inc("wake_listen_ms", SystemClock.elapsedRealtime() - flushed)
        }
        finishRun(run)
    }

    /** Back on the main thread once the AudioRecord is released. */
    private fun finishRun(run: MonitorRun) {
        main.post {
            if (monitorRun !== run) return@post // stopped or replaced meanwhile
            monitorRun = null
            monitorThread = null
            if (!started || busy) return@post
            when {
                run.triggered -> onTrigger()
                run.openFailed -> {
                    setStatus("Mic abhi kisi aur ke paas hai: 10 s baad dobara")
                    scheduleMonitor(RETRY_OPEN_MS)
                }
                else -> scheduleMonitor(2000L)
            }
        }
    }

    private fun open(source: Int, size: Int): AudioRecord? {
        return try {
            val r = AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            if (r.state == AudioRecord.STATE_INITIALIZED) r else {
                r.release()
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    // ---------- recognizer step ----------

    private fun onTrigger() {
        val now = SystemClock.elapsedRealtime()
        while (attempts.isNotEmpty() && now - attempts[0] > STORM_WINDOW_MS) attempts.removeAt(0)
        if (attempts.size >= STORM_MAX) {
            backoffUntil = now + STORM_BACKOFF_MS
            attempts.clear()
            if (!standalone) ConvKpi.inc("wake_backoff")
            setStatus("Shor zyada: 30 s rest")
            scheduleMonitor(STORM_BACKOFF_MS)
            return
        }
        attempts.add(now)
        if (!standalone) ConvKpi.inc("wake_attempts")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            startRecognizer()
        } else {
            fatal("Wake word ke liye Android 13+ chahiye")
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun startRecognizer() {
        val r = recognizer ?: try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(app).also {
                it.setRecognitionListener(recListener)
                recognizer = it
            }
        } catch (e: Throwable) {
            fatal("On-device recognizer start nahi hua: " + (e.message ?: e.javaClass.simpleName))
            return
        }
        recognizerActive = true
        matched = null
        matchedHeard = ""
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, wakeLanguage(Store.wakePhrase))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 600L)
        }
        setStatus("Phrase check ho raha hai")
        try {
            r.startListening(intent)
        } catch (e: Throwable) {
            recognizerActive = false
            fatal("Recognizer start nahi hua: " + (e.message ?: e.javaClass.simpleName))
            return
        }
        main.removeCallbacks(attemptTimeout)
        main.postDelayed(attemptTimeout, RECOGNIZER_MAX_MS)
    }

    private val attemptTimeout = Runnable {
        if (recognizerActive) {
            if (matched != null) fireFromPartial() else endAttempt()
        }
    }

    private val partialGrace = Runnable {
        if (recognizerActive && matched != null) fireFromPartial()
    }

    private fun level(): WakeMatcher.Level = WakeMatcher.levelOf(Store.wakeSensitivity)

    private fun wakeLanguage(phrase: String): String =
        if (phrase.any { it in '\u0900'..'\u097F' }) "hi-IN" else "en-IN"

    private val recListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (!recognizerActive) return
            val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
            if (list.isEmpty()) return
            val r = WakeMatcher.matchAny(list, Store.wakePhrase, level())
            if (!r.matched) return
            val first = matched == null
            matched = r
            matchedHeard = list[0]
            // wait a moment for the rest of the sentence ("hey jarvis <command>")
            if (first) main.postDelayed(partialGrace, PARTIAL_GRACE_MS)
        }

        override fun onResults(results: Bundle?) {
            if (!recognizerActive) return
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: ArrayList<String>()
            val r = WakeMatcher.matchAny(list, Store.wakePhrase, level())
            when {
                r.matched -> fire(if (list.isEmpty()) "" else list[0], r.remainder)
                matched != null -> fireFromPartial()
                else -> endAttempt()
            }
        }

        override fun onError(error: Int) {
            if (!recognizerActive) return
            if (matched != null) {
                fireFromPartial()
                return
            }
            when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    destroyRecognizer()
                    backoffUntil = SystemClock.elapsedRealtime() + 5000L
                    endAttempt()
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fatal("Mic permission nahi hai")
                12, 13 -> fatal("Wake word ke liye offline speech pack (English India) download karo")
                else -> endAttempt()
            }
        }
    }

    private fun fireFromPartial() {
        val m = matched ?: return
        fire(matchedHeard, m.remainder)
    }

    private fun fire(heard: String, remainder: String) {
        finishRecognizer()
        setStatus("Wake phrase suna")
        listener.onWake(heard, remainder)
        if (started && !busy && !standalone) scheduleMonitor(POST_WAKE_REST_MS)
    }

    private fun endAttempt() {
        finishRecognizer()
        if (started && !busy) {
            setStatus("Sun raha hai")
            scheduleMonitor(COOLDOWN_MS)
        }
    }

    private fun finishRecognizer() {
        recognizerActive = false
        matched = null
        main.removeCallbacks(attemptTimeout)
        main.removeCallbacks(partialGrace)
        try {
            recognizer?.cancel()
        } catch (_: Throwable) {
            // already idle
        }
    }

    private fun destroyRecognizer() {
        finishRecognizer()
        try {
            recognizer?.destroy()
        } catch (_: Throwable) {
            // ignore
        }
        recognizer = null
    }

    private fun fatal(reason: String) {
        finishRecognizer()
        setStatus("Band: $reason")
        listener.onUnavailable(reason)
    }

    private fun setStatus(text: String) {
        if (!standalone) WakeCoordinator.status = text
    }
}
