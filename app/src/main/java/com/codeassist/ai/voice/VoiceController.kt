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
import com.codeassist.ai.ai.ChatRunner
import com.codeassist.ai.ai.ConvKpi
import com.codeassist.ai.data.Store
import java.util.Locale

/**
 * Real voice loop on top of the Android platform engines:
 *   speech in  = android.speech.SpeechRecognizer (live partials + real mic levels), or - only when the user
 *                picked "Offline model" in Voice and AI - the on-device sherpa-onnx engine ([OfflineStt])
 *   speech out = android.speech.tts.TextToSpeech
 * All calls must come from the main thread.
 *
 * Modes: TAP (one utterance, auto-stop on pause), HOLD (push-to-talk: release sends),
 * CONTINUOUS (listen -> reply -> speak -> listen again until stopped or the user says "bas").
 *
 * Phase 2 (audit PDF Sec 9.1 / 9.4 / 9.7), all on top of the same engines:
 *  - pause guard: an unfinished-sounding sentence keeps the mic open a little longer ([Endpointing])
 *  - follow-up window: after a spoken reply the mic re-opens for ~8 s without another tap
 *  - dialog acts: "hmm / haan" is not sent as a message, "aage batao" resumes, "dobara bolo" repeats
 *  - barge-in: talking over the assistant stops it ([BargeInDetector]); the cut text can be resumed
 *  - filler: a cached "ek second" is played when a slow brain is thinking ([PhraseCache])
 *  - audio-focus loss (a call, another app) stops speech instead of talking over it
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
            offlineEngineSelected(context) || SpeechRecognizer.isRecognitionAvailable(context.applicationContext)

        /** True when the user picked the offline model and it (plus the sherpa-onnx library) is installed. */
        fun offlineEngineSelected(context: Context): Boolean {
            Store.init(context)
            return Store.sttEngine == "offline" && OfflineSttStore.ready(context)
        }

        private const val RESUME_TTL_MS = 60_000L
        private const val ANSWER_WINDOW_MS = 10_000L
        private const val FILLER_FIRST_MS = 1_600L
        private const val FILLER_GAP_MS = 7_000L

        /** After an ElevenLabs network / quota failure: use the phone voice for this long before trying again. */
        private const val ELEVEN_BACKOFF_MS = 60_000L

        /** After the wake phrase: how long the mic waits for the first word (audit Sec 9.4: ~6 s). */
        private const val WAKE_WINDOW_MS = 6_000L
    }

    private val app: Context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    var state: State = State.IDLE
        private set
    var mode: Mode = Mode.TAP
        private set

    /** True while a conversation is open: a voice turn is in progress or a follow-up window may open. */
    val sessionActive: Boolean get() = voiceTurn || continuousOn || state != State.IDLE

    private var recognizer: SttEngine? = null
    private var recognizerOffline = false
    private val collected = StringBuilder()
    private var startedAt = 0L
    private var silentRounds = 0
    private var holding = false
    private var releasing = false
    private var continuousOn = false
    private var busyRetries = 0

    // Phase 2 conversation state
    private var followUpMode = false
    private var followUpDeadline = 0L
    private var followUpCounted = false
    private var reopenAsFollowUp = false
    private var speechSeen = false
    private var extendRounds = 0
    private var extendActive = false
    private var voiceTurn = false
    private var fromBarge = false
    private var endOfTurnAt = 0L
    private var firstAudioPending = false
    private var firstAudioFast = false
    private var expectAnswer = false
    private var pendingQuestionUntil = 0L
    private var resumeText: String? = null
    private var resumeAt = 0L
    private var lastSpoken: String = ""
    private var fillerCount = 0
    private var fillerAttempts = 0
    private var wakePending = false // opened by the wake word and nothing was said yet

    // ElevenLabs engine (audit PDF Sec 9.7): own PCM player; the platform TTS below is its fallback
    private var eleven: ElevenPlayer? = null
    private var elevenPieces: List<String> = emptyList()
    private var elevenBackoffUntil = 0L

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var afterTtsReady: (() -> Unit)? = null
    private var lastUtterance: String = ""
    private var speakBase: String = ""
    private val tracker = SpokenTracker()
    private var focusRequest: AudioFocusRequest? = null
    private var bargeDetector: BargeInDetector? = null

    private val phrases = PhraseCache(context)

    init {
        ConvKpi.init(app)
    }
    private var cacheTts: TextToSpeech? = null
    private var cacheReady = false
    private var cachePreparedKey = ""

    // ---------- public control ----------

    fun startTap() = begin(Mode.TAP)

    fun startHold() = begin(Mode.HOLD)

    fun startContinuous() {
        continuousOn = true
        begin(Mode.CONTINUOUS)
    }

    /**
     * The wake phrase was heard. [initial] = words said right after it ("hey code assist time batao" ->
     * "time batao"): when present they are sent as the user's turn at once, otherwise the mic opens for a
     * short initial-silence window (a quiet close counts as a possible false wake, KPI wake_empty).
     */
    fun startFromWake(initial: String) {
        if (state != State.IDLE) return
        val text = initial.trim()
        if (text.isEmpty()) {
            begin(Mode.TAP, followUp = true, windowMs = WAKE_WINDOW_MS, wake = true)
            return
        }
        voiceTurn = true
        followUpMode = false
        wakePending = false
        silentRounds = 0
        ConvKpi.inc("voice_turns")
        endOfTurnAt = SystemClock.elapsedRealtime()
        firstAudioPending = true
        firstAudioFast = false
        mode = Mode.TAP
        setState(State.THINKING)
        scheduleFillers()
        cb.onFinalText(text, 0L)
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
        main.removeCallbacks(extendTimeout)
        main.removeCallbacks(followUpTimeout)
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
        voiceTurn = false
        followUpMode = false
        extendActive = false
        reopenAsFollowUp = false
        fromBarge = false
        expectAnswer = false
        wakePending = false
        main.removeCallbacks(releaseTimeout)
        main.removeCallbacks(restartRunnable)
        main.removeCallbacks(extendTimeout)
        main.removeCallbacks(followUpTimeout)
        main.removeCallbacks(reopenRunnable)
        cancelFillers()
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
            cancelFillers()
            if (continuousOn && mode == Mode.CONTINUOUS) {
                begin(Mode.CONTINUOUS)
            } else {
                voiceTurn = false
                setState(State.IDLE)
            }
        }
    }

    /**
     * The AI reply (or error) is ready. [spoken] null/blank = say nothing.
     * [expectsAnswer]: the reply asked a question, so a bare "haan / nahi" in the next ~10 s is an answer.
     * [fast]: the reply came from the Tier-0 fast path (no filler, separate latency target).
     */
    fun replyReady(spoken: String?, expectsAnswer: Boolean = false, fast: Boolean = false) {
        if (state != State.THINKING) return
        cancelFillers()
        firstAudioFast = fast
        expectAnswer = expectsAnswer
        if (spoken.isNullOrBlank()) {
            voiceTurn = false // nothing was spoken, so no follow-up window
            afterSpeaking()
        } else {
            speakNow(spoken)
        }
    }

    /** Speak a reply that was typed (Settings: speak replies = always) when nothing else is running. */
    fun speakIfIdle(text: String) {
        if (state != State.IDLE) return
        firstAudioPending = false
        speakNow(text)
    }

    fun isBusy(): Boolean = state != State.IDLE

    fun markThinking() {
        if (state == State.LISTENING || state == State.IDLE) setState(State.THINKING)
    }

    fun destroy() {
        stopAll()
        WakeCoordinator.setBusy(this, false)
        main.removeCallbacksAndMessages(null)
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
            // ignore
        }
        recognizer = null
        eleven?.stop()
        eleven = null
        try {
            tts?.shutdown()
        } catch (_: Exception) {
            // ignore
        }
        tts = null
        ttsReady = false
        try {
            cacheTts?.shutdown()
        } catch (_: Exception) {
            // ignore
        }
        cacheTts = null
        cacheReady = false
        phrases.release()
    }

    /** Used by the options screen so "Test voice" shares the configured engine. */
    fun sample(text: String) {
        if (state == State.LISTENING) return
        firstAudioPending = false
        speakNow(text, countTurn = false)
    }

    // ---------- listening ----------

    private fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun begin(
        m: Mode,
        followUp: Boolean = false,
        barge: Boolean = false,
        windowMs: Long = -1L,
        wake: Boolean = false
    ) {
        if (!recognitionAvailable(app)) {
            if (!followUp) cb.onMessage("Is phone par speech recognition service nahi hai. Google app install / enable karo, ya Voice and AI mein Offline model download karke use karo.")
            continuousOn = false
            voiceTurn = false
            return
        }
        if (!hasMic()) {
            continuousOn = false
            voiceTurn = false
            if (!followUp) cb.onNeedPermission()
            return
        }
        stopSpeech()
        main.removeCallbacks(restartRunnable)
        main.removeCallbacks(extendTimeout)
        main.removeCallbacks(followUpTimeout)
        main.removeCallbacks(reopenRunnable)
        mode = m
        holding = m == Mode.HOLD
        releasing = false
        collected.setLength(0)
        silentRounds = 0
        busyRetries = 0
        followUpMode = followUp
        fromBarge = barge
        wakePending = wake
        speechSeen = false
        extendRounds = 0
        extendActive = false
        followUpCounted = false
        startedAt = SystemClock.elapsedRealtime()
        if (followUp) {
            val window = if (windowMs > 0L) windowMs else Store.followUpMs()
            followUpDeadline = startedAt + window
            if (!wake) ConvKpi.inc("followup_open")
            main.postDelayed(followUpTimeout, window)
        }
        cb.onPartial("")
        setState(State.LISTENING)
        startRecognizer()
        ensurePhraseCache()
    }

    private fun startRecognizer() {
        val wantOffline = offlineEngineSelected(app)
        if (recognizer != null && recognizerOffline != wantOffline) {
            // the engine was switched in Settings: drop the old one, a new one is made below
            try {
                recognizer?.destroy()
            } catch (_: Exception) {
                // ignore
            }
            recognizer = null
        }
        val r = recognizer ?: try {
            val engine: SttEngine = if (wantOffline) {
                OfflineStt(app) { message -> if (state == State.LISTENING) fail(message) }
            } else {
                PlatformStt(SpeechRecognizer.createSpeechRecognizer(app))
            }
            engine.also {
                it.setRecognitionListener(listener)
                recognizer = it
                recognizerOffline = wantOffline
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
            // Hindi sentences are verb-final, so a pause often comes before the verb. With the pause guard on,
            // the recognizer may close sooner (the guard re-opens it when the sentence sounds unfinished);
            // with it off, keep the long Phase-1 silence. Some recognizers ignore these hints.
            val smart = Store.smartEndpoint && mode != Mode.HOLD
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, if (smart) 1000L else 1600L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, if (smart) 800L else 1300L)
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

    /** Pause guard ran out: nothing more was said, so send what we have. */
    private val extendTimeout = Runnable {
        if (state == State.LISTENING && extendActive && !speechSeen) {
            extendActive = false
            try {
                recognizer?.cancel()
            } catch (_: Exception) {
                // already stopped
            }
            finishUtterance()
        }
    }

    /** Follow-up window ran out without speech: the session goes back to standby, quietly. */
    private val followUpTimeout = Runnable {
        if (state == State.LISTENING && followUpMode && !speechSeen && collected.isEmpty()) {
            countQuietClose()
            try {
                recognizer?.cancel()
            } catch (_: Exception) {
                // already stopped
            }
            collected.setLength(0)
            cb.onPartial("")
            quietEnd()
        }
    }

    private val reopenRunnable = Runnable {
        if (state != State.IDLE) return@Runnable
        if (continuousOn) {
            begin(Mode.CONTINUOUS)
        } else if (reopenAsFollowUp) {
            reopenAsFollowUp = false
            begin(Mode.TAP, followUp = true)
        }
    }

    private fun join(a: String, b: String): String =
        if (a.isBlank()) b else if (b.isBlank()) a else "$a $b"

    private fun markSpeech() {
        if (speechSeen) return
        speechSeen = true
        main.removeCallbacks(followUpTimeout)
        main.removeCallbacks(extendTimeout)
        if (extendActive) {
            extendActive = false
            ConvKpi.inc("endpoint_saved")
        }
        if (followUpMode && !followUpCounted) {
            followUpCounted = true
            if (wakePending) wakePending = false else ConvKpi.inc("followup_used")
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {
            if (state == State.LISTENING) markSpeech()
        }

        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onRmsChanged(rmsdB: Float) {
            if (state == State.LISTENING) cb.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (state != State.LISTENING) return
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            if (t.isNotBlank()) markSpeech()
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
            } else if (shouldExtend()) {
                startExtension()
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
                    } else if (followUpMode && !speechSeen && !releasing && collected.isEmpty() &&
                        SystemClock.elapsedRealtime() + 300L < followUpDeadline
                    ) {
                        // the platform gave up earlier than our window: listen again until the window ends
                        main.postDelayed(restartRunnable, 200L)
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

    // ---------- pause guard (Endpointing) ----------

    private fun shouldExtend(): Boolean =
        Store.smartEndpoint && mode != Mode.HOLD && !releasing && extendRounds < 2 &&
            Endpointing.isIncomplete(collected.toString())

    private fun startExtension() {
        val text = collected.toString()
        extendRounds++
        if (extendRounds == 1) ConvKpi.inc("endpoint_guard")
        extendActive = true
        speechSeen = false
        cb.onPartial(text)
        main.removeCallbacks(extendTimeout)
        main.postDelayed(restartRunnable, 120L)
        main.postDelayed(extendTimeout, 120L + Endpointing.extraWaitMs(text))
    }

    // ---------- finishing an utterance ----------

    private fun finishUtterance() {
        main.removeCallbacks(releaseTimeout)
        main.removeCallbacks(restartRunnable)
        main.removeCallbacks(extendTimeout)
        main.removeCallbacks(followUpTimeout)
        extendActive = false
        val text = collected.toString().trim()
        val duration = SystemClock.elapsedRealtime() - startedAt
        collected.setLength(0)
        releasing = false
        holding = false
        cb.onPartial("")

        if (text.isEmpty()) {
            if (fromBarge) ConvKpi.inc("barge_false") // we stopped the assistant for nothing
            fromBarge = false
            silentRounds++
            if (followUpMode) {
                countQuietClose()
                quietEnd()
                return
            }
            if (mode == Mode.CONTINUOUS && continuousOn && silentRounds < 3) {
                main.postDelayed(restartRunnable, 250L)
            } else {
                if (mode != Mode.HOLD) cb.onMessage("Kuch sunai nahi diya.")
                continuousOn = false
                voiceTurn = false
                setState(State.IDLE)
            }
            return
        }
        fromBarge = false
        wakePending = false

        val act = DialogActs.classify(
            text,
            DialogActs.Context(
                pendingQuestion = SystemClock.elapsedRealtime() < pendingQuestionUntil,
                hasResume = hasResume(),
                hasRepeat = lastSpoken.isNotBlank(),
                sessionLike = followUpMode || mode == Mode.CONTINUOUS
            )
        )
        when (act) {
            DialogActs.Act.BACKCHANNEL -> {
                ConvKpi.inc("backchannel_ignored")
                if (mode == Mode.CONTINUOUS && continuousOn) main.postDelayed(restartRunnable, 250L) else quietEnd()
                return
            }
            DialogActs.Act.STOP, DialogActs.Act.GOODBYE -> {
                continuousOn = false
                quietEnd()
                return
            }
            DialogActs.Act.RESUME -> {
                val rest = resumeText
                resumeText = null
                if (rest != null) {
                    ConvKpi.inc("resume_used")
                    followUpMode = false
                    voiceTurn = true
                    speakNow(rest)
                    return
                }
            }
            DialogActs.Act.REPEAT -> {
                if (lastSpoken.isNotBlank()) {
                    ConvKpi.inc("repeat_used")
                    followUpMode = false
                    voiceTurn = true
                    speakNow(lastSpoken)
                    return
                }
            }
            else -> Unit
        }

        silentRounds = 0
        followUpMode = false
        voiceTurn = true
        ConvKpi.inc("voice_turns")
        endOfTurnAt = SystemClock.elapsedRealtime()
        firstAudioPending = true
        firstAudioFast = false
        setState(State.THINKING)
        scheduleFillers()
        cb.onFinalText(text, duration)
    }

    /** A follow-up / wake window closed with nothing said. After a wake word it is a possible false accept. */
    private fun countQuietClose() {
        if (wakePending) {
            wakePending = false
            ConvKpi.inc("wake_empty")
        } else {
            ConvKpi.inc("followup_timeout")
        }
    }

    private fun quietEnd() {
        voiceTurn = false
        followUpMode = false
        reopenAsFollowUp = false
        setState(State.IDLE)
    }

    private fun cancelListening() {
        main.removeCallbacks(releaseTimeout)
        main.removeCallbacks(restartRunnable)
        main.removeCallbacks(extendTimeout)
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

    // ---------- filler while a slow brain thinks (PhraseCache) ----------

    // An object (not a Runnable { } lambda) on purpose: it re-posts itself with `this`. A lambda that names its own
    // val fails to compile ("recursive problem" / "must be initialized").
    private val fillerRunnable: Runnable = object : Runnable {
        override fun run() {
            if (state != State.THINKING || !Store.fillers) return
            val current = ChatRunner.current
            // fast path (and nothing running yet): never a filler. Max 2, at least ~7 s apart.
            if (current == null || current.engine == "tool") return
            fillerAttempts++
            val kind = if (fillerCount == 0) PhraseCache.Kind.ACK else PhraseCache.Kind.SLOW
            if (phrases.play(kind)) {
                fillerCount++
                ConvKpi.inc("filler_played")
            }
            if (fillerCount < 2 && fillerAttempts < 4) main.postDelayed(this, FILLER_GAP_MS)
        }
    }

    private fun scheduleFillers() {
        fillerCount = 0
        fillerAttempts = 0
        main.removeCallbacks(fillerRunnable)
        if (Store.fillers) main.postDelayed(fillerRunnable, FILLER_FIRST_MS)
    }

    private fun cancelFillers() {
        main.removeCallbacks(fillerRunnable)
        phrases.stop()
    }

    private fun voiceKey(): String = Store.ttsVoice + "|" + Store.ttsSpeed + "|en-IN"

    /** Synthesizes the filler phrases on a second TTS engine so the reply engine's queue is never delayed. */
    private fun ensurePhraseCache() {
        if (!Store.fillers) return
        val elevenKey = Store.elevenKey
        if (Store.elevenActive() && !elevenKey.isNullOrBlank() && SystemClock.elapsedRealtime() >= elevenBackoffUntil) {
            // fillers in the same ElevenLabs voice as the replies (audit Sec 9.7: match the live voice)
            val voiceId = Store.elevenVoiceId
            val model = Store.elevenModel
            val speed = Store.ttsSpeed
            phrases.prepareEleven("eleven|" + voiceId + "|" + model + "|" + speed, elevenKey, voiceId, model, speed)
            cachePreparedKey = ""
            return
        }
        val key = voiceKey()
        val existing = cacheTts
        if (existing != null) {
            if (cacheReady && key != cachePreparedKey) {
                configure(existing, "Hmm ek second")
                phrases.prepare(existing, key)
                cachePreparedKey = key
            }
            return
        }
        cacheTts = TextToSpeech(app) { status ->
            main.post {
                val engine = cacheTts ?: return@post
                if (status != TextToSpeech.SUCCESS) {
                    cacheTts = null
                    return@post
                }
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        main.post { phrases.onSynthesized(utteranceId, true) }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        main.post { phrases.onSynthesized(utteranceId, false) }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        main.post { phrases.onSynthesized(utteranceId, false) }
                    }
                })
                configure(engine, "Hmm ek second")
                val k = voiceKey()
                phrases.prepare(engine, k)
                cachePreparedKey = k
                cacheReady = true
            }
        }
    }

    // ---------- speaking ----------

    private fun hasResume(): Boolean =
        resumeText != null && SystemClock.elapsedRealtime() - resumeAt < RESUME_TTL_MS

    private fun speakNow(raw: String, countTurn: Boolean = true) {
        val clean = SpeechText.clean(raw)
        if (clean.isBlank()) {
            if (state == State.THINKING) afterSpeaking()
            return
        }
        cancelFillers()
        stopBargeWatch()
        resumeText = null
        lastSpoken = clean
        setState(State.SPEAKING)
        if (countTurn) ConvKpi.inc("speaking_turns")
        if (Store.elevenActive() && SystemClock.elapsedRealtime() >= elevenBackoffUntil) {
            speakEleven(clean)
        } else {
            speakPlatform(clean)
        }
    }

    /** Phone voice (android.speech.tts). Also the fallback when ElevenLabs fails. */
    private fun speakPlatform(clean: String) {
        ensureTts {
            val engine = tts
            if (engine == null || state != State.SPEAKING) return@ensureTts
            configure(engine, clean)
            requestFocus()
            val pieces = SpeechText.chunks(clean, 500)
            tracker.reset(clean, pieces)
            val base = "r" + System.currentTimeMillis()
            speakBase = base
            lastUtterance = base + "_" + (pieces.size - 1)
            for (i in pieces.indices) {
                val queue = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                engine.speak(pieces[i], queue, null, base + "_" + i)
            }
        }
    }

    /**
     * ElevenLabs voice: sentence-sized chunks (small first chunk = quick first audio) are streamed as PCM.
     * Any failure hands the unspoken rest to the phone voice, so the assistant never goes silent.
     */
    private fun speakEleven(clean: String) {
        val key = Store.elevenKey
        if (key.isNullOrBlank()) {
            speakPlatform(clean)
            return
        }
        eleven?.stop()
        requestFocus()
        val pieces = SpeechText.chunks(clean, 220)
        elevenPieces = pieces
        tracker.reset(clean, pieces)
        val player = ElevenPlayer(object : ElevenPlayer.Listener {
            override fun onChunkStart(index: Int) {
                chunkStarted(index)
            }

            override fun onDone() {
                finishedSpeaking()
            }

            override fun onFailed(fromIndex: Int, reason: String, authFailure: Boolean) {
                elevenFailed(fromIndex, reason, authFailure)
            }
        })
        eleven = player
        player.play(pieces, key, Store.elevenVoiceId, Store.elevenModel, Store.ttsSpeed)
    }

    private fun elevenFailed(fromIndex: Int, reason: String, authFailure: Boolean) {
        eleven = null
        if (state != State.SPEAKING) return
        ConvKpi.inc("eleven_fallback")
        if (authFailure) {
            Store.elevenKeyStatus = "invalid"
        } else {
            elevenBackoffUntil = SystemClock.elapsedRealtime() + ELEVEN_BACKOFF_MS
        }
        cb.onMessage(reason + " Phone ki awaaz se bol raha hoon.")
        val rest = elevenPieces.drop(fromIndex.coerceAtLeast(0)).joinToString(" ").trim()
        if (rest.isBlank()) finishedSpeaking() else speakPlatform(rest)
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

    private fun chunkIndex(utteranceId: String?): Int {
        val id = utteranceId ?: return -1
        if (speakBase.isEmpty() || !id.startsWith(speakBase + "_")) return -1
        return id.substringAfterLast('_').toIntOrNull() ?: -1
    }

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            main.post { chunkStarted(chunkIndex(utteranceId)) }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            val index = chunkIndex(utteranceId)
            if (index >= 0) main.post { tracker.onRange(index, end) }
        }

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

    private fun chunkStarted(index: Int) {
        if (index < 0 || state != State.SPEAKING) return
        tracker.onChunkStart(index)
        if (index == 0) {
            if (firstAudioPending) {
                firstAudioPending = false
                ConvKpi.recordFirstAudio(SystemClock.elapsedRealtime() - endOfTurnAt, firstAudioFast)
            }
            startBargeWatch()
        }
    }

    private fun finishedSpeaking() {
        stopBargeWatch()
        abandonFocus()
        if (state == State.SPEAKING) {
            resumeText = null
            if (expectAnswer) pendingQuestionUntil = SystemClock.elapsedRealtime() + ANSWER_WINDOW_MS
            expectAnswer = false
            afterSpeaking()
        }
    }

    private fun afterSpeaking() {
        stopBargeWatch()
        if (continuousOn && mode == Mode.CONTINUOUS) {
            setState(State.IDLE)
            main.postDelayed(reopenRunnable, 350L)
        } else if (voiceTurn && Store.followUpMs() > 0L && hasMic() &&
            recognitionAvailable(app)
        ) {
            // follow-up window: the mic re-opens without another tap; silence ends it quietly
            setState(State.IDLE)
            reopenAsFollowUp = true
            main.postDelayed(reopenRunnable, 400L)
        } else {
            voiceTurn = false
            setState(State.IDLE)
        }
    }

    private fun stopSpeech() {
        stopBargeWatch()
        phrases.stop()
        eleven?.stop()
        eleven = null
        try {
            tts?.stop()
        } catch (_: Exception) {
            // ignore
        }
        abandonFocus()
    }

    // ---------- barge-in ----------

    private fun startBargeWatch() {
        val level = Store.bargeIn
        if (level == "off" || !hasMic() || state != State.SPEAKING) return
        stopBargeWatch()
        val detector = BargeInDetector(app, level == "strict", object : BargeInDetector.Listener {
            override fun onCandidate() {
                ConvKpi.inc("barge_candidates")
                eleven?.duck() // only possible with the own PCM player (audit: duck -> verify -> cancel)
            }

            override fun onRejected() {
                ConvKpi.inc("barge_rejected")
                eleven?.unduck()
            }

            override fun onValid() {
                handleBargeIn()
            }

            override fun onUnavailable(reason: String) {
                // not fatal: the assistant keeps talking, the user can still tap the mic
            }
        })
        bargeDetector = detector
        detector.start()
    }

    private fun stopBargeWatch() {
        val d = bargeDetector ?: return
        bargeDetector = null
        d.stop()
    }

    private fun handleBargeIn() {
        if (state != State.SPEAKING) return
        ConvKpi.inc("barge_valid")
        resumeText = tracker.remainingFromSentence()
        resumeAt = SystemClock.elapsedRealtime()
        expectAnswer = false
        stopSpeech() // also releases the mic before the recognizer takes it
        voiceTurn = true
        val m = if (continuousOn) Mode.CONTINUOUS else Mode.TAP
        begin(m, barge = true)
    }

    // ---------- audio focus ----------

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            main.post { focusLost() }
        }
    }

    /** A call or another app took the audio: stop talking, keep the rest so "aage batao" can continue. */
    private fun focusLost() {
        if (state != State.SPEAKING) return
        ConvKpi.inc("focus_loss")
        resumeText = tracker.remainingFromSentence()
        resumeAt = SystemClock.elapsedRealtime()
        continuousOn = false
        voiceTurn = false
        expectAnswer = false
        stopSpeech()
        setState(State.IDLE)
    }

    private fun requestFocus() {
        if (focusRequest != null) return // already held for this reply (ElevenLabs -> phone voice fallback asks twice)
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener, main)
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
        // the hands-free engine must not hold the mic while a voice turn (or its follow-up gap) is open
        WakeCoordinator.setBusy(this, sessionActive)
        cb.onState(s, mode)
    }
}
