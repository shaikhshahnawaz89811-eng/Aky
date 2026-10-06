package com.codeassist.ai.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Opens the right Android settings screens for the guided battery setup (audit PDF Gap B3, part 2B).
 *
 * The app deliberately does NOT use the one-tap "ignore battery optimisations" request
 * (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS): it needs a permission that Google Play restricts. It opens the
 * system list instead and the user flips the switch. Phone-maker screens (autostart and similar) are opened
 * by their package names, best effort: they differ per phone and version, so every call falls back to the
 * app info screen.
 */
object BatterySetup {

    /** True when Android does not restrict this app's background work through battery optimisation. */
    fun isUnrestricted(ctx: Context): Boolean = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm != null && pm.isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (_: Throwable) {
        false
    }

    fun statusLine(ctx: Context): String =
        if (isUnrestricted(ctx)) "Battery optimisation: band (achha)" else "Battery optimisation: chalu (service band ho sakti hai)"

    /** The system list of apps with battery optimisation. @return false when no screen could be opened. */
    fun openOptimisationList(ctx: Context): Boolean =
        tryStart(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) || openAppInfo(ctx)

    fun openAppInfo(ctx: Context): Boolean = tryStart(
        ctx,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.fromParts("package", ctx.packageName, null))
    )

    /** The phone maker's autostart / background screen, if this phone has one. Falls back to the app info screen. */
    fun openMakerScreen(ctx: Context): Boolean {
        for (c in makerComponents(BatteryAdvice.oemKey(Build.MANUFACTURER))) {
            if (tryStart(ctx, Intent().setComponent(c))) return true
        }
        return openAppInfo(ctx)
    }

    private fun makerComponents(key: String): List<ComponentName> = when (key) {
        BatteryAdvice.XIAOMI -> listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        )
        BatteryAdvice.OPPO -> listOf(
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
        )
        BatteryAdvice.VIVO -> listOf(
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
        )
        BatteryAdvice.SAMSUNG -> listOf(
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
        )
        BatteryAdvice.ONEPLUS -> listOf(
            ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
        )
        BatteryAdvice.HUAWEI -> listOf(
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        )
        else -> emptyList()
    }

    private fun tryStart(ctx: Context, intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        true
    } catch (_: Throwable) {
        // ActivityNotFoundException, SecurityException (screen not exported on this phone) ...
        false
    }
}
