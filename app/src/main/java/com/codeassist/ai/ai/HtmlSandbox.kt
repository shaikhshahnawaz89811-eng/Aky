package com.codeassist.ai.ai

/**
 * Sketch Studio, stage 3 (14 Oct 2026): web designs (HTML / CSS / JS, glassmorphism, animation) and 3D scenes (Three.js).
 * This file is the PURE half: what the model may write, how the answer is checked, and how a checked page is wrapped before it
 * is shown. The screen half is `ui/SketchWebHost` (a locked-down WebView). No Android types here, so the JVM tests cover it.
 *
 * Safety is in layers, and the first layer (this file's word list) is NOT the one to trust:
 *  1. [check] refuses an answer that mentions the usual ways out of a page (network calls, other pages, storage, eval, forms,
 *     external files) with a reason the model can fix on its retry. A word list can always be got around, so it is only
 *     a first filter and a way to give the model a clear reason.
 *  2. [wrap] puts a Content-Security-Policy `<meta>` in front of everything the model wrote: no network (`connect-src 'none'`),
 *     no frames, no forms, no external script / style / image / font ([CSP]). The page can only run its own inline code.
 *  3. `SketchWebHost` turns network loads off in the WebView, blocks every request except data: / about:blank, blocks
 *     navigation, new windows, file and content access, permission prompts and JavaScript dialogs, and adds NO JavaScript
 *     bridge object (so the page cannot call into the app at all).
 *  4. Size and time limits: [MAX_HTML] characters of page, a ping every [PING_MS] ms; a page that does not answer for [HANG_MS] ms
 *     is destroyed ([HANG_MS]); every run stops by itself after [RUN_LIMIT_MS].
 *
 * Three.js: the library file is NOT in this project (it could not be downloaded when this was written). The studio looks for
 * `assets/sketch/three.min.js` ([THREE_ASSET]); with the file there, [wrap] puts it in front of the page as a global `THREE`
 * and the 3D prompt ([systemPrompt] with `three = true`) tells the model not to load anything. Without the file the 3D option
 * says so and nothing is faked.
 */
object HtmlSandbox {
    const val MAX_HTML = 20_000

    /** Same idea as [Sketch.MAX_REVISE_JSON]: a page this long cannot be handed back to the phone model for an edit. */
    const val MAX_REVISE_HTML = 3_500

    /** What the model is told to stay under (shorter than [MAX_HTML] so that a small model finishes the page). */
    const val ASK_CHARS = 6_000

    const val THREE_ASSET = "sketch/three.min.js"
    const val THREE_MAX_BYTES = 2_000_000
    const val PING_MS = 2_000L
    const val HANG_MS = 4_000L
    const val RUN_LIMIT_MS = 10L * 60 * 1000

    /** The page may run its own inline code and show data: images / fonts. Nothing leaves the device, nothing else loads. */
    const val CSP = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; " +
        "font-src data:; media-src data:; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"

    /** Console line the WebGL probe writes; the screen reads it (the page has no other way to talk to the app). */
    const val WEBGL_PROBE_PREFIX = "SK_WEBGL:"

    /** Lower-case that keeps the length (so positions found in the lower-case copy are valid in the original). */
    private fun lc(s: String): String = String(CharArray(s.length) { s[it].lowercaseChar() })

    sealed class Checked {
        class Ok(val html: String) : Checked()
        class Bad(val reason: String) : Checked()
    }

    // ---------- reading what the model wrote ----------

    /** The HTML in [raw]: inside a ``` fence, or from <!DOCTYPE / <html to </html>, or bare tags wrapped into a page. Null if none. */
    fun extract(raw: String): String? {
        var t = raw.trim()
        if (t.isEmpty()) return null
        val fence = t.indexOf("```")
        if (fence >= 0) {
            val afterTicks = t.indexOf('\n', fence)
            val close = t.indexOf("```", fence + 3)
            if (afterTicks in 0 until close) t = t.substring(afterTicks + 1, close).trim()
        }
        val low = lc(t)
        val doc = low.indexOf("<!doctype").let { if (it >= 0) it else low.indexOf("<html") }
        if (doc >= 0) {
            val end = low.lastIndexOf("</html>")
            return if (end > doc) t.substring(doc, end + 7) else t.substring(doc)
        }
        val first = low.indexOf('<')
        if (first < 0) return null
        val tagLike = Regex("<\\s*(body|div|canvas|svg|style|script|section|main|h1|h2|p|button|span|ul|header|nav)\\b")
        if (!tagLike.containsMatchIn(low)) return null
        return t.substring(first)
    }

    /** Words that are not allowed in a page and the reason shown to the model. Matched on lower-case text. */
    private val FORBIDDEN: List<Pair<Regex, String>> = listOf(
        Regex("<\\s*script[^>]*\\ssrc\\s*=") to "do not load a script file: write all code inline in one <script> (THREE is already there in a 3D scene)",
        Regex("<\\s*link\\b") to "do not use <link>: put CSS in one <style>",
        Regex("<\\s*(iframe|frame|frameset|object|embed|applet)\\b") to "do not use <iframe>, <object> or <embed>",
        Regex("<\\s*base\\b") to "do not use <base>",
        Regex("<\\s*meta[^>]*http-equiv") to "do not use <meta http-equiv>",
        Regex("<\\s*form\\b") to "do not use <form>: use <button> and input elements with JavaScript",
        Regex("@import\\b") to "do not use @import",
        Regex("(src|href|poster|action|srcset)\\s*=\\s*[\"']?\\s*(https?:|//|ftp:|file:|content:)") to "do not point to outside files or links (no http / https / file)",
        Regex("url\\(\\s*[\"']?\\s*(https?:|//|file:|content:)") to "do not use outside files in CSS url()",
        Regex("\\bfetch\\s*\\(") to "no network: do not use fetch()",
        Regex("xmlhttprequest") to "no network: do not use XMLHttpRequest",
        Regex("\\b(websocket|eventsource|rtcpeerconnection)\\b") to "no network: do not use WebSocket / EventSource / WebRTC",
        Regex("sendbeacon") to "no network: do not use sendBeacon",
        Regex("\\b(importscripts|serviceworker)\\b|new\\s+(shared)?worker\\b") to "do not use workers",
        Regex("\\bimport\\s*\\(|^\\s*import\\s+[\\w{*]", RegexOption.MULTILINE) to "do not import modules: write plain inline JavaScript",
        Regex("\\beval\\s*\\(|new\\s+function\\s*\\(") to "do not use eval() or new Function()",
        Regex("document\\s*\\.\\s*cookie|localstorage|sessionstorage|indexeddb") to "do not use cookies or browser storage",
        Regex("window\\s*\\.\\s*open\\s*\\(|\\blocation\\s*(\\.\\s*(href|replace|assign)\\s*[=(]|=[^=])") to "do not open or change pages (window.open, location)",
        Regex("navigator\\s*\\.\\s*(geolocation|mediadevices|clipboard|sendbeacon)") to "do not use location, camera, microphone or clipboard"
    )

    private val VISIBLE = Regex("<\\s*(body|canvas|div|svg|p|h1|h2|h3|button|section|main|span|ul|style|script)\\b")

    /** Extracts and checks [raw]. [Checked.Ok.html] is the page as written (the CSP is added later by [wrap]). */
    fun check(raw: String): Checked {
        val html = extract(raw) ?: return Checked.Bad("no HTML page in the answer (start with <!DOCTYPE html>)")
        if (html.length > MAX_HTML) return Checked.Bad("the page is too long (more than $MAX_HTML characters)")
        val low = lc(html)
        for ((re, why) in FORBIDDEN) {
            if (re.containsMatchIn(low)) return Checked.Bad(why)
        }
        if (!VISIBLE.containsMatchIn(low)) return Checked.Bad("the page shows nothing (no body, div, canvas or svg)")
        return Checked.Ok(html)
    }

    /** The text of the page's <title> (one line, at most 40 characters), or null. Used to name an untitled design. */
    fun titleOf(html: String): String? {
        val m = Regex("<\\s*title[^>]*>([^<]{1,200})<", RegexOption.IGNORE_CASE).find(html) ?: return null
        val t = m.groupValues[1].replace(Regex("\\s+"), " ").trim()
        return if (t.isEmpty()) null else t.take(40).trimEnd()
    }

    /** True when the page uses the global THREE (so the library must be put in front of it). */
    fun usesThree(html: String): Boolean = Regex("\\bTHREE\\s*\\.").containsMatchIn(html)

    // ---------- wrapping a checked page ----------

    private const val VIEWPORT = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
    private const val PROBE = "(function(){try{var c=document.createElement('canvas');" +
        "var ok=!!(c.getContext('webgl')||c.getContext('experimental-webgl'));console.log('$WEBGL_PROBE_PREFIX'+(ok?'ok':'none'));}" +
        "catch(e){console.log('$WEBGL_PROBE_PREFIX'+'none');}})();"

    /** The library text made safe to sit inside a `<script>` element. */
    private fun scriptText(js: String): String = js.replace("</script", "<\\/script", ignoreCase = true)

    /**
     * The document to load: CSP and viewport first, then (for a 3D page) the library and the WebGL probe, then the model's page.
     * [threeJs] is the text of three.min.js or null. A page without <head> gets one.
     */
    fun wrap(html: String, threeJs: String?): String {
        val head = StringBuilder()
        head.append("<meta http-equiv=\"Content-Security-Policy\" content=\"").append(CSP).append("\">")
        head.append(VIEWPORT)
        if (threeJs != null) {
            head.append("<script>").append(scriptText(threeJs)).append("</script>")
            head.append("<script>").append(PROBE).append("</script>")
        }
        return inject(html, head.toString())
    }

    /**
     * The page as a file to share: the model's page with a viewport tag and, when it uses THREE and the library is there, the
     * library in front of it, so the file also works on its own in a browser. No CSP and no probe (it is not run inside the app).
     */
    fun forExport(html: String, threeJs: String?): String {
        val head = StringBuilder(VIEWPORT)
        if (threeJs != null && usesThree(html)) head.append("<script>").append(scriptText(threeJs)).append("</script>")
        return inject(html, head.toString())
    }

    /** [headText] placed first inside <head> (created when the page has none). */
    private fun inject(html: String, headText: String): String {
        val low = lc(html)
        val headOpen = Regex("<\\s*head\\b[^>]*>").find(low)
        if (headOpen != null) {
            val at = headOpen.range.last + 1
            return html.substring(0, at) + headText + html.substring(at)
        }
        val htmlOpen = Regex("<\\s*html\\b[^>]*>").find(low)
        if (htmlOpen != null) {
            val at = htmlOpen.range.last + 1
            return html.substring(0, at) + "<head>" + headText + "</head>" + html.substring(at)
        }
        val body = if (Regex("<\\s*body\\b").containsMatchIn(low)) html else "<body>$html</body>"
        return "<!DOCTYPE html><html><head>$headText</head>$body</html>"
    }

    // ---------- what the model is asked ----------

    private fun commonRules(lang: String): String {
        val text = if (lang == LocalMemory.LANG_HINGLISH) "Write visible text in simple Roman Hinglish." else "Write visible text in English."
        return "Output ONE complete HTML page and nothing else (no explanation, no code fence). Everything inline: one <style> and, if needed, " +
            "one <script>. NOT allowed: external files or links, <link>, <iframe>, <form>, fetch / XMLHttpRequest / WebSocket, " +
            "localStorage / cookies, eval, import, opening other pages. The screen is a phone about 360 px wide: use flex / grid, " +
            "percent or rem sizes, a dark colour scheme unless asked otherwise. For glass cards use a translucent background, backdrop-filter: blur " +
            "and a thin light border; for motion use CSS @keyframes or requestAnimationFrame. Keep the whole page under $ASK_CHARS characters. $text"
    }

    /** New page. With [three] the 3D rules are added. */
    fun systemPrompt(lang: String, three: Boolean): String {
        if (!three) return "You turn a design request into a web page. " + commonRules(lang)
        return "You turn a request into a 3D scene on a web page. " + commonRules(lang) +
            " THREE (Three.js, a global) is ALREADY loaded: add no <script src> and no import. Make a WebGLRenderer({antialias:true}), " +
            "append renderer.domElement to document.body, size it with window.innerWidth and window.innerHeight, use a PerspectiveCamera, " +
            "simple meshes (BoxGeometry, SphereGeometry, CylinderGeometry, TorusGeometry, PlaneGeometry) with MeshStandardMaterial or MeshBasicMaterial, " +
            "one or two lights, and animate with requestAnimationFrame. No texture files and no model files."
    }

    fun reviseSystemPrompt(lang: String, three: Boolean): String =
        systemPrompt(lang, three) + " You are now EDITING a page: the user message holds the current page and ONE change request. " +
            "Answer with the FULL updated page. Change only what was asked and keep everything else exactly as it is."

    fun userPrompt(request: String): String = "Request: " + request.trim().take(300)

    fun revisePrompt(currentHtml: String, request: String): String =
        "Current page:\n" + currentHtml + "\nChange request: " + request.trim().take(300)

    /** One line for the list screen: how big the page is and whether it needs the 3D library. */
    fun summary(html: String): String {
        if (html.isBlank()) return "khali"
        return (html.length / 100 / 10.0).toString() + " KB" + (if (usesThree(html)) " · 3D" else "")
    }
}
