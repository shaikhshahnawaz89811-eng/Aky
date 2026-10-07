package com.codeassist.ai.ai

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Gemini Developer API over plain HTTPS (no SDK). The key travels in the x-goog-api-key header,
 * never in the URL. Replies are streamed with streamGenerateContent?alt=sse.
 */
object GeminiClient {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    const val FALLBACK_MODEL = "gemini-3.5-flash"

    class ApiError(val http: Int, message: String) : Exception(message)
    class Image(val mime: String, val bytes: ByteArray)
    class Turn(val role: String, val text: String, val images: List<Image> = emptyList())

    /** One function call the model asked for; argument values are kept as plain strings. */
    class FnCall(val name: String, val args: Map<String, String>)

    /** A finished reply: the text it wrote and the function calls it made (either can be empty). */
    class Streamed(val text: String, val calls: List<FnCall>)

    private val excluded = listOf(
        "embedding", "tts", "live", "audio", "image", "imagen", "veo",
        "robotics", "computer-use", "aqa"
    )

    /** Calls models.list with the key: this is both the "Test key" check and the model picker source. */
    fun listModels(key: String): List<String> {
        val found = LinkedHashSet<String>()
        var token: String? = null
        var pages = 0
        do {
            val url = StringBuilder("$BASE/models?pageSize=100")
            val t = token
            if (t != null) url.append("&pageToken=").append(URLEncoder.encode(t, "UTF-8"))
            val conn = open(url.toString(), "GET", key)
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw ApiError(code, friendly(code, readError(conn)))
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val json = JSONObject(body)
                val arr = json.optJSONArray("models") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val name = m.optString("name").removePrefix("models/")
                    val methods = m.optJSONArray("supportedGenerationMethods")
                    var canGenerate = false
                    if (methods != null) {
                        for (j in 0 until methods.length()) {
                            if (methods.optString(j) == "generateContent") canGenerate = true
                        }
                    }
                    if (canGenerate && name.startsWith("gemini") && excluded.none { name.contains(it) }) {
                        found.add(name)
                    }
                }
                val next: String? = json.optString("nextPageToken", "").ifBlank { null }
                token = next
            } finally {
                conn.disconnect()
            }
            pages++
        } while (token != null && pages < 5)
        return found.sortedWith(compareBy<String>({ rank(it) }, { it }))
    }

    private fun rank(name: String): Int = when {
        name.contains("flash") && !name.contains("lite") -> 0
        name.contains("flash") -> 1
        name.contains("pro") -> 2
        else -> 3
    }

    fun pickDefault(models: List<String>): String {
        if (models.contains(FALLBACK_MODEL)) return FALLBACK_MODEL
        return models.firstOrNull() ?: FALLBACK_MODEL
    }

    /**
     * Streams one reply. [isCancelled] is polled between SSE lines; [onDelta] receives each text
     * fragment as it arrives. Returns the full text or throws [ApiError] / an IOException.
     */
    fun stream(
        key: String,
        model: String,
        system: String,
        turns: List<Turn>,
        temperature: Float,
        isCancelled: () -> Boolean,
        onDelta: (String) -> Unit
    ): String = streamWithTools(key, model, system, turns, temperature, emptyList(), isCancelled, onDelta).text

    /**
     * Same as [stream], but also sends [tools] as Gemini function declarations (mode AUTO) and collects the
     * functionCall parts of the answer. An answer with only function calls and no text is a valid answer.
     */
    fun streamWithTools(
        key: String,
        model: String,
        system: String,
        turns: List<Turn>,
        temperature: Float,
        tools: List<ToolSpec>,
        isCancelled: () -> Boolean,
        onDelta: (String) -> Unit
    ): Streamed {
        if (!model.matches(Regex("[A-Za-z0-9._-]+"))) throw ApiError(0, "Gemini model ka naam galat hai.")

        val body = JSONObject()
        if (system.isNotBlank()) {
            body.put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
            )
        }
        val contents = JSONArray()
        for (t in turns) {
            val parts = JSONArray()
            for (img in t.images) {
                parts.put(
                    JSONObject().put(
                        "inlineData",
                        JSONObject()
                            .put("mimeType", img.mime)
                            .put("data", Base64.encodeToString(img.bytes, Base64.NO_WRAP))
                    )
                )
            }
            if (t.text.isNotBlank() || parts.length() == 0) {
                parts.put(JSONObject().put("text", t.text.ifBlank { " " }))
            }
            contents.put(JSONObject().put("role", t.role).put("parts", parts))
        }
        body.put("contents", contents)
        body.put("generationConfig", JSONObject().put("temperature", temperature.toDouble()))
        if (tools.isNotEmpty()) {
            body.put("tools", toolsJson(tools))
            body.put(
                "toolConfig",
                JSONObject().put("functionCallingConfig", JSONObject().put("mode", "AUTO"))
            )
        }

        val conn = open("$BASE/models/$model:streamGenerateContent?alt=sse", "POST", key)
        val out = StringBuilder()
        val calls = ArrayList<FnCall>()
        var blocked: String? = null
        var finishReason = ""
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) throw ApiError(code, friendly(code, readError(conn)))
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    if (isCancelled()) break
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue
                    val json = JSONObject(payload)
                    val feedback = json.optJSONObject("promptFeedback")
                    if (feedback != null) {
                        val reason = feedback.optString("blockReason", "")
                        if (reason.isNotBlank()) blocked = reason
                    }
                    val candidates = json.optJSONArray("candidates")
                    if (candidates == null || candidates.length() == 0) continue
                    val candidate = candidates.getJSONObject(0)
                    val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val p = parts.getJSONObject(i)
                            if (p.optBoolean("thought", false)) continue
                            val fc = p.optJSONObject("functionCall")
                            if (fc != null) {
                                val fname = fc.optString("name", "")
                                val argsObj = fc.optJSONObject("args")
                                val args = LinkedHashMap<String, String>()
                                if (argsObj != null) {
                                    val keys = argsObj.keys()
                                    while (keys.hasNext()) {
                                        val k = keys.next()
                                        if (!argsObj.isNull(k)) args[k] = argsObj.get(k).toString()
                                    }
                                }
                                if (fname.isNotBlank()) calls.add(FnCall(fname, args))
                                continue
                            }
                            val text = p.optString("text", "")
                            if (text.isNotEmpty()) {
                                out.append(text)
                                onDelta(text)
                            }
                        }
                    }
                    val reason = candidate.optString("finishReason", "")
                    if (reason.isNotBlank()) finishReason = reason
                }
            }
        } finally {
            conn.disconnect()
        }

        if (out.isEmpty() && calls.isEmpty() && !isCancelled()) {
            val b = blocked
            if (b != null) throw ApiError(0, "Gemini ne ye request block kar di ($b).")
            if (finishReason == "SAFETY" || finishReason == "PROHIBITED_CONTENT") {
                throw ApiError(0, "Gemini ne ye reply safety ki wajah se roki.")
            }
            throw ApiError(0, "Gemini ne khaali reply di. Dobara try karo.")
        }
        return Streamed(out.toString(), calls)
    }

    /** Gemini function declarations built from the tool table. Types are the proto enum names (OBJECT, STRING ...). */
    private fun toolsJson(tools: List<ToolSpec>): JSONArray {
        val decls = JSONArray()
        for (s in tools) {
            val d = JSONObject().put("name", s.name).put("description", s.desc)
            if (s.params.isNotEmpty()) {
                val props = JSONObject()
                val required = JSONArray()
                for (p in s.params) {
                    props.put(
                        p.name,
                        JSONObject().put("type", p.type.uppercase()).put("description", p.desc)
                    )
                    if (p.required) required.put(p.name)
                }
                val params = JSONObject().put("type", "OBJECT").put("properties", props)
                if (required.length() > 0) params.put("required", required)
                d.put("parameters", params)
            }
            decls.put(d)
        }
        return JSONArray().put(JSONObject().put("functionDeclarations", decls))
    }

    private fun open(url: String, method: String, key: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 90_000
        conn.setRequestProperty("x-goog-api-key", key)
        conn.setRequestProperty("Content-Type", "application/json")
        if (method == "POST") conn.doOutput = true
        return conn
    }

    private fun readError(conn: HttpURLConnection): String? {
        return try {
            conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    private fun friendly(code: Int, body: String?): String {
        val msg = try {
            JSONObject(body ?: "").getJSONObject("error").optString("message", "")
        } catch (_: Exception) {
            ""
        }
        return when {
            code == 400 && msg.contains("API key", ignoreCase = true) ->
                "Gemini API key galat hai. Key dobara copy karke daalo."
            code == 400 -> "Gemini ne request reject ki: " + msg.take(160)
            code == 401 || code == 403 ->
                "Gemini ne permission nahi di (" + code + "). Key, region ya billing check karo." +
                    (if (msg.isNotBlank()) " " + msg.take(120) else "")
            code == 404 -> "Ye Gemini model is key par nahi mila. Voice and AI mein dusra model chuno."
            code == 429 -> "Gemini ki limit / quota khatam. Thodi der baad try karo."
            code >= 500 -> "Gemini server abhi busy hai ($code). Thodi der baad try karo."
            else -> "Gemini error $code: " + msg.take(160)
        }
    }
}
