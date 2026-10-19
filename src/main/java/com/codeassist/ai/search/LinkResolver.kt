package com.codeassist.ai.search

import java.net.URI
import java.net.URLDecoder

/** What kind of page a search result is (audit section 3, rows 3 and 4: "release page / vendor / store / mirror"). */
enum class LinkClass { GITHUB_RELEASE, GITHUB_REPO, HUGGINGFACE, VENDOR, STORE, MIRROR, UNKNOWN }

/**
 * How far a page is trusted. The words are the ones shown to the user. "Verified" is never printed (audit section 7): the best
 * word is [LIKELY_OFFICIAL], and it needs the domain to carry the product name AND two search results on that domain.
 */
enum class Trust { LIKELY_OFFICIAL, KNOWN_HOST, NOT_CONFIRMED, LOW }

/** One search result, classified. [results] = how many distinct result pages the search gave on the same registrable domain. */
class Candidate(
    val url: String,
    val title: String,
    val host: String,
    val registrable: String,
    val cls: LinkClass,
    val trust: Trust,
    /** Why a [Trust.LOW] page is low: "third-party mirror", "look-alike name" or "unusual domain". Empty otherwise. */
    val reason: String,
    val score: Double,
    val results: Int,
    val order: Int
)

class Ranked(val primary: Candidate?, val alternates: List<Candidate>) {
    val all: List<Candidate> get() = if (primary == null) alternates else listOf(primary) + alternates
}

/**
 * Net Search Agent, stage S3: turns raw search results into "which link is what". Pure JVM, no network, no model: the only
 * data it reads are the URLs, titles and scores of the search results and the words the user wrote.
 *
 * The one hard rule: a page is "likely official" only when the registrable domain is the product name (or the name plus a short
 * word such as `ai`, `app`, `dev`), the ending is not a known abuse-heavy one, it is not a user-content host (`github.io`,
 * `vercel.app` ...), and the search gave at least two result pages on that domain. Everything else stays "not confirmed",
 * and mirror sites and look-alike names are "low trust". The word lists below are a heuristic, not a security product.
 */
object LinkResolver {
    const val MAX_ALTERNATES = 2
    const val MAX_FILE_LINKS = 5
    private const val MIN_TOKEN = 3

    /** Words that name no product: dropped when the product name is taken out of the user's words. */
    private val GENERIC = setOf(
        "official", "site", "website", "web", "homepage", "page", "link", "links", "url", "download", "downloads",
        "downloading", "apk", "app", "apps", "android", "ios", "windows", "mac", "linux", "free", "latest", "newest",
        "new", "version", "release", "releases", "file", "files", "direct", "install", "installer", "setup", "for",
        "the", "and", "with", "from", "how", "where", "what", "which", "kahan", "kaha", "kahaan", "milega", "mil",
        "hai", "hain", "karo", "kar", "do", "dena", "batao", "bata", "dikhao", "mujhe", "please", "pls", "github", "repo",
        "repository", "source", "code", "model", "models", "zip", "gguf", "pdf", "open", "get", "give", "find", "search"
    )

    private val tokenSplitter = Regex("[^\\p{L}\\p{N}]+")

    /** The product name words of a message: lower case, 3+ letters, no generic word, not a number. */
    fun entityTokens(query: String): List<String> {
        val out = LinkedHashSet<String>()
        for (t in query.lowercase().split(tokenSplitter)) {
            if (t.length < MIN_TOKEN || t in GENERIC || t.all { it.isDigit() }) continue
            out.add(t)
        }
        return out.toList()
    }

    // ---------- word lists ----------

    private val AFFIXES = setOf("ai", "app", "apps", "dev", "hq", "labs", "lab", "io", "official", "inc", "hub", "tech", "software")

    private val BAD_WORDS = setOf(
        "download", "downloads", "free", "crack", "cracked", "mod", "modded", "apk", "apks", "setup", "installer", "full",
        "premium", "pro", "hack", "unlock", "keygen", "patch", "torrent", "portable", "latest", "online"
    )

    /** Endings that are mostly used by throw-away and abusive sites. A heuristic: it only ever lowers trust. */
    private val SUSPICIOUS_TLDS = setOf(
        "zip", "mov", "xyz", "top", "click", "loan", "work", "gq", "tk", "ml", "cf", "ga", "icu", "rest", "buzz", "cyou",
        "monster", "cam", "sbs", "bond", "cfd", "men", "country", "stream", "download", "review", "racing", "win",
        "party", "date", "faith", "accountant", "science", "trade", "webcam"
    )

    /** Hosts where anybody can publish a page under a name they choose: the name proves nothing. */
    private val HOSTING = setOf(
        "github.io", "gitlab.io", "vercel.app", "netlify.app", "pages.dev", "workers.dev", "herokuapp.com", "blogspot.com",
        "wordpress.com", "medium.com", "wixsite.com", "weebly.com", "notion.site", "web.app", "firebaseapp.com", "glitch.me",
        "repl.co", "replit.app", "onrender.com", "fly.dev", "surge.sh", "000webhostapp.com", "carrd.co", "substack.com"
    )

    /** Third-party download portals: they re-host other people's files. */
    private val MIRRORS = setOf(
        "apkpure.com", "apkpure.net", "apkmirror.com", "uptodown.com", "softpedia.com", "filehippo.com", "softonic.com",
        "cnet.com", "download.com", "sourceforge.net", "aptoide.com", "apkcombo.com", "apkmody.io", "happymod.com", "an1.com",
        "rexdl.com", "getintopc.com", "filecr.com", "malavida.com", "majorgeeks.com", "filepuma.com", "apkhome.net",
        "apk-dl.com", "apksfull.com", "modyolo.com", "liteapks.com", "apkdone.com", "mobilism.org", "chip.de",
        "downloadly.ir", "cracksnow.com", "oceanofgames.com", "igg-games.com", "fileplanet.com", "tucows.com"
    )

    private val STORES = setOf(
        "play.google.com", "apps.apple.com", "f-droid.org", "apps.microsoft.com", "galaxystore.samsung.com",
        "appgallery.huawei.com", "chromewebstore.google.com", "addons.mozilla.org", "marketplace.visualstudio.com"
    )

    private val NON_REPO_FIRST = setOf(
        "topics", "search", "orgs", "marketplace", "features", "settings", "login", "signup", "sponsors", "about", "pricing",
        "collections", "trending", "explore", "apps", "users", "enterprise", "readme", "site", "security", "notifications",
        "issues", "pulls", "codespaces", "new", "join", "organizations", "customer-stories", "events", "resources",
        "solutions", "why-github", "contact", "stars", "watching", "discussions"
    )

    private val NOT_A_REPO = setOf(
        "and/or", "on/off", "yes/no", "he/she", "his/her", "him/her", "tcp/ip", "input/output", "read/write", "client/server",
        "front/back", "pass/fail", "true/false", "this/that", "either/or", "start/stop", "up/down", "in/out", "buy/sell",
        "android/ios", "ios/android", "windows/linux", "linux/windows", "mac/windows", "windows/mac", "ui/ux", "ux/ui"
    )

    // ---------- classifying ----------

    private fun labelOf(registrable: String): String = registrable.substringBefore('.')

    private fun tldOf(registrable: String): String = registrable.substringAfterLast('.')

    private fun pathSegments(url: String): List<String> {
        val raw = try {
            URI(url.trim()).rawPath
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return raw.split('/').filter { it.isNotEmpty() }
    }

    /** `owner` and `repo` of a github.com page ([url] may point to a repo, a release, a file, an issue ...), else null. */
    fun githubRepoOf(url: String): Pair<String, String>? {
        if (UrlTools.host(url) != "github.com") return null
        val segs = pathSegments(url)
        if (segs.size < 2) return null
        val owner = segs[0]
        val repo = segs[1].removeSuffix(".git")
        if (owner.lowercase() in NON_REPO_FIRST) return null
        if (!GitHubClient.validOwner(owner) || !GitHubClient.validRepo(repo)) return null
        return owner to repo
    }

    private fun isReleasePath(url: String): Boolean {
        val segs = pathSegments(url)
        return segs.size >= 3 && segs[2] == "releases"
    }

    private val repoMention = Regex("(?<![\\w/.:@-])([A-Za-z0-9][A-Za-z0-9-]{1,38})/([A-Za-z0-9_.-]{2,100})(?![\\w/])")

    /** An `owner/repo` written in the user's words ("ggml-org/llama.cpp apk"), or null. A real lookup still decides if it exists. */
    fun repoFromText(text: String): Pair<String, String>? {
        for (m in repoMention.findAll(text)) {
            val owner = m.groupValues[1]
            val repo = m.groupValues[2].trimEnd('.', '-')
            if (repo.length < 2 || owner.all { it.isDigit() } || repo.all { it.isDigit() }) continue
            if (!owner.any { it.isLetter() } || !repo.any { it.isLetter() }) continue
            if ((owner + "/" + repo).lowercase() in NOT_A_REPO) continue
            if (extensionOf(repo) != null) continue
            if (!GitHubClient.validOwner(owner) || !GitHubClient.validRepo(repo)) continue
            return owner to repo
        }
        return null
    }

    /** True when the domain name is the product name, or the name plus a short word (`tavily` / `tavilyai` / `tavily-app`). */
    fun labelMatchesVendor(label: String, tokens: List<String>): Boolean {
        val l = label.replace("-", "")
        if (l.length < MIN_TOKEN) return false
        val names = ArrayList<String>(tokens)
        if (tokens.size >= 2) names.add(tokens.joinToString(""))
        for (t in names) {
            if (t.length < MIN_TOKEN) continue
            if (l == t) return true
            if (l.startsWith(t) && l.substring(t.length) in AFFIXES) return true
        }
        return false
    }

    /** One typing difference (one letter changed, added or missing) between two words. */
    private fun oneEditApart(a: String, b: String): Boolean {
        if (a == b) return false
        val d = a.length - b.length
        if (d > 1 || d < -1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                i++
                j++
            } else {
                edits++
                if (edits > 1) return false
                if (a.length > b.length) i++
                else if (a.length < b.length) j++
                else {
                    i++
                    j++
                }
            }
        }
        edits += (a.length - i) + (b.length - j)
        return edits == 1
    }

    /** A name that wears the product name but is not it: "gemma-apk-download", "freegemma", "gemmma". */
    private fun looksLikeLookalike(label: String, tokens: List<String>): Boolean {
        val parts = label.split('-')
        val l = label.replace("-", "")
        for (t in tokens) {
            if (t.length < MIN_TOKEN) continue
            if (l.contains(t) && l != t) {
                if (parts.any { it in BAD_WORDS }) return true
                for (b in BAD_WORDS) if (l == t + b || l == b + t) return true
            }
            if (t.length >= 5 && l.length >= 5 && oneEditApart(l, t)) return true
        }
        return false
    }

    /** Class, trust and the reason of a low trust, for one result URL. [pagesOnDomain] = result pages of the same registrable domain. */
    fun classify(url: String, tokens: List<String>, pagesOnDomain: Int): Triple<LinkClass, Trust, String> {
        val host = UrlTools.host(url)
        if (host.isEmpty()) return Triple(LinkClass.UNKNOWN, Trust.LOW, "unusual domain")
        val reg = UrlTools.registrableDomain(host)
        if (host == "github.com") {
            if (githubRepoOf(url) == null) return Triple(LinkClass.UNKNOWN, Trust.NOT_CONFIRMED, "")
            val cls = if (isReleasePath(url)) LinkClass.GITHUB_RELEASE else LinkClass.GITHUB_REPO
            return Triple(cls, Trust.KNOWN_HOST, "")
        }
        if (reg == "github.com" || reg == "githubusercontent.com") return Triple(LinkClass.UNKNOWN, Trust.NOT_CONFIRMED, "")
        if (reg == "huggingface.co" || reg == "hf.co") return Triple(LinkClass.HUGGINGFACE, Trust.KNOWN_HOST, "")
        if (host in STORES || reg in STORES) return Triple(LinkClass.STORE, Trust.KNOWN_HOST, "")
        if (reg in MIRRORS) return Triple(LinkClass.MIRROR, Trust.LOW, "third-party mirror")
        if (reg in HOSTING) return Triple(LinkClass.UNKNOWN, Trust.NOT_CONFIRMED, "")
        val label = labelOf(reg)
        val tld = tldOf(reg)
        if (labelMatchesVendor(label, tokens)) {
            val likely = tld !in SUSPICIOUS_TLDS && pagesOnDomain >= 2
            return Triple(LinkClass.VENDOR, if (likely) Trust.LIKELY_OFFICIAL else Trust.NOT_CONFIRMED, "")
        }
        if (looksLikeLookalike(label, tokens)) return Triple(LinkClass.UNKNOWN, Trust.LOW, "look-alike name")
        if (tld in SUSPICIOUS_TLDS) return Triple(LinkClass.UNKNOWN, Trust.LOW, "unusual domain")
        return Triple(LinkClass.UNKNOWN, Trust.NOT_CONFIRMED, "")
    }

    /** The word on the badge of a card. */
    fun badge(c: Candidate): String = when {
        c.trust == Trust.LIKELY_OFFICIAL -> "likely official"
        c.cls == LinkClass.GITHUB_RELEASE -> "GitHub releases"
        c.cls == LinkClass.GITHUB_REPO -> "GitHub"
        c.cls == LinkClass.HUGGINGFACE -> "Hugging Face"
        c.cls == LinkClass.STORE -> "app store"
        c.trust == Trust.LOW -> "low trust: " + c.reason
        else -> "not confirmed"
    }

    // ---------- ranking ----------

    /** All usable results as candidates, in the order the search gave them. A result that is no http(s) page is skipped. */
    fun candidates(results: List<TavilyResult>, tokens: List<String>): List<Candidate> {
        val pages = HashMap<String, MutableSet<String>>()
        for (r in results) {
            if (!UrlTools.isWebUrl(r.url)) continue
            val reg = UrlTools.registrableDomain(UrlTools.host(r.url))
            pages.getOrPut(reg) { HashSet() }.add(UrlTools.normalize(r.url))
        }
        val out = ArrayList<Candidate>()
        for ((i, r) in results.withIndex()) {
            if (!UrlTools.isWebUrl(r.url)) continue
            val host = UrlTools.host(r.url)
            val reg = UrlTools.registrableDomain(host)
            val (cls, trust, reason) = classify(r.url, tokens, pages[reg]?.size ?: 1)
            out.add(Candidate(r.url.trim(), r.title.trim(), host, reg, cls, trust, reason, r.score, pages[reg]?.size ?: 1, i))
        }
        return out
    }

    private fun preference(c: Candidate, want: SearchKind): Int {
        if (c.trust == Trust.LOW) return 90
        val official = c.cls == LinkClass.VENDOR && c.trust == Trust.LIKELY_OFFICIAL
        val vendor = c.cls == LinkClass.VENDOR
        return if (want == SearchKind.OFFICIAL_LINK) {
            when {
                official -> 0
                vendor -> 1
                c.cls == LinkClass.GITHUB_REPO -> 2
                c.cls == LinkClass.GITHUB_RELEASE -> 3
                c.cls == LinkClass.HUGGINGFACE -> 4
                c.cls == LinkClass.STORE -> 5
                else -> 6
            }
        } else {
            when {
                c.cls == LinkClass.GITHUB_RELEASE -> 0
                official -> 1
                c.cls == LinkClass.STORE -> 2
                c.cls == LinkClass.HUGGINGFACE -> 3
                c.cls == LinkClass.GITHUB_REPO -> 4
                vendor -> 5
                else -> 6
            }
        }
    }

    private fun dedupeKey(c: Candidate): String {
        val repo = githubRepoOf(c.url)
        return if (repo != null) "gh:" + repo.first.lowercase() + "/" + repo.second.lowercase() else "d:" + c.registrable
    }

    /**
     * One primary and up to [MAX_ALTERNATES] alternates for [want] (official link, download page or direct file). A site appears once.
     * An official-link request never shows a low-trust page (a mirror cannot be official); a download request shows them last, labelled.
     */
    fun rank(results: List<TavilyResult>, tokens: List<String>, want: SearchKind): Ranked {
        var list = candidates(results, tokens)
        if (want == SearchKind.OFFICIAL_LINK) list = list.filter { it.trust != Trust.LOW }
        val sorted = list.sortedWith(
            compareBy<Candidate> { preference(it, want) }.thenByDescending { it.score }.thenBy { it.order }
        )
        val seen = HashSet<String>()
        val unique = ArrayList<Candidate>()
        for (c in sorted) if (seen.add(dedupeKey(c))) unique.add(c)
        if (unique.isEmpty()) return Ranked(null, emptyList())
        return Ranked(unique[0], unique.drop(1).take(MAX_ALTERNATES))
    }

    /** Distinct GitHub repos named by the results, best first (release pages before repo pages), at most [limit]. */
    fun githubRepos(results: List<TavilyResult>, limit: Int): List<Pair<String, String>> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<Pair<String, String>>()
        val ordered = results.sortedBy { if (isReleasePath(it.url)) 0 else 1 }
        for (r in ordered) {
            val repo = githubRepoOf(r.url) ?: continue
            if (seen.add(repo.first.lowercase() + "/" + repo.second.lowercase())) out.add(repo)
            if (out.size >= limit) break
        }
        return out
    }

    // ---------- files ----------

    /** File types a download request may mean. Order is the order of [requestedExtensions]. */
    val FILE_EXTENSIONS: Set<String> = linkedSetOf(
        "apk", "aab", "zip", "7z", "tar.gz", "tgz", "gz", "xz", "tar", "gguf", "litertlm", "tflite", "task", "onnx",
        "safetensors", "bin", "exe", "msi", "dmg", "pkg", "deb", "rpm", "appimage", "jar", "iso"
    )

    /** Files that run code once opened: the confirm dialog says so. */
    val RUNNABLE: Set<String> = setOf("apk", "aab", "exe", "msi", "dmg", "pkg", "deb", "rpm", "appimage", "jar")

    private fun lastSegment(nameOrUrl: String): String {
        var s = nameOrUrl.trim()
        if (s.contains("://")) {
            s = try {
                URI(s).rawPath ?: ""
            } catch (_: Exception) {
                ""
            }
        } else {
            s = s.substringBefore('?').substringBefore('#')
        }
        return s.trimEnd('/').substringAfterLast('/')
    }

    /** `apk`, `zip`, `tar.gz` ... of a file name or URL, lower case, only when it is in [FILE_EXTENSIONS]. */
    fun extensionOf(nameOrUrl: String): String? {
        val seg = lastSegment(nameOrUrl).lowercase()
        if (seg.endsWith(".tar.gz")) return "tar.gz"
        val ext = seg.substringAfterLast('.', "")
        return if (ext in FILE_EXTENSIONS) ext else null
    }

    /** The file types the user named ("apk link", "gguf download"), in [FILE_EXTENSIONS] order. */
    fun requestedExtensions(text: String): List<String> {
        val lower = text.lowercase()
        val tokens = lower.split(tokenSplitter).toSet()
        val out = ArrayList<String>()
        for (e in FILE_EXTENSIONS) {
            if (e == "tar.gz") {
                if (lower.contains("tar.gz")) out.add(e)
            } else if (e in tokens) {
                out.add(e)
            }
        }
        return out
    }

    /** A safe file name for the phone: the last part of the address, letters / digits / `. _ -` only, not hidden, at most 80 characters. */
    fun safeFileName(nameOrUrl: String): String {
        val seg = lastSegment(nameOrUrl)
        val decoded = try {
            URLDecoder.decode(seg, "UTF-8")
        } catch (_: Exception) {
            seg
        }
        var s = decoded.map { c -> if (c.isLetterOrDigit() || c == '.' || c == '_' || c == '-') c else '_' }.joinToString("")
        s = s.trimStart('.', '_')
        if (s.isEmpty()) s = "download"
        if (s.length > 80) {
            val ext = extensionOf(s)
            s = if (ext != null) s.take(80 - ext.length - 1) + "." + ext else s.take(80)
        }
        return s
    }

    private val urlInText = Regex("https?://[^\\s)\\]>\"'<]+")

    /**
     * File addresses written inside [text] (search snippet or page text) that are https, end in a known file type (one of [wanted] when it
     * is not empty) and stay on the registrable domain of [pageUrl]. A link to another site is not followed: a page of a look-alike site can
     * point anywhere. At most [MAX_FILE_LINKS], in text order, each once.
     */
    fun findFileLinks(text: String, pageUrl: String, wanted: List<String>): List<String> {
        val pageReg = UrlTools.registrableDomain(UrlTools.host(pageUrl))
        if (pageReg.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        for (m in urlInText.findAll(text)) {
            val u = m.value.trimEnd('.', ',', ';', ':', '!', '?', '\'', '"')
            if (!UrlTools.isHttps(u)) continue
            if (UrlTools.registrableDomain(UrlTools.host(u)) != pageReg) continue
            val ext = extensionOf(u) ?: continue
            if (wanted.isNotEmpty() && ext !in wanted) continue
            out.add(u)
            if (out.size >= MAX_FILE_LINKS) break
        }
        return out.toList()
    }
}
