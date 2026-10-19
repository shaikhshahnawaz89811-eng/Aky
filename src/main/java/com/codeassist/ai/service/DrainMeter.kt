package com.codeassist.ai.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.codeassist.ai.ai.ConvKpi

/**
 * Battery drain meter for the hands-free service (audit PDF Sec 12.1 "Idle battery drain": measure first).
 * The service calls [start] / [sample] / [stop]; the numbers go into [ConvKpi] as `drain_ms` / `drain_uah`.
 *
 * Only clean intervals count (see [DrainMath.step]): screen off, no charger, nothing plugged in or switched
 * on in between. The meter measures the whole phone's battery during those intervals, not only this app,
 * so it is an upper bound for the app's own share. Phones whose battery does not report a charge counter
 * produce no numbers and the KPI screen says so.
 * Everything runs on the main thread.
 */
object DrainMeter {
    private var prev: DrainMath.Sample? = null
    private var dirty = false
    private var receiver: BroadcastReceiver? = null

    /** False after the first read that returned nothing: this phone has no usable charge counter. */
    @Volatile
    var supported: Boolean = true
        private set

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        stop(app)
        dirty = false
        prev = read(app)
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // screen on or charger plugged in: the interval that is running is no longer clean
                dirty = true
            }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_POWER_CONNECTED)
        }
        try {
            ContextCompat.registerReceiver(app, r, f, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiver = r
        } catch (_: Throwable) {
            // without the receiver the meter still works, it just cannot see short screen-on moments
            receiver = null
        }
    }

    fun sample(ctx: Context) {
        val now = read(ctx.applicationContext) ?: return
        val d = DrainMath.step(prev, now, dirty)
        dirty = false
        prev = now
        if (d != null) {
            ConvKpi.inc("drain_ms", d.ms)
            ConvKpi.inc("drain_uah", d.uah)
        }
    }

    fun stop(ctx: Context) {
        val r = receiver
        if (r != null) {
            try {
                ctx.applicationContext.unregisterReceiver(r)
            } catch (_: Throwable) {
                // already gone
            }
        }
        receiver = null
        prev = null
    }

    private fun read(app: Context): DrainMath.Sample? {
        return try {
            val bm = app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
            val uah = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            if (uah <= 0L) {
                supported = false
                return null
            }
            val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val screenOff = pm != null && !pm.isInteractive
            DrainMath.Sample(uah, SystemClock.elapsedRealtime(), screenOff, bm.isCharging)
        } catch (_: Throwable) {
            null
        }
    }
}
