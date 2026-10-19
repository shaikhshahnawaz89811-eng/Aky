package com.codeassist.ai.search

/**
 * Checks the text a model wrote from numbered web evidence (audit section 4, "CitationValidator"). The model is never trusted
 * with links or numbers:
 *
 *  1. Links the model wrote are removed (it only ever cites by number; the app builds the Sources list from the evidence).
 *  2. Only [n] markers that exist in the evidence stay. "[1, 2]" is read as "[1][2]"; a marker after the full stop moves in
 *     front of it.
 *  3. A sentence that contains a number (a price, a version, a year, a date, a percentage ...) must carry a valid marker, and
 *     every such number must appear in the text of a source it cites. Otherwise the sentence is flagged and (by default)
 *     gets [Options.unverifiedLabel] appended, or is removed when [Options.dropUnsupported] is on.
 *
 * What it does NOT do: judge whether a sentence without numbers is true. That stays a model risk; the answer text is
 * kept short, from numbered evidence only, and the user sees the sources.
 *
 * Single digits (0..9) without a unit are not checked: small models use them for plain counts ("2 options"). That is a known
 * hole for version numbers like "Gemma 4"; the evidence block already contains the sentence the model read it from.
 */
object CitationValidator {
    enum class IssueKind {
        /** A sentence with a number has no valid marker. */
        UNCITED_NUMBER,

        /** A number of a sentence is not in any source the sentence cites. */
        NUMBER_NOT_IN_SOURCE,

        /** A marker pointed at a source that does not exist and was removed. */
        INVALID_MARKER,

        /** The model wrote a link; it was removed. */
        LINK_REMOVED
    }

    class Issue(val kind: IssueKind, val sentence: String)

    class Options(
        val dropUnsupported: Boolean = false,
        val unverifiedLabel: String = "(unverified)"
    )

    class Result(
        val text: String,
        /** Source ids cited by the final text, ascending. */
        val usedIds: List<Int>,
        val issues: List<Issue>
    ) {
        val clean: Boolean get() = issues.isEmpty()
        fun count(kind: IssueKind): Int = issues.count { it.kind == kind }
    }

    private val mdLink = Regex("\\[([^\\]]*)]\\((?:[^)]*)\\)")
    private val bareUrl = Regex("(?:https?://|www\\.)[^\\s)\\]]+")
    private val listMarker = Regex("\\[(\\d+(?:\\s*[,;]\\s*\\d+)+)]")
    private val marker = Regex("\\[(\\d{1,3})]")
    private val markersAfterStop = Regex("([.!?\\u0964])[ \\t]*((?:\\[\\d{1,3}][ \\t]*)+)")
    private val sentenceBoundary = Regex("(?<=[.!?\\u0964])\\s+(?=\\S)")
    private val leadingListNumber = Regex("^\\s*\\d{1,2}[.)]\\s+")
    private val numberToken = Regex("(?<![\\d.,])\\d[\\d,]*(?:\\.\\d+)?")
    private val listNumberOnly = Regex("^\\d{1,3}[.)]$")
    private val spaces = Regex("[ \\t]{2,}")

    fun validate(modelText: String, evidence: List<Evidence>, options: Options = Options()): Result {
        val byId = HashMap<Int, Evidence>()
        for (e in evidence) byId[e.id] = e
        val issues = ArrayList<Issue>()

        // 1. links
        var text = modelText
        if (mdLink.containsMatchIn(text) || bareUrl.containsMatchIn(text)) {
            issues.add(Issue(IssueKind.LINK_REMOVED, ""))
            text = mdLink.replace(text) { m -> m.groupValues[1] }
            text = bareUrl.replace(text, "")
        }

        // 2. marker shapes
        text = listMarker.replace(text) { m ->
            m.groupValues[1].split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("") { "[$it]" }
        }
        text = markersAfterStop.replace(text) { m ->
            " " + m.groupValues[2].trim().replace(" ", "").replace("\t", "") + m.groupValues[1] + " "
        }

        val out = StringBuilder()
        val used = java.util.TreeSet<Int>()
        val lines = text.split('\n')
        for ((li, line) in lines.withIndex()) {
            if (li > 0) out.append('\n')
            if (line.isBlank()) {
                out.append(line)
                continue
            }
            val rebuilt = ArrayList<String>()
            for (piece in splitSentences(line)) {
                val fixed = checkSentence(piece, byId, options, issues, used)
                if (fixed.isNotEmpty()) rebuilt.add(fixed)
            }
            out.append(rebuilt.joinToString(" "))
        }
        return Result(spaces.replace(out.toString(), " ").trim(), used.toList(), issues)
    }

    private fun splitSentences(line: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        for (m in sentenceBoundary.findAll(line)) {
            val end = m.range.first
            out.add(line.substring(start, end))
            start = m.range.last + 1
        }
        if (start < line.length) out.add(line.substring(start))
        return out
    }

    private fun checkSentence(
        raw: String,
        byId: Map<Int, Evidence>,
        options: Options,
        issues: MutableList<Issue>,
        used: MutableSet<Int>
    ): String {
        val piece = raw.trim()
        if (piece.isEmpty()) return ""
        if (listNumberOnly.matches(piece)) return piece

        // keep valid markers, remove the others
        val valid = ArrayList<Int>()
        var invalid = 0
        val withoutBad = marker.replace(piece) { m ->
            val id = m.groupValues[1].toInt()
            if (byId.containsKey(id)) {
                if (!valid.contains(id)) valid.add(id)
                "[$id]"
            } else {
                invalid++
                ""
            }
        }
        if (invalid > 0) issues.add(Issue(IssueKind.INVALID_MARKER, piece))
        val sentence = withoutBad.replace(Regex(" {2,}"), " ").replace(" .", ".").replace(" ,", ",").trim()
        if (sentence.isEmpty()) return ""

        val numbers = factNumbers(sentence)
        if (numbers.isEmpty()) {
            used.addAll(valid)
            return sentence
        }
        if (valid.isEmpty()) {
            issues.add(Issue(IssueKind.UNCITED_NUMBER, sentence))
            return flagged(sentence, options)
        }
        val sourceText = valid.joinToString(" ") { id -> normalizeDigits(byId[id]?.text.orEmpty()) }
        for (n in numbers) {
            if (!appearsIn(n, sourceText)) {
                issues.add(Issue(IssueKind.NUMBER_NOT_IN_SOURCE, sentence))
                return flagged(sentence, options)
            }
        }
        used.addAll(valid)
        return sentence
    }

    private fun flagged(sentence: String, options: Options): String =
        if (options.dropUnsupported) "" else sentence + " " + options.unverifiedLabel

    /** Numbers of [sentence] that must be backed by a source, as written without thousands commas ("8,000" -> "8000"). */
    internal fun factNumbers(sentence: String): List<String> {
        val bare = marker.replace(sentence, " ")
        val body = leadingListNumber.replace(bare, "")
        val out = ArrayList<String>()
        for (m in numberToken.findAll(body)) {
            val n = m.value.replace(",", "").trimEnd('.')
            if (n.isEmpty()) continue
            val single = n.length == 1
            if (single && !hasUnitNext(body, m.range.last + 1)) continue
            out.add(n)
        }
        return out
    }

    private fun hasUnitNext(body: String, from: Int): Boolean {
        if (from >= body.length) return false
        val c = body[from]
        if (c == '%') return true
        val rest = body.substring(from).trimStart().lowercase()
        for (u in units) if (rest.startsWith(u) && (rest.length == u.length || !rest[u.length].isLetter())) return true
        return false
    }

    private val units = listOf("gb", "mb", "kb", "tb", "ms", "kg", "km", "usd", "inr", "rs", "dollar", "dollars", "rupees")

    private fun normalizeDigits(text: String): String = text.replace(Regex("(?<=\\d),(?=\\d)"), "")

    private fun appearsIn(number: String, normalizedSource: String): Boolean {
        val rx = Regex("(?<![\\d.])" + Regex.escape(number) + "(?!\\d|\\.\\d)")
        return rx.containsMatchIn(normalizedSource)
    }

    /**
     * The app's own Sources list for the ids a text cites: "[1] domain - title". The model never writes this part, so a
     * link in it always comes from a real search result.
     */
    fun sourcesList(evidence: List<Evidence>, usedIds: List<Int>): String {
        val lines = ArrayList<String>()
        for (e in evidence) {
            if (e.id !in usedIds) continue
            val date = if (e.date != null) " (" + e.date + ")" else ""
            lines.add("[" + e.id + "] " + e.domain + " - " + e.title + date + "\n" + e.url)
        }
        return lines.joinToString("\n")
    }
}
