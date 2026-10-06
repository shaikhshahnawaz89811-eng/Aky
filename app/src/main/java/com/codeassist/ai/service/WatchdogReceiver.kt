package com.codeassist.ai.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.codeassist.ai.MainActivity
import com.codeassist.ai.R
import com.codeassist.ai.ai.ConvKpi
import com.codeassist.ai.data.Store

/**
 * Watchdog for the hands-free service (audit PDF Gap B3: "watchdog; time-based work on AlarmManager").
 *
 * A battery saver can kill the service (and the whole process) without telling anybody. This receiver is woken by
 * an inexact alarm about every 15 minutes while hands-free is on. If the service is gone it posts one notification
 * ("Hands-free ruk gaya, tap karke dobara chalu karo"); the tap opens the app, and opening the app is the moment
 * Android allows the microphone service to start again (a background start would be refused: audit B2).
 * It cannot restart the service itself, on purpose.
 *
 * The alarm chain stops after the notice (no more wake-ups), when the user switches hands-free off, and after
 * a reboot (Android clears alarms). A force stop also clears it. Inexact alarms need no special permission.
 */
class WatchdogReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.codeassist.ai.action.HANDSFREE_WATCHDOG"
        const val ID_NOTICE = 4203
        private const val CH_HEALTH = "handsfree_health"
        private const val INTERVAL_MS = 15L * 60 * 1000

        private fun pending(ctx: Context): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, 3,
                Intent(ctx, WatchdogReceiver::class.java).setAction(ACTION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        fun schedule(ctx: Context) {
            try {
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                am.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + INTERVAL_MS,
                    pending(ctx)
                )
            } catch (_: Throwable) {
                // no alarm: the app-open health check still works
            }
        }

        fun cancel(ctx: Context) {
            try {
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                am.cancel(pending(ctx))
            } catch (_: Throwable) {
                // nothing to cancel
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION) return
        val app = context.applicationContext
        Store.init(app)
        ConvKpi.init(app)
        if (!Store.wakeWord) return // switched off: the chain ends here
        val r = HealthCheck.current(app)
        when (r.kind) {
            HealthVerdict.Kind.OK -> schedule(app)
            HealthVerdict.Kind.KILLED -> {
                if (!Store.wakeKillNotified) {
                    Store.wakeKillNotified = true
                    ConvKpi.inc("wake_watchdog_notice")
                    postNotice(app)
                }
                // chain ends: the next check happens when the user opens the app
            }
            else -> {
                // reboot or never started: nothing to warn about, stop the chain
            }
        }
    }

    private fun postNotice(ctx: Context) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CH_HEALTH, "Hands-free band hua", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Jab Android hands-free service ko band kar de, ek baar yaad dilati hai."
                }
            )
            val open = PendingIntent.getActivity(
                ctx, 4,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val text = "Android ne mic service band kar di. Tap karo: app khulte hi hands-free dobara chalu ho jaata hai."
            val n = NotificationCompat.Builder(ctx, CH_HEALTH)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("Hands-free ruk gaya")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            nm.notify(ID_NOTICE, n)
        } catch (_: Throwable) {
            // notifications may be switched off: the in-app health line still shows it
        }
    }
}
