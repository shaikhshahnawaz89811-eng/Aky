package com.codeassist.ai.search

import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Web text is untrusted. [clean] turns one scrap of it into plain text a model may read: no HTML, no links, no
 * invisible characters, no square brackets (they would look like our own [n] markers), no angle brackets (so the
 * `<web_data>` delimiter cannot be forged). Pieces that read like an instruction to the model are dropped (audit section 7).
 */
object Scrub {
    class Cleaned(val text: String, val droppedPieces: Int)

    private val injectionPhrases = listOf(
        "ignore previous", "ignore all previous", "ignore the previous", "ignore prior", "ignore above", "ignore the above",
        "ignore your instructions", "ignore all instructions", "ignore these instructions",
        "disregard previous", "disregard the previous", "disregard all previous", "disregard your instructions",
        "forget previous", "forget all previous", "forget your instructions",
        "system prompt", "developer message", "tool_call", "function_call", "new instructions",
        "from now on you", "you are now", "reveal your prompt", "reveal your instructions"
    )

    private val chunkSeparator = Regex("\\[\\s*(?:\\.\\.\\.|\\u2026)\\s*]")
    private val mdImage = Regex("!\\[[^\\]]*]\\([^)]*\\)")
    private val mdLink = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val htmlTag = Regex("<[^>]*>")
    private val url = Regex("(?:https?://|www\\.)\\S+")
    private val invisible = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\uFEFF]")
    private val spaces = Regex("\\s+")

    fun looksInjected(piece: String): Boolean {
        val lower = piece.lowercase()
        for (p in injectionPhrases) if (lower.contains(p)) return true
        return false
    }

    /** Cleans one string without splitting it (titles). */
    fun cleanLine(raw: String): String {
        var s = invisible.replace(raw, "")
        s = mdImage.replace(s, " ")
        s = mdLink.replace(s) { m -> m.groupValues[1] }
        s = htmlTag.replace(s, " ")
        s = url.replace(s, " ")
        s = s.replace('<', ' ').replace('>', ' ')
        s = s.replace('[', '(').replace(']', ')')
        return spaces.replace(s, " ").trim()
    }

    /** Cleans a snippet: it is cut at Tavily's "[...]" chunk marks and at line breaks, injection-like pieces are dropped. */
    fun clean(raw: String): Cleaned {
        var dropped = 0
        val kept = ArrayList<String>()
        for (chunk in chunkSeparator.split(raw)) {
            for (line in chunk.split('\n')) {
                if (line.isBlank()) continue
                if (looksInjected(line)) {
                    dropped++
                    continue
                }
                val c = cleanLine(line)
                if (c.isNotEmpty()) kept.add(c)
            }
        }
        return Cleaned(kept.joinToString(" "), dropped)
    }
}

/** One numbered source of an answer. [id] is the [n] the model may cite. */
class Evidence(
    val id: Int,
    val title: String,
    val url: String,
    val domain: String,
    val text: String,
    /** yyyy-MM-dd or null. */
    val date: String?,
    val score: Double
)

/**
 * Collects search results for one question, removes duplicates and junk, ranks them and packs the best into a prompt block of a
 * fixed size (audit section 5.3): the block is sized BEFORE the model call, so ContextBudget.fit() never has to cut it.
 *
 * The prompt block has no URL and no markdown link in it; the model can only cite by number and the app builds the
 * Sources list from [Evidence.url].
 */
class EvidenceBook {
    private class Item(
        val title: String,
        val url: String,
        val urlKey: String,
        val titleKey: String,
        val domain: String,
        val text: String,
        val date: String?,
        val score: Double,
        val order: Int
    )

    /** What [pack] produced. [block] is empty when there is nothing to show. */
    class Packed(val sources: List<Evidence>, val block: String, val omitted: Int, val trimmedChars: Int)

    private val items = ArrayList<Item>()
    private var counter = 0

    /** Pieces dropped because they looked like an instruction to the model (KPI). */
    var droppedInjected = 0
        private set

    /** Results dropped as duplicates of one already held (same page, or the same headline). */
    var droppedDuplicates = 0
        private set

    val size: Int get() = items.size

    /** Number of different registrable domains among the held results (stage S2: the planner stops at 2). */
    fun independentDomainCount(): Int =
        items.map { UrlTools.registrableDomain(it.domain) }.filter { it.isNotEmpty() }.toSet().size

    /** Adds results; returns how many were kept. */
    fun add(results: List<TavilyResult>): Int {
        var added = 0
        for (r in results) {
            if (!UrlTools.isWebUrl(r.url)) continue
            val cleaned = Scrub.clean(r.content)
            droppedInjected += cleaned.droppedPieces
            if (cleaned.text.length < MIN_TEXT_CHARS) continue
            val title = Scrub.cleanLine(r.title).take(MAX_TITLE_CHARS)
            val urlKey = UrlTools.normalize(r.url)
            val titleKey = titleKey(title)
            val host = UrlTools.host(r.url)
            val item = Item(title, r.url.trim(), urlKey, titleKey, host, cleaned.text, normalizeDate(r.publishedDate), r.score, counter++)

            val sameUrl = items.indexOfFirst { it.urlKey == urlKey }
            if (sameUrl >= 0) {
                droppedDuplicates++
                if (item.score > items[sameUrl].score) items[sameUrl] = item
                continue
            }
            val sameTitle = if (titleKey.length >= MIN_TITLE_KEY) items.indexOfFirst { it.titleKey == titleKey } else -1
            if (sameTitle >= 0) {
                droppedDuplicates++
                if (item.score > items[sameTitle].score) items[sameTitle] = item
                continue
            }
            items.add(item)
            added++
        }
        return added
    }

    private fun ranked(): List<Item> =
        items.sortedWith(
            compareByDescending<Item> { it.score }
                .thenByDescending { it.date ?: "" }
                .thenBy { it.order }
        )

    /**
     * The best sources that fit [totalChars] (headers included), at most [maxSources], each text cut to [perSourceChars] at a word
     * boundary. Ids are 1..n in rank order and are the numbers the model must use.
     */
    fun pack(totalChars: Int, perSourceChars: Int = DEFAULT_PER_SOURCE, maxSources: Int = DEFAULT_MAX_SOURCES): Packed {
        val sources = ArrayList<Evidence>()
        val sb = StringBuilder()
        var used = 0
        var trimmed = 0
        val all = ranked()
        for (item in all) {
            if (sources.size >= maxSources) break
            val id = sources.size + 1
            val header = header(id, item.title, item.domain, item.date)
            val room = totalChars - used - header.length - SEPARATOR_CHARS
            if (room < MIN_SOURCE_ROOM) {
                if (sources.isNotEmpty()) break
                if (room < 40) break
            }
            val limit = minOf(perSourceChars, room)
            val text = cut(item.text, limit)
            trimmed += item.text.length - text.length
            sb.append(header).append(text).append("\n\n")
            used += header.length + text.length + SEPARATOR_CHARS
            sources.add(Evidence(id, item.title, item.url, item.domain, text, item.date, item.score))
        }
        if (sources.isEmpty()) return Packed(emptyList(), "", all.size, 0)
        val block = "<web_data>\n" + sb.toString().trimEnd() + "\n</web_data>"
        return Packed(sources, block, all.size - sources.size, trimmed)
    }

    companion object {
        const val MIN_TEXT_CHARS = 20
        const val MAX_TITLE_CHARS = 120
        const val MIN_TITLE_KEY = 12
        const val DEFAULT_PER_SOURCE = 900
        const val DEFAULT_MAX_SOURCES = 8
        private const val MIN_SOURCE_ROOM = 200
        private const val SEPARATOR_CHARS = 2

        private fun header(id: Int, title: String, domain: String, date: String?): String {
            val sb = StringBuilder()
            sb.append('[').append(id).append("] ").append(title.ifEmpty { domain }).append(" \u00B7 ").append(domain)
            if (date != null) sb.append(" \u00B7 ").append(date)
            sb.append('\n')
            return sb.toString()
        }

        /** Lower-case letters and digits of a headline: two copies of one story compare equal. */
        fun titleKey(title: String): String = title.lowercase().filter { it.isLetterOrDigit() }

        /** [text] cut to at most [limit] characters at a word boundary, with an ellipsis when something was cut. */
        fun cut(text: String, limit: Int): String {
            if (text.length <= limit) return text
            if (limit <= 1) return ""
            val hard = text.substring(0, limit - 1)
            val space = hard.lastIndexOf(' ')
            val body = if (space >= limit / 2) hard.substring(0, space) else hard
            return body.trimEnd() + "\u2026"
        }

        private val isoDate = Regex("^(\\d{4})-(\\d{2})-(\\d{2})")

        /** yyyy-MM-dd from an ISO date(time) or an RFC 1123 date ("Mon, 12 Oct 2026 10:00:00 GMT"); null when unreadable. */
        fun normalizeDate(raw: String?): String? {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) return null
            val m = isoDate.find(s)
            if (m != null) {
                return try {
                    LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()).toString()
                } catch (_: Exception) {
                    null
                }
            }
            return try {
                ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDate().toString()
            } catch (_: Exception) {
                null
            }
        }

        const val OLD_AFTER_DAYS = 30L

        /** Days between [date] (yyyy-MM-dd) and [today]; null when [date] is missing or unreadable. Negative = in the future. */
        fun ageDays(date: String?, today: LocalDate): Long? {
            if (date == null) return null
            return try {
                ChronoUnit.DAYS.between(LocalDate.parse(date), today)
            } catch (_: Exception) {
                null
            }
        }

        /** Audit section 3, news: a source older than 30 days is flagged "old". */
        fun isOld(date: String?, today: LocalDate): Boolean {
            val a = ageDays(date, today) ?: return false
            return a > OLD_AFTER_DAYS
        }

        /** Number of different registrable domains among [sources]: "at least 2 independent domains agree". */
        fun independentDomains(sources: List<Evidence>): Int =
            sources.map { UrlTools.registrableDomain(it.domain) }.filter { it.isNotEmpty() }.toSet().size
    }
}
