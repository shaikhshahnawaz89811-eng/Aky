package com.codeassist.ai.ai

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Phase 3B-2: the first tool that can be slow, `weather_now(city)`. Open-Meteo, no API key (audit Sec 10: "Web search /
 * weather: HTTP API, tier C, output is untrusted data").
 *
 * Two calls: the geocoding API turns the city name into a place, the forecast API gives the current values. The
 * reply sentence is built here from numbers and a short place name only; nothing the server sends is ever passed back to a
 * model, so a server cannot talk the assistant into an action (audit Sec 11.3).
 *
 * Pure JVM (java.net + Gson, no Android class). The HTTP getter is a parameter, so the tests run with canned JSON.
 */
object WeatherClient {
    class Place(val name: String, val country: String, val lat: Double, val lon: Double)

    class Now(val tempC: Double, val feelsC: Double?, val humidity: Int?, val windKmh: Double?, val code: Int)

    sealed class Answer {
        class Ok(val reply: String) : Answer()
        class NotFound(val city: String) : Answer()
        class Failed(val reason: String) : Answer()
    }

    private const val GEO_HOST = "https://geocoding-api.open-meteo.com/v1/search"
    private const val FORECAST_HOST = "https://api.open-meteo.com/v1/forecast"
    private const val MAX_CHARS = 64 * 1024
    private const val HTTP_TIMEOUT_MS = 4000

    fun geocodeUrl(city: String): String =
        GEO_HOST + "?name=" + URLEncoder.encode(city.trim(), "UTF-8") + "&count=1&language=en&format=json"

    fun forecastUrl(p: Place): String =
        FORECAST_HOST + "?latitude=" + String.format(Locale.US, "%.4f", p.lat) +
            "&longitude=" + String.format(Locale.US, "%.4f", p.lon) +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m&timezone=auto"

    // ---------- parsing (pure) ----------

    private fun obj(e: JsonElement?): JsonObject? = if (e != null && e.isJsonObject) e.asJsonObject else null

    private fun num(o: JsonObject, key: String): Double? {
        val e = o.get(key) ?: return null
        if (!e.isJsonPrimitive || !e.asJsonPrimitive.isNumber) return null
        return e.asDouble
    }

    private fun text(o: JsonObject, key: String): String? {
        val e = o.get(key) ?: return null
        if (!e.isJsonPrimitive || !e.asJsonPrimitive.isString) return null
        return e.asString
    }

    /** A place name that is safe to show: letters, digits, space, dot, apostrophe, hyphen, at most 40 characters. */
    fun cleanName(raw: String): String =
        raw.filter { it.isLetterOrDigit() || it == ' ' || it == '.' || it == '\'' || it == '-' }.trim().take(40)

    /** First match of a geocoding answer, or null when there is none or the JSON is not what we expect. */
    fun parsePlace(json: String): Place? {
        return try {
            val root = obj(JsonParser.parseString(json)) ?: return null
            val results = root.get("results") ?: return null
            if (!results.isJsonArray || results.asJsonArray.size() == 0) return null
            val first = obj(results.asJsonArray.get(0)) ?: return null
            val lat = num(first, "latitude") ?: return null
            val lon = num(first, "longitude") ?: return null
            if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
            val name = cleanName(text(first, "name").orEmpty())
            if (name.isEmpty()) return null
            Place(name, cleanName(text(first, "country").orEmpty()), lat, lon)
        } catch (_: Throwable) {
            null
        }
    }

    /** The `current` block of a forecast answer, or null when temperature or weather code is missing. */
    fun parseNow(json: String): Now? {
        return try {
            val root = obj(JsonParser.parseString(json)) ?: return null
            val cur = obj(root.get("current")) ?: return null
            val temp = num(cur, "temperature_2m") ?: return null
            val code = num(cur, "weather_code") ?: return null
            if (temp < -90.0 || temp > 60.0) return null
            Now(
                temp,
                num(cur, "apparent_temperature"),
                num(cur, "relative_humidity_2m")?.roundToInt(),
                num(cur, "wind_speed_10m"),
                code.toInt()
            )
        } catch (_: Throwable) {
            null
        }
    }

    /** WMO weather code in short Hinglish. */
    fun describe(code: Int): String = when (code) {
        0 -> "aasman saaf hai"
        1 -> "zyadatar saaf hai"
        2 -> "kuch badal hain"
        3 -> "badal chhaye hain"
        45, 48 -> "kohra hai"
        51, 53, 55, 56, 57 -> "halki phuhaar ho rahi hai"
        61 -> "halki barish ho rahi hai"
        63 -> "barish ho rahi hai"
        65, 66, 67 -> "tez barish ho rahi hai"
        71, 73, 75, 77 -> "baraf gir rahi hai"
        80, 81, 82 -> "barish ki bauchhar ho rahi hai"
        85, 86 -> "baraf ki bauchhar ho rahi hai"
        95, 96, 99 -> "garaj ke saath toofan hai"
        else -> "mausam badla hua hai"
    }

    fun reply(p: Place, n: Now): String {
        val sb = StringBuilder()
        sb.append(p.name).append(" mein abhi ").append(n.tempC.roundToInt()).append("°C hai")
        val feels = n.feelsC
        if (feels != null && Math.abs(feels - n.tempC) >= 2.0) sb.append(" (mehsoos ").append(feels.roundToInt()).append("°C)")
        sb.append(", ").append(describe(n.code))
        val h = n.humidity
        if (h != null) sb.append(", humidity ").append(h).append('%')
        val w = n.windKmh
        if (w != null) sb.append(", hawa ").append(w.roundToInt()).append(" km/h")
        sb.append('.')
        return sb.toString()
    }

    // ---------- the two calls ----------

    /** Blocking. Call it through [StepTimeout.call] (the task engine does): it can take as long as the network does. */
    fun lookup(city: String, getter: ((String) -> String)? = null): Answer {
        val get: (String) -> String = getter ?: { u: String -> httpGet(u) }
        return try {
            val place = parsePlace(get(geocodeUrl(city))) ?: return Answer.NotFound(cleanName(city))
            val now = parseNow(get(forecastUrl(place)))
                ?: return Answer.Failed("mausam ka data samajh nahi aaya")
            Answer.Ok(reply(place, now))
        } catch (e: UnknownHostException) {
            Answer.Failed("Internet nahi mil raha")
        } catch (e: ConnectException) {
            Answer.Failed("Internet nahi mil raha")
        } catch (e: SocketTimeoutException) {
            Answer.Failed("server ne jawab dene mein der ki")
        } catch (e: IOException) {
            Answer.Failed("server se jawab nahi aaya (" + (e.message ?: "network error").take(40) + ")")
        }
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = HTTP_TIMEOUT_MS
            c.readTimeout = HTTP_TIMEOUT_MS
            c.requestMethod = "GET"
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "CodeAssistAI")
            val code = c.responseCode
            if (code != 200) throw IOException("HTTP $code")
            val sb = StringBuilder()
            c.inputStream.bufferedReader().use { r ->
                val buf = CharArray(4096)
                while (true) {
                    val n = r.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                    if (sb.length > MAX_CHARS) throw IOException("jawab bahut bada")
                }
            }
            return sb.toString()
        } finally {
            c.disconnect()
        }
    }
}
