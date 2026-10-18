package com.codeassist.ai.search

/**
 * A small in-memory cache of search results, so the same question asked twice costs one credit (audit 6.1 step 6).
 *
 * The key has a time bucket in it: news-like kinds change by the day, the others by the week, so an old answer is never served
 * for a fresh question. Entries also expire by age ([ttlMs]) and the oldest-unused entry goes first when the cache is full.
 * The cache lives in memory only (nothing is written to disk) and is lost with the process.
 */
class SearchCache<V>(
    private val maxEntries: Int = 32,
    private val ttlMs: Long = 7L * 24 * 60 * 60 * 1000,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private class Entry<V>(val value: V, val at: Long)

    private val map = object : LinkedHashMap<String, Entry<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry<V>>?): Boolean = size > maxEntries
    }

    @Synchronized
    fun get(key: String): V? {
        val e = map[key] ?: return null
        if (clock() - e.at > ttlMs) {
            map.remove(key)
            return null
        }
        return e.value
    }

    @Synchronized
    fun put(key: String, value: V) {
        map[key] = Entry(value, clock())
    }

    @Synchronized
    fun clear() {
        map.clear()
    }

    @Synchronized
    fun size(): Int = map.size

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** Lower case, letters and digits only, single spaces: "Gemma 4?" and "gemma  4" are the same question. */
        fun normalizeQuery(q: String): String {
            val sb = StringBuilder()
            var lastSpace = true
            for (c in q.lowercase()) {
                if (c.isLetterOrDigit()) {
                    sb.append(c)
                    lastSpace = false
                } else if (!lastSpace) {
                    sb.append(' ')
                    lastSpace = true
                }
            }
            return sb.toString().trim()
        }

        /** News-like kinds go stale daily, the rest weekly. */
        fun bucketMs(kind: SearchKind): Long = when (kind) {
            SearchKind.NEWS, SearchKind.VALUE, SearchKind.FACT_CHECK, SearchKind.GENERAL -> DAY_MS
            else -> 7 * DAY_MS
        }

        fun keyFor(query: String, kind: SearchKind, nowMs: Long): String =
            normalizeQuery(query) + "|" + kind.name + "|" + (nowMs / bucketMs(kind))
    }
}
