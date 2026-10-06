package com.codeassist.ai.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import com.codeassist.ai.data.Store
import java.util.Locale

/**
 * Real voice loop on top of the Android platform engines:
 *   speech in  = android.speech.SpeechRecognizer (live partials + real mic levels)
 *   speech out = android.speech.tts.TextToSpeech
 * All calls must come from the main thread.
 *
 * Modes: TAP (one utterance, auto-stop on pause), HOLD (push-to-talk: release sends),
 * CONTINUOUS (listen -> reply -> speak -> listen again until stopped or the user says "bas").
 */
class VoiceController(context: Context, private val cb: Callbacks) {

    enum class State { IDLE, LISTENING, THINKING, SPEAKING }
    enum class Mode { TAP, HOLD, CONTINUOUS }

    interface Callbacks {
        fun onState(state: State, mode: Mode)
        fun onPartial(text: String)
        fun onLevel(level: Float)

        /** One finished utterance. The owner must answer it and then call [replyReady] (or [idle]). */
        fun onFinalText(text: String, durationMs: Long)
        fun onMessage(text: String)
        fun onNeedPermission()
    }

    class VoiceOption(val name: String, val label: String)

    companion object {
        /** Lists installed Hindi / English TTS voices (async; the callback runs on the main thread). */
        fun queryVoices(context: Context, done: (List<VoiceOption>) -> Unit) {
            val app = context.applicationContext
            val handler = Handler(Looper.getMainLooper())
            var engine: TextToSpeech? = null
            engine = TextToSpeech(app) { status ->
                handler.post {
                    val result = ArrayList<VoiceOption>()
                    if (status == TextToSpeech.SUCCESS) {
                        val voices = engine?.voices
                        if (voices != null) {
                            for (v in voices) {
                                val lang = v.locale.language
                                if (lang == "hi" || lang == "en") {
                                    val tag = v.locale.toLanguageTag()
                                    val net = if (v.isNetworkConnectionRequired) " · online" else " · offline"
                                    result.add(VoiceOption(v.name, tag + " · " + v.name + net))
                                }
                            }
                        }
                    }
                    try {
                        engine?.shutdown()
                    } catch (_: Exception) {
                        // nothing to do
                    }
                    result.sortBy { it.label }
                    done(result)
                }
            }
        }

        fun recognitionAvailable(context: Context): Boolean =
            SpeechRecognizer.isRecognitionAvailable(context.applicationContext)
    }

    private val app: Context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    var state: State = State.IDLE
        private set
    var mode: Mode = Mode.TAP
        private set

    private var recognizer: SpeechRecognizer? = null
    private val collected = StringBuilder()
    private var startedAt = 0L
    private var silentRounds = 0
    private var holding = false
    private var releasing = false
    private var continuousOn = false
    private var busyRetries = 0

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var afterTtsReady: (() -> Unit)? = null
    private var lastUtterance: String = ""
    private var focusRequest: AudioFocusRequest? = null

    // ---------- public control ----------

    fun startTap() = begin(Mode.TAP)

    fun startHold() = begin(Mode.HOLD)

    fun startContinuous() {
        continuousOn = true
        begin(Mode.CONTINUOUS)
    }

    /** Finger released on the mic. [cancel] = slid left to discard. */
    fun releaseHold(cancel: Boolean) {
        if (mode != Mode.HOLD || state != State.LISTENING) return
        holding = false
        if (cancel) {
            cancelListening()
            return
        }
        releasing = true
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
            // handled by the timeout below
        }
        main.postDelayed(releaseTimeout, 2500L)
    }

    /** Tap on the active mic in tap-to-talk: stop recording now and send what was heard. */
    fun finishNow() {
        if (state != State.LISTENING) return
        holding = false
        releasing = true
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
            // the timeout below finishes the utterance
        }
        main.postDelayed(releaseTimeout, 2500L)
    }

    /** Slide-up lock while holding: keep listening hands-free. */
    fun lockToContinuous() {
        if (mode != Mode.HOLD || state != State.LISTENING) return
        holding = false
        continuousOn = true
        mode = Mode.CONTINUOUS
        cb.onState(state, mode)
    }

    /** User pressed the mic/stop while something was running. */
    fun stopAll() {
        continuousOn = false
        holding = false
        releasing = false
        main.removeCallbacks(releaseTimeout)
        main.removeCallbacks(restartRunnable)
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
            // already stopped
        }
        stopSpeech()
        collected.setLength(0)
        cb.onPartial("")
        setState(State.IDLE)
    }

    /** Owner decided not to answer (e.g. auto-send is off): leave THINKING. */
    fun idle() {
        if (state == State.THINKING) {
            if (continuousOn && mode == Mode.CONTINUOUS) begin(Mode.CONTINUOUS) else setState(State.IDLE)
        }
    }

    /** The AI reply (or error) is ready. [spoken] null/blank = say nothing. */
    fun replyReady(spoken: String?) {
        if (state != State.THINKING) return
        if (spoken.isNullOrBlank()) afterSpeaking() else speakNow(spoken)
    }

    /** Speak a reply that was typed (Settings: speak replies = always) when nothing else is running. */
    fun speakIfIdle(text: String) {
        if (state != State.IDLE) return
        speakNow(text)
    }

    fun isBusy(): Boolean = state != State.IDLE

    fun markThinking() {
        if (state == State.LISTENING || state == State.IDLE) setState(State.THINKING)
    }

    fun destroy() {
        stopAll()
        main.removeCallbacksAndMessages(null)
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
            // ignore
        }
        recognizer = null
        try {
            tts?.shutdown()
        } catch (_: Exception) {
            // ignore
        }
        tts = null
        ttsReady = false
    }

    /** Used by the options screen so "Test voice" shares the configured engine. */
    fun sample(text: String) {
        if (state == State.LISTENING) return
        speakNow(text)
    }

    // ---------- listening ----------

    private fun begin(m: Mode) {
        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            cb.onMessage("Is phone par speech recognition service nahi hai. Google app install / enable karo.")
            continuousOn = false
            return
        }
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            continuousOn = false
            cb.onNeedPermission()
            return
        }
        stopSpeech()
        main.removeCallbacks(restartRunnable)
        mode = m
        holding = m == Mode.HOLD
        releasing = false
        collected.setLength(0)
        silentRounds = 0
        busyRetries = 0
        startedAt = SystemClock.elapsedRealtime()
        cb.onPartial("")
        setState(State.LISTENING)
        startRecognizer()
    }

    private fun startRecognizer() {
        val r = recognizer ?: try {
            SpeechRecognizer.createSpeechRecognizer(app).also {
                it.setRecognitionListener(listener)
                recognizer = it
            }
        } catch (e: Exception) {
            fail("Speech recognizer start nahi hua: " + (e.message ?: "unknown error"))
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Store.sttLang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, Store.sttPreferOffline)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
            // Hindi sentences are verb-final: allow a longer pause before the utterance is closed
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1300L)
        }
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            fail("Mic start nahi hua: " + (e.message ?: "unknown error"))
        }
    }

    private val restartRunnable = Runnable {
        if (state == State.LISTENING && (!releasing)) startRecognizer()
    }

    private val releaseTimeout = Runnable {
        if (releasing && state == State.LISTENING) finishUtterance()
    }

    private fun join(a: String, b: String): String =
        if (a.isBlank()) b else if (b.isBlank()) a else "$a $b"

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onRmsChanged(rmsdB: Float) {
            if (state == State.LISTENING) cb.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (state != State.LISTENING) return
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            cb.onPartial(join(collected.toString(), t))
        }

        override fun onResults(results: Bundle?) {
            if (state != State.LISTENING) return
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (t.isNotEmpty()) {
                if (collected.isNotEmpty()) collected.append(' ')
                collected.append(t)
            }
            if (mode == Mode.HOLD && holding && !releasing) {
                cb.onPartial(collected.toString())
                main.postDelayed(restartRunnable, 160L)
            } else {
                finishUtterance()
            }
        }

        override fun onError(error: Int) {
            if (state != State.LISTENING) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    if (mode == Mode.HOLD && holding && !releasing) {
                        main.postDelayed(restartRunnable, 160L)
                    } else {
                        finishUtterance()
                    }
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    if (busyRetries < 2) {
                        busyRetries++
                        try {
                            recognizer?.destroy()
                        } catch (_: Exception) {
                            // ignore
                        }
                        recognizer = null
                        main.postDelayed(restartRunnable, 400L)
                    } else {
                        fail("Speech recognizer busy hai. Thodi der baad try karo.")
                    }
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    stopAll()
                    cb.onNeedPermission()
                }
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    fail("Speech recognition ko internet chahiye. Net on karo, ya Voice and AI mein offline pack wala option use karo.")
                SpeechRecognizer.ERROR_AUDIO ->
                    fail("Mic audio error. Kya koi aur app mic use kar rahi hai?")
                SpeechRecognizer.ERROR_CLIENT -> Unit
                12, 13 -> fail("Ye bhasha is phone par available nahi. Voice and AI mein Speech language badlo.")
                else -> fail("Speech error ($error). Dobara try karo.")
            }
        }
    }

    private fun finishUtterance() {
        main.removeCallbacks(releaseTimeout)
        main.removeCallbacks(restartRunnable)
        val text = collected.toString().trim()
        val duration = SystemClock.elapsedRealtime() - startedAt
        collected.setLength(0)
        releasing = false
        holding = false
        cb.onPartial("")

        if (text.isEmpty()) {
            silentRounds++
            if (mode == Mode.CONTINUOUS && continuousOn && silentRounds < 3) {
                main.postDelayed(restartRunnable, 250L)
            } else {
                if (mode != Mode.HOLD) cb.onMessage("Kuch sunai nahi diya.")
                continuousOn = false
                setState(State.IDLE)
            }
            return
        }
        if (mode == Mode.CONTINUOUS && SpeechText.isStopPhrase(text)) {
            continuousOn = false
            setState(State.IDLE)
            return
        }
        silentRounds = 0
        setState(State.THINKING)
        cb.onFinalText(text, duration)
    }

    private fun cancelListening() {
        main.removeCallbacks(releaseTimeout)
        main.removeCallbacks(restartRunnable)
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
            // ignore
        }
        collected.setLength(0)
        holding = false
        releasing = false
        cb.onPartial("")
        setState(State.IDLE)
    }

    private fun fail(message: String) {
        stopAll()
        cb.onMessage(message)
    }

    // ---------- speaking ----------

    private fun speakNow(raw: String) {
        val clean = SpeechText.clean(raw)
        if (clean.isBlank()) {
            if (state == State.THINKING) afterSpeaking()
            return
        }
        setState(State.SPEAKING)
        ensureTts {
            val engine = tts
            if (engine == null || state != State.SPEAKING) return@ensureTts
            configure(engine, clean)
            requestFocus()
            val pieces = SpeechText.chunks(clean, 500)
            val base = "r" + System.currentTimeMillis()
            lastUtterance = base + "_" + (pieces.size - 1)
            for (i in pieces.indices) {
                val queue = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                engine.speak(pieces[i], queue, null, base + "_" + i)
            }
        }
    }

    private fun configure(engine: TextToSpeech, text: String) {
        var devanagari = 0
        for (ch in text) if (ch in '\u0900'..'\u097F') devanagari++
        val locale = if (devanagari * 4 > text.length) Locale("hi", "IN") else Locale("en", "IN")
        var result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            result = engine.setLanguage(Locale.US)
        }
        val wanted = Store.ttsVoice
        if (wanted.isNotBlank()) {
            val voice = engine.voices?.firstOrNull { it.name == wanted }
            if (voice != null) engine.setVoice(voice)
        }
        engine.setSpeechRate(Store.ttsSpeed.coerceIn(0.5f, 2.0f))
    }

    private fun ensureTts(afterReady: () -> Unit) {
        if (ttsReady) {
            afterReady()
            return
        }
        afterTtsReady = afterReady
        if (tts != null) return // initialisation already running
        tts = TextToSpeech(app) { status ->
            main.post {
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    tts?.setOnUtteranceProgressListener(utteranceListener)
                    val next = afterTtsReady
                    afterTtsReady = null
                    next?.invoke()
                } else {
                    tts = null
                    afterTtsReady = null
                    cb.onMessage("Text-to-speech engine start nahi hua. Phone ki TTS settings check karo.")
                    afterSpeaking()
                }
            }
        }
    }

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}

        override fun onDone(utteranceId: String?) {
            if (utteranceId == lastUtterance) main.post { finishedSpeaking() }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            if (utteranceId == lastUtterance) main.post { finishedSpeaking() }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            if (utteranceId == lastUtterance) main.post { finishedSpeaking() }
        }
    }

    private fun finishedSpeaking() {
        abandonFocus()
        if (state == State.SPEAKING) afterSpeaking()
    }

    private fun afterSpeaking() {
        if (continuousOn && mode == Mode.CONTINUOUS) {
            setState(State.IDLE)
            main.postDelayed({ if (continuousOn && state == State.IDLE) begin(Mode.CONTINUOUS) }, 350L)
        } else {
            setState(State.IDLE)
        }
    }

    private fun stopSpeech() {
        try {
            tts?.stop()
        } catch (_: Exception) {
            // ignore
        }
        abandonFocus()
    }

    private fun requestFocus() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        am.requestAudioFocus(request)
        focusRequest = request
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.abandonAudioFocusRequest(request)
        focusRequest = null
    }

    private fun setState(s: State) {
        state = s
        cb.onState(s, mode)
    }
}
