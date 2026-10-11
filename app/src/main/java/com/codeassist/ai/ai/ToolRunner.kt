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
            // slow tool: the task engine calls it through StepTimeout; a direct call (old paths) still works, it just blocks
            "weather_now" -> weather(a["city"].orEmpty().trim())
            else -> Tier0.Result("Tool", "Ye action mujhe aati nahi.", ok = false)
        }
    }

    /** Blocking network call: only the task engine runs it, off the main thread and under [StepTimeout]. */
    fun weather(city: String): Tier0.Result =
        when (val ans = WeatherClient.lookup(city)) {
            is WeatherClient.Answer.Ok -> Tier0.Result("Mausam", ans.reply)
            is WeatherClient.Answer.NotFound ->
                Tier0.Result("Mausam", "\"" + ans.city + "\" naam ka shehar nahi mila. Spelling ek baar check karke bolo.", ok = false)
            is WeatherClient.Answer.Failed ->
                Tier0.Result("Mausam", "Mausam nahi bata paya: " + ans.reason + ".", ok = false)
        }
}
