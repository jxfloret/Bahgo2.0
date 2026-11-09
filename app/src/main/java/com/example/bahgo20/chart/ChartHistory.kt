package com.example.bahgo20.chart

import com.example.bahgo20.SensorTracker

/** Where a chart point came from. RECORDED is simulated sample data, never real sensor history. */
enum class ChartSource { RECORDED, LIVE }

/**
 * One water level point on the chart.
 * breakBefore = true means "don't join this point to the one before it" (a gap, e.g. the device was offline).
 */
data class ChartPoint(
    val timeMs: Long,
    val levelCm: Double,
    val source: ChartSource,
    val breakBefore: Boolean = false
)

/** Time windows offered above the chart. */
enum class ChartWindow(val label: String, val durationMs: Long?, val recordedLimit: Int?) {
    H6("6h", 6 * HOUR, 72),
    H24("24h", 24 * HOUR, 288),
    D3("3 days", 72 * HOUR, 864),
    ALL("All recorded", null, null);   // whole recorded history (about 4,600 points per sensor)
}

private const val HOUR = 60 * 60_000L

/**
 * Merging and thinning of chart points. Plain Kotlin (no Android), so it is unit tested.
 *   1) one point per 5-minute slot; when recorded and live share a slot, LIVE wins
 *   2) keep only the selected time window, sorted by time
 *   3) mark a break wherever two neighbours are more than 20 minutes apart
 *   4) thin long windows to at most [MAX_POINTS], keeping each bucket's highest level
 *      (so flood peaks never disappear) and never joining across a break
 */
object ChartHistory {
    const val SLOT_MS = 5 * 60_000L
    const val GAP_MS = 20 * 60_000L
    const val MAX_POINTS = 300

    fun slotOf(timeMs: Long): Long = Math.floorDiv(timeMs, SLOT_MS) * SLOT_MS

    /** Water level from the sensor's Distance_cm, or null for a bad echo (<= 0). Clamped at 0. */
    fun levelFromDistance(distanceCm: Double): Double? =
        if (distanceCm <= 0) null else maxOf(0.0, SensorTracker.SENSOR_HEIGHT_CM - distanceCm)

    /**
     * Builds the points to draw for the window [fromMs, toMs].
     * [fromMs] null = from the oldest point available.
     */
    fun build(
        recorded: List<ChartPoint>,
        live: List<ChartPoint>,
        fromMs: Long?,
        toMs: Long,
        maxPoints: Int = MAX_POINTS
    ): List<ChartPoint> {
        // 1) One point per 5-minute slot, live wins
        val bySlot = sortedMapOf<Long, ChartPoint>()
        for (p in recorded) bySlot[slotOf(p.timeMs)] = p.copy(source = ChartSource.RECORDED, breakBefore = false)
        for (p in live) bySlot[slotOf(p.timeMs)] = p.copy(source = ChartSource.LIVE, breakBefore = false)

        // 2) Window, in time order
        val inWindow = bySlot.values.filter { (fromMs == null || it.timeMs >= fromMs) && it.timeMs <= toMs }

        // 3) Breaks at gaps longer than 20 minutes
        val withGaps = inWindow.mapIndexed { i, p ->
            if (i > 0 && p.timeMs - inWindow[i - 1].timeMs > GAP_MS) p.copy(breakBefore = true) else p
        }

        // 4) Thin out long windows
        return downsample(withGaps, maxPoints)
    }

    /**
     * At most [maxPoints] points: equal time buckets, the highest level of each bucket kept.
     * Buckets never span a break, and the first point after a break keeps its break mark.
     */
    fun downsample(points: List<ChartPoint>, maxPoints: Int): List<ChartPoint> {
        if (points.size <= maxPoints || points.size < 2) return points
        val span = (points.last().timeMs - points.first().timeMs).coerceAtLeast(1L)
        val bucketMs = maxOf(SLOT_MS, span / maxPoints + 1)
        val out = mutableListOf<ChartPoint>()
        var bucketStart = 0L
        var best: ChartPoint? = null
        var bucketBreak = false
        fun flush() {
            best?.let { out += it.copy(breakBefore = bucketBreak) }
            best = null
        }
        for (p in points) {
            // (No "now - Long.MIN_VALUE" here: that overflowed and put every point in one bucket)
            val newBucket = best == null || p.breakBefore || p.timeMs - bucketStart >= bucketMs
            if (newBucket) {
                flush()
                bucketStart = p.timeMs
                bucketBreak = p.breakBefore
            }
            if (best == null || p.levelCm > best!!.levelCm) best = p
        }
        flush()
        return out
    }
}
