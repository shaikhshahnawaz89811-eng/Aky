package com.codeassist.ai.search

import com.codeassist.ai.ai.ActionHint

/**
 * Decides, with plain word rules, whether a chat message needs the net and what kind of request it is (audit section 3.1).
 * No model is involved: a rule router is free, testable on the JVM and cannot be talked into spending credits. English and
 * Roman Hinglish cues are matched together.
 *
 * Safety rules baked in:
 *  - Web search only runs on a cue (a search word, "latest", "official website", "download link", "kaun sa behtar", "photo
 *    dikhao" ...), when the Web toggle is on ([forced]), or as a follow-up of a web answer ([prevWeb]). An ordinary message gives null.
 *  - A phone action ("alarm laga do", "torch on karo", "mausam") never becomes a search unless the user says "search" in words.
 *  - Messages with code, a long paste or many lines give null (unless [forced]).
 *  - The expensive kinds (report, site crawl) need more than a hint: a report needs an explicit web word or the toggle.
 *
 * This object only classifies. Building queries, rounds and stopping rules are the SearchPlanner's job (stage S2).
 */
object SearchIntent {
    const val MAX_KINDS = 3
    const val MULTI_CREDIT_CAP = 12
    const val MAX_QUERY_CHARS = 300
    private const val MAX_TEXT_CHARS = 600
    private const val MAX_LINES = 6

    private class Spec(val rounds: Int, val credits: Int)

    // rounds, credits: the "max rounds / credits" column of the audit table
    private fun spec(kind: SearchKind): Spec = when (kind) {
        SearchKind.GENERAL -> Spec(2, 2)
        SearchKind.NEWS -> Spec(2, 2)
        SearchKind.OFFICIAL_LINK -> Spec(2, 2)
        SearchKind.DOWNLOAD_PAGE -> Spec(2, 2)
        SearchKind.DIRECT_FILE -> Spec(2, 3)
        SearchKind.COMPARE -> Spec(3, 8)
        SearchKind.IMAGE -> Spec(1, 1)
        SearchKind.VIDEO -> Spec(1, 1)
        SearchKind.PAPER -> Spec(2, 3)
        SearchKind.GITHUB -> Spec(2, 1)
        SearchKind.SITE -> Spec(1, 2)
        SearchKind.SITE_EXPLORE -> Spec(2, 6)
        SearchKind.REPORT -> Spec(4, 12)
        SearchKind.FACT_CHECK -> Spec(2, 4)
        SearchKind.VALUE -> Spec(2, 3)
        SearchKind.FOLLOW_UP -> Spec(1, 1)
    }

    fun roundsFor(kind: SearchKind): Int = spec(kind).rounds

    fun creditsFor(kind: SearchKind): Int = spec(kind).credits

    // ---------- word lists ----------

    /** Words that carry no topic: a message made only of these (and cue words) has no entity. */
    private val STOP = setOf(
        "a", "an", "the", "is", "are", "was", "were", "be", "am", "of", "for", "on", "in", "to", "at", "by", "with", "from",
        "and", "or", "as", "it", "its", "this", "that", "these", "those", "what", "which", "who", "whom", "how", "about",
        "me", "my", "i", "you", "your", "we", "can", "could", "will", "would", "should", "please", "pls", "plz", "tell",
        "give", "show", "do", "does", "did", "any", "some", "also", "more", "details", "detail", "same",
        "hai", "hain", "ho", "hoga", "hogi", "hoge", "tha", "thi", "the", "ka", "ki", "ke", "ko", "se", "mein", "mai", "par",
        "pe", "pr", "kya", "kaun", "kaunsa", "konsa", "kon", "kaise", "kese", "kab", "kahan", "kahaan", "kitna", "kitni",
        "kitne", "aur", "ya", "bhi", "to", "toh", "ye", "yeh", "yah", "wo", "woh", "vo", "iska", "uska", "iski", "uski",
        "iske", "uske", "isme", "usme", "unka", "unki", "unke", "mujhe", "mujhko", "mera", "meri", "mere", "tum", "aap",
        "tumhe", "bhai", "yaar", "zara", "jara", "thoda", "thodi", "batao", "bata", "bataiye", "bataye", "dikhao", "dikha",
        "karo", "kar", "kro", "kijiye", "kare", "karu", "karun", "karna", "karte", "karein", "kardo", "karke", "de", "dena",
        "dedo", "chahiye", "chaiye", "raha", "rahi", "rahe", "sa", "si", "wala", "wali", "wale", "ek", "koi", "kuch", "sab",
        "saare", "saari", "sara", "hi", "na", "ji", "bas", "wahi", "pehla", "dusra", "doosra", "teesra", "first", "second",
        "third", "last", "next", "baaki", "baki", "hua", "hui", "hue", "gaya", "gayi", "gaye", "file", "files", "baare", "bare", "liye"
    )

    /** Words that ask for something to be made or changed: a web search is not what the user wants. */
    private val CREATE = setOf(
        "banao", "bana", "banate", "banana", "banaye", "banaiye", "create", "build", "design", "make", "generate", "draw",
        "edit", "resize", "compress", "convert", "crop", "upload", "attach", "analyze", "analyse", "describe", "ocr",
        "scan", "likho", "write", "delete", "hatao", "hata"
    )

    /** Programming and project-work words: "latest zip", "fix the bug", "array vs list" are about the user's own work, not the web. */
    private val CODE = setOf(
        "function", "functions", "variable", "variables", "array", "arrays", "loop", "loops", "syntax", "algorithm",
        "algorithms", "complexity", "compile", "compiler", "recursion", "pointer", "pointers", "class", "classes", "method",
        "methods", "snippet", "code", "coding", "zip", "project", "bug", "fix", "error", "errors", "patch", "commit", "phase", "stage"
    )

    private val HOWTO = setOf("kaise", "kese", "kaisey", "how", "why", "kyu", "kyun")

    /** "search" followed by one of these is about a search feature in code, not a request to search the net. */
    private val CODE_SEARCH = setOf(
        "bar", "box", "algorithm", "algo", "function", "tree", "binary", "linear", "index", "indexing", "query", "view",
        "feature", "screen", "field", "icon", "button", "ui", "layout", "fragment", "activity", "engine"
    )

    /** Words that ask to be shown / found / suggested something. */
    private val SHOW = setOf(
        "dikhao", "dikha", "dikhana", "dikhaiye", "dikhado", "show", "link", "links", "find", "search", "dhundo", "dhoondo",
        "khojo", "suggest", "recommend", "batao", "bata", "bataiye", "chahiye", "chaiye", "dedo", "bhejo", "lao", "laao",
        "give", "get", "need", "want", "kahan", "kahaan", "where", "konsa", "kaunsa", "kaun", "which", "best", "top",
        "acha", "achha", "accha"
    )

    private val EXPLICIT_PHRASES = listOf(
        "search karo", "search kar", "search kro", "search kijiye", "search for", "search about", "search the web",
        "search web", "search online", "web search", "web pe", "web par", "web mein", "net pe", "net par", "net se",
        "net mein", "internet pe", "internet par", "internet se", "internet mein", "online search", "google karo",
        "google kar", "google pe", "google par", "google se", "google search", "look up", "find online", "check online",
        "dekho online", "online dekho", "online check", "search karke", "search kar ke"
    )

    private val EXPLICIT_WORDS = setOf("dhundo", "dhoondo", "dhundh", "khojo", "lookup")

    private val NEWS_STRONG = setOf("news", "khabar", "khabrein", "khabren", "samachar", "headlines", "headline", "breaking")
    private val NEWS_MODERATE = setOf("latest", "newest", "recent", "recently", "trending")
    private val DAY_WORDS = setOf("aaj", "today", "todays", "now", "live")
    private val NEWS_NOUNS = setOf("score", "result", "results", "match", "election", "elections", "announced", "announcement")

    private val FACT_PHRASES = listOf(
        "fact check", "factcheck", "sach hai", "sach h", "sach he", "true hai", "is it true", "is this true", "is that true",
        "kya ye sach", "kya yeh sach", "kya yah sach", "afwah", "rumor", "rumour", "fake news", "jhooth", "jhuth", "jhoot",
        "hoax", "debunk"
    )

    private val COMPARE_STRONG = setOf("vs", "versus", "compare", "comparison", "comparing")
    /** "kaun sa behtar", "which is the best": a preference question when a question word and a quality word are both there. */
    private val COMPARE_WEAK_QUALITY = setOf("behtar", "better", "best")
    private val COMPARE_WEAK_QUESTION = setOf("kaun", "kaunsa", "konsa", "kon", "which")
    private val COMPARE_WEAK_PHRASES = listOf("better than")

    private val OFFICIAL_NOUNS = setOf("site", "website", "link", "page", "url", "web", "homepage", "webpage", "portal")

    private val DOWNLOAD_WORDS = setOf("download", "downloads", "apk", "gguf", "installer", "litertlm")
    private val LINKISH = setOf("link", "links", "page", "url", "kahan", "kahaan", "where")
    private val DIRECT_PHRASES = listOf(
        "apk link", "zip link", "gguf link", "file link", "direct link", "direct download", "direct url", "exact link",
        "exact url", "litertlm link"
    )
    private val fileExt = Regex("\\.(?:apk|zip|gguf|aab|exe|dmg|deb|litertlm|safetensors|onnx)\\b")

    private val GITHUB_CUES = setOf("github", "repo", "repository", "stars", "star", "forks", "release", "releases")
    private val GIT_OPS = setOf(
        "push", "pull", "commit", "clone", "merge", "branch", "upload", "gitignore", "workflow", "actions", "pr", "rebase",
        "checkout"
    )
    private val repoShape = Regex("\\b[a-z0-9][a-z0-9_.-]{0,38}/[a-z0-9_.-]{1,60}\\b")
    private val notRepos = setOf("and/or", "yes/no", "km/h", "w/o", "i/o", "on/off", "true/false", "input/output", "read/write")

    private val IMAGE_NOUNS = setOf(
        "image", "images", "photo", "photos", "picture", "pictures", "pic", "pics", "tasveer", "tasvir", "tasweer",
        "wallpaper", "logo", "logos"
    )
    private val IMAGE_SHOW = setOf(
        "dikhao", "dikha", "dikhana", "dikhaiye", "dikhado", "show", "find", "search", "dhundo", "dhoondo", "khojo",
        "laao", "lao", "bhejo", "send", "chahiye", "chaiye", "dedo"
    )
    private val IMAGE_PHRASES = listOf(
        "logo of", "photo of", "image of", "images of", "pictures of", "photos of", "picture of", "ki photo", "ka photo",
        "ki image", "ka image", "ki tasveer", "ka logo", "ki pic", "ka pic"
    )
    private val OWN_WORDS = setOf("meri", "mera", "mere", "my")

    private val VIDEO_NOUNS = setOf("video", "videos", "tutorial", "tutorials", "youtube", "vimeo", "playlist", "lecture", "lectures")
    private val OPEN_PLAY = setOf("kholo", "khol", "open", "launch", "chalao", "chala", "play", "bajao", "baja", "start", "pause", "stop")

    private val PAPER_NOUNS = setOf("paper", "papers", "whitepaper", "journal")
    private val DOC_NOUNS = setOf("documentation", "docs", "spec", "specs", "specification", "datasheet", "rfc")

    private val siteOperator = Regex("site:([a-z0-9][a-z0-9.-]*\\.[a-z]{2,})")
    private val domainShape = Regex("\\b[a-z0-9][a-z0-9-]*(?:\\.[a-z0-9-]+)*\\.(?:com|org|net|io|dev|ai|app|gov|edu|info|tech|xyz|in|co)\\b")
    private val SITE_LOCATORS = setOf("par", "pe", "pr", "on", "mein", "me", "se", "from", "site")
    private val SITE_ASK = setOf("kya", "kaise", "what", "how", "hai", "batao", "bata")

    private val SITE_EXPLORE_PHRASES = listOf(
        "explore site", "explore website", "explore the site", "explore the website", "saari pages", "saare pages",
        "sare pages", "saari page", "poori site", "puri site", "poora site", "pura site", "poori website", "puri website",
        "pura website", "poora docs", "pura docs", "puri docs", "poori docs", "full docs", "whole site", "whole website",
        "crawl site", "crawl website", "site explore", "website explore"
    )

    private val REPORT_PHRASES = listOf(
        "detailed report", "deep dive", "deep research", "poori jaankari", "puri jaankari", "poora jaankari", "full report",
        "research report", "in depth", "complete report", "pura report", "poori report", "puri report", "detailed research",
        "comprehensive report"
    )
    private val WEB_WORDS = setOf("web", "net", "internet", "online", "sources", "source", "latest", "current", "recent", "google")

    private val PRICE_WORDS = setOf("price", "prices", "pricing", "cost", "costs", "kimat", "keemat", "qeemat", "daam", "bhav", "bhaav")
    private val PRICE_PHRASES = listOf("kitne ka", "kitna ka", "kitni ki", "kitne ki", "kitne mein", "kitne me", "release date", "launch date", "kab release", "kab launch")
    private val VERSION_ASK = setOf("latest", "current", "newest", "kaunsa", "konsa", "kaun", "which", "what", "kya")
    private val SIZE_CONTEXT = setOf("download", "file", "apk", "model", "install", "storage")

    private val UNKNOWN_FACT_PHRASES = listOf(
        "who is the current", "who is current", "current ceo", "current president", "current prime minister", "current pm",
        "kaun hai current", "abhi ka pm", "abhi ka president", "abhi ka ceo"
    )

    private val SMALLTALK = setOf(
        "thanks", "thank", "thankyou", "shukriya", "dhanyavaad", "ok", "okay", "theek", "thik", "accha", "acha", "achha",
        "hmm", "haan", "ha", "nahi", "no", "yes", "great", "nice", "cool", "bye", "hello", "hi", "hey"
    )
    private val PRONOUNS = setOf(
        "iska", "uska", "iski", "uski", "iske", "uske", "isme", "usme", "wo", "woh", "vo", "ye", "yeh", "yah", "it", "that",
        "this", "them", "those", "these", "unka", "unki", "unke", "aur", "more", "wahi", "same", "pehla", "dusra", "doosra",
        "teesra", "first", "second", "third", "last", "next", "baaki", "baki", "details", "detail"
    )
    private val QUESTION_WORDS = setOf(
        "kya", "kab", "kitna", "kitni", "kitne", "kaun", "kaunsa", "konsa", "kahan", "kahaan", "kaise", "kyu", "kyun", "what",
        "when", "how", "where", "which", "who", "why", "price", "version", "size", "date", "link", "more", "details", "detail", "aur"
    )

    /** Everything that is a cue and therefore not a topic word. */
    private val CUES: Set<String> = SHOW + NEWS_STRONG + NEWS_MODERATE + DAY_WORDS + NEWS_NOUNS + COMPARE_STRONG +
        OFFICIAL_NOUNS + DOWNLOAD_WORDS + GITHUB_CUES + IMAGE_NOUNS + VIDEO_NOUNS + PAPER_NOUNS + DOC_NOUNS + PRICE_WORDS +
        EXPLICIT_WORDS + setOf(
            "search", "google", "internet", "online", "net", "official", "direct", "exact", "behtar", "better", "fact", "check",
            "sach", "true", "report", "detailed", "deep", "dive", "crawl", "explore", "version", "versions", "size", "licence",
            "license", "licensed", "compatible", "compatibility", "arxiv", "research", "pages"
        )

    /** Dropped from the query text that goes out: politeness and "do it" fillers. */
    private val QUERY_DROP = setOf(
        "karo", "kar", "kro", "kijiye", "karke", "kardo", "please", "pls", "plz", "bhai", "yaar", "zara", "jara", "dhundo",
        "dhoondo", "khojo", "batao", "bata", "bataiye", "dikhao", "dikha", "dikhaiye", "mujhe", "mujhko"
    )
    private val searchPhrase =
        Regex("(?i)\\b(?:google|search|web search|web|net|internet|online)\\s+(?:pe|par|pr|se|mein|me|karo|kar|kro|kijiye|karke|for|about|online)\\b")
    private val urlShape = Regex("https?://\\S+")
    private val tokenSplitter = Regex("[^\\p{L}\\p{M}\\p{N}]+")
    private val richSplitter = Regex("[\\s,;!?()\"'\\u2018\\u2019\\u201C\\u201D]+")

    // ---------- tokenizing ----------

    private fun tokenize(s: String): List<String> = s.lowercase().split(tokenSplitter).filter { it.isNotEmpty() }

    /** Words with "." "+" "#" "-" kept inside ("2.5", "c++", "gemma-4", "huggingface.co"); case is kept. */
    private fun richSplit(s: String): List<String> =
        s.split(richSplitter).map { it.trim { c -> c == '.' || c == ':' || c == '-' || c == '/' } }.filter { it.isNotEmpty() }

    /** The text to send to the search server: the message without links and without "search karo" style words. */
    fun cleanQuery(text: String): String {
        var s = urlShape.replace(text.trim(), " ")
        s = searchPhrase.replace(s, " ")
        val kept = richSplit(s).filter { it.lowercase() !in QUERY_DROP && !it.lowercase().startsWith("site:") }
        val q = kept.joinToString(" ").take(MAX_QUERY_CHARS).trim()
        return if (q.isEmpty()) text.trim().take(MAX_QUERY_CHARS) else q
    }

    // ---------- the router ----------

    /**
     * [prevWeb]: the previous AI message of this chat was a web answer. [forced]: the composer's Web toggle is on.
     * Returns null when the message is no web request.
     */
    fun detect(text: String, prevWeb: Boolean = false, forced: Boolean = false): SearchPlan? {
        val raw = text.trim()
        if (raw.isEmpty()) return null
        if (raw.length > MAX_TEXT_CHARS || raw.count { it == '\n' } > MAX_LINES || raw.contains("```")) {
            return if (forced) generalPlan(raw, emptyList(), true) else null
        }
        val body = urlShape.replace(raw, " ")
        val lower = body.lowercase()
        val tokens = tokenize(body)
        if (tokens.isEmpty()) return null
        val tokenSet = tokens.toSet()
        val padded = " " + tokens.joinToString(" ") + " "
        fun has(phrase: String): Boolean = padded.contains(" $phrase ")

        val rich = richSplit(lower)
        val content = rich.filter { it !in STOP && it !in CUES && it !in CREATE && !it.startsWith("site:") }
        val hasEntity = content.isNotEmpty()

        val actionLike = ActionHint.looksLikeAction(body)
        val creating = tokens.any { it in CREATE }
        val howTo = tokens.any { it in HOWTO }
        val hasCode = tokens.any { it in CODE }

        val explicit = EXPLICIT_PHRASES.any { has(it) } || tokens.any { it in EXPLICIT_WORDS } ||
            (tokens[0] == "search" && tokens.size >= 2 && tokens.none { it in CODE_SEARCH })
        val codeBlocked = hasCode && !explicit && !forced
        val showWord = tokens.any { it in SHOW }

        // news
        val strongNews = tokens.any { it in NEWS_STRONG }
        val moderateNews = tokens.any { it in NEWS_MODERATE }
        val dayWord = tokens.any { it in DAY_WORDS }
        val weakNews = !actionLike && !creating && !codeBlocked &&
            ((moderateNews && hasEntity) || (dayWord && tokens.size >= 3 && tokens.any { it in NEWS_NOUNS }))
        val news = strongNews || weakNews

        // fact check
        val factCheck = FACT_PHRASES.any { has(it) } && (hasEntity || prevWeb)

        // comparison
        val compareStrong = tokens.any { it in COMPARE_STRONG }
        val compareWeak = (tokens.any { it in COMPARE_WEAK_QUALITY } && tokens.any { it in COMPARE_WEAK_QUESTION }) ||
            COMPARE_WEAK_PHRASES.any { has(it) }
        val parts = if (compareStrong || compareWeak) splitEntities(lower) else emptyList()
        val compare = parts.size >= 2 && !codeBlocked && (compareStrong || (compareWeak && !howTo))

        // links and downloads
        val official = !creating &&
            (("official" in tokenSet && tokens.any { it in OFFICIAL_NOUNS }) ||
                (tokens.any { it == "website" || it == "homepage" } && hasEntity && showWord))
        val directFile = (hasEntity || prevWeb) &&
            ((("direct" in tokenSet) && tokens.any { it == "link" || it == "url" || it == "download" || it == "file" }) ||
                DIRECT_PHRASES.any { has(it) } ||
                (fileExt.containsMatchIn(lower) && tokens.any { it == "link" || it == "url" || it == "download" || it == "downloads" }))
        val download = (hasEntity || prevWeb) && !creating && tokens.any { it in DOWNLOAD_WORDS } &&
            (!howTo || tokens.any { it in LINKISH })

        // github
        val gitOps = tokens.any { it in GIT_OPS }
        val repoMention = repoShape.findAll(lower).any { it.value !in notRepos && it.value.any { c -> c.isLetter() } }
        val github = !gitOps &&
            ((repoMention && tokens.any { it in GITHUB_CUES }) || ("github" in tokenSet && hasEntity && !howTo))

        // media
        val image = tokens.any { it in IMAGE_NOUNS } && !creating && tokens.none { it in OWN_WORDS } &&
            (tokens.any { it in IMAGE_SHOW } || IMAGE_PHRASES.any { has(it) })
        val video = tokens.any { it in VIDEO_NOUNS } && !creating && tokens.none { it in OPEN_PLAY } && showWord

        // papers and documentation
        val arxiv = "arxiv" in tokenSet
        val googleDocs = has("google docs") || has("google doc")
        val paper = !creating && (
            arxiv ||
                (tokens.any { it in PAPER_NOUNS } && hasEntity && (showWord || "research" in tokenSet)) ||
                (tokens.any { it in DOC_NOUNS } && !googleDocs && hasEntity && (showWord || "official" in tokenSet))
            )

        // one site
        val siteMatch = siteOperator.find(lower)
        val domainMatch = domainShape.findAll(lower).firstOrNull { m -> m.range.first == 0 || lower[m.range.first - 1] != '@' }
        val site = !creating && (
            siteMatch != null ||
                (domainMatch != null && tokens.any { it in SITE_LOCATORS } &&
                    (explicit || showWord || tokens.any { it in SITE_ASK }))
            )
        val siteDomain: String? = siteMatch?.let { it.groupValues[1] } ?: domainMatch?.value

        // many pages of a site, long reports
        val siteExplore = SITE_EXPLORE_PHRASES.any { has(it) } ||
            ("crawl" in tokenSet && tokens.any { it == "site" || it == "website" || it == "docs" || it == "domain" })
        val report = REPORT_PHRASES.any { has(it) } && hasEntity && (explicit || forced || tokens.any { it in WEB_WORDS })

        // price, version, size, licence, compatibility
        val valueCue = tokens.any { it in PRICE_WORDS } || PRICE_PHRASES.any { has(it) } ||
            (tokens.any { it == "version" || it == "versions" } && tokens.any { it in VERSION_ASK }) ||
            (tokens.any { it == "size" } && tokens.any { it in SIZE_CONTEXT }) ||
            tokens.any { it == "licence" || it == "license" || it == "licensed" } ||
            tokens.any { it == "compatible" || it == "compatibility" }
        val value = valueCue && hasEntity && !codeBlocked && !howTo && !actionLike

        val unknownFact = !actionLike && UNKNOWN_FACT_PHRASES.any { has(it) }

        // ---- assemble, in priority order ----
        val kinds = LinkedHashSet<SearchKind>()
        if (siteExplore) kinds.add(SearchKind.SITE_EXPLORE)
        if (report) kinds.add(SearchKind.REPORT)
        if (factCheck) kinds.add(SearchKind.FACT_CHECK)
        if (compare) kinds.add(SearchKind.COMPARE)
        if (github) kinds.add(SearchKind.GITHUB)
        if (directFile) kinds.add(SearchKind.DIRECT_FILE)
        if (download) kinds.add(SearchKind.DOWNLOAD_PAGE)
        if (official) kinds.add(SearchKind.OFFICIAL_LINK)
        if (image) kinds.add(SearchKind.IMAGE)
        if (video) kinds.add(SearchKind.VIDEO)
        if (paper) kinds.add(SearchKind.PAPER)
        if (site) kinds.add(SearchKind.SITE)
        if (value) kinds.add(SearchKind.VALUE)
        if (news) kinds.add(SearchKind.NEWS)

        if (SearchKind.DIRECT_FILE in kinds) kinds.remove(SearchKind.DOWNLOAD_PAGE)
        if (SearchKind.GITHUB in kinds) {
            kinds.remove(SearchKind.DOWNLOAD_PAGE)
            kinds.remove(SearchKind.OFFICIAL_LINK)
        }
        if (SearchKind.DOWNLOAD_PAGE in kinds || SearchKind.DIRECT_FILE in kinds) kinds.remove(SearchKind.OFFICIAL_LINK)
        if (SearchKind.FACT_CHECK in kinds || SearchKind.REPORT in kinds) kinds.remove(SearchKind.NEWS)
        if (SearchKind.SITE_EXPLORE in kinds) kinds.remove(SearchKind.SITE)
        if (!strongNews && kinds.size > 1) kinds.remove(SearchKind.NEWS)

        if (kinds.isEmpty()) {
            val onlySmallTalk = tokens.all { it in SMALLTALK || it in STOP }
            val general = (explicit || forced || unknownFact) && !(actionLike && !explicit) && !onlySmallTalk
            if (general) {
                if (hasEntity) return generalPlan(raw, content, explicit || forced)
                return if (prevWeb) followUpPlan(raw) else null
            }
            if (prevWeb && isFollowUp(raw, tokens, actionLike)) return followUpPlan(raw)
            return null
        }

        val list = kinds.toList().take(MAX_KINDS)
        val entities = if (SearchKind.COMPARE in list) parts else if (hasEntity) listOf(content.take(6).joinToString(" ")) else emptyList()
        val credits = minOf(MULTI_CREDIT_CAP, list.sumOf { spec(it).credits })
        val rounds = list.maxOf { spec(it).rounds }
        val domains = if (list.size == 1) domainsFor(list[0], siteDomain, arxiv) else emptyList()
        val freshness = freshnessOf(list, dayWord, has("is mahine") || has("this month") || has("iss mahine"))
        return SearchPlan(list, entities, freshness, domains, credits, rounds, explicit || forced, cleanQuery(raw))
    }

    private fun domainsFor(kind: SearchKind, siteDomain: String?, arxiv: Boolean): List<String> = when (kind) {
        SearchKind.GITHUB -> listOf("github.com")
        SearchKind.VIDEO -> listOf("youtube.com", "vimeo.com")
        SearchKind.PAPER -> if (arxiv) listOf("arxiv.org") else emptyList()
        SearchKind.SITE -> if (siteDomain != null) listOf(siteDomain) else emptyList()
        else -> emptyList()
    }

    private fun freshnessOf(kinds: List<SearchKind>, dayWord: Boolean, monthPhrase: Boolean): Freshness = when {
        SearchKind.NEWS in kinds -> if (monthPhrase) Freshness.MONTH else if (dayWord) Freshness.DAY else Freshness.WEEK
        SearchKind.VALUE in kinds && dayWord -> Freshness.WEEK
        else -> Freshness.ANY
    }

    private fun generalPlan(raw: String, content: List<String>, explicit: Boolean): SearchPlan {
        val s = spec(SearchKind.GENERAL)
        val entities = if (content.isEmpty()) emptyList() else listOf(content.take(6).joinToString(" "))
        return SearchPlan(listOf(SearchKind.GENERAL), entities, Freshness.ANY, emptyList(), s.credits, s.rounds, explicit, cleanQuery(raw))
    }

    private fun followUpPlan(raw: String): SearchPlan {
        val s = spec(SearchKind.FOLLOW_UP)
        return SearchPlan(listOf(SearchKind.FOLLOW_UP), emptyList(), Freshness.ANY, emptyList(), s.credits, s.rounds, false, cleanQuery(raw))
    }

    /** A short or pronoun message right after a web answer ("uska price?", "aur batao", "wo kab release hua"). */
    private fun isFollowUp(raw: String, tokens: List<String>, actionLike: Boolean): Boolean {
        if (actionLike) return false
        if (tokens.all { it in SMALLTALK || it in STOP && it !in PRONOUNS }) return false
        val pronoun = tokens.any { it in PRONOUNS }
        val questionish = raw.endsWith("?") || tokens.any { it in QUESTION_WORDS }
        return (pronoun && tokens.size <= 12) || (tokens.size <= 6 && questionish)
    }

    /** The things compared in "A vs B", "A, B aur C compare karo", "gemma ya qwen kaun sa behtar": 2..3 short names. */
    private fun splitEntities(lower: String): List<String> {
        val tokens = richSplit(lower.replace(",", " vs "))
        val hasVs = tokens.any { it == "vs" || it == "versus" }
        val separators = if (hasVs) setOf("vs", "versus") else setOf("ya", "or", "aur", "and")
        val parts = ArrayList<List<String>>()
        var current = ArrayList<String>()
        for (t in tokens) {
            if (t in separators) {
                parts.add(current)
                current = ArrayList()
            } else {
                current.add(t)
            }
        }
        parts.add(current)
        return parts
            .map { p -> p.filter { it !in STOP && it !in CUES }.take(4).joinToString(" ") }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(3)
    }
}
