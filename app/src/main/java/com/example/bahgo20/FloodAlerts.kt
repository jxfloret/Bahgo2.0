package com.example.bahgo20

import com.example.bahgo20.utils.AppLanguage
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import java.util.Locale

/**
 * Flood alert notifications.
 *   - The always-on monitor (FloodMonitorService) watches the 4 street sensors while the app is
 *     closed and calls [showAlert] when a street rises into Warning or Critical.
 *   - Firebase push (BahGoMessagingService) uses the same [showAlert], in case a server sends alerts.
 * One notification per street: a newer alert replaces the older one.
 */
object FloodAlerts {

    const val CHANNEL_ID = "flood_alerts"
    const val MONITOR_CHANNEL_ID = "flood_monitor"
    private const val TOPIC = "flood_alerts"
    private const val TAG = "FloodAlerts"
    private const val PREFS_NAME = "bahgo_monitor_prefs"
    private const val KEY_ENABLED = "flood_alerts_enabled"
    private const val REQUEST_CODE_NOTIFICATIONS = 1001

    /** The four monitored streets (same names everywhere in the app). */
    val STREETS = mapOf(
        1 to "Sealion Street",
        2 to "Samaritan Street",
        3 to "Swingfire Street",
        4 to "Striker Street"
    )

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    /** Turns alerts on/off (the switch in Settings): starts or stops the always-on monitor too. */
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) {
            subscribe()
            startMonitor(context)
        } else {
            unsubscribe()
            stopMonitor(context)
        }
    }

    /** Call from an Activity: creates the channels, asks permission (Android 13+), starts monitoring. */
    fun setup(activity: Activity) {
        createChannel(activity)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_CODE_NOTIFICATIONS
            )
        }

        if (isEnabled(activity)) {
            subscribe()
            startMonitor(activity)
        }
    }

    // ───────────────────────── Always-on monitor ─────────────────────────

    fun startMonitor(context: Context) {
        setMonitorActive(context, true)
        try {
            ContextCompat.startForegroundService(context, Intent(context, FloodMonitorService::class.java))
        } catch (e: Exception) {
            // Android blocks starting it from the background in some cases; it starts next time the app opens
            Log.e(TAG, "Couldn't start the flood monitor", e)
        }
    }

    fun stopMonitor(context: Context) {
        setMonitorActive(context, false)
        context.stopService(Intent(context, FloodMonitorService::class.java))
    }

    /** Remembered so BootReceiver knows whether to start the monitor after a restart. */
    private fun setMonitorActive(context: Context, active: Boolean) {
        context.getSharedPreferences(BootReceiver.MONITOR_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(BootReceiver.KEY_ACTIVE, active).apply()
    }

    // ───────────────────────── Firebase push topic ─────────────────────────

    fun subscribe() {
        FirebaseMessaging.getInstance().subscribeToTopic(TOPIC)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) Log.d(TAG, "Subscribed to $TOPIC")
                else Log.e(TAG, "Subscribe failed", task.exception)
            }
    }

    fun unsubscribe() {
        FirebaseMessaging.getInstance().unsubscribeFromTopic(TOPIC)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) Log.d(TAG, "Unsubscribed from $TOPIC")
                else Log.e(TAG, "Unsubscribe failed", task.exception)
            }
    }

    // ───────────────────────── Notifications ─────────────────────────

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val text = AppLanguage.localized(context)   // channel names follow the app language
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, text.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH).apply {
                description = text.getString(R.string.channel_alerts_desc)
                enableVibration(true)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(MONITOR_CHANNEL_ID, text.getString(R.string.channel_monitor), NotificationManager.IMPORTANCE_LOW).apply {
                description = text.getString(R.string.channel_monitor_desc)
                setShowBadge(false)
            }
        )
    }

    /** Opens Home, then that street's Conditions, when the notification is tapped. */
    fun openStreetIntent(context: Context, sensorId: Int): PendingIntent {
        val home = Intent(context, HomeActivity::class.java)
        val conditions = Intent(context, ConditionsActivity::class.java).apply {
            putExtra("SENSOR_ID", sensorId)
            putExtra("STREET_NAME", STREETS[sensorId] ?: "Sensor $sensorId")
        }
        return TaskStackBuilder.create(context)
            .addNextIntent(home)
            .addNextIntent(conditions)
            .getPendingIntent(sensorId, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)!!
    }

    /**
     * Shows "Warning flood level · Swingfire Street" (or Dangerous), with the level and the
     * Person / Car advice picked in Settings. [risk] is MEDIUM or CRITICAL.
     */
    @SuppressLint("MissingPermission")
    fun showAlert(context: Context, sensorId: Int, risk: Risk, levelCm: Double?) {
        if (risk != Risk.MEDIUM && risk != Risk.CRITICAL) return
        if (!isEnabled(context)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val critical = risk == Risk.CRITICAL
        val street = STREETS[sensorId] ?: "Sensor $sensorId"
        val hasVehicle = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("has_vehicle", false)
        val adviceRes = if (hasVehicle) {
            if (critical) R.string.critical_desc else R.string.risky_desc
        } else {
            if (critical) R.string.waist_desc else R.string.knee_desc
        }
        val text = AppLanguage.localized(context)   // the app language, even with no screen open
        val title = text.getString(
            if (critical) R.string.alert_title_dangerous else R.string.alert_title_warning, street
        )
        val levelText = levelCm?.let { "${formatNumber(it)} cm. " }.orEmpty()
        val body = levelText + text.getString(adviceRes)

        createChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_warning)
            .setColor(Color.parseColor(if (critical) "#D32F2F" else "#FF9100"))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openStreetIntent(context, sensorId))
            .build()
        NotificationManagerCompat.from(context).notify(sensorId, notification)
    }

    private fun formatNumber(n: Double): String =
        if (n % 1.0 == 0.0) n.toInt().toString() else String.format(Locale.US, "%.1f", n)
}
