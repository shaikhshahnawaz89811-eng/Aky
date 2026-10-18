package com.codeassist.ai.search

/**
 * Net Search Agent, stage S1 (foundations; nothing here is called from the chat flow yet).
 *
 * The plain data types the rest of the package shares. No Android class, no I/O: everything in this package is pure
 * Kotlin and runs in the JVM unit tests (same rule as WeatherClient / StepTimeout / ContextBudget).
 */

/** The request kinds of the audit table (section 3). Order here is only for readability; [SearchIntent] has its own priority list. */
enum class SearchKind {
    GENERAL, NEWS, OFFICIAL_LINK, DOWNLOAD_PAGE, DIRECT_FILE, COMPARE, IMAGE, VIDEO, PAPER, GITHUB, SITE,
    SITE_EXPLORE, REPORT, FACT_CHECK, VALUE, FOLLOW_UP
}

/** How fresh the sources must be. [DAY] / [WEEK] / [MONTH] map to Tavily's `time_range`. */
enum class Freshness(val timeRange: String?) {
    ANY(null), DAY("day"), WEEK("week"), MONTH("month")
}

/**
 * What [SearchIntent.detect] decided. [kinds] are 1..3 ordered sub-intents; [maxCredits] / [maxRounds] come from the audit table
 * (several sub-intents add their credits, capped at [SearchIntent.MULTI_CREDIT_CAP]).
 *
 * [domains] is a restriction for the search (github.com, youtube.com, `site:`), [query] the cleaned text to send (before
 * [QueryGuard]). [explicit] is true when the user asked for the net in words (or the Web toggle was on): only then may the
 * expensive kinds (report) run.
 */
class SearchPlan(
    val kinds: List<SearchKind>,
    val entities: List<String>,
    val freshness: Freshness,
    val domains: List<String>,
    val maxCredits: Int,
    val maxRounds: Int,
    val explicit: Boolean,
    val query: String
) {
    val primary: SearchKind get() = kinds.first()

    override fun toString(): String =
        "SearchPlan(kinds=$kinds, entities=$entities, freshness=$freshness, domains=$domains, credits<=$maxCredits, rounds<=$maxRounds, explicit=$explicit, query='$query')"
}

// ---------- provider answers ----------

class TavilyImage(val url: String, val description: String?)

/** One search hit. [content] is the snippet Tavily returns (chunks, about 500 to 800 characters); it is untrusted text. */
class TavilyResult(
    val title: String,
    val url: String,
    val content: String,
    val score: Double,
    val publishedDate: String?,
    val images: List<TavilyImage>
)

class TavilySearchResponse(
    val results: List<TavilyResult>,
    /** Top-level images: only a URL (and maybe a description), no source page. */
    val images: List<TavilyImage>,
    /** Tavily's own LLM summary. An external AI claim: a hint at most, never the final answer. */
    val answer: String?,
    /** `usage.credits` when `include_usage` was on, else null. */
    val credits: Int?
)

class TavilyExtractItem(val url: String, val rawContent: String)

class TavilyExtractResponse(
    val results: List<TavilyExtractItem>,
    /** URLs listed in `failed_results`: an HTTP 200 can still contain these. */
    val failedUrls: List<String>,
    val credits: Int?
)

// ---------- failures ----------

enum class FailureKind {
    /** 401: key wrong or expired. */
    BAD_KEY,

    /** 429: too many requests; [SearchFailure.retryAfterSec] may be set. */
    RATE_LIMITED,

    /** 432 / 433: the key's plan or the pay-as-you-go limit is used up. */
    PLAN_LIMIT,

    /** 400 / 422 and other 4xx: our request was not accepted. */
    BAD_REQUEST,

    /** 5xx. */
    SERVER,

    /** Could not reach the server at all (no route, DNS, refused). */
    OFFLINE,

    /** Other I/O trouble while talking to the server. */
    NETWORK,

    /** The call took longer than its timeout. */
    TIMEOUT,

    /** HTTP 200 but the body is not the JSON shape we expect (or too big). Never partly parsed. */
    MALFORMED
}

class SearchFailure(val kind: FailureKind, val retryAfterSec: Int? = null, val detail: String = "") {
    /** Worth one more try (audit 6.4): rate limit, server error, timeout, a network hiccup. */
    val retryable: Boolean
        get() = kind == FailureKind.RATE_LIMITED || kind == FailureKind.SERVER ||
            kind == FailureKind.TIMEOUT || kind == FailureKind.NETWORK

    /** One short Hinglish line for the user (audit 6.4). Never contains the key or the server's text. */
    fun userMessage(): String = when (kind) {
        FailureKind.BAD_KEY -> "Tavily key galat ya expire ho gayi hai. Settings mein nayi key daalo."
        FailureKind.RATE_LIMITED -> "Search abhi bahut tez chal raha hai. Thodi der baad dobara try karo."
        FailureKind.PLAN_LIMIT -> "Tavily ka credit ya plan limit khatam ho gaya hai."
        FailureKind.BAD_REQUEST -> "Search request theek se nahi ban paayi."
        FailureKind.SERVER -> "Search server abhi theek se kaam nahi kar raha."
        FailureKind.OFFLINE -> "Internet nahi mil raha, isliye net search nahi ho sakta."
        FailureKind.NETWORK -> "Network mein dikkat aayi, search poora nahi hua."
        FailureKind.TIMEOUT -> "Search ne bahut der laga di."
        FailureKind.MALFORMED -> "Search ka jawab samajh nahi aaya."
    }
}

sealed class SearchOutcome<out T> {
    class Ok<out T>(val value: T) : SearchOutcome<T>()
    class Fail(val failure: SearchFailure) : SearchOutcome<Nothing>()
}
