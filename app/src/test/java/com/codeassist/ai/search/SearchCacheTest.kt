package com.codeassist.ai.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchCacheTest {
    private val day = 24L * 60 * 60 * 1000

    // 1000 whole weeks after the epoch: a boundary of both the day bucket and the week bucket
    private val t0 = day * 7 * 1000

    @Test fun aStoredValueComesBack() {
        val c = SearchCache<String>()
        assertNull(c.get("k"))
        c.put("k", "v")
        assertEquals("v", c.get("k"))
        assertEquals(1, c.size())
        c.clear()
        assertNull(c.get("k"))
    }

    @Test fun entriesExpireByAge() {
        var t = 0L
        val c = SearchCache<String>(ttlMs = 1000, clock = { t })
        c.put("k", "v")
        t = 999
        assertEquals("v", c.get("k"))
        t = 1001
        assertNull(c.get("k"))
        assertEquals(0, c.size())
    }

    @Test fun theLeastRecentlyUsedEntryLeavesFirst() {
        val c = SearchCache<String>(maxEntries = 2)
        c.put("a", "1")
        c.put("b", "2")
        assertEquals("1", c.get("a"))
        c.put("c", "3")
        assertNull(c.get("b"))
        assertEquals("1", c.get("a"))
        assertEquals("3", c.get("c"))
        assertEquals(2, c.size())
    }

    @Test fun normalizeQueryIgnoresCaseAndPunctuation() {
        assertEquals("gemma 4", SearchCache.normalizeQuery("  Gemma 4? "))
        assertEquals("a b", SearchCache.normalizeQuery("a--b"))
        assertEquals("", SearchCache.normalizeQuery("?!"))
    }

    @Test fun theSameQuestionGivesTheSameKeyInsideItsBucket() {
        assertEquals(
            SearchCache.keyFor("Gemma 4?", SearchKind.NEWS, t0),
            SearchCache.keyFor("gemma  4", SearchKind.NEWS, t0 + 3_600_000)
        )
    }

    @Test fun newsKeysChangeByTheDayOthersByTheWeek() {
        assertNotEquals(SearchCache.keyFor("gemma 4", SearchKind.NEWS, t0), SearchCache.keyFor("gemma 4", SearchKind.NEWS, t0 + day))
        assertEquals(
            SearchCache.keyFor("gemma 4", SearchKind.OFFICIAL_LINK, t0),
            SearchCache.keyFor("gemma 4", SearchKind.OFFICIAL_LINK, t0 + 6 * day)
        )
        assertNotEquals(
            SearchCache.keyFor("gemma 4", SearchKind.OFFICIAL_LINK, t0),
            SearchCache.keyFor("gemma 4", SearchKind.OFFICIAL_LINK, t0 + 7 * day)
        )
    }

    @Test fun theKindIsPartOfTheKey() {
        assertNotEquals(SearchCache.keyFor("gemma 4", SearchKind.NEWS, t0), SearchCache.keyFor("gemma 4", SearchKind.GENERAL, t0))
    }
}
