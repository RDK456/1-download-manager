package com.downloadhub.app.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery-optimisation exemption.
 *
 * Even a correct foreground service plus a wake lock can be throttled or killed by
 * aggressive OEM skins (MIUI, EMUI, One UI, battery saver). Asking for the
 * "ignore battery optimisations" exemption is the standard, user-visible way for a
 * download manager to keep transferring in the background.
 */
class BatteryOptimisation(context: Context) {
    private val appContext = context.applicationContext
    private val power = appContext.getSystemService(PowerManager::class.java)

    /** True when the app is exempt, or the device has no such restriction. */
    fun isExempt(): Boolean = runCatching {
        power?.isIgnoringBatteryOptimizations(appContext.packageName) == true
    }.getOrDefault(false)

    /**
     * Intent that asks the user to exempt the app, or null when nothing can handle
     * it so the caller can fall back to [settingsIntent].
     */
    fun requestIntent(): Intent? {
        if (isExempt()) return null
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${appContext.packageName}")
        )
        if (direct.resolveActivity(appContext.packageManager) != null) return direct
        return settingsIntent()
    }

    /** Always-available fallback: the list of battery-optimised apps. */
    fun settingsIntent(): Intent = Intent(
        Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
