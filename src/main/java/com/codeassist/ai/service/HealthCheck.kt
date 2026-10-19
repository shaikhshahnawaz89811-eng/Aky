package com.codeassist.ai.service

import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import com.codeassist.ai.ai.ConvKpi
import com.codeassist.ai.data.Store

/**
 * Android-side glue of the health check (audit PDF Gap B3). Call [onAppStart] when the app becomes visible,
 * before [HandsFree.rearmIfNeeded]. The pure decision is in [HealthVerdict].
 */
object HealthCheck {

    /** Android's reboot counter (API 24+, readable without a permission); -1 when the phone hides it. */
    fun bootCount(ctx: Context): Int = try {
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (_: Throwable) {
        -1
    }

    fun current(ctx: Context): HealthVerdict.Result = HealthVerdict.evaluate(
        wakeOn = Store.wakeWord,
        flagRunning = Store.wakeRunning,
        serviceAlive = AssistantService.running,
        bootThen = Store.wakeBootCount,
        bootNow = bootCount(ctx),
        heartbeatAt = Store.wakeHeartbeat,
        now = System.currentTimeMillis()
    )

    /**
     * Decide what happened while the app was away and record it. A kill is consumed here (the "running" flag
     * is cleared), so a second call a moment later, for example after a screen rotation, does not count it twice.
     * @return true when the user should now be asked to fix battery settings.
     */
    fun onAppStart(ctx: Context): Boolean {
        ConvKpi.init(ctx)
        val now = System.currentTimeMillis()
        val r = current(ctx)
        when (r.kind) {
            HealthVerdict.Kind.KILLED -> {
                ConvKpi.inc("wake_killed")
                ConvKpi.inc("wake_down_ms", r.downMs)
                Store.wakeKillTimes = HealthVerdict.appendKill(Store.wakeKillTimes, now)
                Store.wakeRunning = false
            }
            HealthVerdict.Kind.REBOOT -> {
                ConvKpi.inc("wake_reboot")
                Store.wakeRunning = false
            }
            else -> {}
        }
        if (r.kind == HealthVerdict.Kind.KILLED || r.kind == HealthVerdict.Kind.REBOOT) {
            // the user is back: the "hands-free ruk gaya" notice has done its job
            Store.wakeKillNotified = false
            try {
                ctx.getSystemService(NotificationManager::class.java)?.cancel(WatchdogReceiver.ID_NOTICE)
            } catch (_: Throwable) {
                // nicety only
            }
        }
        if (r.kind == HealthVerdict.Kind.KILLED &&
            HealthVerdict.shouldPrompt(Store.wakeKillTimes, now, Store.batteryPromptAt)
        ) {
            Store.batteryPromptAt = now
            return true
        }
        return false
    }

    /** One line for the settings screen. */
    fun summary(): String {
        val now = System.currentTimeMillis()
        val kills = ConvKpi.get("wake_killed")
        val times = HealthVerdict.parseTimes(Store.wakeKillTimes)
        if (kills == 0L) return "Abhi tak koi baar band nahi hua"
        val last = if (times.isEmpty()) "" else " · aakhri baar " + HealthVerdict.ago(now - times.last())
        return "Android ne $kills baar band kiya$last"
    }
}
