package com.codeassist.ai.voice

/**
 * Decides whether a short transcript contains the wake phrase (audit PDF Sec 9.1 "Wake word", Gap B4).
 * Pure Kotlin, no Android types (JVM unit tested).
 *
 * The wake engine only sees the transcript the on-device recognizer produced, and that transcript often
 * misses the first word or two (the recognizer starts a moment after the voice detector fired). So the
 * greeting part of the phrase ("hey", "ok") is never required, and the sensitivity levels are defined by
 * how many of the remaining key words must be heard:
 *
 *  - STRICT : every key word, spelled almost exactly (similarity >= 0.85). Fewest false accepts.
 *  - NORMAL : every key word (>= 0.75), or, for a phrase of two or more key words, just the last key word
 *             when it is long (>= 5 letters) and clearly heard (>= 0.85). Tolerates a clipped start.
 *  - LOOSE  : any one key word of 4+ letters (>= 0.8). Most hits, most false accepts.
 */
object WakeMatcher {
    enum class Level { STRICT, NORMAL, LOOSE }

    /** [remainder] = what was said after the phrase ("hey jarvis time batao" -> "time batao"). */
    class Result(val matched: Boolean, val score: Float, val remainder: String)

    private val NO_MATCH = Result(false, 0f, "")

    private val greetings = setOf("hey", "hi", "hello", "ok", "okay", "oye", "arre", "are", "ae", "he")

    private val commonNames = setOf(
        "rani", "sara", "priya", "riya", "pooja", "neha", "anaiza", "ravi", "rahul", "amit", "raj",
        "siri", "alexa", "google", "mummy", "papa", "bhai", "didi"
    )

    fun levelOf(name: String): Level = when (name) {
        "strict" -> Level.STRICT
        "loose" -> Level.LOOSE
        else -> Level.NORMAL
    }

    fun normalize(raw: String): String =
        raw.lowercase()
            .replace(Regex("[^\\p{L}\\p{M}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun words(raw: String): List<String> {
        val n = normalize(raw)
        return if (n.isEmpty()) emptyList() else n.split(' ')
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }

    fun similarity(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val d = levenshtein(a, b)
        return 1f - d.toFloat() / maxOf(a.length, b.length).toFloat()
    }

    /**
     * Word-level similarity. Recognizers like to "correct" a word into a longer one ("assist" -> "assistant"),
     * so a long word that is the start of the other (up to 3 extra letters) counts as a close match.
     */
    fun wordSimilarity(a: String, b: String): Float {
        val base = similarity(a, b)
        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length <= b.length) b else a
        if (shorter.length >= 5 && longer.startsWith(shorter) && longer.length - shorter.length <= 3) {
            return maxOf(base, 0.9f)
        }
        return base
    }

    private fun keyWords(phrase: List<String>): List<String> {
        val key = phrase.filter { it !in greetings }
        return if (key.isEmpty()) phrase else key
    }

    private fun remainderFrom(t: List<String>, from: Int): String =
        if (from >= t.size) "" else t.subList(from, t.size).joinToString(" ")

    /** Best result over the recognizer's alternatives (first = most likely). */
    fun matchAny(transcripts: List<String>, phrase: String, level: Level): Result {
        var best = NO_MATCH
        for (t in transcripts) {
            val r = match(t, phrase, level)
            if (r.matched && (!best.matched || r.score > best.score)) best = r
        }
        return best
    }

    fun match(transcript: String, phrase: String, level: Level): Result {
        val t = words(transcript)
        val p = words(phrase)
        if (t.isEmpty() || p.isEmpty()) return NO_MATCH
        val key = keyWords(p)

        val wordSim = when (level) {
            Level.STRICT -> 0.85f
            Level.NORMAL -> 0.75f
            Level.LOOSE -> 0.8f
        }

        if (level == Level.LOOSE) {
            // any one distinctive key word is enough
            var bestIdx = -1
            var bestScore = 0f
            for (k in key) {
                if (k.length < 4) continue
                for (i in t.indices) {
                    val s = wordSimilarity(t[i], k)
                    if (s >= wordSim && (s > bestScore || (s == bestScore && i > bestIdx))) {
                        bestScore = s
                        bestIdx = i
                    }
                }
            }
            if (bestIdx >= 0) return Result(true, bestScore, remainderFrom(t, bestIdx + 1))
            return joinedWindow(t, key, 0.8f) ?: NO_MATCH
        }

        // all key words, in order, next to each other
        if (t.size >= key.size) {
            for (start in 0..(t.size - key.size)) {
                var sum = 0f
                var ok = true
                for (j in key.indices) {
                    val s = wordSimilarity(t[start + j], key[j])
                    if (s < wordSim) {
                        ok = false
                        break
                    }
                    sum += s
                }
                if (ok) return Result(true, sum / key.size, remainderFrom(t, start + key.size))
            }
        }

        // recognizers sometimes glue or split words: "jarvis" / "jar vis"
        val joined = joinedWindow(t, key, if (level == Level.STRICT) 0.88f else 0.8f)
        if (joined != null) return joined

        // NORMAL only: the front of the phrase was clipped, so accept a long last key word on its own
        if (level == Level.NORMAL && key.size >= 2) {
            val last = key.last()
            if (last.length >= 5) {
                for (i in t.indices) {
                    val s = wordSimilarity(t[i], last)
                    if (s >= 0.85f) return Result(true, s * 0.8f, remainderFrom(t, i + 1))
                }
            }
        }
        return NO_MATCH
    }

    /** Joins 1..3 neighbouring transcript words and compares them with the joined key words. */
    private fun joinedWindow(t: List<String>, key: List<String>, minSim: Float): Result? {
        val target = key.joinToString("")
        if (target.length < 5) return null
        for (start in t.indices) {
            var acc = ""
            for (len in 1..3) {
                val idx = start + len - 1
                if (idx >= t.size) break
                acc += t[idx]
                val s = similarity(acc, target)
                if (s >= minSim) return Result(true, s, remainderFrom(t, idx + 1))
            }
        }
        return null
    }

    /** Rough syllable count: vowel groups in Latin script, consonant / vowel letters in Devanagari. */
    fun syllables(word: String): Int {
        var n = 0
        var inVowel = false
        for (ch in word) {
            val deva = ch in '\u0904'..'\u0939'
            val latinVowel = ch in "aeiouy"
            if (deva) {
                n++
                inVowel = false
            } else if (latinVowel) {
                if (!inVowel) n++
                inVowel = true
            } else {
                inVowel = false
            }
        }
        return if (n == 0 && word.isNotEmpty()) 1 else n
    }

    /** A hint for the settings screen: null = the phrase looks fine. */
    fun phraseWarning(phrase: String): String? {
        val w = words(phrase)
        if (w.isEmpty()) return "Phrase khali hai."
        val key = keyWords(w)
        if (key.any { it in commonNames }) {
            return "Isme aam naam / bolchaal ka shabd hai (jaise Rani, Sara, Google). Ghar ki baat-cheet se baar-baar trigger hoga."
        }
        val syl = w.sumOf { syllables(it) }
        if (syl < 3) return "Bahut chhota hai. 3-4 syllable ka alag sa phrase rakho (jaise \"hey jarvis\")."
        if (key.joinToString("").length < 6) return "Key shabd bahut chhote hain; false trigger zyada honge."
        return null
    }
}
