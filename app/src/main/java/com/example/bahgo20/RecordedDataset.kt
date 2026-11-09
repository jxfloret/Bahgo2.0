package com.example.bahgo20

import android.content.Context
import com.example.bahgo20.ml.SensorSeries
import java.io.BufferedReader
import java.io.Reader
import java.time.OffsetDateTime

/**
 * The recorded flood days (Oct 13-28, 4 sensors, one reading every 5 minutes), shipped inside
 * the app as assets/recorded/taguig_rain_4sensors_data.csv. They never change, so they are
 * read from the app itself instead of being downloaded from Firebase on every app start
 * (that download was the main Realtime Database bandwidth cost).
 *
 * Used for training the forecast models (RecordedTraining) and for the recorded part of the
 * Conditions chart (chart/RecordedChartLoader). Live readings still come from Firebase.
 *
 * The CSV has extra calculated columns (lags, rates, junk_1..3, next-30-min targets); only
 * sensor, Last_Updated, Distance_cm and Rain_Value are read. The app works out its own inputs.
 */
object RecordedDataset {

    const val ASSET = "recorded/taguig_rain_4sensors_data.csv"

    /** Recorded rain sensor reads 0..1023, 1023 = dry. */
    const val RAIN_MAX = 1023.0

    /** One recorded reading. */
    class Row(val timeMs: Long, val distanceCm: Double, val rainValue: Double)

    @Volatile private var appContext: Context? = null
    @Volatile private var cached: Map<Int, List<Row>>? = null

    /** Called once from BahGoApp so the screens don't have to pass a Context. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Readings per sensor id (1..4), oldest first. Reads the file once; call off the main thread. */
    fun rows(): Map<Int, List<Row>> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val context = appContext ?: error("RecordedDataset.init() was not called")
            val parsed = context.assets.open(ASSET).bufferedReader().use { parse(it) }
            if (parsed.isEmpty()) error("No readings in $ASSET")
            cached = parsed
            return parsed
        }
    }

    /** The readings as model input: water level (cm) and rain (0 = dry .. 1 = heaviest). */
    fun series(): List<SensorSeries> = rows().toSortedMap().values.map { toSeries(it) }

    fun toSeries(rows: List<Row>): SensorSeries = SensorSeries(
        LongArray(rows.size) { rows[it].timeMs },
        DoubleArray(rows.size) { (SensorTracker.SENSOR_HEIGHT_CM - rows[it].distanceCm).coerceAtLeast(0.0) },
        DoubleArray(rows.size) { ((RAIN_MAX - rows[it].rainValue) / RAIN_MAX).coerceIn(0.0, 1.0) }
    )

    /**
     * Reads the CSV by column name, so extra or reordered columns don't matter.
     * Rows with a missing time or distance are skipped. Plain Kotlin, so it is unit tested.
     */
    fun parse(reader: Reader): Map<Int, List<Row>> {
        val lines = (reader as? BufferedReader ?: reader.buffered()).lineSequence().iterator()
        if (!lines.hasNext()) return emptyMap()
        val header = lines.next().removePrefix("﻿").split(",").map { it.trim() }
        val iSensor = header.indexOf("sensor")
        val iTime = header.indexOf("Last_Updated")
        val iDistance = header.indexOf("Distance_cm")
        val iRain = header.indexOf("Rain_Value")
        require(iSensor >= 0 && iTime >= 0 && iDistance >= 0) { "CSV needs sensor, Last_Updated and Distance_cm columns" }

        val out = HashMap<Int, MutableList<Row>>()
        while (lines.hasNext()) {
            val line = lines.next()
            if (line.isBlank()) continue
            val f = line.split(",")
            val id = f.getOrNull(iSensor)?.trim()?.removePrefix("Sensordata")?.toIntOrNull() ?: continue
            val time = f.getOrNull(iTime)?.trim()?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } ?: continue
            val distance = f.getOrNull(iDistance)?.trim()?.toDoubleOrNull() ?: continue
            val rain = if (iRain >= 0) f.getOrNull(iRain)?.trim()?.toDoubleOrNull() ?: RAIN_MAX else RAIN_MAX
            out.getOrPut(id) { ArrayList() } += Row(time, distance, rain)
        }
        return out.mapValues { (_, list) -> list.sortedBy { it.timeMs } }
    }
}
