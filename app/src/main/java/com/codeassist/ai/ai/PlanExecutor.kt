package com.codeassist.ai.ai

import android.content.Context
import com.codeassist.ai.data.Message
import com.codeassist.ai.data.Store
import com.google.gson.Gson

/**
 * Runs a plan the brain proposed (audit Sec 9.6, 11): each task goes through [PolicyGate] first, T0 / T1 tools
 * reuse the same Tier-0 code as the fast path, T2 tools wait for the user's tap, and every action that really
 * happened is written to [ActivityLog] (with an undo token where Android allows one).
 */
object PlanExecutor {
    /** What a plan turned into: the reply text, chip text, undo ids and the T2 action still waiting (JSON). */
    class Outcome(
        val text: String,
        val chip: String?,
        val undoIds: List<String>,
        val pendingJson: String?
    )

    private val gson = Gson()

    fun run(ctx: Context, ack: String, tasks: List<PlanTask>): Outcome {
        val app = ctx.applicationContext
        ActivityLog.init(app)
        val lines = ArrayList<ReplyComposer.Line>()
        val undo = ArrayList<String>()
        val chips = ArrayList<String>()
        var pending: PendingAction? = null

        for (t in tasks) {
            val decision = PolicyGate.check(t)
            if (decision is PolicyGate.Decision.Deny) {
                lines.add(ReplyComposer.Line(decision.reason, false, false))
            } else if (decision is PolicyGate.Decision.Confirm) {
                if (pending == null) {
                    pending = PendingAction(t.tool, t.args, decision.readback, "")
                } else {
                    lines.add(
                        ReplyComposer.Line("Ek baar mein ek hi cheez confirm kar sakta hoon, baaki alag se bolna.", false, false)
                    )
                }
            } else {
                val r = ToolRunner.execute(app, t)
                lines.add(ReplyComposer.Line(r.reply, r.ok, ToolSpecs.find(t.tool)?.readOnly == true))
                if (r.ok) {
                    val entry = ActivityLog.add(r.title, r.tier, r.reply, r.undo)
                    if (r.undo != null) undo.add(entry.id)
                    chips.add(r.title)
                }
            }
        }

        val base = ReplyComposer.results(ack, lines)
        val waiting = pending
        val pendingJson: String? = if (waiting == null) null else gson.toJson(
            PendingAction(waiting.tool, waiting.args, waiting.readback, if (lines.isEmpty()) "" else base)
        )
        val text = ReplyComposer.withPending(if (lines.isEmpty()) "" else base, waiting?.readback)
        val chip: String? = when {
            chips.isNotEmpty() -> chips.distinct().joinToString(" · ")
            waiting != null -> "Confirm chahiye"
            else -> null
        }
        return Outcome(text, chip, undo, pendingJson)
    }

    /**
     * The user tapped Haan ([yes] true) or Nahi on a pending T2 action. Updates the stored message and returns it
     * (null when the message no longer exists). Re-checks everything: nothing here trusts the stored JSON.
     */
    fun confirm(ctx: Context, chatId: String, messageId: String, yes: Boolean): Message? {
        val app = ctx.applicationContext
        Store.init(app)
        ActivityLog.init(app)
        val list = Store.messages(chatId)
        val i = list.indexOfFirst { it.id == messageId }
        if (i < 0) return null
        val m = list[i]
        val json = m.pending ?: return m
        val p: PendingAction? = try {
            gson.fromJson(json, PendingAction::class.java)
        } catch (_: Throwable) {
            null
        }

        var text = (p?.base ?: "").trim()
        var undoIds: String? = m.undoId
        var chip: String? = m.actions
        if (p == null) {
            text = join(text, "Ye confirm padh nahi paya. Dobara bolo.")
        } else if (!yes) {
            text = join(text, "Theek hai, cancel kar diya.")
        } else if (PolicyGate.pendingExpired(m.time, System.currentTimeMillis())) {
            text = join(text, "Ye confirm purana ho gaya (10 minute se zyada). Dobara bolo.")
        } else if (!PolicyGate.canRunAfterConfirm(p.tool)) {
            text = join(text, "Ye action tap se confirm nahi hoti.")
        } else {
            val task = PlanTask("t1", p.tool, p.args)
            val problem = ToolSpecs.validate(task)
            if (problem != null) {
                text = join(text, "Ye action ab chal nahi sakti: " + problem)
            } else {
                val r = ToolRunner.execute(app, task)
                text = join(text, r.reply)
                if (r.ok) {
                    val entry = ActivityLog.add(r.title, r.tier, r.reply, r.undo)
                    if (r.undo != null) undoIds = if (undoIds.isNullOrBlank()) entry.id else undoIds + "," + entry.id
                    chip = if (chip == null || chip == "Confirm chahiye") r.title else chip + " · " + r.title
                }
            }
        }
        val updated = m.copy(
            text = text, pending = null, undoId = undoIds,
            actions = if (chip == "Confirm chahiye") null else chip
        )
        list[i] = updated
        Store.saveMessages(chatId, list)
        return updated
    }

    private fun join(a: String, b: String): String = if (a.isBlank()) b else a.trim() + " " + b
}
