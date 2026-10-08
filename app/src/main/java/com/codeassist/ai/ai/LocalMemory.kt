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
        "kya", "kaun", "kahan", "kab", "btao", "batao", "bata", "bataiye", "bolo", "sunao", "pata", "nahi", "nahin", "kon"
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
    private val forgetRx = Regex(
        "(?i)^\\s*(?:sab\\s+(?:kuch\\s+)?bhool\\s+jao|sab\\s+bhool\\s+ja|bhool\\s+jao|forget\\s+everything|forget\\s+me|" +
            "yaad\\s+(?:hata|mita|saaf)\\s+do|memory\\s+clear\\s+karo)\\s*[.!]?\\s*$"
    )

    // ---------- reading what the user said ----------

    /** Facts the user stated about themselves in [text]; empty when there are none. Encoded "kind|text". */
    fun extract(text: String): List<String> {
        val t = text.trim()
        if (t.isEmpty() || t.length > 300) return emptyList()
        val out = ArrayList<String>()
        pick(nameRx, t, 2)?.let { out.add(encode("name", "The user's name is " + titleCase(it) + ".")) }
        (pick(livesRx, t, 3) ?: pick(livesEnRx, t, 3))?.let {
            out.add(encode("lives", "The user lives in " + titleCase(it) + "."))
        }
        pick(likesRx, t, 4)?.let { out.add(encode("like:" + it.lowercase(), "The user likes " + it.lowercase() + ".")) }
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

    /** Handles a whole-message memory command. Returns null when [text] is an ordinary message. */
    fun command(text: String, current: List<String>): Outcome? {
        val t = text.trim()
        if (t.isEmpty() || t.length > 240) return null

        if (forgetRx.matches(t)) {
            return if (current.isEmpty()) {
                Outcome("Abhi mujhe aapke baare mein kuch yaad hi nahi tha.", null)
            } else {
                Outcome("Theek hai, maine sab bhula diya.", emptyList())
            }
        }
        if (showRx.matches(t)) {
            if (current.isEmpty()) {
                return Outcome(
                    "Abhi mujhe aapke baare mein kuch yaad nahi hai. Bolo \"yaad rakho ki ...\" ya \"mera naam ... hai\".",
                    null
                )
            }
            val lines = current.joinToString("\n") { "• " + readable(it) }
            return Outcome("Mujhe aapke baare mein ye yaad hai:\n$lines\n\nSab bhulana ho toh bolo \"bhool jao\".", null)
        }
        val m = if (t.endsWith("?")) null else rememberRx.find(t)
        if (m != null) {
            val note = m.groupValues[1].trim().trimEnd('.', '!').replace(Regex("\\s+"), " ").take(MAX_TEXT)
            if (note.length < 3) return null
            if (note.lowercase().substringAfterLast(' ') in danglingEnd) {
                return Outcome("Ye adhoora lag raha hai. Poora likho, jaise: \"yaad rakho ki meri bhanji ka naam Gaib hai\".", null)
            }
            val merged = merge(current, listOf(encode("note:" + note.lowercase().take(40), note)))
            return Outcome("Theek hai, yaad rakh liya: $note", merged)
        }
        return null
    }

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
