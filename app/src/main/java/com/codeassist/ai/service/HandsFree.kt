package com.codeassist.ai.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.codeassist.ai.ai.ConvKpi
import com.codeassist.ai.data.Store
import com.codeassist.ai.voice.WakeSupport

/** Start / stop / re-arm of the hands-free service. Call only while the app is visible (Android 11+ rule). */
object HandsFree {

    fun hasMic(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** @return false when Android refused to start the foreground service. */
    fun start(ctx: Context): Boolean {
        return try {
            val i = Intent(ctx, AssistantService::class.java).setAction(AssistantService.ACTION_START)
            ContextCompat.startForegroundService(ctx, i)
            true
        } catch (e: Exception) {
            ConvKpi.init(ctx)
            ConvKpi.inc("wake_fgs_denied")
            false
        }
    }

    fun stop(ctx: Context) {
        ctx.stopService(Intent(ctx, AssistantService::class.java))
        Store.wakeRunning = false
        WatchdogReceiver.cancel(ctx)
    }

    /**
     * The user switched hands-free on earlier but the service is not alive (phone restarted, or a battery
     * saver killed it): start it again now that the user has opened the app.
     */
    fun rearmIfNeeded(ctx: Context) {
        if (!Store.wakeWord || AssistantService.running) return
        if (!hasMic(ctx) || !WakeSupport.check(ctx).ok) return
        start(ctx)
    }
}
