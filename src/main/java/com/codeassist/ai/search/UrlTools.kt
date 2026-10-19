package com.codeassist.ai.search

import java.net.URI

/**
 * Small URL helpers shared by [EvidenceBook] (dedupe, "independent domains") and, later, the link resolver. Pure JVM.
 *
 * [registrableDomain] is a heuristic: it knows a short list of two-part suffixes (co.uk, com.au, co.in ...), not the whole
 * Public Suffix List. It is only used to count "independent sources", never for a security decision.
 */
object UrlTools {
    private val trackingParams = setOf(
        "fbclid", "gclid", "msclkid", "mc_cid", "mc_eid", "igshid", "yclid", "ref", "ref_src", "_hsenc", "_hsmi"
    )

    private val twoPartSuffixes = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "org.au", "edu.au",
        "co.in", "org.in", "net.in", "gov.in", "ac.in", "edu.in", "co.jp", "ne.jp", "or.jp",
        "com.br", "com.cn", "com.mx", "com.tr", "co.nz", "co.za", "com.sg", "com.hk", "co.kr"
    )

    private val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    private fun parse(url: String): URI? {
        return try {
            val u = URI(url.trim())
            val s = u.scheme?.lowercase()
            if ((s == "http" || s == "https") && !u.host.isNullOrEmpty()) u else null
        } catch (_: Exception) {
            null
        }
    }

    /** True for http(s) URLs with a host. */
    fun isWebUrl(url: String): Boolean = parse(url) != null

    fun isHttps(url: String): Boolean = parse(url)?.scheme?.lowercase() == "https"

    /** Host in lower case without a leading "www."; empty when [url] is not an http(s) URL. */
    fun host(url: String): String {
        val u = parse(url) ?: return ""
        return u.host.lowercase().removePrefix("www.")
    }

    fun isIpLiteral(host: String): Boolean = host.contains(':') || host.startsWith("[") || ipv4.matches(host)

    /** "docs.example.co.uk" -> "example.co.uk"; "a.b.example.com" -> "example.com"; an IP stays as it is. */
    fun registrableDomain(host: String): String {
        val h = host.lowercase().trim('.')
        if (h.isEmpty()) return ""
        if (isIpLiteral(h)) return h
        val parts = h.split('.')
        if (parts.size <= 2) return h
        val last2 = parts.takeLast(2).joinToString(".")
        return if (last2 in twoPartSuffixes) parts.takeLast(3).joinToString(".") else last2
    }

    fun sameSite(a: String, b: String): Boolean {
        val x = registrableDomain(host(a))
        val y = registrableDomain(host(b))
        return x.isNotEmpty() && x == y
    }

    /**
     * Same page, same text: scheme collapsed to https, host without "www.", default port dropped, no fragment, no trailing slash,
     * no tracking parameters (utm_*, fbclid, gclid ...). A text that is no http(s) URL comes back trimmed and unchanged.
     */
    fun normalize(url: String): String {
        val u = parse(url) ?: return url.trim()
        val host = u.host.lowercase().removePrefix("www.")
        val scheme = u.scheme.lowercase()
        val port = u.port
        val portText = if (port > 0 && !(scheme == "http" && port == 80) && !(scheme == "https" && port == 443)) ":$port" else ""
        var path = u.rawPath ?: ""
        while (path.length > 1 && path.endsWith("/")) path = path.substring(0, path.length - 1)
        if (path == "/") path = ""
        val q = keptQuery(u.rawQuery)
        return "https://" + host + portText + path + (if (q.isEmpty()) "" else "?$q")
    }

    private fun keptQuery(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val kept = ArrayList<String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val key = pair.substringBefore('=').lowercase()
            if (key.startsWith("utm_") || key in trackingParams) continue
            kept.add(pair)
        }
        return kept.joinToString("&")
    }
}
