package com.codeassist.ai.ai

import java.util.Locale
import kotlinx.coroutines.CancellationException

/**
 * Sketch Studio stage 2: the studio screen asks the phone model for a drawing without going through a chat message.
 * Same rules as `ChatRunner.runSketch` (T4 / stage 1): [Sketch.systemPrompt] or [Sketch.reviseSystemPrompt], the answer is
 * checked by [Sketch.parse], a wrong answer gets ONE retry with the reason, a drawing too big for the phone model is refused
 * for editing. Offline Gemma only (the Gemini brain is not used, same as the chat sketch path). `ChatRunner` is not changed:
 * the chat path and this one stay separate on purpose, so a mistake here cannot break the chat.
 *
 * The model call itself is serialised by [LocalLlm]'s own lock: when a chat reply is being written, this waits for it.
 *
 * Stage 3 (14 Oct 2026): the studio prompts now also offer arcs, gradients, transforms and layers ([Sketch.studioSystemPrompt]);
 * [runVariants] asks for three different drawings of one request ([SketchVariants]); [runPage] writes or edits a web page /
 * 3D scene ([HtmlSandbox]) instead of a drawing.
 */
object SketchGen {
    sealed class Result {
        /** [json] is [Sketch.toJson] of the checked drawing. */
        class Ok(val drawing: Sketch.Drawing, val json: String, val seconds: Long, val retried: Boolean, val caption: String) : Result()

        /** [message] is shown to the user as it is (Hinglish, says what happened and that the old drawing is unchanged). */
        class Fail(val message: String) : Result()
    }

    /**
     * [request] is what the user typed; [previous] is the current drawing's JSON for an edit, or null for a new drawing.
     * [status] gets short progress lines ("Model RAM mein load ho raha hai", ...). [isCancelled] is polled by the model call.
     */
    suspend fun run(request: String, previous: String?, isCancelled: () -> Boolean, status: (String) -> Unit): Result {
        val ask = request.trim()
        if (ask.isEmpty()) return Result.Fail("Pehle likho ki kya banana ya badalna hai.")
        ConvKpi.inc("sk_studio")
        if (previous != null && previous.length > Sketch.MAX_REVISE_JSON) {
            ConvKpi.inc("sk_failed")
            return Result.Fail(
                "Ye chitra phone model ke liye edit karne ko bahut bada hai (" + previous.length + " akshar, limit " +
                    Sketch.MAX_REVISE_JSON + "). Purana chitra waisa hi hai. \"Naya\" se halka chitra banwao."
            )
        }
        prepare(status)?.let { return Result.Fail(it) }

        val lang = PlanBuilder.language(ask, MemoryStore.replyLanguage())
        val system = if (previous != null) Sketch.studioReviseSystemPrompt(lang) else Sketch.studioSystemPrompt(lang)
        val prompt = if (previous != null) Sketch.revisePrompt(previous, ask) else Sketch.userPrompt(ask)

        var millis = 0L
        var retried = false
        var parsed: Sketch.Parsed = Sketch.Parsed.Bad("no answer")
        try {
            status(if (previous != null) "Gemma chitra badal raha hai" else "Gemma chitra bana raha hai")
            val first = LocalLlm.chat(system, emptyList(), prompt, emptyList(), isCancelled) { }
            millis += first.millis
            parsed = Sketch.parse(first.text)
            val bad = parsed
            if (bad is Sketch.Parsed.Bad && !isCancelled()) {
                ConvKpi.inc("sk_retry")
                retried = true
                status("Gemma jawab theek kar raha hai")
                val again = LocalLlm.chat(system, emptyList(), prompt + Sketch.retryNote(bad.reason), emptyList(), isCancelled) { }
                millis += again.millis
                parsed = Sketch.parse(again.text)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ConvKpi.inc("sk_failed")
            return Result.Fail("Model ne kaam nahi kiya (" + (e.message ?: e.javaClass.simpleName) + "). Chitra waisa hi hai.")
        }
        if (isCancelled()) return Result.Fail("Roka gaya. Chitra waisa hi hai.")

        val where = if (LocalLlm.backendName.isEmpty()) "" else " · " + LocalLlm.backendName.uppercase(Locale.US)
        val seconds = millis / 1000
        val caption = (if (previous != null) "Badla · " else "Naya · ") + "Gemma 4 E2B" + where + " · " + seconds + "s" +
            (if (retried) " · dobara" else "")
        return when (val result = parsed) {
            is Sketch.Parsed.Ok -> {
                ConvKpi.inc("sk_ok")
                Result.Ok(result.drawing, Sketch.toJson(result.drawing), seconds, retried, caption)
            }
            is Sketch.Parsed.Bad -> {
                ConvKpi.inc("sk_failed")
                Result.Fail(
                    (if (previous != null) "Badal nahi paya" else "Chitra nahi ban paya") + " (" + result.reason + "). " +
                        "Chitra waisa hi hai; thoda saaf likhkar dobara try karo."
                )
            }
        }
    }

    // ---------- shared pieces (stage 3) ----------

    /** A short message when the model cannot be used right now, else null (loads the model first when needed). */
    private suspend fun prepare(status: (String) -> Unit): String? {
        when (Modules.phase) {
            Modules.Phase.NOT_IMPORTED, Modules.Phase.ERROR, Modules.Phase.DOWNLOADING, Modules.Phase.IMPORTING ->
                return "Gemma 4 abhi taiyaar nahi hai. Voice and AI mein Download ya Import karo."
            else -> Unit
        }
        if (!LocalLlm.loaded) {
            status("Model RAM mein load ho raha hai")
            try {
                Modules.ensureLoaded()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return e.message ?: "Model load nahi ho paya."
            }
        }
        if (LocalLlm.busy) status("Pichla reply khatam ho raha hai")
        return null
    }

    private class Attempt<T>(val value: T?, val reason: String, val millis: Long, val retried: Boolean)

    /**
     * One model call, and ONE retry with the reason when [parse] refuses the answer. [parse] gives the value or null with a reason.
     * Exceptions (including cancellation) go to the caller.
     */
    private suspend fun <T> attempt(
        system: String,
        prompt: String,
        isCancelled: () -> Boolean,
        status: (String) -> Unit,
        parse: (LocalLlm.Reply) -> Pair<T?, String>
    ): Attempt<T> {
        var millis = 0L
        var retried = false
        val first = LocalLlm.chat(system, emptyList(), prompt, emptyList(), isCancelled) { }
        millis += first.millis
        var out = parse(first)
        if (out.first == null && !isCancelled()) {
            ConvKpi.inc("sk_retry")
            retried = true
            status("Gemma jawab theek kar raha hai")
            val again = LocalLlm.chat(system, emptyList(), prompt + Sketch.retryNote(out.second), emptyList(), isCancelled) { }
            millis += again.millis
            out = parse(again)
        }
        return Attempt(out.first, out.second, millis, retried)
    }

    private fun backendTag(): String = if (LocalLlm.backendName.isEmpty()) "" else " \u00B7 " + LocalLlm.backendName.uppercase(Locale.US)

    // ---------- three variations of one request ----------

    sealed class VariantsResult {
        /** 1 to [SketchVariants.COUNT] different, checked drawings. */
        class Ok(val items: List<SketchVariants.Candidate>, val seconds: Long, val caption: String) : VariantsResult()
        class Fail(val message: String) : VariantsResult()
    }

    /** Three drawings of [request], one after the other (each with its own layout idea). A failed one is skipped. */
    suspend fun runVariants(request: String, isCancelled: () -> Boolean, status: (String) -> Unit): VariantsResult {
        val ask = request.trim()
        if (ask.isEmpty()) return VariantsResult.Fail("Pehle likho ki kya banana hai.")
        ConvKpi.inc("sk_studio")
        ConvKpi.inc("sk_variants")
        prepare(status)?.let { return VariantsResult.Fail(it) }
        val lang = PlanBuilder.language(ask, MemoryStore.replyLanguage())
        val system = Sketch.studioSystemPrompt(lang)
        val good = ArrayList<SketchVariants.Candidate>()
        var millis = 0L
        var lastReason = "no answer"
        try {
            for (i in 0 until SketchVariants.COUNT) {
                if (isCancelled()) break
                status("Gemma " + SketchVariants.label(i) + " bana raha hai (" + (i + 1) + " / " + SketchVariants.COUNT + ")")
                val at = attempt<Sketch.Drawing>(system, SketchVariants.prompt(ask, i), isCancelled, status) { reply ->
                    when (val p = Sketch.parse(reply.text)) {
                        is Sketch.Parsed.Ok -> Pair(p.drawing, "")
                        is Sketch.Parsed.Bad -> Pair(null, p.reason)
                    }
                }
                millis += at.millis
                val d = at.value
                if (d != null) good.add(SketchVariants.Candidate(i, d, Sketch.toJson(d))) else lastReason = at.reason
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ConvKpi.inc("sk_failed")
            return VariantsResult.Fail("Model ne kaam nahi kiya (" + (e.message ?: e.javaClass.simpleName) + "). Chitra waisa hi hai.")
        }
        if (isCancelled()) return VariantsResult.Fail("Roka gaya. Chitra waisa hi hai.")
        val items = SketchVariants.distinct(good)
        if (items.isEmpty()) {
            ConvKpi.inc("sk_failed")
            return VariantsResult.Fail("Koi option nahi ban paya (" + lastReason + "). Chitra waisa hi hai; thoda saaf likhkar dobara try karo.")
        }
        ConvKpi.inc("sk_ok")
        val seconds = millis / 1000
        return VariantsResult.Ok(items, seconds, items.size.toString() + " option \u00B7 Gemma 4 E2B" + backendTag() + " \u00B7 " + seconds + "s")
    }

    // ---------- web page / 3D scene ----------

    sealed class PageResult {
        /** [html] is the checked page as written by the model (the CSP is added when it is shown, see [HtmlSandbox.wrap]). */
        class Ok(val html: String, val seconds: Long, val retried: Boolean, val caption: String) : PageResult()
        class Fail(val message: String) : PageResult()
    }

    /** A new web page (or 3D scene when [three]) from [request], or an edit of [previous]. */
    suspend fun runPage(request: String, previous: String?, three: Boolean, isCancelled: () -> Boolean, status: (String) -> Unit): PageResult {
        val ask = request.trim()
        if (ask.isEmpty()) return PageResult.Fail("Pehle likho ki kya banana ya badalna hai.")
        ConvKpi.inc("sk_studio")
        ConvKpi.inc("sk_web")
        if (previous != null && previous.length > HtmlSandbox.MAX_REVISE_HTML) {
            ConvKpi.inc("sk_failed")
            return PageResult.Fail(
                "Ye page phone model ke liye edit karne ko bahut bada hai (" + previous.length + " akshar, limit " +
                    HtmlSandbox.MAX_REVISE_HTML + "). Purana page waisa hi hai. \"Naya\" se halka page banwao."
            )
        }
        prepare(status)?.let { return PageResult.Fail(it) }
        val lang = PlanBuilder.language(ask, MemoryStore.replyLanguage())
        val system = if (previous != null) HtmlSandbox.reviseSystemPrompt(lang, three) else HtmlSandbox.systemPrompt(lang, three)
        val prompt = if (previous != null) HtmlSandbox.revisePrompt(previous, ask) else HtmlSandbox.userPrompt(ask)
        val at: Attempt<String> = try {
            status(if (previous != null) "Gemma page badal raha hai" else "Gemma page bana raha hai")
            attempt<String>(system, prompt, isCancelled, status) { reply ->
                when (val c = HtmlSandbox.check(reply.text)) {
                    is HtmlSandbox.Checked.Ok ->
                        if (reply.capped && !c.html.lowercase(Locale.US).contains("</html>")) {
                            Pair(null, "the page was cut off before the end; write a shorter page")
                        } else {
                            Pair(c.html, "")
                        }
                    is HtmlSandbox.Checked.Bad -> Pair(null, c.reason)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ConvKpi.inc("sk_failed")
            return PageResult.Fail("Model ne kaam nahi kiya (" + (e.message ?: e.javaClass.simpleName) + "). Page waisa hi hai.")
        }
        if (isCancelled()) return PageResult.Fail("Roka gaya. Page waisa hi hai.")
        val html = at.value
        if (html == null) {
            ConvKpi.inc("sk_failed")
            return PageResult.Fail(
                (if (previous != null) "Badal nahi paya" else "Page nahi ban paya") + " (" + at.reason + "). " +
                    "Page waisa hi hai; thoda saaf likhkar dobara try karo."
            )
        }
        ConvKpi.inc("sk_ok")
        val seconds = at.millis / 1000
        val caption = (if (previous != null) "Badla \u00B7 " else "Naya \u00B7 ") + "Gemma 4 E2B" + backendTag() + " \u00B7 " + seconds + "s" +
            (if (at.retried) " \u00B7 dobara" else "")
        return PageResult.Ok(html, seconds, at.retried, caption)
    }
}
