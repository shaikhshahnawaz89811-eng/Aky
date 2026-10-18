package com.codeassist.ai.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Net search stage S2: Message.web is a JSON string; a damaged or newer one must never crash the chat list. */
class WebInfoTest {
    private fun src(id: Int, title: String = "Title $id", date: String? = "2026-10-16", old: Boolean = false) =
        WebSource(id, title, "https://site$id.com/page", "site$id.com", date, old)

    private fun info(sources: List<WebSource>) =
        WebInfo("NEWS", sources, false, 2, 2, 1800L, 9200L, true, 1, 1, false)

    @Test fun aRoundTripKeepsEveryField() {
        val back = WebInfo.parse(info(listOf(src(1), src(2, date = null, old = true))).toJson())!!
        assertEquals("NEWS", back.kind)
        assertEquals(2, back.sources.size)
        assertEquals("site1.com", back.sources[0].domain)
        assertEquals("https://site1.com/page", back.sources[0].url)
        assertEquals("2026-10-16", back.sources[0].date)
        assertNull(back.sources[1].date)
        assertTrue(back.sources[1].old)
        assertFalse(back.citedNone)
        assertEquals(2, back.credits)
        assertEquals(2, back.rounds)
        assertEquals(1800L, back.searchMs)
        assertEquals(9200L, back.composeMs)
        assertTrue(back.cached)
        assertEquals(1, back.unverified)
        assertEquals(1, back.linksRemoved)
        assertFalse(back.oneSite)
    }

    @Test fun emptyOrBrokenJsonGivesNullAndNeverThrows() {
        assertNull(WebInfo.parse(null))
        assertNull(WebInfo.parse(""))
        assertNull(WebInfo.parse("   "))
        assertNull(WebInfo.parse("not json"))
        assertNull(WebInfo.parse("[1,2,3]"))
        assertNull(WebInfo.parse("{\"v\":1,\"sources\":"))
    }

    @Test fun aNewerFormatIsIgnored() {
        assertNull(WebInfo.parse("{\"v\":2,\"kind\":\"NEWS\",\"sources\":[]}"))
        assertNull(WebInfo.parse("{\"kind\":\"NEWS\",\"sources\":[]}"))
    }

    @Test fun missingFieldsFallBackToDefaults() {
        val i = WebInfo.parse("{\"v\":1}")!!
        assertEquals("", i.kind)
        assertTrue(i.sources.isEmpty())
        assertEquals(0, i.credits)
        assertFalse(i.cached)
    }

    @Test fun aSourceWithoutAnUrlIsSkippedAndTheOthersStay() {
        val json = "{\"v\":1,\"sources\":[{\"id\":1,\"title\":\"x\"},{\"id\":2,\"url\":\"https://a.com/p\",\"domain\":\"a.com\"}]}"
        val i = WebInfo.parse(json)!!
        assertEquals(1, i.sources.size)
        assertEquals(2, i.sources[0].id)
        assertEquals("", i.sources[0].title)
    }

    @Test fun wrongTypesAreIgnoredNotCrashed() {
        val json = "{\"v\":1,\"credits\":\"many\",\"cached\":\"yes\",\"sources\":\"none\"}"
        val i = WebInfo.parse(json)!!
        assertEquals(0, i.credits)
        assertFalse(i.cached)
        assertTrue(i.sources.isEmpty())
    }

    @Test fun onlyEightSourcesAndShortTitlesAreStored() {
        val many = (1..12).map { src(it, title = "T".repeat(300)) }
        val back = WebInfo.parse(info(many).toJson())!!
        assertEquals(WebInfo.MAX_SOURCES, back.sources.size)
        assertEquals(WebInfo.MAX_TITLE, back.sources[0].title.length)
    }

    @Test fun theJsonHasNoTextOfTheAnswerAndNoKey() {
        val json = info(listOf(src(1))).toJson()
        assertNotNull(json)
        assertFalse(json.contains("tvly"))
        assertTrue(json.contains("\"v\":1"))
    }
}
