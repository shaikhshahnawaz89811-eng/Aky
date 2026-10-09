package com.codeassist.ai.voice

/** Which kind of offline speech model is installed. */
enum class OfflineSttKind {
    /** One NeMo CTC file (AI4Bharat IndicConformer): model.int8.onnx + tokens.txt. */
    CTC,

    /** A Whisper model (for example the Hinglish one): encoder + decoder + tokens.txt. */
    WHISPER
}

/**
 * Turns whatever the user pasted as the "model link" into the files the offline engine needs.
 * Pure Kotlin (JVM unit tested).
 *
 * Accepted:
 *  - a Hugging Face model page / tree link:   https://huggingface.co/org/repo   (or .../tree/main/hi-hinglish-swift)
 *  - a folder link:                           https://host/path/folder
 *      * a Whisper folder holds encoder.int8.onnx + decoder.int8.onnx + tokens.txt
 *      * a CTC folder holds model.int8.onnx + tokens.txt (tokens.txt may also sit one folder up, as in
 *        the IndicConformer repo where one tokens.txt is shared by every language folder)
 *    A folder link does not say which of the two it is, so the download tries Whisper first and falls back to CTC.
 *  - a direct model file link (.onnx):        https://host/path/anything.onnx   (tokens.txt is taken from the same folder)
 *      * a file called encoder... / decoder... is a Whisper model, the other half is taken from the same folder
 */
object OfflineSttUrls {

    /**
     * [modelUrl] / [tokensUrl] describe the CTC layout. [encoderUrl] / [decoderUrl] describe the Whisper layout
     * (null when the link cannot be a Whisper model). [folder] = the link is a folder, so the type is found by trying.
     * [parentTokensUrl] = where to look for tokens.txt when the folder itself does not have one.
     */
    class Plan(
        val modelUrl: String,
        val tokensUrl: String,
        val encoderUrl: String? = null,
        val decoderUrl: String? = null,
        val parentTokensUrl: String? = null,
        val folder: Boolean = false
    )

    const val MODEL_NAME = "model.int8.onnx"
    const val ENCODER_NAME = "encoder.int8.onnx"
    const val DECODER_NAME = "decoder.int8.onnx"
    const val TOKENS_NAME = "tokens.txt"

    /** Hinglish (Hindi + English mixed) Whisper-base, int8. About 161 MB: encoder 29 MB + decoder 131 MB + tokens. */
    const val DEFAULT_URL =
        "https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/tree/main/hi-hinglish-swift"

    private val hfPage =
        Regex("https://huggingface\\.co/([^/?#]+)/([^/?#]+)(?:/(tree|blob|resolve)/([^/?#]+)(/[^?#]*)?)?")

    fun plan(input: String): Plan? {
        var url = input.trim()
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        url = url.substringBefore('#').substringBefore('?').trimEnd('/')

        val hf = hfPage.matchEntire(url)
        if (hf != null) {
            val owner = hf.groupValues[1]
            if (owner == "datasets" || owner == "spaces" || owner == "api") return null
            val repo = hf.groupValues[2]
            val rev = hf.groupValues[4].ifEmpty { "main" }
            val rest = hf.groupValues[5]
            url = "https://huggingface.co/$owner/$repo/resolve/$rev$rest"
        }

        val afterScheme = url.substringAfter("://")
        if (!afterScheme.contains('/')) return null // just a host name: nothing to download

        if (url.endsWith(".onnx", ignoreCase = true)) {
            val dir = url.substringBeforeLast('/')
            val file = url.substringAfterLast('/')
            val lower = file.lowercase()
            val parentTokens = parentTokens(dir)
            if (lower.startsWith("encoder") || lower.startsWith("decoder")) {
                val tail = file.substring(7) // "encoder" and "decoder" are both 7 letters
                return Plan(
                    modelUrl = url,
                    tokensUrl = "$dir/$TOKENS_NAME",
                    encoderUrl = "$dir/encoder$tail",
                    decoderUrl = "$dir/decoder$tail",
                    parentTokensUrl = parentTokens,
                    folder = false
                )
            }
            return Plan(url, "$dir/$TOKENS_NAME", parentTokensUrl = parentTokens)
        }

        return Plan(
            modelUrl = "$url/$MODEL_NAME",
            tokensUrl = "$url/$TOKENS_NAME",
            encoderUrl = "$url/$ENCODER_NAME",
            decoderUrl = "$url/$DECODER_NAME",
            parentTokensUrl = parentTokens(url),
            folder = true
        )
    }

    private val hfRepoTop = Regex("https://huggingface\\.co/[^/]+/[^/]+/resolve/[^/]+")

    /** tokens.txt one folder above [folder], or null when there is no folder above it (host or top of a HF repo). */
    private fun parentTokens(folder: String): String? {
        if (hfRepoTop.matches(folder)) return null
        val parent = folder.substringBeforeLast('/')
        return if (parent.substringAfter("://").contains('/')) "$parent/$TOKENS_NAME" else null
    }

    /** Short text for the settings row, e.g. "huggingface.co/…/hi-hinglish-swift". */
    fun short(input: String, max: Int = 34): String {
        val t = input.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        if (t.isEmpty()) return ""
        if (t.length <= max) return t
        val host = t.substringBefore('/')
        val last = t.substringAfterLast('/')
        val s = "$host/…/$last"
        return if (s.length <= max) s else "…" + t.takeLast(max - 1)
    }
}
