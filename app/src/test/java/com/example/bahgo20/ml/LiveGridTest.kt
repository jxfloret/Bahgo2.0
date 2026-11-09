package com.example.bahgo20.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LiveGridTest {

    private val minute = 60_000L

    /** A reading every 5 seconds for [minutes], level rising 0.2 cm per minute. */
    private fun live(minutes: Int, rain: Double = 0.5): Triple<LongArray, DoubleArray, DoubleArray> {
        val n = minutes * 12 + 1
        val t = LongArray(n) { it * 5_000L }
        return Triple(t, DoubleArray(n) { 10 + 0.2 * t[it] / minute }, DoubleArray(n) { rain })
    }

    @Test
    fun buildsFiveMinuteGrid() {
        val (t, l, r) = live(60)
        val g = LiveGrid.resample(t, l, r, t.last())
        assertNotNull(g)
        g!!
        assertEquals(FloodFeatures.HISTORY_STEPS + 1, g.levels.size)
        // Last point averages the final minute (59..60 min), so it lands half a minute back
        assertEquals(10 + 0.2 * 59.5, g.levels.last(), 1e-9)
        // Points 5 minutes apart differ by 1 cm (0.2 cm/min)
        assertEquals(1.0, g.levels[5] - g.levels[4], 1e-9)
        assertEquals(0.5, g.rain[0], 1e-9)
    }

    @Test
    fun notEnoughHistory_givesNull() {
        val (t, l, r) = live(40)   // needs about 55 minutes
        assertNull(LiveGrid.resample(t, l, r, t.last()))
    }

    @Test
    fun earlyGrid_needsOnlyFiveMinutes() {
        val (t, l, r) = live(6)
        val g = LiveGrid.resample(t, l, r, t.last(), EarlyFeatures.HISTORY_STEPS)
        assertNotNull(g)
        assertEquals(2, g!!.levels.size)
        assertEquals(1.0, g.levels[1] - g.levels[0], 1e-9)
        // ...while the full grid still has to wait
        assertNull(LiveGrid.resample(t, l, r, t.last()))
    }

    @Test
    fun longGap_givesNull() {
        val (t, l, r) = live(60)
        // Remove 20 minutes of readings in the middle
        val keep = t.indices.filter { t[it] < 20 * minute || t[it] > 40 * minute }
        val g = LiveGrid.resample(
            LongArray(keep.size) { t[keep[it]] },
            DoubleArray(keep.size) { l[keep[it]] },
            DoubleArray(keep.size) { r[keep[it]] },
            t.last()
        )
        assertNull(g)
    }

    @Test
    fun shortGap_isFilledWithStraightLine() {
        val (t, l, r) = live(60)
        val keep = t.indices.filter { t[it] < 28 * minute || t[it] > 33 * minute }
        val g = LiveGrid.resample(
            LongArray(keep.size) { t[keep[it]] },
            DoubleArray(keep.size) { l[keep[it]] },
            DoubleArray(keep.size) { r[keep[it]] },
            t.last()
        )
        assertNotNull(g)
    }

    @Test
    fun missingRain_countsAsDry() {
        val (t, l, _) = live(60)
        val g = LiveGrid.resample(t, l, DoubleArray(t.size) { Double.NaN }, t.last())!!
        assertEquals(0.0, g.rain.max(), 0.0)
    }
}
