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
        when (Modules.phase) {
            Modules.Phase.NOT_IMPORTED, Modules.Phase.ERROR, Modules.Phase.DOWNLOADING, Modules.Phase.IMPORTING ->
                return Result.Fail("Gemma 4 abhi taiyaar nahi hai. Voice and AI mein Download ya Import karo.")
            else -> Unit
        }
        if (!LocalLlm.loaded) {
            status("Model RAM mein load ho raha hai")
            try {
                Modules.ensureLoaded()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return Result.Fail(e.message ?: "Model load nahi ho paya.")
            }
        }
        if (LocalLlm.busy) status("Pichla reply khatam ho raha hai")

        val lang = PlanBuilder.language(ask, MemoryStore.replyLanguage())
        val system = if (previous != null) Sketch.reviseSystemPrompt(lang) else Sketch.systemPrompt(lang)
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
}
