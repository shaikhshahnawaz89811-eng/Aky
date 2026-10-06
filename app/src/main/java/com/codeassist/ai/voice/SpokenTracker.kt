package com.codeassist.ai.voice

/**
 * Remembers how far the assistant got while speaking (audit PDF Sec 9.1 "spoken_up_to"), so an
 * interrupted answer can be resumed ("aage batao") from the sentence that was cut. Platform TTS only
 * reports progress through UtteranceProgressListener.onRangeStart, and some engines send none, so the
 * start of the current chunk is the fallback. Pure Kotlin (JVM unit tested).
 */
class SpokenTracker {
    private var text: String = ""
    private var offsets: IntArray = IntArray(0)
    private var spoken: Int = 0

    fun reset(clean: String, chunks: List<String>) {
        text = clean
        offsets = IntArray(chunks.size)
        var from = 0
        for (i in chunks.indices) {
            val at = clean.indexOf(chunks[i], from)
            offsets[i] = if (at >= 0) at else from
            from = offsets[i] + chunks[i].length
        }
        spoken = 0
    }

    fun onChunkStart(index: Int) {
        if (index in offsets.indices && offsets[index] > spoken) spoken = offsets[index]
    }

    /** [endInChunk] is the `end` value of onRangeStart: a character index inside chunk [index]. */
    fun onRange(index: Int, endInChunk: Int) {
        if (index !in offsets.indices) return
        val abs = (offsets[index] + endInChunk).coerceIn(0, text.length)
        if (abs > spoken) spoken = abs
    }

    fun spokenUpTo(): Int = spoken

    /** Text from the start of the sentence that was being spoken, or null when (almost) all was said. */
    fun remainingFromSentence(): String? {
        if (text.isEmpty()) return null
        val head = text.substring(0, spoken.coerceIn(0, text.length))
        var cut = 0
        for (i in head.indices.reversed()) {
            val c = head[i]
            if ((c == '.' || c == '!' || c == '?' || c == '\u0964') && i + 1 < head.length) {
                cut = i + 1
                break
            }
            if ((c == '.' || c == '!' || c == '?' || c == '\u0964') && i + 1 == head.length) {
                // spoken text ended exactly on a sentence end: resume from the next sentence
                cut = head.length
                break
            }
        }
        val rest = text.substring(cut).trim()
        if (rest.length < 12) return null
        return rest
    }
}
