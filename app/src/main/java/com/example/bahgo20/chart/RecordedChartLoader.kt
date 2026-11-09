package com.example.bahgo20.chart

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.bahgo20.RecordedDataset
import java.util.concurrent.Executors

/**
 * The recorded (simulated sample) water levels for the chart, from the recorded data shipped
 * inside the app (see RecordedDataset), so nothing is downloaded from Firebase.
 * Only the newest N records are used (N comes from the time window). Results are kept in
 * memory, so switching windows is instant. [load] reads on a background thread and answers
 * on the main thread.
 */
object RecordedChartLoader {
    private const val TAG = "RecordedChartLoader"

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** id -> (how many records were asked for, null = all; the points) */
    private val cache = mutableMapOf<Int, Pair<Int?, List<ChartPoint>>>()

    /** Recorded points already in memory that cover [limit], or null if they must be read. */
    fun cached(id: Int, limit: Int?): List<ChartPoint>? {
        val (have, points) = cache[id] ?: return null
        val enough = have == null || (limit != null && limit <= have)
        return if (enough) points else null
    }

    /**
     * Reads the newest [limit] records (all when null) and calls [done] on the main thread with
     * the points, or with an empty list if the recorded data couldn't be read.
     */
    fun load(id: Int, limit: Int?, done: (List<ChartPoint>) -> Unit) {
        cached(id, limit)?.let { done(it); return }
        worker.execute {
            val points = try {
                val rows = RecordedDataset.rows()[id].orEmpty()
                val newest = if (limit != null) rows.takeLast(limit) else rows
                newest.mapNotNull { r ->
                    ChartHistory.levelFromDistance(r.distanceCm)?.let { ChartPoint(r.timeMs, it, ChartSource.RECORDED) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recorded data read failed", e)
                null
            }
            main.post {
                if (points != null) {
                    Log.d(TAG, "Sensordata$id: ${points.size} recorded points (limit ${limit ?: "all"})")
                    cache[id] = limit to points
                }
                done(points.orEmpty())
            }
        }
    }
}
