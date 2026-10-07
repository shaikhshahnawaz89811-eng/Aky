package com.codeassist.ai.ai

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Reads the Qwen reply. The Free llama-android API has no function calling and no grammar-constrained JSON,
 * so the model writes Qwen's own <tool_call>{...}</tool_call> text and the app parses and checks it here.
 * Audit Sec 9.3: JSON check, one repair retry (done by the caller), then a short question, never a guess.
 */
object PlanParser {
    sealed class Parsed {
        /** No tool call at all: an ordinary answer. */
        class Text(val text: String) : Parsed()

        /** Every tool call is well-formed. [ack] is whatever the model wrote around the calls. */
        class Calls(val ack: String, val tasks: List<PlanTask>) : Parsed()

        /** A tool call was attempted but is broken: [reason] goes back to the model for one repair try. */
        class Invalid(val reason: String, val ack: String) : Parsed()
    }

    private const val OPEN = "<tool_call>"
    private const val CLOSE = "</tool_call>"

    fun parse(raw: String): Parsed {
        val text = ChatMl.clean(raw)
        if (!text.contains(OPEN)) return Parsed.Text(text)

        val ack = StringBuilder()
        val tasks = ArrayList<PlanTask>()
        var pos = 0
        while (true) {
            val start = text.indexOf(OPEN, pos)
            if (start < 0) {
                ack.append(text.substring(pos))
                break
            }
            ack.append(text.substring(pos, start))
            val bodyStart = start + OPEN.length
            val end = text.indexOf(CLOSE, bodyStart)
            // a reply cut off by the token limit may lack the closing tag: the JSON itself decides if it is usable
            val body = if (end < 0) text.substring(bodyStart) else text.substring(bodyStart, end)
            pos = if (end < 0) text.length else end + CLOSE.length

            val call = parseOne(body)
            if (call == null) return Parsed.Invalid("the tool call is not valid JSON", tidy(ack))
            tasks.add(PlanTask("t" + (tasks.size + 1), call.first, call.second))
        }
        val problem = ToolSpecs.check(tasks)
        if (problem != null) return Parsed.Invalid(problem, tidy(ack))
        return Parsed.Calls(tidy(ack), tasks)
    }

    private fun tidy(sb: StringBuilder): String = sb.toString().replace(Regex("\\s+"), " ").trim()

    private fun parseOne(body: String): Pair<String, Map<String, String>>? {
        val json = body.replace("```json", "").replace("```", "").trim()
        if (json.isEmpty()) return null
        val root: JsonObject = try {
            val e = JsonParser.parseString(json)
            if (!e.isJsonObject) return null
            e.asJsonObject
        } catch (_: Throwable) {
            return null
        }
        val nameEl = root.get("name") ?: return null
        if (!nameEl.isJsonPrimitive) return null
        val name = nameEl.asString.trim()
        if (name.isEmpty()) return null

        var argsEl: JsonElement? = root.get("arguments") ?: root.get("parameters")
        // some small models write the arguments as a JSON string: "arguments": "{\"hour\": 8}"
        if (argsEl != null && argsEl.isJsonPrimitive && argsEl.asJsonPrimitive.isString) {
            argsEl = try {
                JsonParser.parseString(argsEl.asString)
            } catch (_: Throwable) {
                return null
            }
        }
        val args = LinkedHashMap<String, String>()
        if (argsEl != null && !argsEl.isJsonNull) {
            if (!argsEl.isJsonObject) return null
            for ((k, v) in argsEl.asJsonObject.entrySet()) {
                if (v.isJsonNull) continue
                args[k] = if (v.isJsonPrimitive) v.asString else v.toString()
            }
        }
        return Pair(name, args)
    }
}
