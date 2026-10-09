package com.codeassist.ai.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** The tool table: what the brain may ask for, with argument rules and risk tiers (audit Sec 9.6, 10, 11.1). */
object ToolSpecs {
    const val MAX_TASKS = 4

    /**
     * Phase 3: every tool takes an optional `after`, the id of an earlier call of the same answer (t1, t2 ... in the
     * order the calls are written) that must finish first. It becomes [PlanTask.dependsOn]; if that earlier call
     * fails, this one is skipped and the reply says so. Without `after` a call is independent.
     */
    private val AFTER = ToolParam(
        "after", "string",
        "Optional: id of an earlier call that must finish first.",
        required = false
    )

    private fun spec(
        name: String, desc: String, tier: String, params: List<ToolParam>,
        readOnly: Boolean = false, idempotent: Boolean = false
    ): ToolSpec = ToolSpec(name, desc, tier, params + AFTER, readOnly, idempotent)

    val all: List<ToolSpec> = listOf(
        spec("time_now", "Current time.", "T0", emptyList(), readOnly = true, idempotent = true),
        spec("date_today", "Today's date and weekday.", "T0", emptyList(), readOnly = true, idempotent = true),
        spec("battery_level", "Battery percent and charging state.", "T0", emptyList(), readOnly = true, idempotent = true),
        spec(
            "torch_set", "Turn the flashlight on or off.", "T1",
            listOf(ToolParam("on", "boolean", "true = on, false = off")),
            idempotent = true
        ),
        spec(
            "timer_set", "Start a countdown timer.", "T1",
            listOf(ToolParam("seconds", "integer", "Length in seconds, 1 to 86400"))
        ),
        spec(
            "alarm_set", "Set an alarm for the next time this clock time comes.", "T1",
            listOf(
                ToolParam("hour", "integer", "Hour, 24-hour format 0-23 (8 pm = 20)"),
                ToolParam("minute", "integer", "Minute 0-59"),
                ToolParam("tomorrow", "boolean", "true only if the user said tomorrow (kal)", required = false)
            )
        ),
        spec(
            "app_open", "Open an installed app.", "T0",
            listOf(ToolParam("name", "string", "App name as the user said it")),
            idempotent = true
        ),
        spec(
            "call_dial", "Open the dialer with a number. The user presses Call.", "T2",
            listOf(ToolParam("number", "string", "Digits only, optional leading +"))
        )
    )

    fun find(name: String): ToolSpec? = all.firstOrNull { it.name == name }

    // ---------- argument helpers ----------

    fun intArg(args: Map<String, String>, key: String): Int? {
        val s = args[key]?.trim() ?: return null
        s.toIntOrNull()?.let { return it }
        val d = s.toDoubleOrNull() ?: return null
        return if (d % 1.0 == 0.0 && d >= Int.MIN_VALUE && d <= Int.MAX_VALUE) d.toInt() else null
    }

    fun boolArg(args: Map<String, String>, key: String): Boolean? =
        when (args[key]?.trim()?.lowercase()) {
            "true", "yes", "1" -> true
            "false", "no", "0" -> false
            else -> null
        }

    fun cleanNumber(raw: String): String = raw.replace(Regex("[ \\-().]"), "")

    /**
     * Builds a [PlanTask] from a raw call of either brain: the optional `after` argument is taken out of the
     * arguments and becomes the task's dependencies. "t1", "T1", "1" and "t1, t2" are all understood; anything else is kept
     * as written so [TaskGraph.validate] can refuse the plan instead of the app guessing what was meant.
     */
    fun task(id: String, tool: String, rawArgs: Map<String, String>): PlanTask {
        if (!rawArgs.containsKey("after")) return PlanTask(id, tool, rawArgs)
        val args = LinkedHashMap<String, String>()
        for ((k, v) in rawArgs) if (k != "after") args[k] = v
        return PlanTask(id, tool, args, parseAfter(rawArgs["after"]))
    }

    fun parseAfter(raw: String?): List<String> {
        if (raw == null) return emptyList()
        val out = ArrayList<String>()
        for (part in raw.split(',', ';', ' ')) {
            val p = part.trim().lowercase()
            if (p.isEmpty()) continue
            val id = if (p.all { it.isDigit() }) "t$p" else p
            if (!out.contains(id)) out.add(id)
        }
        return out
    }

    // ---------- validation ----------

    /** Returns null when [task] is a well-formed call of a known tool, otherwise a short English reason. */
    fun validate(task: PlanTask): String? {
        val spec = find(task.tool) ?: return "unknown tool '" + task.tool + "'"
        for (p in spec.params) {
            val v = task.args[p.name]
            if (v == null || v.isBlank()) {
                if (p.required) return "missing argument '" + p.name + "' for " + spec.name
                continue
            }
            when (p.type) {
                "integer" -> if (intArg(task.args, p.name) == null) return "argument '" + p.name + "' must be an integer"
                "boolean" -> if (boolArg(task.args, p.name) == null) return "argument '" + p.name + "' must be true or false"
            }
        }
        return when (spec.name) {
            "timer_set" -> {
                val s = intArg(task.args, "seconds") ?: return "seconds missing"
                if (s !in 1..86400) "seconds must be 1 to 86400" else null
            }
            "alarm_set" -> {
                val h = intArg(task.args, "hour") ?: return "hour missing"
                val m = intArg(task.args, "minute") ?: return "minute missing"
                if (h !in 0..23) "hour must be 0 to 23" else if (m !in 0..59) "minute must be 0 to 59" else null
            }
            "call_dial" -> {
                val n = cleanNumber(task.args["number"].orEmpty())
                if (!Regex("^\\+?[0-9]{6,15}$").matches(n)) "number must be 6 to 15 digits" else null
            }
            "app_open" -> {
                val n = task.args["name"].orEmpty().trim()
                if (n.length !in 2..40) "app name must be 2 to 40 characters" else null
            }
            else -> null
        }
    }

    /** First problem in a whole plan (also enforces the task limit), or null when every task is fine. */
    fun check(tasks: List<PlanTask>): String? {
        if (tasks.isEmpty()) return "no tool calls"
        if (tasks.size > MAX_TASKS) return "too many tool calls (at most $MAX_TASKS)"
        for (t in tasks) {
            val problem = validate(t)
            if (problem != null) return problem
        }
        return TaskGraph.validate(tasks)
    }

    // ---------- Qwen2.5 tool prompt (the format its chat template was trained on) ----------

    private fun declaration(s: ToolSpec): JsonObject {
        val props = JsonObject()
        val required = JsonArray()
        for (p in s.params) {
            val o = JsonObject()
            o.addProperty("type", p.type)
            o.addProperty("description", p.desc)
            props.add(p.name, o)
            if (p.required) required.add(p.name)
        }
        val params = JsonObject()
        params.addProperty("type", "object")
        params.add("properties", props)
        params.add("required", required)
        val fn = JsonObject()
        fn.addProperty("name", s.name)
        fn.addProperty("description", s.desc)
        fn.add("parameters", params)
        val top = JsonObject()
        top.addProperty("type", "function")
        top.add("function", fn)
        return top
    }

    fun qwenToolsBlock(): String {
        val sb = StringBuilder()
        sb.append("# Tools\n\nYou may call one or more functions to assist with the user query.\n\n")
        sb.append("You are provided with function signatures within <tools></tools> XML tags:\n<tools>\n")
        for (s in all) sb.append(declaration(s).toString()).append('\n')
        sb.append("</tools>\n\nFor each function call, return a json object with function name and arguments ")
        sb.append("within <tool_call></tool_call> XML tags:\n<tool_call>\n")
        sb.append("{\"name\": <function-name>, \"arguments\": <args-json-object>}\n</tool_call>")
        return sb.toString()
    }
}
