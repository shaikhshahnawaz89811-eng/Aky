package com.codeassist.ai.ai

import android.content.Context
import com.codeassist.ai.data.Message

/**
 * Phase 3B part 1: runs a [Correction] on the phone. Called by [ChatRunner] first in the quick-reply chain, so
 * "nahi, 9 baje" never reaches Tier-0 as a brand-new alarm or the brain as a chat question.
 *
 * What is remembered: nothing new. The "entity" being corrected is the last reply itself (its title, its time, its one
 * undo id), and the alarm time is read back from the activity-log undo token. So there is no extra store that could
 * disagree with the log.
 *
 * Order of work (each choice protects the user from losing a working alarm):
 *  - Alarm: the NEW alarm is set first. Only if that worked is the old one dismissed (by its own label, which is unique per
 *    time). If the new time is the same as the old one, nothing is done.
 *  - Timer: the Clock app has no "which timer" for dismiss, so the old timer is dismissed first and the new one set right
 *    after. If the new one cannot be set, the reply says so plainly.
 *  - Cancel: same as tapping Undo on the reply.
 * Whether the Clock app really honours the dismiss request depends on the phone's Clock app; the reply always says to check.
 */
object CorrectionRunner {
    /** Returns the reply for [text] when it corrects the last action in [lastAi], else null (the message goes on as usual). */
    fun tryFix(app: Context, text: String, lastAi: Message?, now: Long = System.currentTimeMillis()): Tier0.Result? {
        if (lastAi == null) return null
        val undoId = lastAi.undoId
        val kind = Correction.kindOf(lastAi.engine, lastAi.note, lastAi.actions, undoId, lastAi.time, now) ?: return null
        val oldId = undoId ?: return null
        val old = ActivityLog.get(oldId) ?: return null
        if (old.undone) return null // the user already took it back with the Undo chip: nothing left to correct
        val fix = Correction.parse(text, kind) ?: return null
        ConvKpi.inc("correction_runs")

        return when (fix) {
            is Correction.Fix.Cancel -> {
                val msg = ActivityLog.undo(app, oldId)
                Tier0.Result(kind, msg, "T1", null, ok = true)
            }
            is Correction.Fix.Alarm -> fixAlarm(app, oldId, old, fix)
            is Correction.Fix.Timer -> fixTimer(app, oldId, fix)
        }
    }

    private fun fixAlarm(app: Context, oldId: String, old: ActivityLog.Entry, fix: Correction.Fix.Alarm): Tier0.Result {
        val oldTime = Correction.alarmTimeOfToken(old.undo)
        if (oldTime != null && oldTime.first == fix.hour && oldTime.second == fix.minute) {
            return Tier0.Result("Alarm", "Alarm pehle se hi " + hhmm(fix.hour, fix.minute) + " ka hai, kuch nahi badla.", ok = false)
        }
        val fresh = Tier0.runAlarm(app, fix.hour, fix.minute, fix.tomorrow)
        if (!fresh.ok) {
            ConvKpi.inc("correction_failed")
            // nothing was changed: the old alarm is still there
            return Tier0.Result("Alarm", fresh.reply + " Purana alarm waisa hi hai.", ok = false)
        }
        val removed = ActivityLog.undo(app, oldId)
        val sent = ActivityLog.get(oldId)?.undone == true
        ConvKpi.inc(if (sent) "correction_ok" else "correction_old_stays")
        val tail = if (sent) {
            " Pehla alarm hatane ki request bhej di, Clock app mein ek baar check kar lena."
        } else {
            " Pehla alarm Clock app se khud hata dena (" + removed.trim() + ")"
        }
        return Tier0.Result("Alarm", "Theek hai. " + fresh.reply + tail, fresh.tier, fresh.undo, ok = true)
    }

    private fun fixTimer(app: Context, oldId: String, fix: Correction.Fix.Timer): Tier0.Result {
        val removed = ActivityLog.undo(app, oldId)
        val sent = ActivityLog.get(oldId)?.undone == true
        val fresh = Tier0.runTimer(app, fix.seconds)
        if (!fresh.ok) {
            ConvKpi.inc("correction_failed")
            return Tier0.Result("Timer", "Purana timer rokne ki koshish ki, par naya set nahi hua. " + fresh.reply, ok = false)
        }
        ConvKpi.inc(if (sent) "correction_ok" else "correction_old_stays")
        val tail = if (sent) {
            " Purana timer rokne ki request bhej di, Clock app mein check kar lena."
        } else {
            " Purana timer Clock app se khud rok dena (" + removed.trim() + ")"
        }
        return Tier0.Result("Timer", "Theek hai. " + fresh.reply + tail, fresh.tier, fresh.undo, ok = true)
    }

    private fun hhmm(h: Int, m: Int): String = String.format(java.util.Locale.US, "%02d:%02d", h, m)
}
