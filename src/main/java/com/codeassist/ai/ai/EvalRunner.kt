package com.codeassist.ai.ai

import android.content.Context
import com.codeassist.ai.data.Message
import com.codeassist.ai.data.Role
import com.codeassist.ai.data.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 3C: Settings > Debug: task engine > "Eval chalao". Asks the brain the 30 labelled Hinglish cases of
 * `assets/eval_multi.json` and scores the plan it writes with [EvalCases.judge]. Only the PARSER side is exercised, exactly as
 * the chat does it (same system prompt, same tool list, same one repair retry for the local model, same [ToolSpecs.check]);
 * no tool is ever run, so no alarm is set and nothing is dialled.
 *
 * Which brain is asked: the one chosen in Voice and AI (Gemini or the on-device Gemma). Gemma needs about 5 to 20 seconds per
 * case, so a full run can take several minutes; Gemini about a minute. The result is saved, and the KPI screen shows it
 * under "Phase 3 exit checklist".
 */
object EvalRunner {
    class Report(
        val engine: String,
        val cases: List<EvalCases.Case>,
        val verdicts: List<EvalCases.Verdict>,
        val cancelled: Boolean
    ) {
        val totals: EvalCases.Totals get() = EvalCases.totals(cases, verdicts)

        fun text(): String {
            val t = totals
            val sb = StringBuilder()
            sb.append("Eval · ").append(engine).append('\n')
            sb.append("Sahi: ").append(t.ok).append('/').append(t.total).append(" = ").append(t.percent).append("%")
            if (cancelled) sb.append(" (beech mein roka, poora set nahi chala)")
            sb.append('\n')
            sb.append("Exit rule (").append(EvalCases.EXIT_PERCENT).append("% se upar, ").append(EvalCases.EXIT_MIN_CASES)
                .append(" cases): ").append(if (EvalCases.exitReached(t)) "PASS" else "abhi nahi").append('\n')
            sb.append(t.byGroup.entries.joinToString(" · ") { it.key + " " + it.value.second + "/" + it.value.first }).append('\n')
            val bad = ArrayList<String>()
            for ((i, c) in cases.withIndex()) {
                val v = verdicts.getOrNull(i) ?: continue
                if (!v.ok) bad.add("  " + c.id + ": " + v.why + "  [" + c.text.take(48) + "]")
            }
            if (bad.isNotEmpty()) {
                sb.append("Galat cases:\n").append(bad.joinToString("\n")).append('\n')
            }
            sb.append("Koi tool nahi chala: sirf parser jaanch hui.")
            return sb.toString()
        }
    }

    fun loadCases(app: Context): List<EvalCases.Case> {
        val json = app.assets.open("eval_multi.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        return EvalCases.parse(json)
    }

    /**
     * Runs the set. [onProgress] is called before each case (main thread when called from the main thread). Throws
     * [IllegalStateException] with a short Hinglish reason when the chosen brain cannot be asked at all.
     */
    suspend fun run(
        app: Context,
        onProgress: (done: Int, total: Int, id: String) -> Unit,
        isCancelled: () -> Boolean
    ): Report {
        Store.init(app)
        Modules.init(app)
        ConvKpi.init(app)
        val cases = loadCases(app)
        val gemini = Store.brainProvider == "gemini"
        val engine: String
        if (gemini) {
            if (Store.geminiKey == null) throw IllegalStateException("Gemini API key set nahi hai. Voice and AI mein key daalo.")
            engine = "Gemini · " + Store.geminiModel.ifBlank { GeminiClient.FALLBACK_MODEL }
        } else {
            when (Modules.phase) {
                Modules.Phase.NOT_IMPORTED, Modules.Phase.ERROR, Modules.Phase.DOWNLOADING, Modules.Phase.IMPORTING ->
                    throw IllegalStateException("Gemma 4 abhi taiyaar nahi hai. Voice and AI mein Download ya Import karo, ya Gemini chuno.")
                else -> Unit
            }
            if (!LocalLlm.loaded) Modules.ensureLoaded()
            engine = "Gemma 4 E2B (phone par)"
        }

        val verdicts = ArrayList<EvalCases.Verdict>()
        var cancelled = false
        for ((i, c) in cases.withIndex()) {
            if (isCancelled()) {
                cancelled = true
                break
            }
            onProgress(i, cases.size, c.id)
            val v: EvalCases.Verdict = try {
                if (gemini) askGemini(c, isCancelled) else askLocal(c, isCancelled)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                EvalCases.Verdict(false, "error: " + (e.message ?: e.javaClass.simpleName).take(60))
            }
            verdicts.add(v)
        }
        val report = Report(engine, cases, verdicts, cancelled)
        save(report)
        return report
    }

    private fun save(r: Report) {
        val t = r.totals
        ConvKpi.inc("eval_runs")
        if (r.cancelled || t.total < EvalCases.EXIT_MIN_CASES) {
            // a part-run set is shown but never replaces a full result
            ConvKpi.putText("eval_partial", r.text().take(3000))
            return
        }
        ConvKpi.put("eval_total", t.total.toLong())
        ConvKpi.put("eval_ok", t.ok.toLong())
        ConvKpi.put("eval_time", System.currentTimeMillis())
        ConvKpi.putText("eval_engine", r.engine)
        ConvKpi.putText("eval_report", r.text().take(3000))
    }

    // ---------- one case ----------

    private suspend fun askGemini(c: EvalCases.Case, isCancelled: () -> Boolean): EvalCases.Verdict {
        val key = Store.geminiKey ?: throw IllegalStateException("Gemini API key set nahi hai.")
        val model = Store.geminiModel.ifBlank { GeminiClient.FALLBACK_MODEL }
        val system = Prompts.system(false, personal = false) + "\n" + Prompts.TOOL_RULES
        val turns = listOf(GeminiClient.Turn("user", c.text))
        val streamed = withContext(Dispatchers.IO) {
            GeminiClient.streamWithToolsTimeouts(
                key, model, system, turns, Store.geminiTemp, ToolSpecs.all,
                connectTimeoutMs = 10_000, readTimeoutMs = 30_000, isCancelled = isCancelled
            ) { _ -> }
        }
        val tasks = streamed.calls.mapIndexed { i, f -> ToolSpecs.task("t" + (i + 1), f.name, f.args) }
        return EvalCases.judge(c, tasks, streamed.text)
    }

    private suspend fun askLocal(c: EvalCases.Case, isCancelled: () -> Boolean): EvalCases.Verdict {
        // the app only offers tools to the small model for an action-looking message; a case the gate refuses is a real miss
        if (!ActionHint.looksLikeAction(c.text)) {
            return EvalCases.Verdict(false, "ActionHint is message par tools deta hi nahi")
        }
        val system = Prompts.systemLocal(false, personal = false) + "\n" + Prompts.TOOL_RULES + "\n" + Prompts.LOCAL_TOOL_HINT +
            "\n\n" + ToolSpecs.qwenToolsBlock()
        val first = LocalLlm.chat(system, emptyList(), c.text, emptyList(), isCancelled) { _ -> }
        val firstText = first.text.trim()
        var parsed: PlanParser.Parsed = PlanParser.parse(firstText)
        if (parsed is PlanParser.Parsed.Invalid) {
            val note = "Your tool call was not valid: " + parsed.reason +
                ". Reply again with correct <tool_call> blocks, or answer in plain text."
            val past = listOf(Message(role = Role.USER, text = c.text), Message(role = Role.AI, text = firstText))
            val again = LocalLlm.chat(system, past, note, emptyList(), isCancelled) { _ -> }
            parsed = PlanParser.parse(again.text.trim())
        }
        return when (val p = parsed) {
            is PlanParser.Parsed.Calls -> EvalCases.judge(c, p.tasks, p.ack)
            is PlanParser.Parsed.Text -> EvalCases.judge(c, emptyList(), p.text)
            is PlanParser.Parsed.Invalid -> EvalCases.Verdict(false, "plan do baar refuse hua: " + p.reason.take(50))
        }
    }
}
