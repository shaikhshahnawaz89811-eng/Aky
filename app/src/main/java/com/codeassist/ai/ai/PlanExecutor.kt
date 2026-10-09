package com.codeassist.ai.ai

import android.content.Context
import com.codeassist.ai.data.Message
import com.codeassist.ai.data.Store
import com.google.gson.Gson

/**
 * Entry point used by [ChatRunner] and the chat screen (audit Sec 9.6, 11).
 *
 * Phase 3: the plan itself is now run by [TaskEngine] (a DAG of nodes, saved to disk, partial failure, crash recovery).
 * This object keeps the two calls the rest of the app already uses: [run] for a plan the brain just proposed and
 * [confirm] for the user's Haan / Nahi tap under a reply. A confirmation made by an older build (no plan id in the stored
 * JSON) is still answered by the single-action code at the bottom.
 */
object PlanExecutor {
    /** What a plan turned into: the reply text, chip text, undo ids and the T2 action still waiting (JSON). */
    class Outcome(
        val text: String,
        val chip: String?,
        val undoIds: List<String>,
        val pendingJson: String?,
        /** Id of the stored plan this reply belongs to (null for the old single-action path). */
        val planId: String? = null
    )

    private val gson = Gson()

    /**
     * Runs a checked plan. [chatId] and [messageId] say where its reply will live, so a plan that a crash interrupted
     * can still report to the right chat after the restart ([TaskEngine.recover]). Both may be empty.
     */
    fun run(
        ctx: Context, ack: String, tasks: List<PlanTask>,
        chatId: String = "", messageId: String = ""
    ): Outcome = TaskEngine.run(ctx.applicationContext, chatId, messageId, ack, tasks)

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

        val planId = p?.planId
        val nodeId = p?.nodeId
        if (p != null && planId != null && nodeId != null) {
            val out = TaskEngine.confirm(app, planId, nodeId, yes)
            val updated: Message = if (out != null) {
                m.copy(
                    text = out.text, pending = out.pendingJson,
                    undoId = if (out.undoIds.isEmpty()) null else out.undoIds.joinToString(","),
                    actions = out.chip
                )
            } else {
                // the stored plan is gone (cleared, or older than a day): nothing runs, the message is closed
                m.copy(
                    text = join(p.base, "Ye plan ab mila nahi, isliye kuch nahi chalaya. Dobara bolo."),
                    pending = null,
                    actions = if (m.actions == "Confirm chahiye") null else m.actions
                )
            }
            list[i] = updated
            Store.saveMessages(chatId, list)
            return updated
        }
        return confirmLegacy(app, chatId, list, i, m, p, yes)
    }

    // ---------- confirmation made by a build older than Phase 3 (no stored plan) ----------

    private fun confirmLegacy(
        app: Context, chatId: String, list: MutableList<Message>, i: Int, m: Message,
        p: PendingAction?, yes: Boolean
    ): Message {
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
