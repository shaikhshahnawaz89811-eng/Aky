package com.codeassist.ai.ai

import com.codeassist.ai.data.Message
import com.codeassist.ai.data.Role

/**
 * T3 (test chat 13 Oct 2026, report mistake 4): "ek prompt do, poora plan, dusra AI coding kar de".
 *
 * What went wrong before: the small model wrote the whole plan in one go. Phase 4 and 5 were missing, the UI wishes of the
 * user became "as discussed earlier" (the other AI cannot read the chat), and the last line still said "this plan covers
 * everything". Here the APP owns the structure and the model only fills small boxes:
 *
 *  1. the user's own lines from the chat are copied into the plan word for word ([decisions]), so nothing has to be "remembered";
 *  2. the headings and the 5 phases are fixed by the app; every box is a separate model call ([steps]);
 *  3. every box is checked ([stepProblems]): empty, "as discussed" style words, wrong language, a phase without a Pass test,
 *     known wrong tech claims ([techProblem]); one retry per box, then the problem is reported, never hidden;
 *  4. the closing line is written by the app from what is really in the text, never by the model.
 *
 * Pure Kotlin, no Android types: [build] gets the model as a lambda, so the JVM tests run it with a fake.
 */
object PlanBuilder {
    /** Caption of a finished plan; [redoSource] finds an earlier plan by it. */
    const val NOTE = "Plan builder"
    const val PHASES = 5

    private val splitter = Regex("[^\\p{L}\\p{M}\\p{N}]+")
    private fun words(s: String): List<String> = s.lowercase().split(splitter).filter { it.isNotEmpty() }

    // ---------- who asks for a plan ----------

    private val planNouns = setOf(
        "plan", "prompt", "blueprint", "roadmap", "spec", "specs", "specification", "prd", "brief", "architecture"
    )
    private val giveWords = setOf(
        "do", "de", "dedo", "dena", "den", "dijiye", "dijie", "chahiye", "banao", "bana", "banado", "banana", "likho", "likh",
        "batao", "bata", "create", "make", "write", "give", "generate", "prepare", "draft", "need", "send"
    )
    private val fullCues = setOf(
        "poora", "pura", "poori", "puri", "complete", "full", "saari", "saare", "sari", "sabhi", "detail", "detailed", "details",
        "phase", "phases", "wise", "phasewise", "step"
    )
    private val handoffCues = setOf("dusra", "doosra", "dusre", "doosre", "dusri", "another", "agent", "coding")
    private val languageCues = setOf("hindi", "hinglish", "english", "bhasha", "roman", "latter", "letter", "lipi", "akshar", "angrezi")
    private val projectWords = setOf(
        "app", "android", "ios", "project", "software", "website", "web", "bot", "tool", "code", "coding", "coder", "model", "ai",
        "assistant", "module", "feature", "backend", "frontend", "api", "game", "startup", "screen", "ui"
    )
    private val redoWords = setOf(
        "ye", "yeh", "isko", "isse", "wahi", "wohi", "dobara", "phirse", "phir", "again", "this", "it", "same", "plan", "prompt"
    )
    private const val CONTEXT_USER_MESSAGES = 8
    private const val CONTEXT_MIN_WORDS = 2

    /** Where a plan request comes from: [request] is the text the plan is about; [redo] = the same plan again in another language. */
    class Source(val request: String, val redo: Boolean)

    /** Null when the message is no plan request (the normal chat paths then run). */
    fun source(text: String, history: List<Message>): Source? {
        val redo = redoSource(text, history)
        if (redo != null) return Source(redo, true)
        return if (isRequest(text, history)) Source(text.trim(), false) else null
    }

    /**
     * A request to WRITE a plan / prompt for a software project: a plan word, a "give it" word (do / de / banao ...), a cue that
     * it should be full or handed to another AI (or a language cue), and software context in the message or in the last user
     * messages. "Goa trip ka detailed plan banao" and "kal ka plan batao" are not plan requests.
     */
    fun isRequest(text: String, history: List<Message>): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 600 || t.contains("```")) return false
        val ws = words(t)
        if (ws.size < 4) return false
        if (ws.none { it in planNouns }) return false
        if (ws.none { it in giveWords }) return false
        if (ws.none { it in fullCues || it in handoffCues || it in languageCues }) return false
        if (ws.any { it in projectWords }) return true
        // software only from the earlier chat: then "detailed" alone is not enough ("Goa trip ka detailed plan banao" in a
        // coding chat), the message must hand the plan to another AI or ask for a language
        return ws.any { it in handoffCues || it in languageCues } && projectContext(history)
    }

    private fun projectContext(history: List<Message>): Boolean {
        val found = HashSet<String>()
        var seen = 0
        for (i in history.indices.reversed()) {
            val m = history[i]
            if (m.role != Role.USER || m.state != null) continue
            for (w in words(m.text)) if (w in projectWords) found.add(w)
            seen++
            if (seen >= CONTEXT_USER_MESSAGES) break
        }
        return found.size >= CONTEXT_MIN_WORDS
    }

    /**
     * "English latter rakho or Hindi bhasha me do ye" right after a plan of this builder: the same plan again in the other
     * language. Returns the earlier request (the user message before that plan), or null. A short message with a language word
     * and a "this / again / plan" word only; "Hinglish me batao" alone is a language choice, not a new plan.
     */
    fun redoSource(text: String, history: List<Message>): String? {
        val ws = words(text)
        if (ws.isEmpty() || ws.size > 14) return null
        if (ws.none { it in languageCues }) return null
        if (ws.none { it in redoWords }) return null
        var ai = -1
        for (i in history.indices.reversed()) {
            if (history[i].role == Role.AI) {
                ai = i
                break
            }
        }
        if (ai < 0) return null
        val last = history[ai]
        if (last.state != null || !(last.note ?: "").startsWith(NOTE)) return null
        for (j in ai - 1 downTo 0) {
            if (history[j].role == Role.USER && history[j].text.isNotBlank()) return history[j].text.trim()
        }
        return null
    }

    // ---------- language of the plan ----------

    /**
     * [LocalMemory.LANG_HINGLISH] or [LocalMemory.LANG_ENGLISH] for THIS plan. "English latter me Hindi bhasha me" means Roman
     * Hinglish (the words "english" and "hindi" are both there, the letters are English, the language is Hindi). Else the last of
     * "hindi" / "english" named in the message wins; else the user's saved choice; else the language the message is written in.
     */
    fun language(text: String, remembered: String?): String {
        val ws = words(text)
        if (ws.contains("hinglish")) return LocalMemory.LANG_HINGLISH
        val romanCue = ws.any { it == "roman" || it == "latter" || it == "letter" || it == "lipi" || it == "akshar" }
        val hi = ws.indexOfLast { it == "hindi" }
        val en = ws.indexOfLast { it == "english" || it == "angrezi" }
        if (hi >= 0 && en >= 0) {
            return if (romanCue || hi > en) LocalMemory.LANG_HINGLISH else LocalMemory.LANG_ENGLISH
        }
        if (hi >= 0) return LocalMemory.LANG_HINGLISH
        if (en >= 0) return LocalMemory.LANG_ENGLISH
        if (remembered == LocalMemory.LANG_HINGLISH || remembered == LocalMemory.LANG_ENGLISH) return remembered
        return if (HinglishGuide.userWritesHinglish(text)) LocalMemory.LANG_HINGLISH else LocalMemory.LANG_ENGLISH
    }

    // ---------- the user's own lines ----------

    private val ackWords = setOf(
        "ok", "okay", "haan", "han", "ji", "thik", "theek", "accha", "achha", "hmm", "thanks", "shukriya", "hello", "hi", "hey"
    )
    private val negWords = setOf("nahi", "nahin", "nhi", "mat", "without", "dont", "never", "avoid", "bina", "no", "not")
    private const val MAX_LINES = 14
    private const val MAX_LINE_CHARS = 220
    private const val MAX_BLOCK_CHARS = 1800
    private const val KEEP_FIRST = 3

    private fun looksLikeRequestShape(ws: List<String>): Boolean =
        ws.any { it in planNouns } && ws.any { it in giveWords } &&
            ws.any { it in fullCues || it in handoffCues || it in languageCues }

    private fun looksLikeLanguageSwitch(ws: List<String>): Boolean =
        ws.size <= 12 && ws.any { it in languageCues } && ws.none { it in projectWords }

    /**
     * What the user said about the project in this chat, in their own words, oldest first. Left out: phone commands, short
     * acknowledgements, short questions, the plan request itself ([request]) and "use Hinglish" style messages. These lines go
     * into the plan unchanged, so the other AI reads them instead of "as discussed".
     */
    fun decisions(history: List<Message>, request: String): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        val requestKey = request.trim().lowercase()
        for (m in history) {
            if (m.role != Role.USER || m.state != null) continue
            val t = m.text.trim().replace(Regex("\\s+"), " ")
            if (t.isEmpty() || t.lowercase() == requestKey) continue
            val ws = words(t)
            if (ws.size < 4) continue
            if (ws.all { it in ackWords }) continue
            if (ws.size <= 8 && ActionHint.looksLikeAction(t)) continue
            if (looksLikeRequestShape(ws)) continue
            if (looksLikeLanguageSwitch(ws)) continue
            if (t.endsWith("?") && ws.size < 8) continue
            val clipped = if (t.length > MAX_LINE_CHARS) t.take(MAX_LINE_CHARS).trimEnd() + "..." else t
            if (seen.add(clipped.lowercase())) out.add(clipped)
        }
        return fit(out)
    }

    /** At most [MAX_LINES] lines and [MAX_BLOCK_CHARS] characters: the first lines (they define the project) and the newest ones. */
    internal fun fit(lines: List<String>): List<String> {
        var total = 0
        for (l in lines) total += l.length + 3
        if (lines.size <= MAX_LINES && total <= MAX_BLOCK_CHARS) return lines
        val head = lines.take(KEEP_FIRST)
        var budget = MAX_BLOCK_CHARS - head.sumOf { it.length + 3 }
        var slots = MAX_LINES - head.size
        val tail = ArrayList<String>()
        for (i in lines.indices.reversed()) {
            if (i < head.size) break
            val cost = lines[i].length + 3
            if (slots <= 0 || cost > budget) break
            tail.add(lines[i])
            budget -= cost
            slots--
        }
        tail.reverse()
        return head + tail
    }

    /** The lines of [decisions] that say what NOT to do ("training nahin dena", "bina internet"). */
    fun negatives(decisions: List<String>): List<String> =
        decisions.filter { d -> words(d).any { it in negWords } }

    // ---------- fixed texts ----------

    /** All fixed sentences of the plan, in one of the two languages. */
    class Tx(val hinglish: Boolean) {
        val title = if (hinglish) "# Project Blueprint (dusre AI ke liye)" else "# Project Blueprint (for another AI)"
        val reader = if (hinglish) {
            "Ye document padhne wala AI hamari chat nahi dekh sakta. Jo kuch chahiye wo yahin likha hai. " +
                "Har phase ke baad uska Pass test chalao, tabhi agle phase par jao."
        } else {
            "The AI reading this cannot see our chat. Everything it needs is written here. " +
                "Run each phase's Pass test before you start the next phase."
        }
        val hGoal = "## 1. Goal"
        val hDecisions = if (hinglish) "## 2. Faisle (user ke apne shabd, bina badle)" else "## 2. Decisions (the user's own words, unchanged)"
        val hStack = "## 3. Stack"
        val hScreens = if (hinglish) "## 4. Screens aur UI" else "## 4. Screens and UI"
        val hPhases = "## 5. Phases"
        val hDont = if (hinglish) "## 6. Kya na karna" else "## 6. What not to do"
        val hDone = if (hinglish) "## 7. Kaam kab poora" else "## 7. When the work is done"
        val noDecisions = if (hinglish) {
            "- (Chat me koi alag faisla nahi mila; upar ka Goal hi aadhar hai.)"
        } else {
            "- (No separate decisions were found in the chat; the Goal above is the base.)"
        }
        val passLabel = "Pass test:"
        val missingPass = if (hinglish) "Is phase ke saare kaam bina error ke chalen aur unke tests pass hon." else
            "All tasks of this phase run without errors and their tests pass."
        val missingPart = if (hinglish) "(Ye hissa Gemma bana nahi paya. Plan dobara maango.)" else
            "(The model could not write this part. Ask for the plan again.)"
        val dontBase = if (hinglish) {
            listOf(
                "Upar ke Faisle apni taraf se mat badlo.",
                "Library ya model ka naam tab tak mat likho jab tak pakka na pata ho; shak ho to \"verify karo\" likho.",
                "Kisi phase ka Pass test chhodkar aage mat badho."
            )
        } else {
            listOf(
                "Do not change the decisions above on your own.",
                "Do not write a library or model name unless you are sure it exists; if in doubt write \"verify\".",
                "Do not move on from a phase before its Pass test is green."
            )
        }

        fun phaseTitle(n: Int): String = if (hinglish) {
            when (n) {
                1 -> "Setup aur project ka dhaancha"
                2 -> "Model aur data ka hissa"
                3 -> "UI aur screens"
                4 -> "Core logic aur optimization"
                else -> "Testing aur deployment"
            }
        } else {
            when (n) {
                1 -> "Setup and project structure"
                2 -> "Model and data layer"
                3 -> "UI and screens"
                4 -> "Core logic and optimization"
                else -> "Testing and deployment"
            }
        }

        fun phaseHeading(n: Int): String = "### Phase $n - " + phaseTitle(n)

        fun closing(phases: Int): String = if (hinglish) {
            "Is plan me $phases phase hain (Phase 1 se Phase $phases). Phase $phases ka Pass test hara hone par hi kaam poora maano."
        } else {
            "This plan has $phases phases (Phase 1 to Phase $phases). Treat the work as done only when the Pass test of Phase $phases is green."
        }

        fun incomplete(problems: List<String>): String = if (hinglish) {
            "Dhyan: ye plan adhura hai (" + problems.joinToString("; ") + "). Dobara maango."
        } else {
            "Warning: this plan is incomplete (" + problems.joinToString("; ") + "). Ask again."
        }
    }

    // ---------- the boxes ----------

    /** One box of the plan. [phase] is 0 for Goal / Stack / Screens. */
    class Step(val key: String, val phase: Int, val bullets: Boolean, val checkLanguage: Boolean)

    val steps: List<Step> = listOf(
        Step("goal", 0, false, true),
        Step("stack", 0, true, false),
        Step("screens", 0, true, true),
        Step("phase1", 1, true, true),
        Step("phase2", 2, true, true),
        Step("phase3", 3, true, true),
        Step("phase4", 4, true, true),
        Step("phase5", 5, true, true)
    )

    /** What the user asked ([text], the plan request), their lines ([decisions]) and the plan language ([lang]). */
    class Request(val text: String, val decisions: List<String>, val lang: String) {
        val hinglish: Boolean get() = lang == LocalMemory.LANG_HINGLISH
    }

    class Prompt(val system: String, val user: String)

    /** True facts the small model gets wrong (test chat: "convert GGUF to TFLite", "Llama 3 8B is small"). */
    const val STACK_FACTS =
        "Facts: a GGUF file runs with llama.cpp; Google LiteRT / LiteRT-LM uses .litertlm or .task files; never suggest " +
            "converting GGUF to TFLite. A model of 7B parameters or more needs 4 GB RAM or more and is heavy for a phone; " +
            "phone-sized models are about 1B to 4B. Name only real, well-known libraries. If you are not sure a name exists, " +
            "write \"<part>: choose a library (verify the name)\"."

    fun systemFor(lang: String): String {
        val rule = if (lang == LocalMemory.LANG_HINGLISH) {
            "Write in simple Roman Hinglish (Hindi in English letters), for example \"App me ek chat screen hogi.\" " +
                "Keep tool, library and file names in English. Do not write English sentences."
        } else {
            "Write in clear English."
        }
        return "You write ONE section of a project brief. Another AI coding assistant will read it later and it cannot see " +
            "this chat, so write concrete facts only and never refer to earlier talk (no \"as discussed\", \"as before\", " +
            "\"see above\"). Output only the section content: no title, no greeting, no closing sentence. " + rule
    }

    private fun context(req: Request): String {
        val sb = StringBuilder("PROJECT LINES FROM THE USER (their own words):\n")
        if (req.decisions.isEmpty()) sb.append("(none)\n") else for (d in req.decisions) sb.append("- ").append(d).append("\n")
        sb.append("\nTHE USER NOW ASKS: ").append(req.text.take(600)).append("\n\nYOUR TASK: ")
        return sb.toString()
    }

    fun promptFor(step: Step, req: Request): Prompt {
        val tx = Tx(req.hinglish)
        val task = when (step.key) {
            "goal" -> "Write 2 or 3 short sentences: what is being built, for whom, and what the other AI must deliver. " +
                "Use only the user's lines."
            "stack" -> "List the tech stack as 4 to 6 bullets, each \"Part: choice\", each under 15 words, every bullet starting " +
                "with \"- \". Use what the user said; where they said nothing, choose a common real tool and add " +
                "\"(suggestion)\". " + STACK_FACTS
            "screens" -> "List the screens and UI details as 4 to 7 bullets, each under 18 words, every bullet starting with " +
                "\"- \". Take every UI wish from the user's lines (theme, input box, output area, buttons, icons, settings, " +
                "history). Add nothing the user did not ask for except obvious basics."
            else -> {
                val list = (1..PHASES).joinToString("; ") { "Phase $it: " + tx.phaseTitle(it) }
                "Write ONLY Phase ${step.phase} of $PHASES: \"" + tx.phaseTitle(step.phase) + "\". The phases are: " + list +
                    ". The other phases are written separately; do not repeat their work. Write 3 to 5 bullets of concrete " +
                    "tasks (each under 18 words, each starting with \"- \"), then ONE last line that starts with PASS: and " +
                    "names a test that proves this phase is done."
            }
        }
        return Prompt(systemFor(req.lang), context(req) + task)
    }

    // ---------- cleaning and checking one box ----------

    private val bulletRx = Regex("""^(?:[-*\u2022]|\d{1,2}[.)])\s+(.+)$""")
    private val passRx = Regex("""^[-*\u2022\s]*(?:\*\*)?pass(?:\s*test)?(?:\*\*)?\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)
    private const val MAX_BULLETS = 6
    private const val MAX_PROSE_CHARS = 700
    private const val MIN_CHARS = 25

    /**
     * The model's raw answer reduced to the box: bullets as "- text" (at most [MAX_BULLETS]) and, for a phase, one "PASS: text"
     * line last; or, for prose, the sentences without headings, bullets, fences and a "Sure, here is:" first line.
     * Anything else (greeting, closing sentence) is dropped.
     */
    fun tidy(raw: String, step: Step): String {
        val lines = raw.replace("**", "").lines().map { it.trimEnd() }.filter { !it.trim().startsWith("```") }
        if (!step.bullets) {
            val keep = ArrayList<String>()
            for (l in lines) {
                val s = l.trim()
                if (s.isEmpty() || s.startsWith("#")) continue
                keep.add(bulletRx.find(s)?.groupValues?.get(1)?.trim() ?: s)
            }
            if (keep.isNotEmpty() && keep[0].endsWith(":") && keep[0].length < 120) keep.removeAt(0)
            var text = keep.joinToString(" ").trim()
            if (text.length > MAX_PROSE_CHARS) {
                val cut = text.take(MAX_PROSE_CHARS)
                val dot = cut.lastIndexOf('.')
                text = if (dot > 200) cut.take(dot + 1) else cut.trimEnd() + "..."
            }
            return text
        }
        val out = ArrayList<String>()
        var pass: String? = null
        for (l in lines) {
            val s = l.trim()
            if (s.isEmpty()) continue
            val p = passRx.find(s)
            if (p != null) {
                pass = clip(p.groupValues[1].trim())
                continue
            }
            val b = bulletRx.find(s)
            if (b != null && out.size < MAX_BULLETS) out.add("- " + clip(b.groupValues[1].trim()))
        }
        if (pass != null) out.add("PASS: " + pass)
        return out.joinToString("\n")
    }

    private fun clip(s: String): String = if (s.length > MAX_LINE_CHARS) s.take(MAX_LINE_CHARS).trimEnd() + "..." else s

    /** Phrases that point at a chat the reader of the plan cannot see. */
    private val chatReferences = listOf(
        "as discussed", "as mentioned", "as we discussed", "as i mentioned", "discussed earlier", "mentioned earlier",
        "mentioned above", "as before", "as above", "earlier chat", "previous chat", "previous conversation",
        "earlier conversation", "see above", "pichhli chat", "pichli chat", "pehle bataya", "pehle batayi", "pehle discuss",
        "jaisa pehle", "jaise pehle", "jaisa maine kaha", "jaisa humne", "jaisa hamne", "upar bataya", "upar bataye",
        "upar likha", "hamari baat", "humari baat", "pichhli baat", "pehle wali baat", "jaise chat me", "chat me bataya"
    )

    fun mentionsChat(text: String): Boolean {
        val low = text.lowercase()
        return chatReferences.any { low.contains(it) }
    }

    private val modelSize = Regex("""\b(?:7|8|9|11|13|14|30|34|70)\s*b\b""")
    private val smallWords = Regex("""\b(?:small|lightweight|light-weight|efficient|tiny|chhota|chhote|halka|halke|compact)\b""")
    private val tfliteWords = Regex("""tflite|tf\s*lite|tensorflow\s*lite|litert""")
    private val convertWords = Regex("""convert|conversion|converting|transform|badal|export""")

    /** A known wrong claim in [line], or null. Such a line is dropped from the plan, never repaired by guessing. */
    fun techProblem(line: String): String? {
        val l = line.lowercase()
        if (l.contains("gguf") && tfliteWords.containsMatchIn(l) && convertWords.containsMatchIn(l)) {
            return "GGUF ko TFLite me convert karna koi standard raasta nahi"
        }
        if (modelSize.containsMatchIn(l) && smallWords.containsMatchIn(l)) return "7B+ model phone ke liye halka nahi hai"
        if (l.contains("code typos")) return "\"Code Typos\" naam ki koi jaani-maani library nahi hai"
        return null
    }

    /** [text] without the lines that [techProblem] rejects; [dropped] counts them. */
    class Cleaned(val text: String, val dropped: Int)

    fun dropTechLines(text: String): Cleaned {
        var dropped = 0
        val keep = ArrayList<String>()
        for (l in text.lines()) {
            if (techProblem(l) != null) dropped++ else keep.add(l)
        }
        return Cleaned(keep.joinToString("\n").trim(), dropped)
    }

    /** What is wrong with one finished box: "empty", "chat-reference", "wrong-language", "no-pass". Empty list = fine. */
    fun stepProblems(step: Step, text: String, lang: String): List<String> {
        val out = ArrayList<String>()
        val body = text.lines().filter { !it.startsWith("PASS:") }.joinToString("\n").trim()
        if (body.length < MIN_CHARS) out.add("empty")
        if (mentionsChat(text)) out.add("chat-reference")
        if (step.checkLanguage && body.length >= MIN_CHARS && ReplyGuard.languageMismatch("plan", body, lang)) out.add("wrong-language")
        if (step.phase > 0 && !text.lines().any { it.startsWith("PASS:") }) out.add("no-pass")
        return out
    }

    private fun retryNote(problems: List<String>, lang: String): String {
        val why = ArrayList<String>()
        if ("empty" in problems) why.add("it was empty or too short")
        if ("chat-reference" in problems) why.add("it pointed at an earlier chat; write the facts themselves")
        if ("wrong-language" in problems) why.add(
            if (lang == LocalMemory.LANG_HINGLISH) "it was not in Roman Hinglish" else "it was not in English"
        )
        if ("no-pass" in problems) why.add("the last line did not start with PASS:")
        val tail = if (lang == LocalMemory.LANG_HINGLISH) " Write in simple Roman Hinglish." else ""
        return "\n\n(Your last answer was rejected because " + why.joinToString(", ") + ". Write the section again, " +
            "same format, no intro." + tail + ")"
    }

    // ---------- the whole plan ----------

    /** [text] is the finished plan; [problems] is empty only when every check passed; [phases] counts the phase headings in [text]. */
    class Result(
        val text: String,
        val phases: Int,
        val problems: List<String>,
        val calls: Int,
        val retries: Int,
        val techDropped: Int
    ) {
        val ok: Boolean get() = problems.isEmpty()
    }

    /**
     * Writes the plan box by box. [gen] is one model call (system, user) -> raw text. [progress] gets (box number from 1,
     * box count, box key, the plan so far) before each box; [cancelled] is polled between calls: a cancelled build returns what
     * exists, marked incomplete.
     */
    suspend fun build(
        req: Request,
        gen: suspend (String, String) -> String,
        progress: (Int, Int, String, String) -> Unit,
        cancelled: () -> Boolean
    ): Result {
        val parts = HashMap<String, String>()
        val problems = ArrayList<String>()
        var calls = 0
        var retries = 0
        var techDropped = 0
        var stopped = false
        for ((i, step) in steps.withIndex()) {
            if (cancelled()) {
                problems.add("roka gaya")
                stopped = true
                break
            }
            progress(i + 1, steps.size, step.key, assemble(req, parts, false))
            val prompt = promptFor(step, req)
            var text = tidy(gen(prompt.system, prompt.user), step)
            calls++
            var cleaned = dropTechLines(text)
            techDropped += cleaned.dropped
            text = cleaned.text
            var found = stepProblems(step, text, req.lang)
            if (found.isNotEmpty() && !cancelled()) {
                retries++
                val again = tidy(gen(prompt.system, prompt.user + retryNote(found, req.lang)), step)
                calls++
                cleaned = dropTechLines(again)
                techDropped += cleaned.dropped
                val second = stepProblems(step, cleaned.text, req.lang)
                if (second.size <= found.size) {
                    text = cleaned.text
                    found = second
                }
            }
            if ("chat-reference" in found) {
                text = text.lines().filter { !mentionsChat(it) }.joinToString("\n").trim()
                found = stepProblems(step, text, req.lang)
            }
            parts[step.key] = text
            for (p in found) problems.add(describe(step, p))
        }
        // a stopped build shows only the boxes that exist; a finished one always shows all five phase headings
        val plan = assemble(req, parts, !stopped)
        val phases = Regex("(?m)^### Phase \\d").findAll(plan).count()
        if (phases < PHASES && "roka gaya" !in problems) problems.add("$phases / $PHASES phase")
        val finalText = if (problems.isEmpty()) plan else plan + "\n\n" + Tx(req.hinglish).incomplete(problems)
        return Result(finalText, phases, problems, calls, retries, techDropped)
    }

    private fun describe(step: Step, problem: String): String {
        val label = if (step.phase > 0) "Phase ${step.phase}" else step.key
        return when (problem) {
            "empty" -> "$label khaali"
            "chat-reference" -> "$label me chat ka hawala"
            "wrong-language" -> "$label ki bhasha galat"
            else -> "$label me Pass test nahi"
        }
    }

    /**
     * The plan from the boxes written so far ([done] = all of them are final: the closing lines are added). A box that is
     * empty shows a fixed "could not write" line, so a phase heading is never without a body.
     */
    fun assemble(req: Request, parts: Map<String, String>, done: Boolean): String {
        val tx = Tx(req.hinglish)
        val sb = StringBuilder()
        sb.append(tx.title).append("\n\n").append(tx.reader).append("\n\n")
        sb.append(tx.hGoal).append("\n").append(parts["goal"]?.ifBlank { tx.missingPart } ?: pending(done, tx)).append("\n\n")
        sb.append(tx.hDecisions).append("\n")
        if (req.decisions.isEmpty()) sb.append(tx.noDecisions).append("\n") else for (d in req.decisions) sb.append("- ").append(d).append("\n")
        sb.append("\n").append(tx.hStack).append("\n").append(parts["stack"]?.ifBlank { tx.missingPart } ?: pending(done, tx)).append("\n\n")
        sb.append(tx.hScreens).append("\n").append(parts["screens"]?.ifBlank { tx.missingPart } ?: pending(done, tx)).append("\n\n")
        sb.append(tx.hPhases).append("\n\n")
        for (n in 1..PHASES) {
            val body = parts["phase$n"]
            if (body == null && !done) break
            sb.append(tx.phaseHeading(n)).append("\n")
            sb.append(phaseBody(body, tx)).append("\n\n")
        }
        if (done) {
            sb.append(tx.hDont).append("\n")
            for (d in tx.dontBase) sb.append("- ").append(d).append("\n")
            for (n in negatives(req.decisions)) sb.append("- ").append(n).append("\n")
            sb.append("\n").append(tx.hDone).append("\n").append(tx.closing(PHASES))
        }
        return sb.toString().trimEnd()
    }

    private fun pending(done: Boolean, tx: Tx): String = if (done) tx.missingPart else "..."

    private fun phaseBody(body: String?, tx: Tx): String {
        if (body.isNullOrBlank()) return tx.missingPart + "\n" + tx.passLabel + " " + tx.missingPass
        val lines = body.lines()
        val tasks = lines.filter { !it.startsWith("PASS:") }
        val pass = lines.firstOrNull { it.startsWith("PASS:") }?.removePrefix("PASS:")?.trim()
        val sb = StringBuilder()
        if (tasks.isEmpty()) sb.append(tx.missingPart) else sb.append(tasks.joinToString("\n"))
        sb.append("\n").append(tx.passLabel).append(" ").append(if (pass.isNullOrBlank()) tx.missingPass else pass)
        return sb.toString()
    }
}
