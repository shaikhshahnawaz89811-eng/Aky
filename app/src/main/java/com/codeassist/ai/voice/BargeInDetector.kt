package com.codeassist.ai.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Watches the microphone WHILE the assistant is speaking and reports a real interruption (audit PDF
 * Sec 9.1 barge-in). The platform SpeechRecognizer is not running during playback, so this class owns the
 * mic with its own AudioRecord: source VOICE_COMMUNICATION (the source Android applies echo cancellation
 * to), plus AcousticEchoCanceler / NoiseSuppressor when the device offers them. AEC quality is
 * device-dependent, so with no AEC and no headset the thresholds are raised.
 *
 * The audio is only measured (RMS per 20 ms frame) and thrown away: nothing is stored or sent anywhere.
 * All Listener methods run on the main thread. Call [stop] before the speech recognizer starts.
 */
class BargeInDetector(
    context: Context,
    private val strict: Boolean,
    private val listener: Listener
) {
    interface Listener {
        /** Some speech was heard; not yet an interruption. */
        fun onCandidate()

        /** Short / quiet burst judged to be a backchannel; the assistant keeps talking. */
        fun onRejected()

        /** A real interruption: stop speaking and listen. */
        fun onValid()

        fun onUnavailable(reason: String)
    }

    companion object {
        private const val RATE = 16000
        private const val FRAME = 320 // 20 ms
        private const val WARMUP_MS = 400
        private const val MAX_RUN_MS = 180_000L

        fun headsetConnected(ctx: Context): Boolean {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                when (it.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_USB_HEADSET -> true
                    else -> false
                }
            }
        }

        fun aecAvailable(): Boolean = try {
            AcousticEchoCanceler.isAvailable()
        } catch (_: Throwable) {
            false
        }
    }

    private val app: Context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        val t = Thread({ loop() }, "barge-in-vad")
        thread = t
        t.start()
    }

    /** Blocks briefly (max ~350 ms) so the mic is really released before the recognizer takes it. */
    fun stop() {
        running = false
        val t = thread ?: return
        thread = null
        if (t !== Thread.currentThread()) {
            try {
                t.join(350)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun post(block: () -> Unit) {
        main.post { if (running) block() }
    }

    private fun loop() {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            main.post { listener.onUnavailable("Mic buffer nahi mila") }
            running = false
            return
        }
        val size = maxOf(minBuf, FRAME * 2 * 6)
        var record: AudioRecord? = null
        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null
        try {
            record = open(MediaRecorder.AudioSource.VOICE_COMMUNICATION, size)
                ?: open(MediaRecorder.AudioSource.VOICE_RECOGNITION, size)
            if (record == null) {
                main.post { listener.onUnavailable("Mic start nahi hua (permission / kisi aur app ke paas)") }
                running = false
                return
            }
            var aecOn = false
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(record.audioSessionId)
                    aec?.setEnabled(true)
                    aecOn = aec?.getEnabled() == true
                }
                if (NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(record.audioSessionId)
                    ns?.setEnabled(true)
                }
            } catch (_: Throwable) {
                // effects are optional; the VOICE_COMMUNICATION source may already apply them
            }
            val headset = headsetConnected(app)
            var ratio = if (strict) 3.5f else 2.5f
            if (headset) ratio -= 0.5f // no speaker echo with a headset
            else if (!aecOn) ratio += 1.0f // loud speaker and no AEC: demand a clearer rise
            val vad = EnergyVad(ratio)
            val gate = if (strict) BargeInGate(strongRatio = 6f, strongMinMs = 400, quietMinMs = 700)
            else BargeInGate()

            record.startRecording()
            val buf = ShortArray(FRAME)
            val started = SystemClock.elapsedRealtime()
            var heardMs = 0
            while (running) {
                if (SystemClock.elapsedRealtime() - started > MAX_RUN_MS) break
                var got = 0
                while (got < FRAME && running) {
                    val n = record.read(buf, got, FRAME - got)
                    if (n <= 0) {
                        running = false
                        break
                    }
                    got += n
                }
                if (!running || got < FRAME) break
                val level = EnergyVad.rms(buf, FRAME)
                if (heardMs < WARMUP_MS) {
                    // the first moments of playback only teach the floor (echo level)
                    vad.warm(level)
                    heardMs += 20
                    continue
                }
                val speech = vad.process(level)
                when (gate.feed(speech, vad.lastRatio)) {
                    BargeInGate.Event.CANDIDATE -> post { listener.onCandidate() }
                    BargeInGate.Event.REJECTED -> post { listener.onRejected() }
                    BargeInGate.Event.VALID -> {
                        running = false
                        main.post { listener.onValid() }
                    }
                    BargeInGate.Event.NONE -> Unit
                }
            }
        } catch (_: SecurityException) {
            main.post { listener.onUnavailable("Mic permission nahi hai") }
        } catch (e: Throwable) {
            main.post { listener.onUnavailable("Barge-in band: " + (e.message ?: e.javaClass.simpleName)) }
        } finally {
            try {
                aec?.release()
            } catch (_: Throwable) {
            }
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
            running = false
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
}
