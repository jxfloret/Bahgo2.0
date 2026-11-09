package com.example.bahgo20.ml

/** Readings lined up on the 5-minute steps the models were trained on (oldest first). */
class Grid(val levels: DoubleArray, val rain: DoubleArray)

/**
 * Live sensors write every few seconds, at uneven times. The models were trained on one
 * reading every 5 minutes, so this builds that grid ending at [endMs]:
 *   - each grid point averages the readings in the minute before it (smooths sensor jitter;
 *     the same one-minute window for every point, so the 5-minute steps stay exactly 5 minutes)
 *   - if there are none, it draws a straight line between the readings either side
 *   - it gives up (null) if the history doesn't reach back far enough, or has a long gap
 * Rain that the device didn't send (NaN) is filled from nearby readings, or counted as dry.
 */
object LiveGrid {
    private const val STEP_MS = FloodFeatures.STEP_MIN * 60_000L
    private const val AVERAGE_WITHIN_MS = 60_000L
    private const val MAX_GAP_MS = 10 * 60_000L

    /** [historySteps]: how many 5-minute steps back the grid reaches (11 for the full model, 1 for the early one). */
    fun resample(
        times: LongArray,
        levels: DoubleArray,
        rain: DoubleArray,
        endMs: Long,
        historySteps: Int = FloodFeatures.HISTORY_STEPS
    ): Grid? {
        val n = historySteps + 1
        if (times.isEmpty()) return null
        val outLevel = DoubleArray(n)
        val outRain = DoubleArray(n)
        for (k in 0 until n) {
            val t = endMs - (n - 1 - k) * STEP_MS
            outLevel[k] = valueAt(times, levels, t) ?: return null
            outRain[k] = valueAt(times, rain, t) ?: 0.0
        }
        return Grid(outLevel, outRain)
    }

    /** Value at time [t] (NaN entries are skipped), or null if there's no data close enough. */
    private fun valueAt(times: LongArray, values: DoubleArray, t: Long): Double? {
        // 1) Average of the readings in the minute up to t
        var sum = 0.0
        var count = 0
        var before = -1      // last usable index before the window
        var after = -1       // first usable index after the window
        for (i in times.indices) {
            if (values[i].isNaN()) continue
            val dt = times[i] - t
            when {
                dt < -AVERAGE_WITHIN_MS -> before = i
                dt > 0 -> { if (after < 0) after = i }
                else -> { sum += values[i]; count++ }
            }
        }
        if (count > 0) return sum / count

        // 2) Straight line between the readings either side, if they aren't too far apart
        if (before < 0 || after < 0) return null
        val span = times[after] - times[before]
        if (span > MAX_GAP_MS) return null
        val f = (t - times[before]).toDouble() / span
        return values[before] + f * (values[after] - values[before])
    }
}
