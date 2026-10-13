package com.codeassist.ai.ai

/**
 * Small on-device "yaad" list for the offline brain. Pure Kotlin, no Android types, so it is unit-tested on the JVM.
 *
 * Why it exists: the Qwen prompt is rebuilt from scratch on every turn and old turns fall out of the 4096-token
 * window, so a name told five minutes ago was gone. Gemini does not have that problem inside one chat because it
 * gets far more history. Instead of enlarging the window (every extra token is re-read on the CPU each turn), the
 * few facts that matter are kept here, in a few lines, and put at the top of every offline prompt.
 *
 * Two ways in:
 *  - [extract]: explicit self-descriptions only ("mera naam Rahul hai", "main Pune mein rehta hoon",
 *    "mujhe cricket pasand hai"). Nothing is guessed.
 *  - [command]: "yaad rakho ki ...", "tum mere baare mein kya jaante ho", "bhool jao". These never reach the model,
 *    so they work every time.
 *
 * A stored line looks like "kind|text". Lines with the same kind replace each other (a new name replaces the old).
 */
object LocalMemory {
    const val MAX_FACTS = 12
    private const val MAX_TEXT = 140

    /** [updated] is the new fact list, or null when nothing changed. */
    class Outcome(val reply: String, val updated: List<String>?)

    private const val L = "[\\p{L}\\p{M}]"
    private const val WORDS = "$L+(?:\\s+$L+){0,2}"

    private val stopWords = setOf(
        "hai", "hain", "h", "he", "hoon", "hu", "hun", "hoo", "ho", "aur", "and", "or", "mein", "me", "mai",
        "ko", "ka", "ki", "ke", "se", "par", "pe", "bahut", "bohot", "ji", "bhi", "toh", "to", "na", "yaar",
        "rehta", "rehti", "pasand", "passand", "lekin", "but", "ye", "yeh", "wo", "woh", "kuch", "sab", "koi",
        "kya", "kaun", "kahan", "kab", "btao", "batao", "bata", "bataiye", "bolo", "sunao", "pata", "nahi", "nahin", "kon",
        // Test-chat fix (13 Oct 2026): "mera naam jante hoo" saved the name "Jante". Words of a QUESTION or an order about
        // the name are never part of a name. ("kar" is left out on purpose: Kar is a real surname.)
        "jante", "jaante", "janta", "jaanta", "jaanti", "janti", "yaad", "yad", "tumhe", "tumko", "aapko", "kaise",
        "kyu", "kyun", "kyon", "bhool", "rakho", "rakhna", "rakhiye", "dikhao", "likho"
    )

    /** A note that ends on one of these is a cut-off sentence ("mera naam or meri bhanji ko"): not worth keeping. */
    private val danglingEnd = setOf("ko", "ka", "ki", "ke", "or", "aur", "and", "ye", "yeh", "wo", "woh", "mera", "meri", "mere", "se")

    private val nameRx = Regex(
        "(?i)(?<!$L)(?:mera\\s+naam|mera\\s+name|meraa\\s+naam|my\\s+name\\s+is)\\s+($WORDS)"
    )
    private val livesRx = Regex(
        "(?i)(?<!$L)(?:main|mai|mein)\\s+($WORDS)\\s+(?:mein|me|mai)\\s+reh(?:ta|ti)\\s+(?:hoon|hu|hun|hoo)(?!$L)"
    )
    private val livesEnRx = Regex("(?i)(?<!$L)i\\s+live\\s+in\\s+($WORDS)")
    private val likesRx = Regex(
        "(?i)(?<!$L)mujhe\\s+($L+(?:\\s+$L+){0,3})\\s+(?:pasand|passand|pasnd)\\s+(?:hai|h|hain)(?!$L)"
    )

    private val rememberRx = Regex(
        "(?i)^\\s*(?:yaad\\s+rakho|yad\\s+rakho|yaad\\s+rakhna|remember|note\\s+kar\\s+lo|note\\s+karo)" +
            "(?:\\s+(?:ki|that|ye|ye\\s+ki))?[\\s:,-]+(.{3,})$"
    )
    private val showRx = Regex(
        "(?i)^\\s*(?:tum\\s+|aap\\s+)?(?:mere\\s+(?:baare|bare)\\s+mein\\s+(?:kya|kuch)\\s+(?:jaante|jante|pata|yaad)\\b.*|" +
            "kya\\s+yaad\\s+hai.*|what\\s+do\\s+you\\s+(?:remember|know)\\s+about\\s+me.*|meri\\s+yaad(?:dasht)?\\s+dikhao.*)$"
    )
    /**
     * "mera naam kya hai", "kya tum mera naam jante ho", "tumhe mera naam yaad hai?" (Roman Hinglish) and "what is my name",
     * "do you know my name" (English). Matched on the whole message after [plain]. A statement ("mera naam Rahul hai") or an
     * order ("mera naam yaad rakhna") does not match: the word right after "mera naam" must be a question word.
     */
    private val askNameHiRx = Regex(
        "^(?:(?:kya|tum|tumhe|tumko|aap|aapko|aapke|mujhe|suno|achha|accha|acha|ok|okay|bhai|yaar)\\s+)*" +
            "(?:mera|meraa)\\s+(?:naam|name|nam)\\s+" +
            "(?:kya|kaun|kon|jante|jaante|janta|jaanta|jaanti|yaad|yad|pata|batao|bata|btao|bolo|bataiye|sunao)" +
            "(?:\\s+(?:hai|h|he|hain|ho|hoo|hu|hoon|kya|na|yaar|tumhe|tumko|aapko|aap|tum|bhi|mujhe|ji))*$"
    )
    private val askNameEnRx = Regex(
        "^(?:(?:hey|hi|ok|okay|please|pls)\\s+)*" +
            "(?:do\\s+you\\s+(?:know|remember)|can\\s+you\\s+(?:tell|remind)\\s+me|what\\s+is|what\\s+s|whats|tell\\s+me|remind\\s+me)" +
            "\\s+my\\s+name(?:\\s+(?:please|pls))?$"
    )

    private val forgetRx = Regex(
        "(?i)^\\s*(?:sab\\s+(?:kuch\\s+)?bhool\\s+jao|sab\\s+bhool\\s+ja|bhool\\s+jao|forget\\s+everything|forget\\s+me|" +
            "yaad\\s+(?:hata|mita|saaf)\\s+do|memory\\s+clear\\s+karo)\\s*[.!]?\\s*$"
    )

    // ---------- the language choice (T2, test chat 13 Oct 2026) ----------

    const val KIND_REPLY_LANGUAGE = "reply_language"
    const val LANG_HINGLISH = "hinglish"
    const val LANG_ENGLISH = "english"
    const val FACT_HINGLISH = "The user wants all replies in Roman Hinglish (Hindi written in English letters)."
    const val FACT_ENGLISH = "The user wants all replies in English."

    private const val LANG_HI_NAMES = "(?:hinglish|roman\\s+hinglish|roman\\s+hindi|hindi\\s+in\\s+english(?:\\s+letters)?)"
    private const val LANG_POST = "(?:me|mein|mai|main|mei|m)"
    private const val LANG_SPEAK =
        "(?:batao|bata|btao|bataiye|bolo|bol|bolna|jawab|jawaab|jwab|reply|answer|baat|baatein|baten|respond|speak|talk)"

    /**
     * The phrases that choose a language, matched on [plain] text. Conversation verbs only (batao, jawab, baat): "english me
     * translate karo" and "hinglish me likho" are one-off tasks, not a choice. Three shapes: "hinglish me (hi) batao",
     * "reply in hinglish", and "hinglish only" / "ab se hinglish me".
     */
    private fun languageShapes(name: String): List<Regex> = listOf(
        Regex("(?<!$L)$name\\s+$LANG_POST\\s+(?:hi\\s+)?$LANG_SPEAK(?!$L)"),
        Regex("(?<!$L)(?:reply|answer|respond|speak|talk)\\s+(?:to\\s+me\\s+|me\\s+)?in\\s+$name(?!$L)"),
        Regex(
            "(?<!$L)(?:$name\\s+only|(?:ab\\s+se|aaj\\s+se|hamesha|always|from\\s+now(?:\\s+on)?)" +
                "\\s+(?:sirf\\s+|only\\s+)?$name\\s+$LANG_POST)(?!$L)"
        )
    )

    private val languageRxs: List<Pair<String, List<Regex>>> = listOf(
        LANG_HINGLISH to languageShapes(LANG_HI_NAMES),
        LANG_ENGLISH to languageShapes("english")
    )

    /**
     * "hinglish" or "english" when [text] tells the assistant which language to answer in; null otherwise. When a message holds
     * both ("english me nahi, hinglish me batao") the later phrase wins.
     */
    fun languageChoice(text: String): String? {
        val p = plain(text)
        if (p.isEmpty()) return null
        var bestEnd = -1
        var best: String? = null
        for ((lang, shapes) in languageRxs) {
            for (rx in shapes) {
                for (m in rx.findAll(p)) {
                    if (m.range.last > bestEnd) {
                        bestEnd = m.range.last
                        best = lang
                    }
                }
            }
        }
        return best
    }

    /** The stored line (kind reply_language) for a choice. The same kind replaces the older one, so the newest choice is the only one. */
    fun languageFact(lang: String): String =
        encode(KIND_REPLY_LANGUAGE, if (lang == LANG_ENGLISH) FACT_ENGLISH else FACT_HINGLISH)

    /** The language a stored sentence stands for (the user may have edited it in the Memory screen); null when it names none. */
    fun languageOfFact(text: String): String? {
        val t = text.lowercase()
        return when {
            t.contains("hinglish") -> LANG_HINGLISH
            t.contains("english") -> LANG_ENGLISH
            else -> null
        }
    }

    // ---------- reading what the user said ----------

    /** Facts the user stated about themselves in [text]; empty when there are none. Encoded "kind|text". */
    fun extract(text: String): List<String> {
        val t = text.trim()
        if (t.isEmpty() || t.length > 300) return emptyList()
        if (t.endsWith("?")) return emptyList() // a question is not a statement about the user
        val out = ArrayList<String>()
        pick(nameRx, t, 2)?.let { out.add(encode("name", "The user's name is " + titleCase(it) + ".")) }
        (pick(livesRx, t, 3) ?: pick(livesEnRx, t, 3))?.let {
            out.add(encode("lives", "The user lives in " + titleCase(it) + "."))
        }
        pick(likesRx, t, 4)?.let { out.add(encode("like:" + it.lowercase(), "The user likes " + it.lowercase() + ".")) }
        // T2: "Hinglish me batao" is a standing choice of language, kept like a fact (kind reply_language)
        languageChoice(t)?.let { out.add(languageFact(it)) }
        return out
    }

    private fun pick(rx: Regex, text: String, maxWords: Int): String? {
        val m = rx.find(text) ?: return null
        val words = m.groupValues[1].trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val kept = ArrayList<String>()
        for (w in words) {
            if (w.lowercase() in stopWords) break
            kept.add(w)
            if (kept.size == maxWords) break
        }
        if (kept.isEmpty()) return null
        val s = kept.joinToString(" ")
        return if (s.length > 40) null else s
    }

    private fun titleCase(s: String): String =
        s.split(" ").joinToString(" ") { w -> if (w.isEmpty()) w else w.substring(0, 1).uppercase() + w.substring(1) }

    /** What a whole-message memory command asks for (Phase 4A: the grammar is separate from what is done with it). */
    sealed class Cmd {
        object Forget : Cmd()
        object Show : Cmd()
        class Remember(val note: String) : Cmd()

        /** "mera naam kya hai" / "mera naam jante ho": answered from the stored name, never by the model. */
        object AskName : Cmd()

        /** "yaad rakho ki mera naam or meri bhanji ko": a cut-off sentence, not worth keeping. */
        object Incomplete : Cmd()
    }

    const val INCOMPLETE_REPLY =
        "Ye adhoora lag raha hai. Poora likho, jaise: \"yaad rakho ki meri bhanji ka naam Gaib hai\"."

    /** Reads [text] as a memory command, or null when it is an ordinary message. */
    fun parse(text: String): Cmd? {
        val t = text.trim()
        if (t.isEmpty() || t.length > 240) return null
        if (forgetRx.matches(t)) return Cmd.Forget
        if (showRx.matches(t)) return Cmd.Show
        val plainText = plain(t)
        if (askNameHiRx.matches(plainText) || askNameEnRx.matches(plainText)) return Cmd.AskName
        val m = if (t.endsWith("?")) null else rememberRx.find(t)
        if (m != null) {
            val note = m.groupValues[1].trim().trimEnd('.', '!').replace(Regex("\\s+"), " ").take(MAX_TEXT)
            if (note.length < 3) return null
            if (note.lowercase().substringAfterLast(' ') in danglingEnd) return Cmd.Incomplete
            return Cmd.Remember(note)
        }
        return null
    }

    /** Handles a whole-message memory command on the old "kind|text" list. Returns null when [text] is an ordinary message. */
    fun command(text: String, current: List<String>): Outcome? {
        return when (val c = parse(text) ?: return null) {
            is Cmd.Forget ->
                if (current.isEmpty()) Outcome("Abhi mujhe aapke baare mein kuch yaad hi nahi tha.", null)
                else Outcome("Theek hai, maine sab bhula diya.", emptyList())
            is Cmd.Show ->
                if (current.isEmpty()) {
                    Outcome(
                        "Abhi mujhe aapke baare mein kuch yaad nahi hai. Bolo \"yaad rakho ki ...\" ya \"mera naam ... hai\".",
                        null
                    )
                } else {
                    val lines = current.joinToString("\n") { "• " + readable(it) }
                    Outcome("Mujhe aapke baare mein ye yaad hai:\n$lines\n\nSab bhulana ho toh bolo \"bhool jao\".", null)
                }
            is Cmd.Incomplete -> Outcome(INCOMPLETE_REPLY, null)
            is Cmd.AskName -> Outcome(nameReply(findName(current.map { kindOf(it) to readable(it) })), null)
            is Cmd.Remember -> {
                val merged = merge(current, listOf(encode("note:" + c.note.lowercase().take(40), c.note)))
                Outcome("Theek hai, yaad rakh liya: " + c.note, merged)
            }
        }
    }

    // ---------- the name ----------

    const val NAME_UNKNOWN_REPLY = "Mujhe abhi aapka naam nahi pata. Bataiye, aapka naam kya hai?"

    private val nameLineRx = Regex("^The user's name is (.+?)\\.?$")

    /** Lower case, every run of punctuation / symbols turned into one space: "What's my name?" -> "what s my name". */
    private fun plain(text: String): String =
        text.lowercase().replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").trim()

    /** False when a word of [name] is a filler / question word: an old wrong save such as "Jante" is then never read out. */
    fun validName(name: String): Boolean {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return words.isNotEmpty() && words.none { it.lowercase() in stopWords }
    }

    /** The name inside a stored sentence "The user's name is Rahul.", or null. */
    fun nameFrom(readableText: String): String? {
        val n = nameLineRx.find(readableText.trim())?.groupValues?.get(1)?.trim() ?: return null
        return if (validName(n)) n else null
    }

    /** True for a stored sentence "The user's name is Jante." whose name is a filler / question word (a wrong save of the old rule). */
    fun isWrongName(readableText: String): Boolean {
        val n = nameLineRx.find(readableText.trim())?.groupValues?.get(1)?.trim() ?: return false
        return !validName(n)
    }

    /**
     * The user's name from stored (kind, text) pairs: the "name" fact first; else a note that states it
     * ("yaad rakho ki mera naam Rahul hai"). Null when no usable name is stored.
     */
    fun findName(entries: List<Pair<String, String>>): String? {
        entries.filter { it.first == "name" }.mapNotNull { nameFrom(it.second) }.firstOrNull()?.let { return it }
        for ((_, text) in entries) {
            val fact = extract(text).firstOrNull { kindOf(it) == "name" } ?: continue
            nameFrom(readable(fact))?.let { return it }
        }
        return null
    }

    fun nameReply(name: String?): String = if (name == null) NAME_UNKNOWN_REPLY else "Aapka naam $name hai."

    // ---------- storing ----------

    /** Adds [incoming] to [current]: same kind replaces, newest facts survive when the list is full. */
    fun merge(current: List<String>, incoming: List<String>): List<String> {
        if (incoming.isEmpty()) return current
        val out = ArrayList<String>(current)
        for (line in incoming) {
            val kind = kindOf(line)
            out.removeAll { kindOf(it) == kind }
            out.add(line)
        }
        while (out.size > MAX_FACTS) out.removeAt(0)
        return out
    }

    fun encode(kind: String, text: String): String =
        kind.replace('|', ' ').replace('\n', ' ') + "|" + text.replace('\n', ' ').take(MAX_TEXT)

    fun kindOf(line: String): String = line.substringBefore('|')

    /** The human sentence of a stored line (a line without a kind is treated as plain text). */
    fun readable(line: String): String = if (line.contains('|')) line.substringAfter('|') else line

    /** The prompt part that tells the model what it knows about the user; empty when there is nothing. */
    fun block(facts: List<String>): String {
        if (facts.isEmpty()) return ""
        val sb = StringBuilder("Known about the user (use only when relevant, do not repeat unprompted):")
        for (f in facts) sb.append("\n- ").append(readable(f))
        return sb.toString()
    }

    fun pack(facts: List<String>): String = facts.joinToString("\n")

    fun unpack(raw: String?): List<String> =
        (raw ?: "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }.takeLast(MAX_FACTS)
}
