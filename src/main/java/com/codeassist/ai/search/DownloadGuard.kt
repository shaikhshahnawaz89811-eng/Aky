package com.codeassist.ai.search

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** What a HEAD (or a one-byte ranged GET) answered. Header names are lower case; only the first value of a header is kept. */
class HeadReply(val code: Int, val headers: Map<String, String>) {
    fun header(name: String): String? = headers[name.lowercase()]
}

/** The network part, injectable so the tests run with canned replies. Must NOT follow redirects. Throws IOException on network trouble. */
typealias HeadTransport = (url: String, timeoutMs: Int) -> HeadReply

enum class RejectReason {
    NOT_HTTPS, BAD_HOST, TOO_MANY_REDIRECTS, OFF_SITE_REDIRECT, BAD_REDIRECT, HTTP_ERROR, NOT_A_FILE, TYPE_MISMATCH,
    TYPE_UNKNOWN, NO_EXTENSION, EMPTY_FILE, UNVERIFIED
}

/** What was found out about a file address. [finalUrl] is where the redirects ended; [hops] lists every address visited, first to last. */
class FileFacts(
    val finalUrl: String,
    val hops: List<String>,
    val contentType: String?,
    val sizeBytes: Long?,
    val extension: String
)

sealed class GuardVerdict {
    class Pass(val facts: FileFacts) : GuardVerdict()
    class Reject(val reason: RejectReason, val detail: String) : GuardVerdict()
}

/**
 * Net Search Agent, stage S3: the checks before the app shows a Download button for an address it did not get from a trusted API
 * (audit section 3, row 5, and section 7 "Unsafe downloads"). Pure JVM; the HEAD call is injected.
 *
 * What it checks: https only; a real host name (no IP address, no `user@host` trick, no odd port, no `localhost` / `.local`, no
 * punycode name); at most [MAX_REDIRECTS] redirects and each of them stays on the same site (same registrable domain, or the same
 * trusted family: GitHub and its download hosts, Hugging Face and its CDN); the final answer is a file (not an HTML page), the
 * Content-Type fits the file ending, the size is not zero. What it does NOT do: say a file is safe. The words are always "checked:
 * ...", never "safe" (audit section 7).
 */
object DownloadGuard {
    const val MAX_REDIRECTS = 3
    const val HOP_TIMEOUT_MS = 6_000

    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

    private val GENERIC_TYPES = setOf(
        "application/octet-stream", "binary/octet-stream", "application/x-binary", "application/binary",
        "application/force-download", "application/x-download", "application/download", "application/unknown"
    )

    private val SPECIFIC_TYPES: Map<String, Set<String>> = mapOf(
        "apk" to setOf("application/vnd.android.package-archive", "application/zip", "application/java-archive"),
        "aab" to setOf("application/zip", "application/x-authorware-bin"),
        "zip" to setOf("application/zip", "application/x-zip-compressed", "application/x-zip", "multipart/x-zip"),
        "7z" to setOf("application/x-7z-compressed"),
        "tar.gz" to setOf("application/gzip", "application/x-gzip", "application/x-tar", "application/x-compressed-tar"),
        "tgz" to setOf("application/gzip", "application/x-gzip", "application/x-tar", "application/x-compressed-tar"),
        "gz" to setOf("application/gzip", "application/x-gzip"),
        "xz" to setOf("application/x-xz"),
        "tar" to setOf("application/x-tar"),
        "exe" to setOf("application/x-msdownload", "application/vnd.microsoft.portable-executable", "application/x-dosexec", "application/x-msdos-program"),
        "msi" to setOf("application/x-msi", "application/x-ole-storage"),
        "dmg" to setOf("application/x-apple-diskimage"),
        "pkg" to setOf("application/x-newton-compatible-pkg", "application/vnd.apple.installer+xml"),
        "deb" to setOf("application/vnd.debian.binary-package", "application/x-debian-package", "application/x-deb"),
        "rpm" to setOf("application/x-rpm"),
        "appimage" to setOf("application/x-iso9660-appimage"),
        "jar" to setOf("application/java-archive", "application/x-java-archive", "application/zip"),
        "iso" to setOf("application/x-iso9660-image")
    )

    private val GITHUB_FAMILY = setOf("github.com", "githubusercontent.com")
    private val HUGGINGFACE_FAMILY = setOf("huggingface.co", "hf.co")

    private val LOCAL_ENDINGS = listOf(".local", ".localhost", ".internal", ".lan", ".home", ".corp", ".intranet", ".test", ".invalid")

    // ---------- the address rules ----------

    /** Null when [url] passes the address rules, else why not. No network. */
    fun checkUrl(url: String): GuardVerdict.Reject? {
        val uri = try {
            URI(url.trim())
        } catch (_: Exception) {
            return GuardVerdict.Reject(RejectReason.BAD_HOST, "not an address")
        }
        if (uri.scheme?.lowercase() != "https") return GuardVerdict.Reject(RejectReason.NOT_HTTPS, "https only")
        val host = uri.host?.lowercase() ?: return GuardVerdict.Reject(RejectReason.BAD_HOST, "no host")
        if (uri.rawUserInfo != null) return GuardVerdict.Reject(RejectReason.BAD_HOST, "user info in address")
        if (uri.port != -1 && uri.port != 443) return GuardVerdict.Reject(RejectReason.BAD_HOST, "odd port")
        if (UrlTools.isIpLiteral(host)) return GuardVerdict.Reject(RejectReason.BAD_HOST, "ip address")
        if (host == "localhost" || !host.contains('.')) return GuardVerdict.Reject(RejectReason.BAD_HOST, "not a public name")
        if (LOCAL_ENDINGS.any { host.endsWith(it) }) return GuardVerdict.Reject(RejectReason.BAD_HOST, "local name")
        if (host.split('.').any { it.startsWith("xn--") }) return GuardVerdict.Reject(RejectReason.BAD_HOST, "punycode name")
        if (host.any { it.code > 127 }) return GuardVerdict.Reject(RejectReason.BAD_HOST, "non-ascii name")
        if (url.length > 2000) return GuardVerdict.Reject(RejectReason.BAD_HOST, "address too long")
        return null
    }

    /** Same registrable domain, or both hosts in one trusted family. A redirect to anything else is refused. */
    fun sameSite(a: String, b: String): Boolean {
        val x = UrlTools.registrableDomain(UrlTools.host(a))
        val y = UrlTools.registrableDomain(UrlTools.host(b))
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        if (x in GITHUB_FAMILY && y in GITHUB_FAMILY) return true
        if (x in HUGGINGFACE_FAMILY && y in HUGGINGFACE_FAMILY) return true
        return false
    }

    private fun resolveRedirect(from: String, location: String): String? {
        return try {
            URI(from).resolve(location.trim()).toString()
        } catch (_: Exception) {
            null
        }
    }

    // ---------- content type, size ----------

    /** `application/zip; charset=binary` -> `application/zip`; null when the header is missing or empty. */
    fun mediaType(header: String?): String? {
        val t = header?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return if (t.isEmpty()) null else t
    }

    /** True when [contentType] can be the file type [ext]: a generic binary type always, a specific type when it is in the list of [ext]. */
    fun typeFits(ext: String, contentType: String): Boolean {
        if (contentType in GENERIC_TYPES) return true
        val specific = SPECIFIC_TYPES[ext]
        if (specific != null) return contentType in specific
        // model files and other plain binaries carry no registered type: only a generic one fits
        return false
    }

    /** `bytes 0-0/12345` -> 12345; null for `*` or anything else. */
    fun totalFromContentRange(h: String?): Long? {
        val total = h?.trim()?.substringAfter('/', "")?.trim().orEmpty()
        return total.toLongOrNull()?.takeIf { it > 0 }
    }

    private val dispositionName = Regex("(?i)filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?")

    private fun nameFromDisposition(h: String?): String? {
        if (h == null) return null
        val m = dispositionName.find(h) ?: return null
        return m.groupValues[1].trim().takeIf { it.isNotEmpty() }
    }

    // ---------- the check ----------

    /**
     * Follows the redirects of [url] with [head] (at most [MAX_REDIRECTS], each on the same site) and judges the final answer.
     * [wantedExt] is the file type the user asked for ("apk"); when it is given and the file has another ending the file is refused.
     */
    fun check(url: String, wantedExt: List<String>, head: HeadTransport): GuardVerdict {
        var current = url.trim()
        val hops = ArrayList<String>()
        var redirects = 0
        while (true) {
            checkUrl(current)?.let { return it }
            if (hops.isNotEmpty() && !sameSite(hops[0], current)) {
                return GuardVerdict.Reject(RejectReason.OFF_SITE_REDIRECT, "redirect left the site")
            }
            hops.add(current)
            val reply = try {
                head(current, HOP_TIMEOUT_MS)
            } catch (_: IOException) {
                return GuardVerdict.Reject(RejectReason.UNVERIFIED, "could not reach the file")
            }
            if (reply.code in REDIRECT_CODES) {
                if (redirects >= MAX_REDIRECTS) return GuardVerdict.Reject(RejectReason.TOO_MANY_REDIRECTS, "more than $MAX_REDIRECTS redirects")
                val loc = reply.header("location") ?: return GuardVerdict.Reject(RejectReason.BAD_REDIRECT, "redirect without a place")
                current = resolveRedirect(current, loc) ?: return GuardVerdict.Reject(RejectReason.BAD_REDIRECT, "bad redirect place")
                redirects++
                continue
            }
            return judge(url, current, hops, reply, wantedExt)
        }
    }

    private fun judge(first: String, last: String, hops: List<String>, reply: HeadReply, wantedExt: List<String>): GuardVerdict {
        if (reply.code == 403 || reply.code == 405 || reply.code == 429 || reply.code == 501) {
            return GuardVerdict.Reject(RejectReason.UNVERIFIED, "the site does not answer a check (HTTP ${reply.code})")
        }
        if (reply.code != 200 && reply.code != 206) return GuardVerdict.Reject(RejectReason.HTTP_ERROR, "HTTP ${reply.code}")
        val type = mediaType(reply.header("content-type"))
        if (type != null && (type.startsWith("text/") || type == "application/xhtml+xml" || type == "application/json")) {
            return GuardVerdict.Reject(RejectReason.NOT_A_FILE, "that address is a web page, not a file")
        }
        val ext = LinkResolver.extensionOf(first)
            ?: LinkResolver.extensionOf(last)
            ?: nameFromDisposition(reply.header("content-disposition"))?.let { LinkResolver.extensionOf(it) }
            ?: return GuardVerdict.Reject(RejectReason.NO_EXTENSION, "no known file ending")
        if (wantedExt.isNotEmpty() && ext !in wantedExt) {
            return GuardVerdict.Reject(RejectReason.TYPE_MISMATCH, "the file is .$ext, not what was asked")
        }
        if (type == null) return GuardVerdict.Reject(RejectReason.TYPE_UNKNOWN, "the site did not state a file type")
        if (!typeFits(ext, type)) return GuardVerdict.Reject(RejectReason.TYPE_MISMATCH, "type $type does not fit .$ext")
        val size = if (reply.code == 206) totalFromContentRange(reply.header("content-range"))
        else reply.header("content-length")?.trim()?.toLongOrNull()
        if (size != null && size <= 0L) return GuardVerdict.Reject(RejectReason.EMPTY_FILE, "the file is empty")
        return GuardVerdict.Pass(FileFacts(last, hops, type, size?.takeIf { it > 0 }, ext))
    }

    // ---------- the real transport ----------

    /**
     * HttpURLConnection, never following a redirect. HEAD first; when a site refuses HEAD (403 / 405 / 501) one ranged GET for the first
     * byte is tried instead, and its answer is returned (206 with `content-range` gives the total size). The body is never read.
     */
    val realHead: HeadTransport = { url, timeoutMs ->
        val first = headOnce(url, timeoutMs, "HEAD")
        if (first.code == 403 || first.code == 405 || first.code == 501) headOnce(url, timeoutMs, "GET") else first
    }

    private fun headOnce(url: String, timeoutMs: Int, method: String): HeadReply {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.requestMethod = method
            c.setRequestProperty("User-Agent", "CodeAssistAI")
            if (method == "GET") c.setRequestProperty("Range", "bytes=0-0")
            val code = c.responseCode
            val headers = HashMap<String, String>()
            for ((k, v) in c.headerFields) {
                if (k == null || v.isNullOrEmpty()) continue
                headers[k.lowercase()] = v[0]
            }
            return HeadReply(code, headers)
        } finally {
            c.disconnect()
        }
    }
}
