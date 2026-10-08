package com.codeassist.ai.voice

/**
 * Turns whatever the user pasted as the "model link" into the two files the offline engine needs.
 * Pure Kotlin (JVM unit tested).
 *
 * Accepted:
 *  - a Hugging Face model page / tree link:   https://huggingface.co/org/repo   (or .../tree/main/hi)
 *  - a folder link:                           https://host/path/folder          (needs model.int8.onnx + tokens.txt inside)
 *  - a direct model file link (.onnx):        https://host/path/anything.onnx   (tokens.txt is taken from the same folder)
 */
object OfflineSttUrls {

    class Plan(val modelUrl: String, val tokensUrl: String)

    const val MODEL_NAME = "model.int8.onnx"
    const val TOKENS_NAME = "tokens.txt"

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

        return if (url.endsWith(".onnx", ignoreCase = true)) {
            Plan(url, url.substringBeforeLast('/') + "/" + TOKENS_NAME)
        } else {
            Plan("$url/$MODEL_NAME", "$url/$TOKENS_NAME")
        }
    }

    /** Short text for the settings row, e.g. "huggingface.co/…/betterflow-indicconformer-ctc". */
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
