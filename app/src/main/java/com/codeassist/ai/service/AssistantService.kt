package com.codeassist.ai.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.codeassist.ai.MainActivity
import com.codeassist.ai.R
import com.codeassist.ai.ai.ConvKpi
import com.codeassist.ai.data.Store
import com.codeassist.ai.voice.VadGatedWakeWord
import com.codeassist.ai.voice.WakeCoordinator
import com.codeassist.ai.voice.WakeSupport
import com.codeassist.ai.voice.WakeWordEngine

/**
 * Hands-free listening (audit PDF Sec 9.1 "Service" / Gap B2): a foreground service of type microphone with a
 * visible notification that has a Stop button. It only runs the wake-word engine; the conversation itself
 * still happens on the home screen ([com.codeassist.ai.voice.VoiceController]).
 *
 * Rules from the audit that this class follows:
 *  - opt-in only: started from the settings switch, never from boot;
 *  - started while the app is visible, because Android 11+ refuses a microphone foreground service that
 *    is started from the background;
 *  - when the phone was killed by a battery saver, the app re-arms it the next time the user opens it
 *    ([HandsFree.rearmIfNeeded]); the service never restarts itself in the background (START_NOT_STICKY).
 */
class AssistantService : Service(), WakeWordEngine.Listener {

    companion object {
        const val ACTION_START = "com.codeassist.ai.action.HANDSFREE_START"
        const val ACTION_STOP = "com.codeassist.ai.action.HANDSFREE_STOP"
        private const val CH_LISTEN = "handsfree_listen"
        private const val CH_WAKE = "handsfree_wake"
        private const val ID_LISTEN = 4201
        private const val ID_WAKE = 4202
        private const val HEARTBEAT_MS = 30_000L

        @Volatile
        var running: Boolean = false
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private var engine: WakeWordEngine? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        ConvKpi.init(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // the user pressed Stop in the notification: the opt-in is off until they switch it on again
            Store.wakeWord = false
            Store.wakeRunning = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (!enterForeground()) {
            Store.wakeRunning = false
            stopSelf()
            return START_NOT_STICKY
        }
        val check = WakeSupport.check(this)
        if (!check.ok || !HandsFree.hasMic(this)) {
            Store.wakeRunning = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (engine == null) {
            val e = VadGatedWakeWord(this, this)
            engine = e
            running = true
            Store.wakeRunning = true
            // part 2B: remember which boot this run belongs to (reboot vs kill), start the drain meter
            // and the watchdog alarm that warns the user if Android ends this service later
            Store.wakeBootCount = HealthCheck.bootCount(this)
            Store.wakeKillNotified = false
            DrainMeter.start(this)
            WatchdogReceiver.schedule(this)
            beat()
            e.start()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("InlinedApi")
    private fun enterForeground(): Boolean {
        return try {
            ServiceCompat.startForeground(
                this, ID_LISTEN, buildListenNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
            true
        } catch (e: Exception) {
            // Android refused (for example: started from the background, or the mic permission is gone)
            ConvKpi.inc("wake_fgs_denied")
            false
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        engine?.stop()
        engine = null
        running = false
        // a clean stop: take the last battery sample, stop the meter and the watchdog alarm
        DrainMeter.sample(this)
        DrainMeter.stop(this)
        WatchdogReceiver.cancel(this)
        // a clean stop clears the flag; if the process is killed this line never runs, which is how the
        // health check (part 2B, HealthCheck / WatchdogReceiver) notices that a battery saver killed the service
        Store.wakeRunning = false
        super.onDestroy()
    }

    // ---------- wake events ----------

    override fun onWake(heard: String, remainder: String) {
        ConvKpi.inc("wake_detect")
        if (WakeCoordinator.deliver(remainder)) return
        postWakeNotification(remainder)
    }

    override fun onUnavailable(reason: String) {
        ConvKpi.inc("wake_unavailable")
        engine?.stop()
        engine = null
        Store.wakeWord = false
        val nm = getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(this, CH_WAKE)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Hands-free band ho gaya")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        nm?.notify(ID_WAKE, n)
        stopSelf()
    }

    // ---------- heartbeat ----------

    private val heartbeat: Runnable = object : Runnable {
        override fun run() {
            beat()
            main.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private fun beat() {
        Store.wakeHeartbeat = System.currentTimeMillis()
        DrainMeter.sample(this)
        main.removeCallbacks(heartbeat)
        main.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    // ---------- notifications ----------

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val listen = NotificationChannel(CH_LISTEN, "Hands-free listening", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Jab tak wake word ka mic chalu hai, ye notification dikhti hai (Stop button ke saath)."
            setShowBadge(false)
        }
        val wake = NotificationChannel(CH_WAKE, "Wake word suna", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Wake phrase suna gaya par app band thi: tap karke baat shuru karo."
            enableVibration(true)
        }
        nm.createNotificationChannel(listen)
        nm.createNotificationChannel(wake)
    }

    private fun buildListenNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, AssistantService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CH_LISTEN)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Hands-free on")
            .setContentText("\"" + Store.wakePhrase + "\" bolo · awaaz sirf phone par suni jaati hai")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** Android does not let a background service open an activity, so the user taps this instead. */
    private fun postWakeNotification(remainder: String) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_WAKE, true)
            .putExtra(MainActivity.EXTRA_WAKE_TEXT, remainder)
        val pi = PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CH_WAKE)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Haan? Maine suna")
            .setContentText(if (remainder.isBlank()) "Tap karo aur bolo" else "\"" + remainder + "\" · tap karke bhejo")
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .setTimeoutAfter(20_000L)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(ID_WAKE, n)
    }
}
