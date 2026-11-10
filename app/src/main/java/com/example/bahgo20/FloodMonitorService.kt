package com.example.bahgo20

import com.example.bahgo20.utils.AppLanguage
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.example.bahgo20.alerts.AlertRules
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.util.Locale

/**
 * Always-on flood monitor. Keeps listening to the 4 street sensors (/Sensordata1..4) while the
 * app is closed, and shows a notification when a street rises into Warning (Medium) or Critical.
 *
 * Android only lets an app run like this with a visible, permanent notification, so this one
 * says "BahGo is monitoring floods" with the current status of the streets.
 * It's started by Home (when flood alerts are on), stopped by the Settings switch and on logout,
 * and started again after the phone restarts (BootReceiver). It only reads from Firebase.
 * When a street goes Critical it also texts the logged-in user (alerts/SmsAlert).
 */
class FloodMonitorService : Service() {

    private val db = FirebaseDatabase
        .getInstance("https://bahgo-ee71b-default-rtdb.asia-southeast1.firebasedatabase.app/")
        .reference
    private val tracker = SensorTracker()
    private val listeners = mutableListOf<Pair<String, ValueEventListener>>()
    private val handler = Handler(Looper.getMainLooper())
    private var lastStatusText = ""

    // Re-checks every minute, so a sensor that stops sending shows as offline in the status line
    private val tick = object : Runnable {
        override fun run() {
            evaluate()
            handler.postDelayed(this, 60_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        FloodAlerts.createChannel(this)
        goForeground(res().getString(R.string.monitor_starting))
        listen()
        handler.postDelayed(tick, 60_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!FloodAlerts.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY   // Android restarts it if it has to kill it
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        listeners.forEach { (path, l) -> db.child(path).removeEventListener(l) }
        listeners.clear()
        tracker.saveHistory()
        super.onDestroy()
    }

    private fun listen() {
        for (id in 1..4) {
            val path = "Sensordata$id"
            val l = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    tracker.setSensor(id, snapshot)
                    evaluate()
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "$path cancelled: ${error.message}")
                    tracker.clearSensor(id)
                    evaluate()
                }
            }
            db.child(path).addValueEventListener(l)
            listeners += path to l
        }
        val updated = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                tracker.setRootUpdated(snapshot)
                evaluate()
            }

            override fun onCancelled(error: DatabaseError) {
                tracker.setRootUpdated(null)
            }
        }
        db.child("Last_Updated").addValueEventListener(updated)
        listeners += "Last_Updated" to updated
    }

    /** Checks every street, notifies on a rise, and updates the permanent status line. */
    private fun evaluate() {
        val prefs = getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val status = mutableListOf<String>()
        for (id in 1..4) {
            val r = tracker.reading(id)
            val state = AlertRules.State(
                Risk.entries.firstOrNull { it.name == prefs.getString("risk$id", null) } ?: Risk.SAFE,
                prefs.getLong("sent$id", 0L)
            )
            val d = AlertRules.decide(r.level, r.risk, state, now)
            d.newState?.let {
                prefs.edit().putString("risk$id", it.risk.name).putLong("sent$id", it.sentAtMs).apply()
            }
            if (d.notify) {
                Log.i(TAG, "Alert: sensor $id ${d.risk} at ${r.level} cm")
                FloodAlerts.showAlert(this, id, d.risk, r.level)
                // Critical also goes out by SMS to the logged-in user's registered number
                if (d.risk == Risk.CRITICAL) com.example.bahgo20.alerts.SmsAlert.sendCritical(this, id, r.level)
            }
            if (r.risk == Risk.MEDIUM || r.risk == Risk.CRITICAL) {
                val word = res().getString(if (r.risk == Risk.CRITICAL) R.string.risk_critical else R.string.risk_warning)
                status += "${FloodAlerts.STREETS[id]}: $word"
            }
        }
        val online = (1..4).count { tracker.reading(it).risk != Risk.OFFLINE }
        val text = when {
            status.isNotEmpty() -> status.joinToString(" · ")
            online == 0 -> res().getString(R.string.monitor_none_online)
            else -> res().getString(R.string.monitor_all_safe, online)
        }
        if (text != lastStatusText) {
            lastStatusText = text
            updateStatus(text)
        }
    }

    private fun buildStatus(text: String): Notification =
        NotificationCompat.Builder(this, FloodAlerts.MONITOR_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_raindrop)
            .setContentTitle(res().getString(R.string.monitor_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this, 0, Intent(this, HomeActivity::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    /** Resources in the app language (the service has no screen). */
    private fun res() = AppLanguage.localized(this)

    private fun goForeground(text: String) {
        ServiceCompat.startForeground(
            this, STATUS_ID, buildStatus(text),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun updateStatus(text: String) {
        try {
            NotificationManagerCompat.from(this).notify(STATUS_ID, buildStatus(text))
        } catch (e: SecurityException) {
            Log.w(TAG, "No notification permission", e)
        }
    }

    private companion object {
        const val TAG = "FloodMonitor"
        const val STATUS_ID = 9001                      // street alerts use ids 1..4
        const val STATE_PREFS = "bahgo_alert_state"     // last alerted risk per street
    }
}
