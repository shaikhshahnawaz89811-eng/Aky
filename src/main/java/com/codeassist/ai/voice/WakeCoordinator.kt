package com.codeassist.ai.voice

import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Small hub between the hands-free service (wake engine) and the screens. Everything runs in one process.
 *
 *  - Mic sharing: the wake engine and the voice loop must never hold the microphone at the same time.
 *    Anything that talks (VoiceController, the wake test) calls [setBusy]; the engine stops listening while
 *    somebody is busy and restarts shortly after the last one is done.
 *  - Delivery: when the wake phrase is heard, [deliver] hands the words that followed it to the open
 *    screen. If the app is not visible it returns false and the service shows a notification instead
 *    (Android does not let a background service open an activity by itself).
 * All calls come from the main thread.
 */
object WakeCoordinator {
    interface Listener {
        fun onWake(text: String)
    }

    /** Implemented by the wake engine. */
    interface Gate {
        fun onBusyChanged(busy: Boolean)
    }

    private const val PENDING_TTL_MS = 20_000L

    /** One line for the settings screen: what the engine is doing right now. */
    @Volatile
    var status: String = "Band"

    @Volatile
    var appVisible: Boolean = false

    var gate: Gate? = null

    /** The home screen, while it is on screen. */
    var listener: Listener? = null

    /** MainActivity: bring the home screen back when the wake word is heard on another screen. */
    var activityHook: ((String) -> Unit)? = null

    private val busyOwners: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    private var pending: String? = null
    private var pendingAt = 0L

    fun isBusy(): Boolean = busyOwners.isNotEmpty()

    fun setBusy(owner: Any, busy: Boolean) {
        val before = busyOwners.isNotEmpty()
        if (busy) busyOwners.add(owner) else busyOwners.remove(owner)
        val after = busyOwners.isNotEmpty()
        if (before != after) gate?.onBusyChanged(after)
    }

    /** True when a visible screen took the wake event. */
    fun deliver(text: String): Boolean {
        if (!appVisible) return false
        val l = listener
        if (l != null) {
            l.onWake(text)
            return true
        }
        val hook = activityHook ?: return false
        setPending(text)
        hook(text)
        return true
    }

    fun setPending(text: String) {
        pending = text
        pendingAt = SystemClock.elapsedRealtime()
    }

    fun takePending(): String? {
        val p = pending ?: return null
        pending = null
        return if (SystemClock.elapsedRealtime() - pendingAt <= PENDING_TTL_MS) p else null
    }

    /** A short buzz so a hands-free user feels that the phone heard the wake phrase. */
    fun buzz(context: Context) {
        try {
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!v.hasVibrator()) return
            v.vibrate(VibrationEffect.createOneShot(45L, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Throwable) {
            // vibration is a nicety only
        }
    }
}
