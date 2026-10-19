package com.codeassist.ai.settings

import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.codeassist.ai.data.Store
import com.codeassist.ai.service.BatteryAdvice
import com.codeassist.ai.service.BatterySetup
import com.codeassist.ai.service.HealthVerdict

/**
 * Guided battery setup (audit PDF Gap B3, part 2B). Two entry points:
 *  - [showGuide]: Settings > Hands-free > Battery setup;
 *  - [showKilledPrompt]: shown by MainActivity when Android has stopped the service twice within a week.
 * The app cannot flip these switches itself: it opens the right screens and says what to look for.
 */
object BatteryDialogs {

    fun showKilledPrompt(ctx: Context) {
        val kills = HealthVerdict.recentKills(Store.wakeKillTimes, System.currentTimeMillis())
        AlertDialog.Builder(ctx)
            .setTitle("Hands-free baar baar band ho raha hai")
            .setMessage(
                "Pichhle hafte mein Android ne mic service $kills baar band ki. App kholte hi wo dobara chalu ho gayi, " +
                    "par jab app band hoti hai tab wake word sunne ke liye phone ki battery settings badalni padti hain.\n\n" +
                    "Abhi 1 minute mein theek kar lein?"
            )
            .setPositiveButton("Battery setup") { _, _ -> showGuide(ctx) }
            .setNegativeButton("Baad mein", null)
            .show()
    }

    fun showGuide(ctx: Context) {
        val key = BatteryAdvice.oemKey(Build.MANUFACTURER)
        val steps = BatteryAdvice.steps(key).mapIndexed { i, s -> "${i + 2}. $s" }.joinToString("\n")
        val msg = BatterySetup.statusLine(ctx) + "\n\n" +
            "Phone: " + BatteryAdvice.label(key) + "\n\n" +
            "1. \"Battery list kholo\" dabao, list mein CodeAssist dhundho aur \"Don't optimise / Unrestricted\" karo.\n" +
            steps + "\n\n" +
            BatteryAdvice.CAVEAT
        val b = AlertDialog.Builder(ctx)
            .setTitle("Battery setup")
            .setMessage(msg)
            .setPositiveButton("Battery list kholo") { _, _ ->
                if (!BatterySetup.openOptimisationList(ctx)) toast(ctx, "Settings screen nahi khul payi")
            }
            .setNegativeButton("Band karo", null)
        if (key != BatteryAdvice.OTHER) {
            b.setNeutralButton("Phone ki screen") { _, _ ->
                if (!BatterySetup.openMakerScreen(ctx)) toast(ctx, "Settings screen nahi khul payi")
            }
        } else {
            b.setNeutralButton("App info") { _, _ ->
                if (!BatterySetup.openAppInfo(ctx)) toast(ctx, "Settings screen nahi khul payi")
            }
        }
        b.show()
        Store.batteryPromptAt = System.currentTimeMillis()
    }

    private fun toast(ctx: Context, text: String) {
        Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
    }
}
