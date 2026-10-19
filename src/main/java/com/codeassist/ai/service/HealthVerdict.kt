package com.codeassist.ai.service

/**
 * Pure decision logic of the hands-free health check (audit PDF Gap B3: "in-app health check + watchdog").
 * No Android classes, so it is covered by JVM unit tests.
 *
 * The service sets a flag when it starts and clears it only when it is stopped cleanly (user, Stop button,
 * switch off, engine unavailable). If the flag is still set but the service is not alive, Android ended it
 * behind our back: a battery saver, "clear all", or a force stop. A reboot looks the same, so the boot
 * counter is compared to tell the two apart (a reboot is not the phone maker's fault and is not counted).
 */
object HealthVerdict {
    enum class Kind {
        /** The user never switched hands-free on. */
        OFF,

        /** Switched on and the service is alive. */
        OK,

        /** Switched on, but the service was never started or was stopped cleanly (nothing killed it). */
        NOT_RUNNING,

        /** The phone was restarted since the service started. */
        REBOOT,

        /** Android ended the service (battery saver, task clear, force stop). */
        KILLED
    }

    class Result(val kind: Kind, val downMs: Long)

    /** Kills inside this window count for the "fix your battery settings" prompt. */
    const val PROMPT_WINDOW_MS = 7L * 24 * 3600 * 1000
    const val PROMPT_MIN_KILLS = 2
    const val PROMPT_COOLDOWN_MS = 3L * 24 * 3600 * 1000
    private const val KEEP = 10

    fun evaluate(
        wakeOn: Boolean,
        flagRunning: Boolean,
        serviceAlive: Boolean,
        bootThen: Int,
        bootNow: Int,
        heartbeatAt: Long,
        now: Long
    ): Result {
        if (!wakeOn) return Result(Kind.OFF, 0L)
        if (serviceAlive) return Result(Kind.OK, 0L)
        if (!flagRunning) return Result(Kind.NOT_RUNNING, 0L)
        if (bootThen >= 0 && bootNow >= 0 && bootThen != bootNow) return Result(Kind.REBOOT, 0L)
        val down = if (heartbeatAt > 0L) (now - heartbeatAt).coerceAtLeast(0L) else 0L
        return Result(Kind.KILLED, down)
    }

    fun parseTimes(csv: String): List<Long> =
        csv.split(',').mapNotNull { it.trim().toLongOrNull() }

    /** Adds [at] and keeps the newest [KEEP] entries. */
    fun appendKill(csv: String, at: Long): String =
        (parseTimes(csv) + at).takeLast(KEEP).joinToString(",")

    fun recentKills(csv: String, now: Long, windowMs: Long = PROMPT_WINDOW_MS): Int =
        parseTimes(csv).count { now - it in 0..windowMs }

    /** Ask the user to fix battery settings: repeatedly killed, and not asked in the last few days. */
    fun shouldPrompt(csv: String, now: Long, lastPromptAt: Long): Boolean {
        if (recentKills(csv, now) < PROMPT_MIN_KILLS) return false
        return lastPromptAt <= 0L || now - lastPromptAt >= PROMPT_COOLDOWN_MS
    }

    /** "3 ghante pehle", "12 minute pehle", "abhi" - for the settings line. */
    fun ago(ms: Long): String {
        val m = ms / 60_000L
        return when {
            m < 1L -> "abhi"
            m < 60L -> "$m minute pehle"
            m < 48L * 60 -> "${m / 60} ghante pehle"
            else -> "${m / (24 * 60)} din pehle"
        }
    }
}
