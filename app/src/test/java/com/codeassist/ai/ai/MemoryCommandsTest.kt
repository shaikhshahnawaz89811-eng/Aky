package com.codeassist.ai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCommandsTest {
    private val now = 1_000_000_000L

    private fun run(text: String, items: List<MemoryItem> = emptyList(), priv: Boolean = false): MemoryCommands.Result {
        val r = MemoryCommands.handle(text, items, now, priv)
        assertNotNull(text, r)
        return r!!
    }

    private fun item(kind: String, text: String, at: Long) = MemoryBook.make(kind, text, MemoryBook.LONG, MemoryBook.SRC_SAID, at)

    // ---------- remembering ----------

    @Test fun aNoteIsSavedForGood() {
        val r = run("yaad rakho ki meri bhanji ka naam Gaib hai")
        assertEquals("said", r.event)
        assertEquals(1, r.items!!.size)
        assertEquals(MemoryBook.LONG, r.items!![0].layer)
        assertEquals("meri bhanji ka naam Gaib hai", r.items!![0].text)
        assertTrue(r.reply.contains("hamesha ke liye"))
    }

    @Test fun aPhoneNumberIsKeptForADayAndReadBack() {
        val r = run("yaad rakho ki mera number 98765 43210 hai")
        assertEquals("working", r.event)
        val it = r.items!![0]
        assertEquals(MemoryBook.WORK, it.layer)
        assertEquals(now + MemoryBook.WORK_TTL_MS, it.expiresAt)
        assertTrue(r.reply, r.reply.contains("24 ghante"))
        assertTrue(r.reply, r.reply.contains("9 8 7 6 5 4 3 2 1 0"))
        assertTrue(r.reply, r.reply.contains("hamesha yaad rakho"))
    }

    @Test fun hameshaMakesItPermanent() {
        for (text in listOf(
            "hamesha yaad rakho ki mera number 98765 43210 hai",
            "yaad rakho hamesha ke liye ki mera number 98765 43210 hai"
        )) {
            val r = run(text)
            assertEquals(text, "said", r.event)
            val it = r.items!![0]
            assertEquals(text, MemoryBook.LONG, it.layer)
            assertFalse(text, it.text.lowercase().contains("hamesha"))
            assertTrue(text, it.text.contains("98765 43210"))
            assertTrue(text, r.reply.contains("9 8 7 6 5 4 3 2 1 0"))
        }
    }

    @Test fun aWordHameshaInsideTheNoteIsKept() {
        val r = run("yaad rakho ki main hamesha late hota hoon")
        assertEquals("main hamesha late hota hoon", r.items!![0].text)
    }

    @Test fun secretsAreRefusedAndNothingChanges() {
        val r = run("yaad rakho ki mera otp 482913 hai")
        assertEquals("blocked", r.event)
        assertNull(r.items)
        assertEquals(MemoryGuard.NEVER_MESSAGE, r.reply)
    }

    @Test fun privateModeSavesNothing() {
        val r = run("yaad rakho ki meri bhanji ka naam Gaib hai", priv = true)
        assertEquals("private_blocked", r.event)
        assertNull(r.items)
        assertEquals(MemoryCommands.PRIVATE_BLOCKED_REPLY, r.reply)
    }

    @Test fun forgettingStillWorksInPrivateMode() {
        val r = run("bhool jao", listOf(item("name", "The user's name is Rahul.", now)), priv = true)
        assertEquals("forget_all", r.event)
        assertEquals(0, r.items!!.size)
    }

    @Test fun cutOffNoteIsNotSaved() {
        val r = run("Yaad rakhna Mera naam or meri bhanji ko")
        assertEquals("incomplete", r.event)
        assertNull(r.items)
    }

    // ---------- private mode switch ----------

    @Test fun privateModeCanBeSwitchedByVoice() {
        assertEquals(true, run("private mode on").privateMode)
        assertEquals(true, run("Private Mode ON kar do").privateMode)
        assertEquals(false, run("private mode off").privateMode)
        assertEquals(false, run("private mode off karo").privateMode)
        assertNull(run("private mode on").items)
    }

    // ---------- forgetting ----------

    @Test fun forgetRecentRemovesOnlyTheLastMinutes() {
        val items = listOf(
            item("a", "two minutes ago", now - 2 * 60_000L),
            item("b", "twenty minutes ago", now - 20 * 60_000L),
            MemoryBook.fromLegacy(listOf("name|The user's name is Rahul."))[0]
        )
        val r = run("pichhle 10 minute bhool jao", items)
        assertEquals("forget_recent", r.event)
        assertEquals(2, r.items!!.size)
        assertTrue(r.items!!.none { it.kind == "a" })
        assertTrue(r.reply, r.reply.contains("10 minute") && r.reply.contains("1 yaad"))
    }

    @Test fun forgetRecentUnderDifferentWords() {
        val items = listOf(item("a", "ten minutes ago", now - 10 * 60_000L))
        assertEquals("forget_recent", run("pichhle 1 ghante ki yaad hata do", items).event)
        assertEquals("forget_recent", run("last 30 minute ki memory delete karo", items).event)
        assertEquals("forget_recent", run("forget last 30 minutes", items).event)
        assertEquals("forget_recent", run("pichle 5 min bhula do", listOf(item("a", "x y z", now))).event)
    }

    @Test fun forgetRecentWithNothingToForget() {
        val r = run("pichhle 5 minute bhool jao", listOf(item("a", "an hour ago", now - 3_600_000L)))
        assertEquals("forget_recent_none", r.event)
        assertNull(r.items)
    }

    @Test fun forgetRecentHasALimit() {
        assertEquals("forget_recent_refused", run("pichhle 3000 minute bhool jao").event)
        assertEquals("forget_recent_refused", run("pichhle 0 minute bhool jao").event)
        assertEquals("forget_recent", run("pichhle 24 ghante bhool jao", listOf(item("a", "x y z", now))).event)
    }

    @Test fun forgetEverythingAndNothing() {
        assertEquals("forget_none", run("bhool jao").event)
        val r = run("sab bhool jao", listOf(item("a", "x y z", now), item("b", "p q r", now)))
        assertEquals("forget_all", r.event)
        assertTrue(r.reply.contains("2"))
    }

    // ---------- showing ----------

    @Test fun showListsTheItemsAndMarksTemporaryOnes() {
        val items = listOf(
            item("name", "The user's name is Rahul.", now),
            MemoryBook.make("note:n", "mera number 9876543210", MemoryBook.WORK, MemoryBook.SRC_SAID, now)
        )
        val r = run("tum mere baare mein kya jaante ho", items)
        assertEquals("show", r.event)
        assertNull(r.items)
        assertTrue(r.reply.contains("Rahul"))
        assertTrue(r.reply.contains("9876543210 (abhi ke liye)") || r.reply.contains("(abhi ke liye)"))
        assertTrue(r.reply.contains("Settings > Memory"))
    }

    // ---------- ordinary messages ----------

    @Test fun ordinaryMessagesAreNotCommands() {
        assertNull(MemoryCommands.handle("python mein list kya hai", emptyList(), now, false))
        assertNull(MemoryCommands.handle("remember how to reverse a list?", emptyList(), now, false))
        assertNull(MemoryCommands.handle("pichhle hafte kya hua tha", emptyList(), now, false))
        assertNull(MemoryCommands.handle("", emptyList(), now, false))
    }

    // ---------- the old behaviour of LocalMemory.command is unchanged ----------

    @Test fun theOldCommandStillWorksOnPlainLines() {
        val out = LocalMemory.command("yaad rakho ki mera bhai Ali hai", emptyList())
        assertNotNull(out)
        assertEquals(1, out!!.updated!!.size)
        assertTrue(out.reply.startsWith("Theek hai, yaad rakh liya:"))
    }
}
