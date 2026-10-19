package com.codeassist.ai.ai

import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * ElevenLabs text-to-speech over plain HTTPS (no SDK), same style as [GeminiClient].
 *
 * Audit PDF Sec 9.7: model Flash v2.5, HTTP streaming endpoint, raw `pcm_24000` so it can go straight into an
 * AudioTrack (no mp3 decode delay), previous_text / next_text for continuity between sentence chunks.
 * The key travels in the `xi-api-key` header only, never in the URL. The key is typed by the user and kept
 * encrypted in the Android Keystore (see Store.elevenKey): it is never part of the build (audit Gap G4).
 */
object ElevenLabsClient {
    private const val BASE = "https://api.elevenlabs.io/v1"
    private const val BASE_V2 = "https://api.elevenlabs.io/v2"
    const val SAMPLE_RATE = 24_000
    const val DEFAULT_MODEL = "eleven_flash_v2_5"

    /** A premade voice id taken from the ElevenLabs docs examples. Part 2 replaces it with a picker. */
    const val DEFAULT_VOICE = "JBFqnCBsd6RMkjVDRZzb"

    class ApiError(val http: Int, message: String) : Exception(message)

    /** One selectable voice. [gender] is "female", "male" or "other" (label missing / non-binary / unknown). */
    class Voice(val id: String, val name: String, val gender: String, val accent: String) {
        fun label(): String {
            val g = when (gender) {
                "female" -> "Female"
                "male" -> "Male"
                else -> "Other"
            }
            return if (accent.isBlank()) "$name · $g" else "$name · $g · $accent"
        }
    }

    /** Used when the account's voice list cannot be loaded (for example a key without voices permission). Ids from the ElevenLabs docs examples. */
    val FALLBACK_VOICES = listOf(
        Voice("21m00Tcm4TlvDq8ikWAM", "Rachel", "female", ""),
        Voice("JBFqnCBsd6RMkjVDRZzb", "George", "male", "")
    )

    private fun cleanGender(raw: String): String = when (raw.trim().lowercase()) {
        "female" -> "female"
        "male" -> "male"
        else -> "other"
    }

    /** Parses one page of GET /v2/voices. Pure (JVM unit tested): missing fields never throw. */
    fun parseVoices(json: JSONObject): List<Voice> {
        val out = ArrayList<Voice>()
        val arr = json.optJSONArray("voices") ?: return out
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i) ?: continue
            val id = v.optString("voice_id", "")
            val name = v.optString("name", "")
            if (id.isBlank() || name.isBlank()) continue
            val labels = v.optJSONObject("labels")
            out.add(Voice(id, name, cleanGender(labels?.optString("gender", "") ?: ""), labels?.optString("accent", "") ?: ""))
        }
        return out
    }

    /** Stores the voice list in one preference string: id TAB name TAB gender TAB accent per line. */
    fun encodeVoices(list: List<Voice>): String =
        list.joinToString("\n") { listOf(it.id, it.name, it.gender, it.accent).joinToString("\t") { f -> f.replace('\t', ' ').replace('\n', ' ') } }

    fun decodeVoices(raw: String): List<Voice> =
        raw.split('\n').mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 3 || p[0].isBlank() || p[1].isBlank()) null
            else Voice(p[0], p[1], cleanGender(p[2]), if (p.size > 3) p[3] else "")
        }

    /**
     * The voices this key can use (GET /v2/voices, at most 3 pages of 100). Needs a key with the voices permission;
     * otherwise throws [ApiError] and the caller shows [FALLBACK_VOICES].
     */
    fun listVoices(key: String): List<Voice> {
        val found = LinkedHashMap<String, Voice>()
        var token: String? = null
        var pages = 0
        do {
            val url = StringBuilder("$BASE_V2/voices?page_size=100")
            val t = token
            if (t != null) url.append("&next_page_token=").append(java.net.URLEncoder.encode(t, "UTF-8"))
            val conn = URL(url.toString()).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 8_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("xi-api-key", key)
                val code = conn.responseCode
                if (code !in 200..299) throw ApiError(code, friendly(code, readError(conn)))
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val json = JSONObject(body)
                for (v in parseVoices(json)) found[v.id] = v
                val more = json.optBoolean("has_more", false)
                val next = json.optString("next_page_token", "")
                token = if (more && next.isNotBlank() && next != "null") next else null
            } finally {
                conn.disconnect()
            }
            pages++
        } while (token != null && pages < 3)
        return found.values.toList()
    }

    /** Whole synthesis as raw PCM16 24 kHz (used for the short cached phrases). */
    fun synthesizePcm(key: String, voiceId: String, model: String, text: String, speed: Float): ByteArray {
        val conn = openStream(key, voiceId, model, text, speed, null, null)
        try {
            val bytes = conn.inputStream.use { it.readBytes() }
            return if (bytes.size % 2 == 0) bytes else bytes.copyOf(bytes.size - 1)
        } finally {
            conn.disconnect()
        }
    }

    /** True when the key itself is wrong (not a quota / network / voice problem). */
    fun isAuthFailure(e: Throwable): Boolean = e is ApiError && (e.http == 401 || e.http == 403)

    /**
     * Opens the streaming request and returns the connection with the response body ready to read.
     * The caller reads [HttpURLConnection.getInputStream] and must call disconnect().
     */
    fun openStream(
        key: String,
        voiceId: String,
        model: String,
        text: String,
        speed: Float,
        previousText: String?,
        nextText: String?
    ): HttpURLConnection {
        val url = URL("$BASE/text-to-speech/${voiceId.trim()}/stream?output_format=pcm_$SAMPLE_RATE")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 8_000
        conn.readTimeout = 15_000
        conn.doOutput = true
        conn.setRequestProperty("xi-api-key", key)
        conn.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject()
        body.put("text", text)
        body.put("model_id", model.ifBlank { DEFAULT_MODEL })
        val s = speed.coerceIn(0.7f, 1.2f) // range the API accepts for voice_settings.speed
        if (kotlin.math.abs(s - 1.0f) > 0.02f) {
            body.put("voice_settings", JSONObject().put("speed", s.toDouble()))
        }
        if (!previousText.isNullOrBlank()) body.put("previous_text", previousText)
        if (!nextText.isNullOrBlank()) body.put("next_text", nextText)
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = readError(conn)
                conn.disconnect()
                throw ApiError(code, friendly(code, err))
            }
        } catch (e: ApiError) {
            throw e
        } catch (e: IOException) {
            conn.disconnect()
            throw e
        }
        return conn
    }

    /**
     * "Test key": one very short real synthesis (about 2 characters of credit). It checks exactly what the app
     * needs (a key allowed to do text-to-speech with the chosen voice), which a restricted key may not pass on
     * list endpoints. Throws [ApiError] / [IOException] on failure.
     */
    fun probe(key: String, voiceId: String, model: String) {
        val conn = openStream(key, voiceId, model, "Hi", 1.0f, null, null)
        try {
            val buf = ByteArray(2048)
            val n = conn.inputStream.read(buf)
            if (n <= 0) throw IOException("Khaali audio mili")
        } finally {
            conn.disconnect()
        }
    }

    private fun readError(conn: HttpURLConnection): String {
        return try {
            val stream: InputStream? = conn.errorStream
            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun detail(raw: String): String {
        if (raw.isBlank()) return ""
        return try {
            val d = JSONObject(raw).opt("detail")
            when (d) {
                is JSONObject -> d.optString("message", "")
                is String -> d
                else -> ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun friendly(code: Int, raw: String): String {
        val d = detail(raw)
        val base = when (code) {
            401 -> "ElevenLabs key galat hai ya expire ho gayi."
            403 -> "Is key ko text-to-speech ki permission nahi hai."
            402 -> "ElevenLabs plan ya credits ki limit. Voice abhi use nahi ho sakti."
            404 -> "Ye voice id ElevenLabs par nahi mili."
            422 -> "ElevenLabs ne request nahi mani (voice ya model theek nahi)."
            429 -> "ElevenLabs par abhi bahut requests ya limit poori. Thodi der baad try karo."
            else -> if (code >= 500) "ElevenLabs server abhi down hai." else "ElevenLabs error " + code + "."
        }
        return if (d.isNotBlank()) "$base ($d)" else base
    }
}
