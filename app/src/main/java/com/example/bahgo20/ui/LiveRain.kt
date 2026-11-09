package com.example.bahgo20.ui

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.bahgo20.Risk
import com.example.bahgo20.SensorTracker
import com.example.bahgo20.ml.RainPredictor
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * How hard it's raining right now, from the live street sensors, for the rain effect on screen.
 *   0 = no rain, 1 = light, 2 = moderate, 3 = heavy   (same cut-offs as the Rain box)
 * Offline sensors count as no rain. Listens to /Sensordata1..4 only while a rain view is on
 * screen (read-only), and stops a few seconds after the last one goes away.
 */
object LiveRain {
    private const val TAG = "LiveRain"
    private const val DB_URL = "https://bahgo-ee71b-default-rtdb.asia-southeast1.firebasedatabase.app/"
    private const val STOP_AFTER_MS = 5_000L
    private const val RECHECK_MS = 30_000L      // a sensor that stops sending turns offline -> rain stops

    fun interface Listener {
        fun onRainChanged()
    }

    private val tracker = SensorTracker()
    private val levels = IntArray(5)              // index 1..4
    private val listeners = mutableSetOf<Listener>()
    private val handler = Handler(Looper.getMainLooper())
    private val dbListeners = mutableListOf<Pair<String, ValueEventListener>>()
    private val stopRunnable = Runnable { stopListening() }
    private val recheck = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, RECHECK_MS)
        }
    }

    /** Rain level for one sensor (Conditions follows its own street). */
    fun level(sensorId: Int): Int = levels.getOrElse(sensorId) { 0 }

    /** Heaviest rain at any online sensor (screens that aren't about one street). */
    fun overallLevel(): Int = (1..4).maxOf { levels[it] }

    fun addListener(l: Listener) {
        listeners += l
        handler.removeCallbacks(stopRunnable)
        if (dbListeners.isEmpty()) startListening()
    }

    fun removeListener(l: Listener) {
        listeners -= l
        if (listeners.isEmpty()) handler.postDelayed(stopRunnable, STOP_AFTER_MS)
    }

    private fun startListening() {
        try {
            val db = FirebaseDatabase.getInstance(DB_URL).reference
            for (id in 1..4) {
                val path = "Sensordata$id"
                val l = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        tracker.setSensor(id, snapshot)
                        update()
                    }

                    override fun onCancelled(error: DatabaseError) {
                        tracker.clearSensor(id)
                        update()
                    }
                }
                db.child(path).addValueEventListener(l)
                dbListeners += path to l
            }
            val updated = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    tracker.setRootUpdated(snapshot)
                    update()
                }

                override fun onCancelled(error: DatabaseError) = Unit
            }
            db.child("Last_Updated").addValueEventListener(updated)
            dbListeners += "Last_Updated" to updated
            handler.postDelayed(recheck, RECHECK_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Rain effect can't read the sensors", e)
        }
    }

    private fun stopListening() {
        try {
            val db = FirebaseDatabase.getInstance(DB_URL).reference
            dbListeners.forEach { (path, l) -> db.child(path).removeEventListener(l) }
        } catch (_: Exception) {
        }
        dbListeners.clear()
        handler.removeCallbacks(recheck)
    }

    private fun update() {
        var changed = false
        for (id in 1..4) {
            val online = tracker.reading(id).risk != Risk.OFFLINE
            val intensity = if (online) tracker.rainIntensity(id) else null
            // Same scale as the Rain box: Rain_Value 0..1023, 1023 = dry
            val lvl = intensity?.let { RainPredictor.statusIndex(RainPredictor.DRY_VALUE * (1 - it)) } ?: 0
            if (levels[id] != lvl) {
                levels[id] = lvl
                changed = true
            }
        }
        if (changed) listeners.toList().forEach { it.onRainChanged() }
    }
}
