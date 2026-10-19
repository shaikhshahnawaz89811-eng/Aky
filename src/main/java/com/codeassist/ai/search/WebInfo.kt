package com.codeassist.ai.search

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** One source shown under a net answer. [old] = older than 30 days when the answer was written (audit section 3, news). */
class WebSource(
    val id: Int,
    val title: String,
    val url: String,
    val domain: String,
    /** yyyy-MM-dd or null. */
    val date: String?,
    val old: Boolean
)

/**
 * One link or file card under a link answer (stage S3). [role]: primary, alternate, repo, release or file. [badge] is the word on the card
 * ("likely official", "GitHub", "low trust: third-party mirror" ...). [downloadable] is true only for a file whose address passed the
 * rules of the download policy; the Download button still asks first. [checksum] is a publisher's number (`sha256:...`) or null: the app
 * never computes one and never says "safe".
 */
class WebCard(
    val role: String,
    val title: String,
    val url: String,
    val domain: String,
    val badge: String,
    val trust: String,
    val detail: String?,
    val fileName: String?,
    val sizeBytes: Long?,
    val mime: String?,
    val checksum: String?,
    val downloadable: Boolean,
    val runnable: Boolean
)

/**
 * What a net answer carries besides its text, stored in `Message.web` as a JSON string (like `Message.sketch`: no reflection, so
 * a later R8 / Gson rule is not needed). The text of the message holds no link and no raw evidence; the sources live here.
 *
 *  - [sources]: the sources to show. They are the ones the answer cites, or all packed ones when the answer cited none ([citedNone]).
 *  - [credits] / [rounds] / [searchMs] / [composeMs] / [cached]: what the turn cost (shown in the caption, counted in the KPIs).
 *  - [unverified] / [linksRemoved]: what [CitationValidator] had to flag or remove.
 *  - [oneSite]: every source is from one site, so the answer said it is not confirmed.
 */
class WebInfo(
    val kind: String,
    val sources: List<WebSource>,
    val citedNone: Boolean,
    val credits: Int,
    val rounds: Int,
    val searchMs: Long,
    val composeMs: Long,
    val cached: Boolean,
    val unverified: Int,
    val linksRemoved: Int,
    val oneSite: Boolean,
    /** Stage S3: link and file cards (empty for an answer written from sources). */
    val cards: List<WebCard> = emptyList()
) {
    fun toJson(): String {
        val o = JsonObject()
        o.addProperty("v", VERSION)
        o.addProperty("kind", kind)
        val arr = JsonArray()
        for (s in sources.take(MAX_SOURCES)) {
            val so = JsonObject()
            so.addProperty("id", s.id)
            so.addProperty("title", s.title.take(MAX_TITLE))
            so.addProperty("url", s.url)
            so.addProperty("domain", s.domain)
            if (s.date != null) so.addProperty("date", s.date)
            so.addProperty("old", s.old)
            arr.add(so)
        }
        o.add("sources", arr)
        o.addProperty("citedNone", citedNone)
        o.addProperty("credits", credits)
        o.addProperty("rounds", rounds)
        o.addProperty("searchMs", searchMs)
        o.addProperty("composeMs", composeMs)
        o.addProperty("cached", cached)
        o.addProperty("unverified", unverified)
        o.addProperty("linksRemoved", linksRemoved)
        o.addProperty("oneSite", oneSite)
        if (cards.isNotEmpty()) {
            val ca = JsonArray()
            for (c in cards.take(MAX_CARDS)) {
                val co = JsonObject()
                co.addProperty("role", c.role)
                co.addProperty("title", c.title.take(MAX_TITLE))
                co.addProperty("url", c.url)
                co.addProperty("domain", c.domain)
                co.addProperty("badge", c.badge)
                co.addProperty("trust", c.trust)
                if (c.detail != null) co.addProperty("detail", c.detail.take(MAX_DETAIL))
                if (c.fileName != null) co.addProperty("fileName", c.fileName)
                if (c.sizeBytes != null) co.addProperty("size", c.sizeBytes)
                if (c.mime != null) co.addProperty("mime", c.mime)
                if (c.checksum != null) co.addProperty("checksum", c.checksum)
                co.addProperty("downloadable", c.downloadable)
                co.addProperty("runnable", c.runnable)
                ca.add(co)
            }
            o.add("cards", ca)
        }
        return o.toString()
    }

    companion object {
        const val VERSION = 1
        const val MAX_SOURCES = 8
        const val MAX_TITLE = 120
        const val MAX_CARDS = 12
        const val MAX_DETAIL = 200

        /** The info of a stored message, or null when [json] is empty, damaged or from a newer format. Never throws. */
        fun parse(json: String?): WebInfo? {
            if (json.isNullOrBlank()) return null
            return try {
                val e = JsonParser.parseString(json)
                if (!e.isJsonObject) return null
                val o = e.asJsonObject
                if (int(o, "v", 0) != VERSION) return null
                val out = ArrayList<WebSource>()
                val arr = o.get("sources")
                if (arr != null && arr.isJsonArray) {
                    for (item in arr.asJsonArray) {
                        if (!item.isJsonObject) continue
                        val so = item.asJsonObject
                        val url = str(so, "url") ?: continue
                        out.add(
                            WebSource(
                                id = int(so, "id", out.size + 1),
                                title = str(so, "title").orEmpty(),
                                url = url,
                                domain = str(so, "domain").orEmpty(),
                                date = str(so, "date"),
                                old = bool(so, "old")
                            )
                        )
                    }
                }
                val cards = ArrayList<WebCard>()
                val ca = o.get("cards")
                if (ca != null && ca.isJsonArray) {
                    for (item in ca.asJsonArray) {
                        if (!item.isJsonObject) continue
                        val co = item.asJsonObject
                        val url = str(co, "url") ?: continue
                        cards.add(
                            WebCard(
                                role = str(co, "role").orEmpty(),
                                title = str(co, "title").orEmpty(),
                                url = url,
                                domain = str(co, "domain").orEmpty(),
                                badge = str(co, "badge").orEmpty(),
                                trust = str(co, "trust").orEmpty(),
                                detail = str(co, "detail"),
                                fileName = str(co, "fileName"),
                                sizeBytes = co.get("size")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong,
                                mime = str(co, "mime"),
                                checksum = str(co, "checksum"),
                                downloadable = bool(co, "downloadable"),
                                runnable = bool(co, "runnable")
                            )
                        )
                    }
                }
                WebInfo(
                    kind = str(o, "kind").orEmpty(),
                    sources = out,
                    citedNone = bool(o, "citedNone"),
                    credits = int(o, "credits", 0),
                    rounds = int(o, "rounds", 0),
                    searchMs = long(o, "searchMs"),
                    composeMs = long(o, "composeMs"),
                    cached = bool(o, "cached"),
                    unverified = int(o, "unverified", 0),
                    linksRemoved = int(o, "linksRemoved", 0),
                    oneSite = bool(o, "oneSite"),
                    cards = cards
                )
            } catch (_: Throwable) {
                null
            }
        }

        private fun str(o: JsonObject, key: String): String? {
            val e = o.get(key) ?: return null
            if (!e.isJsonPrimitive || !e.asJsonPrimitive.isString) return null
            return e.asString
        }

        private fun int(o: JsonObject, key: String, fallback: Int): Int {
            val e = o.get(key) ?: return fallback
            if (!e.isJsonPrimitive || !e.asJsonPrimitive.isNumber) return fallback
            return e.asInt
        }

        private fun long(o: JsonObject, key: String): Long {
            val e = o.get(key) ?: return 0L
            if (!e.isJsonPrimitive || !e.asJsonPrimitive.isNumber) return 0L
            return e.asLong
        }

        private fun bool(o: JsonObject, key: String): Boolean {
            val e = o.get(key) ?: return false
            if (!e.isJsonPrimitive || !e.asJsonPrimitive.isBoolean) return false
            return e.asBoolean
        }
    }
}
