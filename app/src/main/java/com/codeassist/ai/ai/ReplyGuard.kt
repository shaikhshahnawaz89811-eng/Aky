package com.codeassist.ai.ai

import com.codeassist.ai.data.Message
import com.codeassist.ai.data.Role

/**
 * Safety net for the on-device model. Pure Kotlin, no Android types, so it is unit-tested on the JVM.
 *
 * A 1.5B model on a phone sometimes degenerates: it never stops, writes foreign-script junk
 * ("sint字样语语语") or loops ("I. I. I. I."). The Free llama-android API cannot stop it mid-way, so the
 * app checks the finished text here, keeps the clean beginning when there is one, and asks for a retry
 * when there is not. The same check keeps such replies out of the history that goes back into the next
 * prompt (a junk reply in the prompt makes the next reply worse).
 */
object ReplyGuard {
    /** [ok] false = unusable. [problem] is a short code for the KPI screen; also set when [text] was trimmed. */
    class Result(val text: String, val ok: Boolean, val problem: String?)

    private class Word(val norm: String, val start: Int, val raw: String)
    private class Loop(val cut: Int, val lowVariety: Boolean)

    private const val MIN_KEEP = 25
    private const val RUN_MIN = 5
    private const val RUN_MIN_CODE = 8
    private const val GRAM_MIN_COUNT = 5
    private const val GRAM_SEEN = 4
    private const val GRAM_MIN_COVER = 0.30
    private const val LOOP_MIN_WORDS = 24
    private const val VARIETY_MIN_WORDS = 40
    private const val VARIETY_MIN_RATIO = 0.40
    private const val SMALL_TALK_SENTENCES = 3
    private const val SMALL_TALK_CHARS = 260
    private const val IMAGE_QUESTION_MAX_WORDS = 14

    /** If the user's own message mentions one of these, foreign-script text in the reply is expected. */
    private val foreignOk = listOf(
        "japan", "chinese", "china", "korean", "korea", "mandarin", "kanji", "hanzi", "translat", "anuvad", "unicode"
    )

    /** One character repeated 30+ times ("2000000000000..."): a digit or letter runaway, not a separator line. */
    private val charRun = Regex("([^\\s\\-=*#_.~+|/\\\\])\\1{29,}")

    private val wordRegex = Regex("\\S+")
    private val sentenceEnds = charArrayOf('.', '!', '?', '\u0964', '\n')

    private val greetVocab = setOf(
        "hello", "hi", "hii", "hey", "hlo", "namaste", "namaskar", "salam", "assalamualaikum", "thanks", "thank",
        "you", "thankyou", "shukriya", "dhanyavad", "ok", "okay", "theek", "thik", "accha", "achha", "bye", "good",
        "morning", "night", "evening", "afternoon", "kaise", "ho", "kya", "haal", "hai", "aap", "bhai", "yaar",
        "dost", "ji", "sir", "bro", "there", "how", "are", "u", "kese", "kaisa", "chal", "raha"
    )
    private val greetAnchors = setOf(
        "hello", "hi", "hii", "hey", "hlo", "namaste", "namaskar", "salam", "assalamualaikum", "thanks", "thank",
        "thankyou", "shukriya", "dhanyavad", "ok", "okay", "bye", "good", "kaise", "kese", "kaisa", "haal"
    )
    private val smallTalkSplit = Regex("[^\\p{L}\\p{N}']+")

    /**
     * Checks one finished reply. [userText] is what the user wrote (to allow Chinese when they asked for it),
     * [capped] means the model ran into the token limit, [smallTalk] limits a greeting to a few sentences.
     */
    fun inspect(raw: String, userText: String, capped: Boolean, smallTalk: Boolean = false): Result {
        val text = raw.trim()
        if (text.isEmpty()) return Result("", false, "empty")

        var cut = -1
        var why = ""
        val foreign = foreignStart(text, userText)
        if (foreign >= 0) {
            cut = foreign
            why = "foreign script"
        }
        val loop = loopStart(text)
        if (loop.cut >= 0 && (cut < 0 || loop.cut < cut)) {
            cut = loop.cut
            why = "repetition"
        }
        val run = charRun.find(text)
        var runawayCut = false
        if (run != null && (cut < 0 || run.range.first < cut)) {
            cut = run.range.first
            why = "character run"
            runawayCut = true
        }
        if (cut >= 0) {
            val kept = trimToSentence(text.substring(0, cut), true)
            // a code block that was cut open by a runaway is broken code: ask again instead of showing it
            if (runawayCut && countFences(kept) % 2 == 1) return Result(kept, false, why)
            if (kept.length >= MIN_KEEP) return Result(finishSmall(kept, smallTalk), true, why + " trimmed")
            return Result(kept, false, why)
        }
        if (loop.lowVariety) return Result(text, false, "low variety")

        var out = text
        var problem: String? = null
        if (capped) {
            out = if (countFences(out) % 2 == 1) out + "\n```" else trimToSentence(out, false)
            problem = "capped"
        }
        val small = finishSmall(out, smallTalk)
        if (small != out && problem == null) problem = "small talk trimmed"
        return Result(small, true, problem)
    }

    /**
     * The history that may go back into a prompt: failed replies are dropped, broken AI replies are dropped,
     * rambling ones are trimmed. User turns always stay (they carry facts such as the user's name).
     */
    fun cleanHistory(previous: List<Message>): List<Message> {
        val out = ArrayList<Message>(previous.size)
        var lastUser = ""
        for (m in previous) {
            if (m.state != null) continue
            val t = m.text.trim()
            if (t.isEmpty()) continue
            if (m.role == Role.USER) {
                lastUser = t
                out.add(m)
            } else {
                val r = inspect(t, lastUser, false)
                if (r.ok) out.add(if (r.text == t) m else m.copy(text = r.text))
            }
        }
        return out
    }

    /** A short greeting / thanks / "my name is ..." message: the reply should be one to three short sentences. */
    fun isSmallTalk(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isEmpty() || t.length > 60 || t.contains('\n')) return false
        val ws = t.split(smallTalkSplit).filter { it.isNotEmpty() }
        if (ws.isEmpty() || ws.size > 8) return false
        if (ws.all { it in greetVocab } && ws.any { it in greetAnchors }) return true
        return t.startsWith("mera naam") || t.startsWith("my name is") || t.startsWith("mera name")
    }

    /**
     * True when the message is most likely about a picture the text-only model cannot see: an image was
     * attached, nothing readable came out of it (no OCR text, no text file) and the question is short.
     */
    fun needsImage(userText: String, hasImage: Boolean, hasOtherText: Boolean): Boolean {
        if (!hasImage || hasOtherText) return false
        val words = userText.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return words.size <= IMAGE_QUESTION_MAX_WORDS
    }

    // ---------- detectors ----------

    private fun isForeign(ch: Char): Boolean {
        val c = ch.code
        return (c in 0x3040..0x30FF) || (c in 0x3400..0x4DBF) || (c in 0x4E00..0x9FFF) || (c in 0xAC00..0xD7AF)
    }

    /** Index of the first Chinese / Japanese / Korean character when the user did not write or ask for any. */
    private fun foreignStart(text: String, userText: String): Int {
        if (userText.any { isForeign(it) }) return -1
        val low = userText.lowercase()
        if (foreignOk.any { low.contains(it) }) return -1
        var first = -1
        var count = 0
        for (i in text.indices) {
            if (isForeign(text[i])) {
                if (first < 0) first = i
                count++
            }
        }
        return if (count >= 2) first else -1
    }

    private fun hasLetter(s: String): Boolean = s.any { it.isLetter() }

    private fun loopStart(text: String): Loop {
        val hasCode = text.contains("```")
        val words = ArrayList<Word>()
        for (m in wordRegex.findAll(text)) {
            val n = m.value.lowercase().trim { !it.isLetterOrDigit() }
            if (n.isNotEmpty()) words.add(Word(n, m.range.first, m.value))
        }
        val n = words.size
        if (n == 0) return Loop(-1, false)

        var best = -1
        // the same word again and again: "I. I. I. I. I."
        val need = if (hasCode) RUN_MIN_CODE else RUN_MIN
        var i = 0
        while (i < n) {
            var j = i
            while (j + 1 < n && words[j + 1].norm == words[i].norm) j++
            if (j - i + 1 >= need && hasLetter(words[i].raw) && i + 1 < n) {
                best = words[i + 1].start
                break
            }
            i = j + 1
        }

        // a three-word phrase that keeps coming back and makes up a large part of the text
        if (!hasCode && n >= LOOP_MIN_WORDS) {
            val positions = HashMap<String, MutableList<Int>>()
            for (k in 0 until n - 2) {
                val g = words[k].norm + " " + words[k + 1].norm + " " + words[k + 2].norm
                positions.getOrPut(g) { ArrayList() }.add(k)
            }
            val covered = BooleanArray(n)
            for (lst in positions.values) {
                if (lst.size >= GRAM_SEEN) {
                    for (k in lst) {
                        covered[k] = true
                        covered[k + 1] = true
                        covered[k + 2] = true
                    }
                }
            }
            val coveredCount = covered.count { it }
            if (coveredCount.toDouble() / n >= GRAM_MIN_COVER) {
                for ((g, lst) in positions) {
                    if (lst.size >= GRAM_MIN_COUNT && hasLetter(g)) {
                        val c = words[lst[1]].start
                        if (best < 0 || c < best) best = c
                    }
                }
            }
        }
        if (best >= 0) return Loop(best, false)

        if (!hasCode && n >= VARIETY_MIN_WORDS) {
            val unique = HashSet<String>()
            for (w in words) unique.add(w.norm)
            if (unique.size.toDouble() / n < VARIETY_MIN_RATIO) return Loop(-1, true)
        }
        return Loop(-1, false)
    }

    // ---------- trimming ----------

    /** Cuts after the last sentence end. Without [force] the end must lie in the second half of the text. */
    private fun trimToSentence(s: String, force: Boolean): String {
        var idx = -1
        for (c in sentenceEnds) idx = maxOf(idx, s.lastIndexOf(c))
        if (idx >= 0 && (force || idx >= maxOf(20, (s.length * 0.4).toInt()))) {
            return s.substring(0, idx + 1).trim()
        }
        return s.trim()
    }

    private fun finishSmall(t: String, smallTalk: Boolean): String =
        if (smallTalk) firstSentences(t, SMALL_TALK_SENTENCES, SMALL_TALK_CHARS) else t

    private fun firstSentences(t: String, k: Int, maxChars: Int): String {
        var count = 0
        var end = -1
        for (i in t.indices) {
            val ch = t[i]
            if ((ch == '.' || ch == '!' || ch == '?' || ch == '\u0964') && (i + 1 == t.length || t[i + 1].isWhitespace())) {
                count++
                if (count == k) {
                    end = i + 1
                    break
                }
            }
        }
        var out = if (end > 0) t.substring(0, end) else t
        if (out.length > maxChars) {
            val cut = trimToSentence(out.substring(0, maxChars), false)
            out = if (cut.isNotEmpty()) cut else out.substring(0, maxChars)
        }
        return out.trim()
    }

    private fun countFences(s: String): Int = s.split("```").size - 1
}
