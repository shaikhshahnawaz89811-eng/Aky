package com.codeassist.ai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMemoryTest {
    @Test fun statedNameIsExtracted() {
        val f = LocalMemory.extract("mera naam Rahul hai")
        assertEquals(1, f.size)
        assertEquals("name", LocalMemory.kindOf(f[0]))
        assertTrue(LocalMemory.readable(f[0]).contains("Rahul"))
    }

    @Test fun nameStopsAtTheFirstFillerWord() {
        val f = LocalMemory.extract("Mera naam rahul sharma hai aur main student hoon")
        assertTrue(LocalMemory.readable(f[0]).contains("Rahul Sharma"))
    }

    @Test fun cityAndLikeAreExtracted() {
        assertTrue(LocalMemory.readable(LocalMemory.extract("main Pune mein rehta hoon")[0]).contains("Pune"))
        assertTrue(LocalMemory.readable(LocalMemory.extract("mujhe cricket pasand hai")[0]).contains("cricket"))
    }

    @Test fun questionsAndOrdinaryMessagesGiveNothing() {
        assertTrue(LocalMemory.extract("mera naam hai kya?").isEmpty())
        assertTrue(LocalMemory.extract("python mein list kya hai").isEmpty())
        assertTrue(LocalMemory.extract("").isEmpty())
    }

    @Test fun rememberCommandStoresTheNote() {
        val out = LocalMemory.command("yaad rakho ki mera bhai Ali hai", emptyList())
        assertNotNull(out)
        val updated = out!!.updated
        assertNotNull(updated)
        assertEquals(1, updated!!.size)
        assertTrue(LocalMemory.readable(updated[0]).contains("mera bhai Ali hai"))
    }

    @Test fun rememberQuestionIsNotACommand() {
        assertNull(LocalMemory.command("remember how to reverse a list?", emptyList()))
    }

    @Test fun showAndForget() {
        val facts = LocalMemory.extract("mera naam Rahul hai")
        val shown = LocalMemory.command("tum mere baare mein kya jaante ho", facts)
        assertNotNull(shown)
        assertTrue(shown!!.reply.contains("Rahul"))
        assertNull(shown.updated)
        val gone = LocalMemory.command("bhool jao", facts)
        assertNotNull(gone)
        assertEquals(0, gone!!.updated!!.size)
    }

    @Test fun sameKindReplacesAndListIsCapped() {
        var facts = LocalMemory.merge(emptyList(), LocalMemory.extract("mera naam Rahul hai"))
        facts = LocalMemory.merge(facts, LocalMemory.extract("mera naam Amit hai"))
        assertEquals(1, facts.size)
        assertTrue(LocalMemory.readable(facts[0]).contains("Amit"))
        for (i in 1..20) facts = LocalMemory.merge(facts, listOf(LocalMemory.encode("note:$i", "note $i")))
        assertEquals(LocalMemory.MAX_FACTS, facts.size)
        assertTrue(LocalMemory.readable(facts.last()).contains("note 20"))
    }

    @Test fun blockAndPackRoundTrip() {
        assertEquals("", LocalMemory.block(emptyList()))
        val facts = LocalMemory.extract("main Pune mein rehta hoon")
        assertTrue(LocalMemory.block(facts).contains("Pune"))
        assertEquals(facts, LocalMemory.unpack(LocalMemory.pack(facts)))
    }
}
