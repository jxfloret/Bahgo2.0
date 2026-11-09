package com.example.bahgo20

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the flood monitor again after the phone restarts (if flood alerts are on and someone is logged in). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!FloodAlerts.isEnabled(context)) return
        if (!context.getSharedPreferences(MONITOR_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ACTIVE, false)) return
        FloodAlerts.startMonitor(context)
    }

    companion object {
        /** Set while a user is logged in and the monitor was started from Home. */
        const val MONITOR_PREFS = "bahgo_monitor_prefs"
        const val KEY_ACTIVE = "flood_monitor_active"
    }
}
