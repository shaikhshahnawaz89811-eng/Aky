package com.codeassist.ai.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Optional on-device speech recognizer (sherpa-onnx, NeMo CTC model such as AI4Bharat IndicConformer).
 * It plugs into [VoiceController] through [SttEngine], so every existing behaviour (pause guard, follow-up
 * window, push-to-talk, barge-in, dialog acts) keeps working on top of it.
 *
 * How one listen works:
 *  1. the mic opens at 16 kHz and the model starts loading in the background (so first words are not lost)
 *  2. a small energy VAD ([EnergyVad]) finds where speech starts and where the pause after it is long enough
 *  3. the model is run once on that audio; the text comes back through onResults, exactly like the Android recognizer
 *
 * Limits, on purpose: the model is OFFLINE (no live partial text; the words appear when you stop talking) and one
 * utterance is cut at ~28 s because a Conformer's memory grows with the square of the audio length.
 */
class OfflineStt(context: Context, private val onFatal: (String) -> Unit) : SttEngine {

    companion object {
        private const val RATE = 16_000
        private const val FRAME = 320 // 20 ms
        private const val NO_SPEECH_MS = 6_000L
        private const val MAX_SPEECH_MS = 28_000L
        private const val PRE_ROLL_SAMPLES = RATE * 320 / 1000
        private const val TAIL_SAMPLES = RATE * 360 / 1000
        private const val MIN_SPEECH_FRAMES = 4 // ~80 ms of energy before it counts as speech
        private const val DEFAULT_SILENCE_MS = 1300L
    }

    private val app: Context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var listener: RecognitionListener? = null
    private var session = 0
    private var capture: Capture? = null

    override fun setRecognitionListener(listener: RecognitionListener) {
        this.listener = listener
    }

    override fun startListening(intent: Intent) {
        cancelCurrent()
        val silence = intent.getLongExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
            DEFAULT_SILENCE_MS
        ).coerceIn(600L, 3_000L)
        session++
        val c = Capture(session, silence)
        capture = c
        Thread(c, "offline-stt-capture").start()
    }

    /** Push-to-talk release / tap-to-stop: stop recording now and decode what was heard. */
    override fun stopListening() {
        capture?.finishRequested = true
    }

    override fun cancel() {
        cancelCurrent()
    }

    override fun destroy() {
        cancelCurrent()
        listener = null
    }

    private fun cancelCurrent() {
        capture?.aborted = true
        capture = null
        session++ // anything still queued from the old capture is dropped
    }

    // ---------- callbacks (always on the main thread, only for the current session) ----------

    private fun deliver(sid: Int, clearCapture: Boolean, block: (RecognitionListener) -> Unit) {
        main.post {
            if (sid == session) {
                if (clearCapture) capture = null
                val l = listener
                if (l != null) block(l)
            }
        }
    }

    private fun postReady(sid: Int) = deliver(sid, false) { it.onReadyForSpeech(null) }
    private fun postBeginning(sid: Int) = deliver(sid, false) { it.onBeginningOfSpeech() }
    private fun postEnd(sid: Int) = deliver(sid, false) { it.onEndOfSpeech() }
    private fun postError(sid: Int, code: Int) = deliver(sid, true) { it.onError(code) }

    private fun postLevel(sid: Int, rms: Float) {
        // VoiceController turns this back into 0..1 with ((db + 2) / 12)
        val db = (rms / 2500f).coerceIn(0f, 1f) * 12f - 2f
        main.post {
            if (sid == session) listener?.onRmsChanged(db)
        }
    }

    private fun postResults(sid: Int, text: String) {
        val b = Bundle()
        b.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
        deliver(sid, true) { it.onResults(b) }
    }

    private fun postFatal(sid: Int, message: String) {
        main.post {
            if (sid == session) {
                capture = null
                onFatal(message)
            }
        }
    }

    // ---------- one listen ----------

    private inner class Capture(private val sid: Int, private val silenceMs: Long) : Runnable {
        @Volatile
        var finishRequested = false

        @Volatile
        var aborted = false

        override fun run() {
            OfflineSttStore.captureStarted()
            try {
                val samples = listen() ?: return
                if (aborted) return
                postEnd(sid)
                val text = OfflineSttStore.transcribe(app, samples)
                if (aborted) return
                if (text.isBlank()) postError(sid, SpeechRecognizer.ERROR_NO_MATCH) else postResults(sid, text)
            } catch (e: SecurityException) {
                postError(sid, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            } catch (e: OutOfMemoryError) {
                postFatal(sid, "Offline model ke liye RAM kam padi. Baaki apps band karo ya chhota bolo.")
            } catch (t: Throwable) {
                if (!aborted) postFatal(sid, t.message ?: ("Offline speech fail: " + t.javaClass.simpleName))
            } finally {
                OfflineSttStore.captureEnded()
            }
        }

        /** Records until the speaker pauses; returns the audio to decode, or null when an error was already reported. */
        private fun listen(): FloatArray? {
            val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) {
                postError(sid, SpeechRecognizer.ERROR_AUDIO)
                return null
            }
            val record = openRecord(maxOf(minBuf, RATE))
            if (record == null) {
                if (!aborted) postError(sid, SpeechRecognizer.ERROR_AUDIO)
                return null
            }

            val pcm = ShortArray(RATE * 40)
            val frame = ShortArray(FRAME)
            val vad = EnergyVad(3.0f)
            vad.warm(60f)
            var n = 0
            var frames = 0
            var started = false
            var voiceRun = 0
            var startIdx = 0
            var lastVoiceIdx = 0
            var timedOut = false

            try {
                try {
                    record.startRecording()
                } catch (e: IllegalStateException) {
                    postError(sid, SpeechRecognizer.ERROR_AUDIO)
                    return null
                }
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    postError(sid, SpeechRecognizer.ERROR_AUDIO)
                    return null
                }
                postReady(sid)
                OfflineSttStore.loadAsync(app) // load the model while the person is still talking

                while (!aborted && !finishRequested) {
                    var got = 0
                    while (got < FRAME && !aborted) {
                        val r = record.read(frame, got, FRAME - got)
                        if (r < 0) {
                            postError(sid, SpeechRecognizer.ERROR_AUDIO)
                            return null
                        }
                        if (r == 0) {
                            Thread.sleep(5L)
                            continue
                        }
                        got += r
                    }
                    if (aborted || got < FRAME) break
                    if (n + FRAME > pcm.size) break
                    System.arraycopy(frame, 0, pcm, n, FRAME)
                    val rms = EnergyVad.rms(frame, FRAME)
                    val voice = vad.process(rms)
                    n += FRAME
                    frames++
                    if (frames % 3 == 0) postLevel(sid, rms)

                    if (!started) {
                        if (voice) voiceRun++ else voiceRun = 0
                        if (voiceRun >= MIN_SPEECH_FRAMES) {
                            started = true
                            startIdx = (n - voiceRun * FRAME - PRE_ROLL_SAMPLES).coerceAtLeast(0)
                            lastVoiceIdx = n
                            postBeginning(sid)
                        } else if (n * 1000L / RATE >= NO_SPEECH_MS) {
                            timedOut = true
                            break
                        }
                    } else {
                        if (voice) lastVoiceIdx = n
                        if ((n - lastVoiceIdx) * 1000L / RATE >= silenceMs) break
                        if ((n - startIdx) * 1000L / RATE >= MAX_SPEECH_MS) break
                    }
                }
            } finally {
                try {
                    record.stop()
                } catch (_: Exception) {
                    // already stopped
                }
                record.release()
            }

            if (aborted) return null
            if (!started) {
                postError(sid, if (timedOut) SpeechRecognizer.ERROR_SPEECH_TIMEOUT else SpeechRecognizer.ERROR_NO_MATCH)
                return null
            }
            val endIdx = minOf(n, lastVoiceIdx + TAIL_SAMPLES)
            val length = endIdx - startIdx
            if (length < RATE / 5) {
                postError(sid, SpeechRecognizer.ERROR_NO_MATCH)
                return null
            }
            return FloatArray(length) { pcm[startIdx + it] / 32768f }
        }

        private fun openRecord(size: Int): AudioRecord? {
            val sources = intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
            for (attempt in 0 until 4) {
                for (source in sources) {
                    if (aborted) return null
                    try {
                        val r = AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
                        if (r.state == AudioRecord.STATE_INITIALIZED) return r
                        r.release()
                    } catch (e: SecurityException) {
                        throw e
                    } catch (_: Exception) {
                        // try the next source
                    }
                }
                // the previous capture may still be letting go of the mic
                Thread.sleep(80L)
            }
            return null
        }
    }
}
