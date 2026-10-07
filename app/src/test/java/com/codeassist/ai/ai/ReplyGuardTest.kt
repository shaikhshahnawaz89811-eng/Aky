package com.codeassist.ai.ai

import com.codeassist.ai.data.Message
import com.codeassist.ai.data.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyGuardTest {
    private fun user(t: String) = Message(role = Role.USER, text = t)
    private fun ai(t: String) = Message(role = Role.AI, text = t)

    // Shape of the real offline reply to "Hello" (Transfer Dock log): fluent start, then foreign-script junk.
    private val junk1 = "sint\u5B57\u6837\u8BED\u8BED\u8BED\u8BED\u8BDD\u8BDD\u8BDD"
    private val junk2 = "sivee\u95EE\u9898\u95EE\u9898\u95EE\u9898\u95EE\u9898\u95EE\u9898"
    private val helloJunk =
        "Hello! How can I help you today? If you're ready, please ask your question or request whatever you need. " +
            "I'll help you either way, either way is fine! It'll be in whichever language you prefer, either use " +
            "your language or use English if you prefer. Regardless, we'll proceed accordingly. If you're unsure " +
            "or unsure is too unclear, letting us know is also okay! Anyways, we'll proceed accordingly. " +
            "Start your request, either start with \"" + junk1 + "\", \"" + junk2 + "``',or anything else, " +
            "depending on your preference or input. We'll proceed accordingly."

    // Real reply to "mera naam shahnawaz hai": a loop of single letters.
    private val nameLoop =
        "Your name is S. N. H. H. H. H. H. H. H. H. H. I. I. I. I. I. I. I. I. I. I. I. I. I."

    private val english =
        "Binary search works on a sorted array. You compare the target with the middle element. " +
            "If the target is smaller, you continue in the left half; otherwise you continue in the right half. " +
            "Each step halves the search space, so the time complexity is O(log n). It needs random access, " +
            "which is why it is used on arrays and not on linked lists."
    private val hinglish =
        "Kotlin mein coroutine ek halka thread jaisa hota hai. Aap launch ya async se kaam shuru karte ho, " +
            "aur suspend function beech mein ruk sakta hai bina thread block kiye. Isse network call ya database " +
            "ka kaam UI ko freeze kiye bina ho jata hai. Dispatchers.IO file aur network ke liye, Dispatchers.Default " +
            "heavy calculation ke liye, aur Dispatchers.Main UI update ke liye use hota hai."
    private val code =
        "Yeh raha code:\n```python\nfor i in range(10):\n    for j in range(10):\n        print(i, j)\n" +
            "        print(i, j)\n        print(i, j)\n        print(i, j)\n```\nIsse 100 lines print hongi."
    private val steps =
        "Steps:\n1. Open Settings\n2. Tap Apps\n3. Tap the app\n4. Tap Storage\n5. Tap Clear cache\n" +
            "6. Tap Clear data\n7. Restart the phone."
    private val parallel =
        "One of the best things about Android is choice. One of the main reasons is the open ecosystem. " +
            "One of the biggest advantages is customisation. One of the most useful parts is the widget system. " +
            "One of the nicest bits is the file access. The platform keeps growing every year with new devices " +
            "and ideas from many developers around the world."

    @Test fun junkAfterAGreetingIsCutAndTheGreetingStays() {
        val r = ReplyGuard.inspect(helloJunk, "Hello", true, true)
        assertTrue(r.ok)
        assertEquals(
            "Hello! How can I help you today? If you're ready, please ask your question or request whatever you need.",
            r.text
        )
        assertEquals("foreign script trimmed", r.problem)
    }

    @Test fun junkWithoutSmallTalkKeepsEverythingBeforeTheJunk() {
        val r = ReplyGuard.inspect(helloJunk, "Hello", false)
        assertTrue(r.ok)
        assertTrue(r.text.startsWith("Hello! How can I help you today?"))
        assertTrue(r.text.endsWith("we'll proceed accordingly."))
        assertFalse(r.text.contains("sint"))
    }

    @Test fun letterLoopIsRejectedSoTheAppCanRetry() {
        val r = ReplyGuard.inspect(nameLoop, "mera naam shahnawaz hai", true, true)
        assertFalse(r.ok)
        assertEquals("repetition", r.problem)
    }

    @Test fun phraseLoopKeepsTheCleanStart() {
        val loop = "Sure. " + "Anyways, let us proceed accordingly. ".repeat(10)
        val r = ReplyGuard.inspect(loop, "q", false)
        assertTrue(r.ok)
        assertEquals("Sure. Anyways, let us proceed accordingly.", r.text)
        assertEquals("repetition trimmed", r.problem)
    }

    @Test fun loopThatLeavesNothingUsableIsRejected() {
        val loop = "I can help with that. " + "I am not sure what you mean by that. ".repeat(7)
        val r = ReplyGuard.inspect(loop, "q", false)
        assertFalse(r.ok)
        assertEquals("I can help with that.", r.text)
    }

    @Test fun normalAnswersAreLeftAlone() {
        for (t in listOf(english, hinglish, code, steps, parallel)) {
            val r = ReplyGuard.inspect(t, "question", false)
            assertTrue(r.ok)
            assertNull(r.problem)
            assertEquals(t.trim(), r.text)
        }
    }

    @Test fun foreignScriptIsAllowedWhenTheUserAsksForIt() {
        val reply = "Konnichiwa means hello: \u3053\u3093\u306B\u3061\u306F"
        val asked = ReplyGuard.inspect(reply, "how do you say hello in japanese", false)
        assertTrue(asked.ok)
        assertNull(asked.problem)
        assertFalse(ReplyGuard.inspect(reply, "say hello", false).ok)
    }

    @Test fun cappedReplyIsCutAtTheLastSentence() {
        val cut = "Binary search works on a sorted array. You compare the target with the middle element. " +
            "If the target is smaller, you continue in the le"
        val r = ReplyGuard.inspect(cut, "x", true)
        assertTrue(r.ok)
        assertEquals("Binary search works on a sorted array. You compare the target with the middle element.", r.text)
        assertEquals("capped", r.problem)
    }

    @Test fun cappedReplyInsideACodeBlockClosesTheFence() {
        val r = ReplyGuard.inspect("Code:\n```python\nprint('a')\nprint('b", "x", true)
        assertTrue(r.ok)
        assertEquals("Code:\n```python\nprint('a')\nprint('b\n```", r.text)
    }

    @Test fun emptyReplyIsRejected() {
        val r = ReplyGuard.inspect("  \n ", "x", false)
        assertFalse(r.ok)
        assertEquals("empty", r.problem)
    }

    @Test fun greetingKeepsOnlyThreeSentences() {
        val r = ReplyGuard.inspect(
            "Hello! How can I help? I can answer questions. I can write code. I can set alarms.", "hi", false, true
        )
        assertTrue(r.ok)
        assertEquals("Hello! How can I help? I can answer questions.", r.text)
    }

    @Test fun historyDropsBrokenAiTurnsButKeepsEveryUserTurn() {
        val failed = Message(role = Role.AI, text = "Internet nahi mil raha.", state = "error")
        val history = listOf(
            user("Hello"), ai(helloJunk),
            user("mera naam shahnawaz hai"), ai(nameLoop),
            user("kya time hua?"), failed,
            ai("Abhi 3:45 PM baj rahe hain.")
        )
        val clean = ReplyGuard.cleanHistory(history)
        assertEquals(5, clean.size)
        assertEquals("Hello", clean[0].text)
        assertEquals(Role.AI, clean[1].role)
        assertTrue(clean[1].text.startsWith("Hello! How can I help you today?"))
        assertFalse(clean[1].text.contains("sint"))
        assertEquals("mera naam shahnawaz hai", clean[2].text)
        assertEquals("kya time hua?", clean[3].text)
        assertEquals("Abhi 3:45 PM baj rahe hain.", clean[4].text)
    }

    @Test fun smallTalkRules() {
        for (t in listOf("Hello", "hi there", "kaise ho", "kya haal hai", "ok theek hai", "thanks", "good morning",
            "mera naam shahnawaz hai", "my name is Sam", "mera naam kya hai")) {
            assertTrue(t, ReplyGuard.isSmallTalk(t))
        }
        for (t in listOf("kya hai", "theek hai", "python kya hai", "hello world program in python", "alarm laga do",
            "torch on karo", "write a function to reverse a string", "Isko jante hoo", "")) {
            assertFalse(t, ReplyGuard.isSmallTalk(t))
        }
    }

    @Test fun photoQuestionRule() {
        assertTrue(ReplyGuard.needsImage("Isko jante hoo", true, false))
        assertFalse(ReplyGuard.needsImage("hi", false, false))
        assertFalse(ReplyGuard.needsImage("isko padho", true, true))
        val longQuestion = "explain this error in detail with every step please and also give code samples for " +
            "fixing it right now ok"
        assertFalse(ReplyGuard.needsImage(longQuestion, true, false))
    }

    @Test fun runawayDigitsInsideACodeBlockAreRejectedSoTheAppCanRetry() {
        val bad = "```python\n print(\"Hello, World! 2" + "0".repeat(60) + "\n```"
        val r = ReplyGuard.inspect(bad, "Create python 2 line code", false)
        assertFalse(r.ok)
    }

    @Test fun separatorLinesAreNotRunaways() {
        val t = "Title\n" + "-".repeat(50) + "\nBody text that is long enough to be kept as a normal answer."
        assertTrue(ReplyGuard.inspect(t, "show a table", false).ok)
    }
}
