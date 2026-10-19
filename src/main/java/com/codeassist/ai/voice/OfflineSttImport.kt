package com.codeassist.ai.voice

/**
 * Decides which of the files the person picked in "Import files" is which piece of the model.
 * Pure Kotlin (JVM unit tested). The picked file names do not have to be exact:
 *  - a CTC model is any single *.onnx; a Whisper model is an *.onnx called encoder... plus one called decoder...
 *  - the tokens list is a *.txt (best: tokens.txt)
 * The store copies them under the fixed names it expects. Other files (README, *.weights, photos) are ignored.
 */
object OfflineSttImport {

    class Entry(val name: String, val size: Long)

    /**
     * Indexes into the picked list; null = that file was not among the picked ones.
     * [encoder] / [decoder] are set for a Whisper model, [model] for a CTC model (never both kinds at once).
     */
    class Pick(
        val model: Int?,
        val tokens: Int?,
        val ignored: Int,
        val error: String?,
        val encoder: Int? = null,
        val decoder: Int? = null
    ) {
        val whisper: Boolean get() = encoder != null || decoder != null
    }

    /** The Whisper layout names its halves encoder... and decoder... (for example encoder.int8.onnx). */
    private fun halfOf(files: List<Entry>, onnx: List<Int>, word: String): Int? {
        val hits = onnx.filter { files[it].name.lowercase().startsWith(word) }
        return when {
            hits.isEmpty() -> null
            hits.size == 1 -> hits[0]
            else -> hits.firstOrNull { files[it].name.lowercase().contains("int8") } ?: hits.maxByOrNull { files[it].size }
        }
    }

    fun classify(files: List<Entry>): Pick {
        if (files.isEmpty()) return Pick(null, null, 0, "Koi file nahi chuni.")

        val onnx = files.indices.filter { files[it].name.lowercase().endsWith(".onnx") }
        val txt = files.indices.filter { files[it].name.lowercase().endsWith(".txt") }

        val tokens: Int? = when {
            txt.isEmpty() -> null
            txt.size == 1 -> txt[0]
            else -> txt.firstOrNull { files[it].name.equals(OfflineSttUrls.TOKENS_NAME, ignoreCase = true) }
                ?: txt.firstOrNull { files[it].name.lowercase().contains("token") }
        }

        val encoder = halfOf(files, onnx, "encoder")
        val decoder = halfOf(files, onnx, "decoder")
        if (encoder != null && decoder != null) {
            // a Whisper model: both halves were picked
            val used = 2 + (if (tokens != null) 1 else 0)
            return Pick(null, tokens, files.size - used, null, encoder, decoder)
        }

        // exactly one half picked on its own: still a Whisper model (the other half may already be installed)
        val lonely = encoder ?: decoder
        if (lonely != null && onnx.size == 1) {
            val used = 1 + (if (tokens != null) 1 else 0)
            return if (encoder != null) {
                Pick(null, tokens, files.size - used, null, encoder, null)
            } else {
                Pick(null, tokens, files.size - used, null, null, decoder)
            }
        }

        val model: Int? = when {
            onnx.isEmpty() -> null
            onnx.size == 1 -> onnx[0]
            else -> onnx.firstOrNull { files[it].name.equals(OfflineSttUrls.MODEL_NAME, ignoreCase = true) }
                ?: onnx.firstOrNull { files[it].name.lowercase().contains("int8") }
                ?: onnx.maxByOrNull { files[it].size }
        }

        if (model == null && tokens == null) {
            val why = if (txt.size > 1) {
                "Kai .txt files hain, tokens wali file ka naam tokens.txt rakho."
            } else {
                "Model (.onnx) aur tokens.txt dono chuno (Whisper ke liye encoder + decoder + tokens.txt). " +
                    "Chuni hui files mein ye nahi mili."
            }
            return Pick(null, null, files.size, why)
        }

        val used = (if (model != null) 1 else 0) + (if (tokens != null) 1 else 0)
        return Pick(model, tokens, files.size - used, null)
    }
}
