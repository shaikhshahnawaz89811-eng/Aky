package com.codeassist.ai.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.codeassist.ai.ai.HtmlSandbox
import java.io.ByteArrayInputStream

/**
 * Sketch Studio, stage 3: shows a web design (HTML / CSS / JS, or a Three.js scene) inside a locked-down WebView.
 * The rules are in [HtmlSandbox]; this class is the part that needs a device:
 *  - JavaScript is ON (the page needs it) but the page gets NO bridge object: there is no addJavascriptInterface anywhere;
 *  - network loads are blocked ([WebSettings.setBlockNetworkLoads]) and [WebViewClient.shouldInterceptRequest] answers every
 *    request except data: / blob: / about:blank with an empty 403; navigation, new windows, file and content access, DOM storage,
 *    geolocation, permission prompts and JavaScript dialogs are all refused;
 *  - the page is loaded with a null base URL (no origin of its own that means anything) and a CSP meta tag in front ([HtmlSandbox.wrap]);
 *  - time: a ping (`evaluateJavascript("1")`) every [HtmlSandbox.PING_MS] ms; with no answer for [HtmlSandbox.HANG_MS] ms the WebView is
 *    destroyed (an endless loop in the page); every run also ends by itself after [HtmlSandbox.RUN_LIMIT_MS];
 *  - the page console is read for one line only, the WebGL probe ([HtmlSandbox.WEBGL_PROBE_PREFIX]).
 * Never compiled or run (BUILD_NOTES, "Phone check for stage 3").
 */
class SketchWebHost @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    /** Called with a short Hinglish line when the page was stopped by a limit or by Android. */
    var onNote: ((String) -> Unit)? = null

    /** Called when the page reports whether WebGL works (3D pages only). */
    var onWebGl: ((Boolean) -> Unit)? = null

    private var web: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var awaiting = false
    private var pingAt = 0L
    private var startedAt = 0L

    val isRunning: Boolean get() = web != null

    private val tick = object : Runnable {
        override fun run() {
            val w = web ?: return
            val now = SystemClock.uptimeMillis()
            if (now - startedAt > HtmlSandbox.RUN_LIMIT_MS) {
                stop()
                onNote?.invoke("10 minute ho gaye, page apne aap band ho gaya. Phir se Run dabao.")
                return
            }
            if (awaiting) {
                if (now - pingAt > HtmlSandbox.HANG_MS) {
                    stop()
                    onNote?.invoke("Page atak gaya (shayad bina ruke chalne wala loop). Page band kar diya.")
                    return
                }
            } else {
                awaiting = true
                pingAt = now
                w.evaluateJavascript("1") { awaiting = false }
            }
            handler.postDelayed(this, HtmlSandbox.PING_MS)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun build(): WebView {
        val w = WebView(context)
        val st = w.settings
        st.javaScriptEnabled = true
        st.domStorageEnabled = false
        st.allowFileAccess = false
        st.allowContentAccess = false
        st.blockNetworkLoads = true
        st.javaScriptCanOpenWindowsAutomatically = false
        st.setSupportMultipleWindows(false)
        st.mediaPlaybackRequiresUserGesture = true
        st.cacheMode = WebSettings.LOAD_NO_CACHE
        st.setGeolocationEnabled(false)
        w.setBackgroundColor(Color.parseColor("#151D28"))
        // the page scrolls / gestures inside itself, the studio's own ScrollView must not take them
        w.setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url.toString()
                if (u.startsWith("data:") || u.startsWith("blob:") || u == "about:blank") return null
                return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                handler.post {
                    stop()
                    onNote?.invoke("Page ka process band ho gaya (shayad memory kam padi). Page band kar diya.")
                }
                return true
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val t = consoleMessage.message()
                if (t.startsWith(HtmlSandbox.WEBGL_PROBE_PREFIX)) onWebGl?.invoke(t.endsWith("ok"))
                return true
            }

            override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                result.cancel()
                return true
            }

            override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                result.cancel()
                return true
            }

            override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
                result.cancel()
                return true
            }

            override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                result.cancel()
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, false, false)
            }
        }
        return w
    }

    /** Runs [document] (the output of [HtmlSandbox.wrap]); a page that is already running is stopped first. */
    fun show(document: String) {
        stop()
        val w = build()
        web = w
        addView(w, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        startedAt = SystemClock.uptimeMillis()
        awaiting = false
        w.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
        handler.postDelayed(tick, HtmlSandbox.PING_MS)
    }

    /** Destroys the WebView (the page stops at once, also an endless loop). Safe to call when nothing runs. */
    fun stop() {
        handler.removeCallbacks(tick)
        val w = web ?: return
        web = null
        awaiting = false
        removeView(w)
        w.stopLoading()
        w.destroy()
    }

    /** Screen is leaving: freeze the page and the watchdog (no ping would be answered while paused). */
    fun pause() {
        handler.removeCallbacks(tick)
        web?.onPause()
        web?.pauseTimers()
    }

    fun resume() {
        val w = web ?: return
        w.onResume()
        w.resumeTimers()
        awaiting = false
        startedAt = SystemClock.uptimeMillis()
        handler.postDelayed(tick, HtmlSandbox.PING_MS)
    }
}

/** The optional Three.js library file: `assets/sketch/three.min.js` (not part of this project, see assets/sketch/README.txt). */
object ThreeLib {
    fun available(ctx: Context): Boolean =
        try {
            ctx.assets.list("sketch")?.contains("three.min.js") == true
        } catch (e: Exception) {
            false
        }

    /** The library text, or null when the file is missing, empty or bigger than [HtmlSandbox.THREE_MAX_BYTES]. */
    fun load(ctx: Context): String? =
        try {
            ctx.assets.open(HtmlSandbox.THREE_ASSET).use { input ->
                val bytes = input.readBytes()
                if (bytes.isEmpty() || bytes.size > HtmlSandbox.THREE_MAX_BYTES) null else String(bytes, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }
}
