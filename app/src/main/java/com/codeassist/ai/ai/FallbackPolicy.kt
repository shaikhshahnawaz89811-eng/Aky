package com.codeassist.ai.ai

import java.io.IOException
import java.io.InterruptedIOException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** B2 routing policy. Only network/transient Gemini failures may switch this turn to local Qwen. */
object FallbackPolicy {
    enum class Level(val label: String, val key: String) {
        L1_DEGRADED("L1 degraded", "l1"),
        L2_OFFLINE("L2 offline", "l2")
    }

    /** Unknown host / no route means offline; a timeout or other I/O failure is degraded. */
    fun forNetworkError(error: Throwable): Level? {
        var cause = error
        val seen = HashSet<Throwable>()
        while (cause.cause != null && seen.add(cause)) cause = cause.cause!!
        return when (cause) {
            is UnknownHostException, is NoRouteToHostException -> Level.L2_OFFLINE
            is SocketTimeoutException, is InterruptedIOException, is IOException -> Level.L1_DEGRADED
            else -> null
        }
    }

    /** HTTP errors eligible for local fallback; auth, request, model, and safety errors are not. */
    fun forHttpStatus(status: Int): Level? =
        if (status == 408 || status == 429 || status in 500..599) Level.L1_DEGRADED else null

    fun transitionLine(level: Level): String = when (level) {
        Level.L1_DEGRADED -> "Gemini slow ya busy hai; is turn ke liye Qwen2.5 fallback try kar raha hoon."
        Level.L2_OFFLINE -> "Internet nahi mila; is turn ke liye phone par Qwen2.5 fallback try kar raha hoon."
    }

    fun successLine(level: Level): String = when (level) {
        Level.L1_DEGRADED ->
            "Gemini slow ya busy tha. Ye jawab phone par Qwen2.5 se bana; agli turn mein Gemini phir try hoga."
        Level.L2_OFFLINE ->
            "Internet nahi mila. Ye jawab phone par Qwen2.5 se bana; connection aane par Gemini agli turn mein phir try hoga."
    }
}
