package com.codeassist.ai.ai

import com.codeassist.ai.data.Store
import kotlinx.coroutines.CancellationException

/**
 * Finds out, once per model file, which prompt shape ([LocalTemplate.Kind]) the model answers well in.
 *
 * The test is a tiny "Hello" with at most [LocalTemplate.PROBE_TOKENS] new tokens per shape. A shape passes when
 * the model stops by itself (it did not run into the token limit) and the answer is sane text. The first
 * passing shape is kept. If none passes, the first one with sane text is used (the reply guard and the retry in
 * [ChatRunner] still protect every answer). The outcome and the three test lines are kept in [Store] and shown
 * in Settings > Activity and debug > Debug: conversation KPIs.
 *
 * Settings > Voice and AI > Qwen options > "Prompt template" can force one shape, or set "auto" again to rerun this.
 */
object LocalCalibration {
    /** Bump when the test logic changes, so phones test again. */
    private const val VERSION = 1

    suspend fun resolve(onStatus: (String) -> Unit): LocalTemplate.Kind {
        val forced = LocalTemplate.Kind.of(Store.localTemplate)
        if (forced != null) return forced

        val sig = Modules.modelFile().length().toString() + ":v" + VERSION
        val saved = LocalTemplate.Kind.of(Store.localTemplateResult)
        if (saved != null && Store.localTemplateSig == sig) return saved

        onStatus("Model ki pehli jaanch (sirf ek baar, lagbhag 1 min)")
        val notes = ArrayList<String>()
        var chosen: LocalTemplate.Kind? = null
        var firstSane: LocalTemplate.Kind? = null
        var completed = 0
        for (kind in LocalTemplate.Kind.values()) {
            onStatus("Model ki jaanch: " + kind.key)
            try {
                val built = LocalTemplate.build(kind, Prompts.LOCAL_SYSTEM, emptyList(), "Hello", 3000)
                val r = LocalLlm.generate(built.system, built.prompt, LocalTemplate.PROBE_TOKENS)
                val probe = LocalTemplate.judge(kind, LocalTemplate.finish(kind, r.text), r.tokens)
                completed++
                notes.add(probe.line())
                if (probe.sane && firstSane == null) firstSane = kind
                if (probe.good) {
                    chosen = kind
                    break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notes.add(kind.key + ": error " + (e.message ?: e.javaClass.simpleName).take(60))
            }
        }

        val pick = chosen ?: firstSane ?: LocalTemplate.Kind.CHATML
        // nothing ran at all (for example the model was unloaded): do not remember a guess, test again next time
        if (completed > 0) {
            ConvKpi.inc("template_probe")
            Store.localTemplateResult = pick.key
            Store.localTemplateSig = sig
            Store.localTemplateNote =
                (if (chosen != null) "chuna gaya: " else "KOI shape saaf nahi nikla, ye rakha: ") + pick.key + "\n" +
                    notes.joinToString("\n")
        }
        return pick
    }
}
