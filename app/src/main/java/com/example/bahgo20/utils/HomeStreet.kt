package com.example.bahgo20.utils

import android.content.Context

/**
 * Which street sensor (1..4) is "your current location" on Home, picked with Change.
 * Kept on the phone; Sealion Street (1) until the user picks another.
 */
object HomeStreet {
    private const val PREFS = "bahgo_monitor_prefs"     // same file as Person/Car mode
    private const val KEY = "home_sensor"

    fun get(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 1).coerceIn(1, 4)

    fun set(context: Context, sensorId: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, sensorId.coerceIn(1, 4)).apply()
    }
}
