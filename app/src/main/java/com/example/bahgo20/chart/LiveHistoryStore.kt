package com.example.bahgo20.chart

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import java.io.File

/**
 * Longer history of LIVE water levels for the chart: up to 3 days per sensor, one point per
 * 5-minute slot (the newest reading in a slot wins). Kept in a small text file per sensor in the
 * app's private storage, so the chart isn't empty after the app restarts.
 *
 * File format, one line per slot:  slotStartMs,levelCm
 */
object LiveHistoryStore {
    private const val TAG = "LiveHistoryStore"
    const val KEEP_MS = 3 * 24 * 60 * 60_000L
    private const val SAVE_EVERY_MS = 60_000L

    private val slots = mutableMapOf<Int, java.util.TreeMap<Long, Double>>()
    private val lastSaved = mutableMapOf<Int, Long>()
    private val dirty = mutableSetOf<Int>()

    /** App storage folder (Firebase is always set up before any screen opens). */
    private fun dir(): File? = try {
        FirebaseApp.getInstance().applicationContext.filesDir
    } catch (e: Exception) {
        Log.e(TAG, "Storage not available", e)
        null
    }

    private fun file(id: Int): File? = dir()?.let { File(it, "live_levels_$id.csv") }

    @Synchronized
    private fun mapFor(id: Int): java.util.TreeMap<Long, Double> = slots.getOrPut(id) {
        val map = java.util.TreeMap<Long, Double>()
        try {
            file(id)?.takeIf { it.isFile }?.readText()?.let { decode(it, map) }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't read saved levels for sensor $id", e)
        }
        prune(map, System.currentTimeMillis())
        map
    }

    /** Remembers a live reading (called by SensorTracker for every reading it gets). */
    @Synchronized
    fun record(id: Int, timeMs: Long, levelCm: Double) {
        val map = mapFor(id)
        map[ChartHistory.slotOf(timeMs)] = levelCm
        prune(map, System.currentTimeMillis())
        dirty += id
        if (System.currentTimeMillis() - (lastSaved[id] ?: 0L) >= SAVE_EVERY_MS) save(id)
    }

    /** The saved live points for a sensor, oldest first. */
    @Synchronized
    fun points(id: Int): List<ChartPoint> =
        mapFor(id).map { (t, level) -> ChartPoint(t, level, ChartSource.LIVE) }

    /** Writes any unsaved points (call when a screen closes). */
    @Synchronized
    fun saveAll() {
        for (id in dirty.toList()) save(id)
    }

    private fun save(id: Int) {
        val target = file(id) ?: return
        try {
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(encode(mapFor(id)))
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
            lastSaved[id] = System.currentTimeMillis()
            dirty -= id
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't save levels for sensor $id", e)
        }
    }

    // ───────── file format (also used by the unit test) ─────────

    fun encode(map: Map<Long, Double>): String =
        map.entries.joinToString("\n") { "${it.key},${it.value}" }

    fun decode(text: String, into: MutableMap<Long, Double>) {
        for (line in text.lineSequence()) {
            val parts = line.split(',')
            if (parts.size != 2) continue
            val t = parts[0].trim().toLongOrNull() ?: continue
            val level = parts[1].trim().toDoubleOrNull() ?: continue
            into[t] = level
        }
    }

    /** Drops points older than 3 days (and anything far in the future from a bad clock). */
    fun prune(map: java.util.TreeMap<Long, Double>, nowMs: Long) {
        map.headMap(nowMs - KEEP_MS).clear()
        map.tailMap(nowMs + KEEP_MS).clear()
    }
}
