package com.codeassist.ai.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.io.File

/**
 * Audit PDF Sec 9.7 "Phrase cache": short acknowledgements ("Hmm, ek second") are synthesized once with
 * the user's current voice and kept as audio files, so they play with no delay and no network while the
 * brain is still thinking. A new voice, speed or language gives a new cache key; old files are deleted.
 *
 * The wording is deliberately gender-neutral ("dekhte hain"): the audit flags mixed masculine /
 * feminine verbs as a persona bug, and the persona setting comes in a later phase.
 */
class PhraseCache(context: Context) {
    enum class Kind { ACK, SLOW }

    companion object {
        val ACK = listOf(
            "Hmm, ek second.", "Theek hai, dekhte hain.", "Ek minute, dekhte hain.", "Bas ek second."
        )
        val SLOW = listOf(
            "Thoda time lag raha hai.", "Abhi bhi dekh rahe hain.", "Bas thodi der aur."
        )
    }

    private val app: Context = context.applicationContext
    private val dir = File(app.cacheDir, "phrases")
    private var key: String = ""
    private val ready = HashSet<String>()
    private var player: MediaPlayer? = null
    private var counter = 0

    private fun all(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        ACK.forEachIndexed { i, s -> out.add("a$i" to s) }
        SLOW.forEachIndexed { i, s -> out.add("s$i" to s) }
        return out
    }

    private fun fileFor(id: String) = File(dir, key + "_" + id + ".wav")

    /**
     * Call after the engine is configured for the voice that will be used. Synthesizes whatever is
     * missing in the background; harmless to call often.
     */
    fun prepare(engine: TextToSpeech, voiceKey: String) {
        val newKey = Integer.toHexString(voiceKey.hashCode())
        if (newKey != key) {
            key = newKey
            ready.clear()
            dir.mkdirs()
            dir.listFiles()?.forEach { if (!it.name.startsWith(key + "_")) it.delete() }
        }
        for ((id, text) in all()) {
            val f = fileFor(id)
            if (f.exists() && f.length() > 1024) {
                ready.add(id)
                continue
            }
            if (id in ready) continue
            val out = f
            try {
                dir.mkdirs()
                engine.synthesizeToFile(text, Bundle(), out, "pc_" + key + "_" + id)
            } catch (_: Throwable) {
                // cache is an optimisation; the normal TTS path still works
            }
        }
    }

    /** Called when a "pc_" utterance finished; returns true when [utteranceId] was a cache job. */
    fun onSynthesized(utteranceId: String?, success: Boolean): Boolean {
        val id = utteranceId ?: return false
        if (!id.startsWith("pc_")) return false
        val parts = id.split('_')
        if (parts.size >= 3 && parts[1] == key) {
            val phrase = parts[2]
            val f = fileFor(phrase)
            if (success && f.exists() && f.length() > 1024) ready.add(phrase) else f.delete()
        }
        return true
    }

    fun hasAny(kind: Kind): Boolean = ready.any { it.startsWith(if (kind == Kind.ACK) "a" else "s") }

    /** Plays one cached phrase. Returns false when none is ready (the caller then stays silent). */
    fun play(kind: Kind): Boolean {
        val prefix = if (kind == Kind.ACK) "a" else "s"
        val candidates = ready.filter { it.startsWith(prefix) }.sorted()
        if (candidates.isEmpty()) return false
        val id = candidates[counter++ % candidates.size]
        val f = fileFor(id)
        if (!f.exists()) return false
        stop()
        return try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(f.absolutePath)
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener {
                it.release()
                if (player === it) player = null
            }
            mp.setOnErrorListener { p, _, _ ->
                p.release()
                if (player === p) player = null
                true
            }
            player = mp
            mp.prepareAsync()
            true
        } catch (_: Throwable) {
            player = null
            false
        }
    }

    fun stop() {
        val p = player ?: return
        player = null
        try {
            p.stop()
        } catch (_: Throwable) {
        }
        try {
            p.release()
        } catch (_: Throwable) {
        }
    }

    fun release() {
        stop()
    }
}
