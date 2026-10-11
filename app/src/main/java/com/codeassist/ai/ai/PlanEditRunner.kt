package com.codeassist.ai.ai

import android.content.Context
import com.codeassist.ai.data.Message

/**
 * Phase 3C: runs a [PlanEdit] on the phone. Called by [ChatRunner] right after the correction check, so "petrol wala kaam
 * hata do" never reaches the brain as a chat question.
 *
 * Guard rails (a wrong guess here would remove a step the user wanted):
 *  - it only looks at the message that comes straight after a plan reply: [lastAi] must be the reply of a stored plan
 *    ([TaskEngine.planOfMessage]), and that plan must be younger than [PlanEdit.WINDOW_MS];
 *  - the words must name a step ([PlanEdit.parse]); a name that is no tool of the plan gets an honest "no such step" answer
 *    that lists what the plan has, and changes nothing;
 *  - two steps of the same tool are never guessed: the user is asked to say "pehla" or "doosra".
 */
object PlanEditRunner {
    fun tryEdit(app: Context, text: String, lastAi: Message?, now: Long = System.currentTimeMillis()): Tier0.Result? {
        if (lastAi == null) return null
        val req = PlanEdit.parse(text) ?: return null
        val plan = TaskEngine.planOfMessage(app, lastAi.id) ?: return null
        if (now - plan.createdAt > PlanEdit.WINDOW_MS || now < plan.createdAt) return null

        return when (val pick = PlanEdit.pick(plan, req)) {
            is PlanEdit.Pick.NotFound -> {
                ConvKpi.inc("plan_edit_notfound")
                Tier0.Result(
                    "Plan",
                    "\"" + req.subject + "\" naam ka koi kaam is plan mein nahi mila. Plan mein ye hai: " +
                        PlanEdit.listing(plan) + ". Jo hatana hai uska naam bolo, jaise \"torch wala kaam hata do\".",
                    ok = false
                )
            }
            is PlanEdit.Pick.Ambiguous -> {
                ConvKpi.inc("plan_edit_ambiguous")
                val name = req.tool?.let { TaskGraph.label(it) } ?: req.subject
                Tier0.Result(
                    "Plan",
                    "Plan mein " + pick.count + " " + name + " wale kaam hain. Bolo \"pehla " + name.lowercase() +
                        " hata do\" ya \"doosra " + name.lowercase() + " hata do\".",
                    ok = false
                )
            }
            is PlanEdit.Pick.Found -> {
                val res = TaskEngine.applyEdit(app, plan.id, pick.node.id)
                    ?: return Tier0.Result("Plan", "Ye plan ab mila nahi, kuch nahi badla.", ok = false)
                Tier0.Result("Plan", res.reply, ok = res.changed)
            }
        }
    }
}
