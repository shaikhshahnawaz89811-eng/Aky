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

    // ---------- stage S3: cards ----------

    private fun card(role: String = "file", url: String = "https://github.com/a/b/releases/download/v1/x.apk", downloadable: Boolean = true) = WebCard(
        role, "x.apk", url, "github.com", "GitHub release file", "KNOWN_HOST", "v1 \u00B7 5 MB", "x.apk", 5_000_000L,
        "application/vnd.android.package-archive", "sha256:" + "a".repeat(64), downloadable, true
    )

    private fun withCards(cards: List<WebCard>) = WebInfo("DIRECT_FILE", emptyList(), false, 1, 1, 0L, 0L, false, 0, 0, false, cards)

    @Test fun cardsRoundTrip() {
        val back = WebInfo.parse(withCards(listOf(card(), card("repo", "https://github.com/a/b", false))).toJson())!!
        assertEquals(2, back.cards.size)
        val c = back.cards[0]
        assertEquals("file", c.role)
        assertEquals("x.apk", c.title)
        assertEquals("github.com", c.domain)
        assertEquals("GitHub release file", c.badge)
        assertEquals("KNOWN_HOST", c.trust)
        assertEquals("v1 \u00B7 5 MB", c.detail)
        assertEquals("x.apk", c.fileName)
        assertEquals(5_000_000L, c.sizeBytes)
        assertEquals("application/vnd.android.package-archive", c.mime)
        assertEquals("sha256:" + "a".repeat(64), c.checksum)
        assertTrue(c.downloadable)
        assertTrue(c.runnable)
        assertFalse(back.cards[1].downloadable)
    }

    @Test fun cardFieldsThatAreMissingStayNull() {
        val bare = WebCard("primary", "T", "https://a.com", "a.com", "not confirmed", "NOT_CONFIRMED", null, null, null, null, null, false, false)
        val back = WebInfo.parse(withCards(listOf(bare)).toJson())!!.cards[0]
        assertNull(back.detail)
        assertNull(back.fileName)
        assertNull(back.sizeBytes)
        assertNull(back.mime)
        assertNull(back.checksum)
        assertFalse(back.downloadable)
    }

    @Test fun anAnswerWithoutCardsHasNoCardsKey() {
        val json = info(listOf(src(1))).toJson()
        assertFalse(json.contains("\"cards\""))
        assertTrue(WebInfo.parse(json)!!.cards.isEmpty())
    }

    @Test fun aCardWithoutAnAddressIsSkippedAndWrongTypesAreIgnored() {
        val json = "{\"v\":1,\"cards\":[{\"role\":\"file\"},{\"url\":\"https://a.com/x.apk\",\"downloadable\":\"yes\",\"size\":\"big\"},5]}"
        val cards = WebInfo.parse(json)!!.cards
        assertEquals(1, cards.size)
        assertFalse(cards[0].downloadable)
        assertNull(cards[0].sizeBytes)
    }

    @Test fun onlyTwelveCardsAreStoredAndDetailIsShort() {
        val many = (1..20).map { card(url = "https://github.com/a/b/releases/download/v1/f$it.apk") }
        val long = WebCard("file", "t", "https://a.com/x.apk", "a.com", "b", "KNOWN_HOST", "d".repeat(500), null, null, null, null, false, false)
        val back = WebInfo.parse(withCards(many + long).toJson())!!
        assertEquals(WebInfo.MAX_CARDS, back.cards.size)
        val b2 = WebInfo.parse(withCards(listOf(long)).toJson())!!
        assertEquals(WebInfo.MAX_DETAIL, b2.cards[0].detail!!.length)
    }

    @Test fun theJsonHasNoTextOfTheAnswerAndNoKey() {
        val json = info(listOf(src(1))).toJson()
        assertNotNull(json)
        assertFalse(json.contains("tvly"))
        assertTrue(json.contains("\"v\":1"))
    }
}
