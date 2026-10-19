package com.codeassist.ai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun askingForTheNameIsNotAName() {
        assertTrue(LocalMemory.extract("Mera naam btao").isEmpty())
        assertTrue(LocalMemory.extract("mera naam kya hai").isEmpty())
    }

    @Test fun cutOffNoteIsNotSaved() {
        val out = LocalMemory.command("Yaad rakhna Mera naam or meri bhanji ko", emptyList())
        assertNotNull(out)
        assertNull(out!!.updated)
    }

    // ---------- test chat 13 Oct 2026: "mera naam jante hoo" saved the name "Jante" ----------

    @Test fun questionWordsAreNeverAName() {
        for (q in listOf(
            "Mera naam jante hoo", "mera naam jaante ho", "mera naam yaad hai", "mera naam yaad hai?",
            "kya tum mera naam jante ho", "mera naam pata hai"
        )) assertTrue(q, LocalMemory.extract(q).isEmpty())
    }

    @Test fun aSurnameThatLooksLikeAFillerIsKept() {
        assertTrue(LocalMemory.readable(LocalMemory.extract("mera naam Ritu Kar hai")[0]).contains("Ritu Kar"))
    }

    @Test fun askingForTheNameIsAnAnswerNotAModelCall() {
        for (q in listOf(
            "Mera naam jante hoo", "kya tum mera naam jante ho", "mera naam kya hai?", "tumhe mera naam yaad hai",
            "mujhe mera naam batao", "What's my name?", "do you know my name"
        )) assertTrue(q, LocalMemory.parse(q) is LocalMemory.Cmd.AskName)
        assertNull(LocalMemory.parse("mera naam Rahul hai"))
        assertNull(LocalMemory.parse("Nahin mera naam shahnawaz"))
        assertNull(LocalMemory.parse("mera naam yaad rakhna"))
    }

    @Test fun theNameIsReadOutOrAskedFor() {
        val facts = LocalMemory.extract("mera naam Shahnawaz hai")
        val known = LocalMemory.command("mera naam kya hai", facts)
        assertNotNull(known)
        assertEquals("Aapka naam Shahnawaz hai.", known!!.reply)
        assertNull(known.updated)
        val unknown = LocalMemory.command("mera naam jante hoo", emptyList())
        assertNotNull(unknown)
        assertEquals(LocalMemory.NAME_UNKNOWN_REPLY, unknown!!.reply)
        assertNull(unknown.updated)
    }

    @Test fun anOldWrongNameIsNeverReadOut() {
        val wrong = listOf(LocalMemory.encode("name", "The user's name is Jante."))
        val out = LocalMemory.command("mera naam kya hai", wrong)
        assertEquals(LocalMemory.NAME_UNKNOWN_REPLY, out!!.reply)
    }

    @Test fun aWrongNameIsLeftOutOfThePrompt() {
        val wrong = MemoryBook.make("name", "The user's name is Jante.", MemoryBook.LONG, MemoryBook.SRC_AUTO, 1L)
        val city = MemoryBook.make("lives", "The user lives in Pune.", MemoryBook.LONG, MemoryBook.SRC_AUTO, 2L)
        val block = MemoryBook.promptBlock(listOf(wrong, city), "hello", 10L)
        assertFalse(block, block.contains("Jante"))
        assertTrue(block, block.contains("Pune"))
        val right = MemoryBook.make("name", "The user's name is Shahnawaz.", MemoryBook.LONG, MemoryBook.SRC_AUTO, 3L)
        assertTrue(MemoryBook.promptBlock(listOf(right), "hello", 10L).contains("Shahnawaz"))
    }

    // ---------- T2: the language choice (test chat 13 Oct 2026) ----------

    @Test fun aHinglishChoiceIsLearnedFromAShortCommand() {
        val phrases = listOf(
            "Hinglish me batao", "hinglish mein jawab do", "ab se hinglish me baat karo", "Reply in Hinglish",
            "hinglish only", "Roman hinglish me bolo", "hindi in english letters me batao", "kal 6 baje utha dena, hinglish me batao"
        )
        for (s in phrases) assertEquals(s + " -> hinglish", s + " -> " + LocalMemory.languageChoice(s))
        val f = LocalMemory.extract("Hinglish me batao")
        assertEquals(1, f.size)
        assertEquals("reply_language", LocalMemory.kindOf(f[0]))
        assertEquals("hinglish", LocalMemory.languageOfFact(LocalMemory.readable(f[0])))
    }

    @Test fun anEnglishChoiceIsLearnedToo() {
        val phrases = listOf(
            "English me batao", "english mein jawab do", "reply in english", "talk to me in English",
            "answer me in english please", "english only", "hamesha english me baat karo"
        )
        for (s in phrases) assertEquals(s + " -> english", s + " -> " + LocalMemory.languageChoice(s))
    }

    @Test fun oneOffTasksAndTalkAboutLanguagesAreNotAChoice() {
        val tasks = listOf(
            "english me translate karo", "hinglish me likho", "mujhe english seekhni hai", "main hamesha english seekhna chahta hoon",
            "write an email in english", "hinglish kya hota hai", "english grammar samjhao", "ye hinglish me likha hai", "hello"
        )
        for (s in tasks) assertNull(s, LocalMemory.languageChoice(s))
        // a question is never a statement about the user, so extract() skips it before the language rule is even looked at
        assertTrue(LocalMemory.extract("kya tum hinglish me baat kar sakte ho?").isEmpty())
    }

    @Test fun theLaterLanguagePhraseWins() {
        assertEquals("hinglish", LocalMemory.languageChoice("english me nahi, hinglish me batao"))
        assertEquals("english", LocalMemory.languageChoice("hinglish me nahi english me batao"))
    }

    @Test fun aNameAndALanguageChoiceInOneMessageAreBothKept() {
        val f = LocalMemory.extract("mera naam Rahul hai, hinglish me batao")
        assertEquals(2, f.size)
        assertEquals(setOf("name", "reply_language"), f.map { LocalMemory.kindOf(it) }.toSet())
    }

    @Test fun aStoredSentenceKnowsItsLanguage() {
        assertEquals("hinglish", LocalMemory.languageOfFact(LocalMemory.FACT_HINGLISH))
        assertEquals("english", LocalMemory.languageOfFact(LocalMemory.FACT_ENGLISH))
        assertEquals("english", LocalMemory.languageOfFact("reply in English please"))
        assertNull(LocalMemory.languageOfFact("something else"))
    }
}
