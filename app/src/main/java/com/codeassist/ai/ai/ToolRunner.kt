package com.codeassist.ai.ai

import android.content.Context

/**
 * Maps a validated [PlanTask] onto the existing Tier-0 phone code, so a tool call from either brain does
 * exactly what the same words on the fast path would do. Call [ToolSpecs.validate] first.
 */
object ToolRunner {
    fun execute(ctx: Context, task: PlanTask): Tier0.Result {
        val a = task.args
        return when (task.tool) {
            "time_now" -> Tier0.runTime()
            "date_today" -> Tier0.runDate()
            "battery_level" -> Tier0.runBattery(ctx)
            "torch_set" -> Tier0.runTorch(ctx, ToolSpecs.boolArg(a, "on") == true)
            "timer_set" -> Tier0.runTimer(ctx, ToolSpecs.intArg(a, "seconds") ?: 0)
            "alarm_set" -> Tier0.runAlarm(
                ctx,
                ToolSpecs.intArg(a, "hour") ?: 0,
                ToolSpecs.intArg(a, "minute") ?: 0,
                ToolSpecs.boolArg(a, "tomorrow") == true
            )
            "app_open" -> Tier0.runOpenApp(ctx, a["name"].orEmpty())
            "call_dial" -> Tier0.runDial(ctx, ToolSpecs.cleanNumber(a["number"].orEmpty()))
            else -> Tier0.Result("Tool", "Ye action mujhe aati nahi.", ok = false)
        }
    }
}
