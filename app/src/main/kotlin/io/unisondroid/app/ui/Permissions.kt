package io.unisondroid.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri

/**
 * Returns the system dialog that asks the user to whitelist this app from battery
 * optimizations, or `null` when the app is already exempt.
 *
 * Auto-sync runs in the background, so it needs the exemption to survive Doze.
 */
@SuppressLint("BatteryLife")
fun batteryExemptionIntent(context: Context): Intent? {
    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    if (power.isIgnoringBatteryOptimizations(context.packageName)) return null
    return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = "package:${context.packageName}".toUri()
    }
}
