package com.codeassist.ai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryBookTest {
    private val t0 = 1_000_000_000L

    private fun item(kind: String, text: String, at: Long = t0, layer: String = MemoryBook.LONG, src: String = MemoryBook.SRC_SAID) =
        MemoryItem("id-$kind", kind, text, layer, at, if (layer == MemoryBook.WORK) at + MemoryBook.WORK_TTL_MS else 0L, src)

    // ---------- adding ----------

    @Test fun sameKindReplacesTheOlderOne() {
        val a = MemoryBook.add(emptyList(), listOf(item("name", "The user's name is Rahul.")), t0)
        val b = MemoryBook.add(a, listOf(item("name", "The user's name is Amit.").copy(id = "other")), t0)
        assertEquals(1, b.size)
        assertEquals("The user's name is Amit.", b[0].text)
    }

    @Test fun aFullBookDropsTheOldestAutomaticItemFirst() {
        val list = (1..MemoryBook.MAX_ITEMS).map {
            item("k$it", "fact $it", at = it.toLong(), src = if (it == 5) MemoryBook.SRC_AUTO else MemoryBook.SRC_SAID)
        }
        val out = MemoryBook.add(list, listOf(item("new", "fact new", at = 999L)), 1000L)
        assertEquals(MemoryBook.MAX_ITEMS, out.size)
        assertTrue(out.none { it.kind == "k5" })
        assertTrue(out.any { it.kind == "k1" })
        assertTrue(out.any { it.kind == "new" })
    }

    @Test fun withoutAutomaticItemsTheOldestGoes() {
        val list = (1..MemoryBook.MAX_ITEMS).map { item("k$it", "fact $it", at = it.toLong()) }
        val out = MemoryBook.add(list, listOf(item("new", "fact new", at = 999L)), 1000L)
        assertTrue(out.none { it.kind == "k1" })
    }

    @Test fun aWorkingItemLivesOneDay() {
        val w = MemoryBook.make("note:x", "kuch", MemoryBook.WORK, MemoryBook.SRC_SAID, t0)
        assertEquals(t0 + MemoryBook.WORK_TTL_MS, w.expiresAt)
        assertEquals(1, MemoryBook.alive(listOf(w), t0 + MemoryBook.WORK_TTL_MS - 1).size)
        assertEquals(0, MemoryBook.alive(listOf(w), t0 + MemoryBook.WORK_TTL_MS).size)
        val l = MemoryBook.make("note:y", "kuch", MemoryBook.LONG, MemoryBook.SRC_SAID, t0)
        assertEquals(0L, l.expiresAt)
    }

    @Test fun textIsCleaned() {
        assertEquals("a b c", MemoryBook.clean("  a \n b\t c  "))
        assertEquals(MemoryBook.MAX_TEXT, MemoryBook.clean("x".repeat(500)).length)
    }

    // ---------- changing ----------

    @Test fun editChangesTheWordsAndKeepsTheRest() {
        val list = listOf(item("name", "The user's name is Rahul."))
        val r = MemoryBook.editText(list, "id-name", "The user's name is Rahul Sharma.", t0)
        assertTrue(r is MemoryBook.Edit.Ok)
        val e = (r as MemoryBook.Edit.Ok).items[0]
        assertEquals("The user's name is Rahul Sharma.", e.text)
        assertEquals("id-name", e.id)
        assertEquals("name", e.kind)
        assertEquals(MemoryBook.SRC_EDIT, e.source)
        assertEquals(t0, e.createdAt)
    }

    @Test fun editRefusesSecretsShortTextAndUnknownIds() {
        val list = listOf(item("name", "The user's name is Rahul."))
        assertTrue(MemoryBook.editText(list, "id-name", "mera otp 123456", t0) is MemoryBook.Edit.Refused)
        assertTrue(MemoryBook.editText(list, "id-name", "ab", t0) is MemoryBook.Edit.Refused)
        assertTrue(MemoryBook.editText(list, "nope", "kuch naya", t0) is MemoryBook.Edit.Refused)
    }

    @Test fun promoteMakesAWorkingItemPermanent() {
        val w = item("note:n", "number 9876543210", layer = MemoryBook.WORK)
        val p = MemoryBook.promote(listOf(w), "id-note:n")[0]
        assertEquals(MemoryBook.LONG, p.layer)
        assertEquals(0L, p.expiresAt)
    }

    @Test fun deleteRemovesExactlyOne() {
        val list = listOf(item("a", "fact a"), item("b", "fact b"))
        val out = MemoryBook.delete(list, "id-a")
        assertEquals(listOf("b"), out.map { it.kind })
        assertEquals(2, MemoryBook.delete(list, "missing").size)
    }

    @Test fun forgetSinceKeepsOldAndOlderItems() {
        val list = listOf(
            item("old", "from before 4A", at = 0L, src = MemoryBook.SRC_OLD),
            item("a", "an hour ago", at = t0 - 3_600_000L),
            item("b", "two minutes ago", at = t0 - 120_000L),
            item("c", "just now", at = t0)
        )
        val (kept, removed) = MemoryBook.forgetSince(list, t0 - 600_000L)
        assertEquals(2, removed)
        assertEquals(listOf("old", "a"), kept.map { it.kind })
    }

    @Test fun searchIgnoresCaseAndEmptyQueryShowsAll() {
        val list = listOf(item("a", "The user likes Cricket."), item("b", "The user lives in Pune."))
        assertEquals(1, MemoryBook.search(list, "cricket").size)
        assertEquals(2, MemoryBook.search(list, "  ").size)
        assertEquals(0, MemoryBook.search(list, "football").size)
    }

    // ---------- what the model gets ----------

    @Test fun nameAndCityAreAlwaysInThePrompt() {
        val list = listOf(item("name", "The user's name is Rahul."), item("lives", "The user lives in Pune.")) +
            (1..10).map { item("note:$it", "note number $it", at = t0 + it) }
        val facts = MemoryBook.promptFacts(list, "python kya hai", t0 + 100)
        assertEquals(MemoryBook.PROMPT_MAX, facts.size)
        assertTrue(facts.any { it.kind == "name" })
        assertTrue(facts.any { it.kind == "lives" })
    }

    @Test fun factsThatMatchTheQuestionComeBeforeNewerOnes() {
        val list = listOf(item("like:cricket", "The user likes cricket.", at = t0 - 5_000)) +
            (1..10).map { item("note:$it", "irrelevant thing $it", at = t0 + it) }
        val facts = MemoryBook.promptFacts(list, "cricket kab khelte hain", t0 + 100)
        assertTrue(facts.any { it.kind == "like:cricket" })
        assertEquals("like:cricket", facts[0].kind)
    }

    @Test fun anExpiredWorkingItemIsNotInThePrompt() {
        val w = item("note:n", "mera number 9876543210", layer = MemoryBook.WORK)
        val block = MemoryBook.promptBlock(listOf(w), "number", t0 + MemoryBook.WORK_TTL_MS + 1)
        assertEquals("", block)
    }

    @Test fun theDeletedFactIsNotInTheNextPrompt() {
        val list = listOf(item("name", "The user's name is Rahul."), item("like:cricket", "The user likes cricket."))
        assertTrue(MemoryBook.promptBlock(list, "mera naam kya hai", t0).contains("Rahul"))
        val after = MemoryBook.delete(list, "id-name")
        val block = MemoryBook.promptBlock(after, "mera naam kya hai", t0)
        assertFalse(block.contains("Rahul"))
        assertTrue(block.contains("cricket"))
    }

    @Test fun emptyBookGivesNoBlock() {
        assertEquals("", MemoryBook.promptBlock(emptyList(), "kuch", t0))
    }

    // ---------- showing and storing ----------

    @Test fun ageAndLeftWords() {
        assertEquals("purani yaad", MemoryBook.ageWords(0L, t0))
        assertEquals("abhi", MemoryBook.ageWords(t0 - 30_000L, t0))
        assertEquals("5 minute pehle", MemoryBook.ageWords(t0 - 5 * 60_000L, t0))
        assertEquals("3 ghante pehle", MemoryBook.ageWords(t0 - 3 * 3_600_000L, t0))
        assertEquals("2 din pehle", MemoryBook.ageWords(t0 - 2 * 86_400_000L, t0))
        val w = item("n", "x y z", layer = MemoryBook.WORK)
        assertEquals("24 ghante baaki", MemoryBook.leftWords(w, t0))
        assertNull(MemoryBook.leftWords(item("n", "x y z"), t0))
    }

    @Test fun exportListsBothLayers() {
        val list = listOf(item("name", "The user's name is Rahul."), item("note:n", "number 9876543210", layer = MemoryBook.WORK))
        val text = MemoryBook.export(list, t0, "11 Oct 2026 10:00")
        assertTrue(text.startsWith("CodeAssistAI memory export · 11 Oct 2026 10:00"))
        assertTrue(text.contains("Hamesha ke liye (1)"))
        assertTrue(text.contains("Abhi ke liye, 24 ghante (1)"))
        assertTrue(text.contains("Rahul"))
        assertTrue(text.contains("9876543210"))
    }

    @Test fun packAndUnpackRoundTrip() {
        val list = listOf(item("name", "The user's name is Rahul."), item("note:n", "kuch yaad", layer = MemoryBook.WORK))
        val back = MemoryBook.unpack(MemoryBook.pack(list))
        assertNotNull(back)
        assertEquals(list, back)
    }

    @Test fun damagedStorageReadsAsNull() {
        assertNull(MemoryBook.unpack(null))
        assertNull(MemoryBook.unpack(""))
        assertNull(MemoryBook.unpack("not json"))
        assertEquals(0, MemoryBook.unpack("[]")!!.size)
    }

    @Test fun oldLinesMoveOverWithUnknownAge() {
        val items = MemoryBook.fromLegacy(listOf("name|The user's name is Rahul.", "a plain line from long ago"))
        assertEquals(2, items.size)
        assertEquals("name", items[0].kind)
        assertEquals(0L, items[0].createdAt)
        assertEquals(MemoryBook.SRC_OLD, items[0].source)
        assertEquals("The user's name is Rahul.", items[0].text)
        assertTrue(items[1].kind.startsWith("note:"))
        // an old fact is never removed by "pichhle N minute bhool jao"
        assertEquals(0, MemoryBook.forgetSince(items, t0).second)
    }
}
