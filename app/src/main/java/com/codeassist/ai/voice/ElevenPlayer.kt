package com.codeassist.ai.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.codeassist.ai.ai.ElevenLabsClient
import java.net.HttpURLConnection
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Speaks a list of sentence chunks with ElevenLabs (audit PDF Sec 9.7): every chunk is streamed as raw PCM and
 * written to an own AudioTrack, so first audio starts while the chunk is still downloading, and the next chunk is
 * already being fetched while the current one plays. Own player = real volume duck for barge-in (Sec 9.1) and an
 * immediate stop with buffer flush.
 *
 * One instance speaks once. All [Listener] callbacks run on the main thread and are dropped after [stop].
 */
class ElevenPlayer(private val listener: Listener) {

    interface Listener {
        /** First audio of chunk [index] is about to be heard. */
        fun onChunkStart(index: Int)

        /** Everything was played. */
        fun onDone()

        /**
         * Playback failed. Chunks from [fromIndex] on were NOT (fully) spoken; the caller may speak them another way.
         * [authFailure] is true when the key was rejected.
         */
        fun onFailed(fromIndex: Int, reason: String, authFailure: Boolean)
    }

    private sealed class Item {
        class Data(val index: Int, val bytes: ByteArray) : Item()
        object End : Item()
        class Fail(val index: Int, val reason: String, val auth: Boolean) : Item()
    }

    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayBlockingQueue<Item>(120) // about 12 s of audio (100 ms per item)

    @Volatile
    private var cancelled = false

    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    private var connection: HttpURLConnection? = null

    @Volatile
    private var duckVolume = 1.0f

    private var producer: Thread? = null
    private var consumer: Thread? = null

    fun play(pieces: List<String>, key: String, voiceId: String, model: String, speed: Float) {
        if (pieces.isEmpty()) {
            main.post { if (!cancelled) listener.onDone() }
            return
        }
        producer = Thread({ produce(pieces, key, voiceId, model, speed) }, "eleven-fetch").also { it.start() }
        consumer = Thread({ consume() }, "eleven-play").also { it.start() }
    }

    /** Immediate stop: no more callbacks, buffer flushed, network closed. Safe to call from any thread, many times. */
    fun stop() {
        if (cancelled) return
        cancelled = true
        try {
            connection?.disconnect()
        } catch (_: Exception) {
            // ignore
        }
        queue.clear()
        queue.offer(Item.End) // wakes a consumer blocked on take()
        val t = track
        if (t != null) {
            try {
                t.pause()
                t.flush()
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    /** Barge-in candidate: lower the volume at once (audit duck -> verify -> cancel). */
    fun duck() {
        duckVolume = 0.3f
        try {
            track?.setVolume(duckVolume)
        } catch (_: Exception) {
            // ignore
        }
    }

    /** Barge-in rejected (backchannel / echo): back to full volume. */
    fun unduck() {
        duckVolume = 1.0f
        try {
            track?.setVolume(duckVolume)
        } catch (_: Exception) {
            // ignore
        }
    }

    // ---------- producer: HTTP -> queue ----------

    private fun produce(pieces: List<String>, key: String, voiceId: String, model: String, speed: Float) {
        for (i in pieces.indices) {
            if (cancelled) return
            val prev = if (i > 0) pieces[i - 1].takeLast(200) else null
            val next = if (i + 1 < pieces.size) pieces[i + 1].take(200) else null
            var conn: HttpURLConnection? = null
            try {
                conn = ElevenLabsClient.openStream(key, voiceId, model, pieces[i], speed, prev, next)
                connection = conn
                val input = conn.inputStream
                val buf = ByteArray(4800) // 100 ms of 24 kHz mono PCM16
                var carry = -1 // PCM16 needs whole samples: keep a dangling odd byte for the next read
                var got = false
                while (!cancelled) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    val have = n + (if (carry >= 0) 1 else 0)
                    val usable = have - (have % 2)
                    val out = ByteArray(usable)
                    var o = 0
                    var s = 0
                    if (carry >= 0) {
                        out[o++] = carry.toByte()
                        carry = -1
                    }
                    while (o < usable) out[o++] = buf[s++]
                    if (s < n) carry = buf[s].toInt() and 0xFF
                    if (out.isNotEmpty()) {
                        got = true
                        if (!put(Item.Data(i, out))) return
                    }
                }
                if (cancelled) return
                if (!got) {
                    put(Item.Fail(i, "ElevenLabs ne khaali audio di.", false))
                    return
                }
            } catch (e: ElevenLabsClient.ApiError) {
                if (!cancelled) put(Item.Fail(i, e.message ?: "ElevenLabs error", ElevenLabsClient.isAuthFailure(e)))
                return
            } catch (e: Exception) {
                if (!cancelled) put(Item.Fail(i, "ElevenLabs tak internet nahi pahunch raha.", false))
                return
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Exception) {
                    // ignore
                }
            }
        }
        put(Item.End)
    }

    /** Blocks while the queue is full (back-pressure). Returns false when cancelled. */
    private fun put(item: Item): Boolean {
        while (!cancelled) {
            if (queue.offer(item, 200, TimeUnit.MILLISECONDS)) return true
        }
        return false
    }

    // ---------- consumer: queue -> AudioTrack ----------

    private fun consume() {
        var at: AudioTrack? = null
        var started = -1
        var framesWritten = 0L
        try {
            while (!cancelled) {
                val item = queue.poll(300, TimeUnit.MILLISECONDS) ?: continue
                if (cancelled) break
                when (item) {
                    is Item.Data -> {
                        val player: AudioTrack = at ?: buildTrack().also {
                            at = it
                            track = it
                            it.setVolume(duckVolume)
                            it.play()
                        }
                        if (item.index != started) {
                            started = item.index
                            val index = item.index
                            main.post { if (!cancelled) listener.onChunkStart(index) }
                        }
                        var off = 0
                        while (off < item.bytes.size && !cancelled) {
                            val w = player.write(item.bytes, off, item.bytes.size - off)
                            if (w < 0) {
                                fail(started, "Audio chalane mein dikkat aayi.", false)
                                return
                            }
                            off += w
                        }
                        framesWritten += item.bytes.size / 2
                    }

                    is Item.Fail -> {
                        // chunks before the failing one were fully played (they came earlier in the queue)
                        val from = if (started == item.index) item.index + 1 else item.index
                        drain(at, framesWritten)
                        fail(from, item.reason, item.auth)
                        return
                    }

                    is Item.End -> {
                        if (cancelled) return
                        drain(at, framesWritten)
                        if (!cancelled) main.post { if (!cancelled) listener.onDone() }
                        return
                    }
                }
            }
        } catch (e: Exception) {
            if (!cancelled) fail(maxOf(started, 0), "Audio chalane mein dikkat aayi.", false)
        } finally {
            try {
                at?.release()
            } catch (_: Exception) {
                // ignore
            }
            track = null
        }
    }

    private fun fail(fromIndex: Int, reason: String, auth: Boolean) {
        if (cancelled) return
        main.post { if (!cancelled) listener.onFailed(fromIndex, reason, auth) }
    }

    /** Waits until everything written has really been heard (stop() in stream mode plays out the buffer). */
    private fun drain(at: AudioTrack?, framesWritten: Long) {
        if (at == null) return
        try {
            at.stop()
        } catch (_: Exception) {
            return
        }
        val limit = System.currentTimeMillis() + framesWritten * 1000L / ElevenLabsClient.SAMPLE_RATE + 1500L
        while (!cancelled && System.currentTimeMillis() < limit) {
            val head = try {
                at.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            } catch (_: Exception) {
                return
            }
            if (head >= framesWritten) return
            try {
                Thread.sleep(30)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun buildTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(
            ElevenLabsClient.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val size = maxOf(min, 9_600) * 2 // about 0.4 s: small enough for quick stop, big enough for network jitter
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(ElevenLabsClient.SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }
}
