package com.codeassist.ai.voice

/**
 * Decides which of the files the person picked in "Import files" is the model and which is the tokens list.
 * Pure Kotlin (JVM unit tested). The picked file names do not have to be exact: any *.onnx is the model
 * and a *.txt (best: tokens.txt) is the tokens list. The store copies them under the fixed names it expects.
 */
object OfflineSttImport {

    class Entry(val name: String, val size: Long)

    /** [model] and [tokens] are indexes into the picked list; null = that file was not among the picked ones. */
    class Pick(val model: Int?, val tokens: Int?, val ignored: Int, val error: String?)

    fun classify(files: List<Entry>): Pick {
        if (files.isEmpty()) return Pick(null, null, 0, "Koi file nahi chuni.")

        val onnx = files.indices.filter { files[it].name.lowercase().endsWith(".onnx") }
        val txt = files.indices.filter { files[it].name.lowercase().endsWith(".txt") }

        val model: Int? = when {
            onnx.isEmpty() -> null
            onnx.size == 1 -> onnx[0]
            else -> onnx.firstOrNull { files[it].name.equals(OfflineSttUrls.MODEL_NAME, ignoreCase = true) }
                ?: onnx.firstOrNull { files[it].name.lowercase().contains("int8") }
                ?: onnx.maxByOrNull { files[it].size }
        }

        val tokens: Int? = when {
            txt.isEmpty() -> null
            txt.size == 1 -> txt[0]
            else -> txt.firstOrNull { files[it].name.equals(OfflineSttUrls.TOKENS_NAME, ignoreCase = true) }
                ?: txt.firstOrNull { files[it].name.lowercase().contains("token") }
        }

        if (model == null && tokens == null) {
            val why = if (txt.size > 1) {
                "Kai .txt files hain, tokens wali file ka naam tokens.txt rakho."
            } else {
                "Model (.onnx) aur tokens.txt dono chuno. Chuni hui files mein ye nahi mili."
            }
            return Pick(null, null, files.size, why)
        }

        val used = (if (model != null) 1 else 0) + (if (tokens != null) 1 else 0)
        return Pick(model, tokens, files.size - used, null)
    }
}
