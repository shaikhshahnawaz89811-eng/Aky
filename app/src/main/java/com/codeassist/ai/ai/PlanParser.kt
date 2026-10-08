package com.codeassist.ai.ai

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.io.StringReader

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
        val cleaned = ChatMl.clean(raw)
        // Gemma sometimes writes the call as a ```json {"tool_call": [...]} block instead of <tool_call> tags
        val text = if (cleaned.contains(OPEN)) cleaned else rewriteJsonCalls(cleaned)
        val rewritten = text != cleaned
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
        // the words around a rewritten block were written for a JSON code block ("Sorry ... yeh raha:"): drop them
        return Parsed.Calls(if (rewritten) "" else tidy(ack), tasks)
    }

    private val jsonFence = Regex("```(?:json)?\\s*([\\s\\S]*?)```")

    private fun rewriteJsonCalls(text: String): String {
        if (!text.contains("tool_call")) return text
        var out = text
        for (m in jsonFence.findAll(text)) {
            val body = m.groupValues[1]
            if (!body.contains("tool_call")) continue
            val calls = try {
                jsonCalls(body)
            } catch (_: Throwable) {
                null
            }
            if (calls == null || calls.isEmpty()) continue
            out = out.replace(m.value, calls.joinToString("") { OPEN + it + CLOSE })
        }
        return out
    }

    /** {"tool_call": [{"name": "date_today", "arguments": {}}]} -> one JSON object per call, or null if it is not that shape. */
    private fun jsonCalls(body: String): List<String>? {
        // parseReader does not insist that the whole text is consumed: a stray "}" after the object is tolerated
        val root = JsonParser.parseReader(JsonReader(StringReader(body.trim())))
        if (!root.isJsonObject) return null
        val holder = root.asJsonObject
        val el = holder.get("tool_call") ?: holder.get("tool_calls") ?: return null
        val items = if (el.isJsonArray) el.asJsonArray.toList() else listOf(el)
        val out = ArrayList<String>()
        for (item in items) {
            if (!item.isJsonObject) return null
            val o = item.asJsonObject
            var name: String? = null
            val nameEl = o.get("name")
            if (nameEl != null && nameEl.isJsonPrimitive) name = nameEl.asString.trim()
            if (name.isNullOrEmpty()) {
                // {"1": "date_today"}: take the value that is a known tool name
                name = null
                for ((_, v) in o.entrySet()) {
                    if (v.isJsonPrimitive && v.asJsonPrimitive.isString && ToolSpecs.find(v.asString.trim()) != null) {
                        name = v.asString.trim()
                    }
                }
            }
            if (name == null) return null
            val call = JsonObject()
            call.addProperty("name", name)
            call.add("arguments", o.get("arguments") ?: o.get("parameters") ?: JsonObject())
            out.add(call.toString())
        }
        return out
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
