package com.codeassist.ai.voice

/** Wraps raw PCM16 mono audio in a 44-byte WAV header so MediaPlayer can play a cached phrase. Pure Kotlin (JVM unit tested). */
object WavHeader {
    fun wrap(pcm: ByteArray, sampleRate: Int): ByteArray {
        val out = ByteArray(44 + pcm.size)
        fun put4(at: Int, v: Int) {
            out[at] = (v and 0xFF).toByte()
            out[at + 1] = ((v shr 8) and 0xFF).toByte()
            out[at + 2] = ((v shr 16) and 0xFF).toByte()
            out[at + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun put2(at: Int, v: Int) {
            out[at] = (v and 0xFF).toByte()
            out[at + 1] = ((v shr 8) and 0xFF).toByte()
        }
        "RIFF".forEachIndexed { i, c -> out[i] = c.code.toByte() }
        put4(4, 36 + pcm.size)
        "WAVE".forEachIndexed { i, c -> out[8 + i] = c.code.toByte() }
        "fmt ".forEachIndexed { i, c -> out[12 + i] = c.code.toByte() }
        put4(16, 16) // fmt chunk size
        put2(20, 1) // PCM
        put2(22, 1) // mono
        put4(24, sampleRate)
        put4(28, sampleRate * 2) // byte rate: mono, 16 bit
        put2(32, 2) // block align
        put2(34, 16) // bits per sample
        "data".forEachIndexed { i, c -> out[36 + i] = c.code.toByte() }
        put4(40, pcm.size)
        System.arraycopy(pcm, 0, out, 44, pcm.size)
        return out
    }
}
