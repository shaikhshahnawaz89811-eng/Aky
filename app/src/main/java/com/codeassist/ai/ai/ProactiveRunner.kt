package com.codeassist.ai.ai

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.provider.Settings
import com.codeassist.ai.data.Store
import java.util.TimeZone

/**
 * Phase 4B: the phone side of [Proactive]. [refresh] is called when the Home screen comes to the front; it looks at the real state
 * of the phone, asks [Proactive.pick] whether anything may be shown, records that it was shown, and keeps the suggestion in [current]
 * until the user acts on it, says "Baad mein" or "Ye mat bolo", or the situation is over (the charger was plugged in).
 *
 * Two kinds exist, both from data the app really has:
 *  - battery: 20 % or less and not charging (BatteryManager, no permission). Action: open the battery saver settings.
 *  - torch: the assistant switched the torch on (activity log) more than 10 minutes ago and the torch is physically still on
 *    (a [CameraManager.TorchCallback] reports the real state; a torch someone else switched on is never ours to mention).
 *    Action: the Undo of that log entry.
 * Calendar, location and notification triggers of the audit need permissions this app does not ask for: not built.
 */
object ProactiveRunner {
    @Volatile
    var current: Proactive.Candidate? = null
        private set

    private val torchOn = HashMap<String, Boolean>()
    private var watching = false

    /** Starts listening to the torch state once (needs a thread with a Looper: the main thread). */
    @Synchronized
    private fun startWatch(ctx: Context) {
        if (watching) return
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    synchronized(this@ProactiveRunner) { torchOn[cameraId] = enabled }
                }

                override fun onTorchModeUnavailable(cameraId: String) {
                    synchronized(this@ProactiveRunner) { torchOn.remove(cameraId) }
                }
            }, null)
            watching = true
        } catch (_: Throwable) {
            // no camera service or no Looper: the torch suggestion simply never appears
        }
    }

    @Synchronized
    private fun torchPhysicallyOn(): Boolean = torchOn.values.any { it }

    private fun battery(ctx: Context): Pair<Int, Boolean>? {
        val i = ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return Pair(level * 100 / scale, charging)
    }

    private fun candidates(ctx: Context, now: Long): List<Proactive.Candidate> {
        val out = ArrayList<Proactive.Candidate>()
        battery(ctx)?.let { (pct, charging) -> Proactive.batteryCandidate(pct, charging)?.let { out.add(it) } }
        val last = ActivityLog.all().firstOrNull { it.tool == "Torch" }
        if (last != null && last.summary.startsWith("Torch on") && !last.undone && last.undo != null && torchPhysicallyOn()) {
            Proactive.torchCandidate(now - last.time, last.id)?.let { out.add(it) }
        }
        return out
    }

    /** What the Home screen should show now (null = nothing). Counts the suggestion against today's budget the moment it is first shown. */
    fun refresh(ctx: Context, now: Long = System.currentTimeMillis()): Proactive.Candidate? {
        val app = ctx.applicationContext
        Store.init(app)
        ActivityLog.init(app)
        ConvKpi.init(app)
        startWatch(app)

        val cands = candidates(app, now)
        val cur = current
        if (cur != null) {
            if (cands.any { it.kind == cur.kind }) return cur
            current = null // the situation is over
        }

        val st = Proactive.unpack(Store.proactiveState)
        val offset = TimeZone.getDefault().getOffset(now)
        val choice = Proactive.pick(cands, st, Store.proactiveLevel, now, offset)
        val c = choice.pick
        if (c == null) {
            if (cands.isNotEmpty()) ConvKpi.inc("proactive_blocked_" + choice.why)
            return null
        }
        val next = Proactive.afterShown(st, c, now, offset)
        Store.proactiveState = Proactive.pack(next)
        // cannot happen: pick() never passes a full day. If it ever does, the checklist turns red.
        if (Proactive.shownToday(next, now, offset) > Proactive.MAX_PER_DAY) ConvKpi.inc("proactive_over_budget")
        ConvKpi.inc("proactive_shown")
        ConvKpi.inc("proactive_kind_" + c.kind)
        current = c
        return c
    }

    /** The action button. Returns the sentence for the Toast. */
    fun act(ctx: Context): String {
        val app = ctx.applicationContext
        val c = current ?: return ""
        current = null
        ConvKpi.inc("proactive_acted")
        return when (c.actionId) {
            "battery_saver" -> {
                val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    app.startActivity(intent)
                    "Battery saver ki settings khol di."
                } catch (_: ActivityNotFoundException) {
                    try {
                        app.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        "Settings khol di, wahan se battery saver chalu karo."
                    } catch (_: Throwable) {
                        "Settings nahi khul paayi."
                    }
                }
            }
            "torch_off" -> ActivityLog.undo(app, c.ref.orEmpty())
            else -> ""
        }
    }

    /** "Baad mein": hidden now; today's budget stays used. */
    fun dismiss(ctx: Context) {
        if (current == null) return
        current = null
        ConvKpi.init(ctx)
        ConvKpi.inc("proactive_dismissed")
    }

    /** "Ye mat bolo": this kind of suggestion is switched off until the user turns it on in Settings. */
    fun mute(ctx: Context) {
        val c = current ?: return
        current = null
        Store.init(ctx)
        ConvKpi.init(ctx)
        Store.proactiveState = Proactive.pack(Proactive.mute(Proactive.unpack(Store.proactiveState), c.kind))
        ConvKpi.inc("proactive_muted")
    }

    /** The kinds the user muted (for Settings). */
    fun mutedKinds(ctx: Context): List<String> {
        Store.init(ctx)
        return Proactive.unpack(Store.proactiveState).muted
    }

    fun setMuted(ctx: Context, kinds: List<String>) {
        Store.init(ctx)
        Store.proactiveState = Proactive.pack(Proactive.setMuted(Proactive.unpack(Store.proactiveState), kinds))
        current = current?.takeIf { it.kind !in kinds }
    }
}
