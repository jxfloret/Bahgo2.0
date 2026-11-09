package com.example.bahgo20.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartHistoryTest {

    private val min = 60_000L
    private val t0 = 1_800_000_000_000L - 1_800_000_000_000L % (5 * 60_000L)   // on a 5-minute boundary

    private fun rec(minute: Int, level: Double) = ChartPoint(t0 + minute * min, level, ChartSource.RECORDED)
    private fun live(minute: Int, level: Double) = ChartPoint(t0 + minute * min, level, ChartSource.LIVE)

    @Test
    fun liveWinsInTheSameFiveMinuteSlot() {
        val out = ChartHistory.build(
            recorded = listOf(rec(0, 10.0), rec(5, 11.0), rec(10, 12.0)),
            live = listOf(live(6, 40.0), live(8, 41.0)),   // both in the 5-10 min slot; newest wins
            fromMs = null, toMs = t0 + 60 * min
        )
        assertEquals(3, out.size)
        assertEquals(ChartSource.LIVE, out[1].source)
        assertEquals(41.0, out[1].levelCm, 0.0)
        assertEquals(ChartSource.RECORDED, out[2].source)
    }

    @Test
    fun sortedAndLimitedToTheWindow() {
        val out = ChartHistory.build(
            recorded = listOf(rec(30, 3.0), rec(0, 1.0), rec(15, 2.0)),
            live = emptyList(),
            fromMs = t0 + 10 * min, toMs = t0 + 20 * min
        )
        assertEquals(listOf(2.0), out.map { it.levelCm })
        val all = ChartHistory.build(listOf(rec(30, 3.0), rec(0, 1.0), rec(15, 2.0)), emptyList(), null, t0 + 60 * min)
        assertEquals(listOf(1.0, 2.0, 3.0), all.map { it.levelCm })
    }

    @Test
    fun gapsLongerThan20MinutesBreakTheLine() {
        val out = ChartHistory.build(
            recorded = emptyList(),
            live = listOf(live(0, 1.0), live(5, 1.0), live(25, 1.0), live(50, 1.0)),
            fromMs = null, toMs = t0 + 60 * min
        )
        assertEquals(listOf(false, false, false, true), out.map { it.breakBefore })   // 20 min is not a gap, 25 is
    }

    @Test
    fun downsampleKeepsAtMost300PointsPeaksAndBreaks() {
        // 3 days of 5-minute points with one flood peak and one 2-hour outage
        val pts = (0 until 864).filter { it !in 400..423 }.map { i ->
            live(i * 5, if (i == 700) 75.0 else 10.0)
        }
        val out = ChartHistory.build(emptyList(), pts, null, t0 + 864 * 5 * min)
        assertTrue("got ${out.size}", out.size <= ChartHistory.MAX_POINTS + 2)
        assertTrue("too few points left: ${out.size}", out.size >= 250)   // thinned, not collapsed
        assertEquals(pts.first().timeMs, out.first().timeMs)              // starts where the data starts
        assertEquals(75.0, out.maxOf { it.levelCm }, 0.0)            // the peak survives
        assertEquals(1, out.count { it.breakBefore })                 // the outage is still a break
        assertTrue(out.zipWithNext().all { (a, b) -> a.timeMs < b.timeMs })
    }

    @Test
    fun allRecordedSixteenDaysPlusLiveStaysALine() {
        // Like "All recorded": 16 days of recorded points, then a live point a day later
        val recorded = (0 until 4608).map { rec(it * 5, (it % 288).toDouble() / 10) }
        val out = ChartHistory.build(recorded, listOf(live(4608 * 5 + 24 * 60, 0.0)), null, t0 + 4900 * 5 * min)
        val rec = out.filter { it.source == ChartSource.RECORDED }
        assertTrue("recorded points left: ${rec.size}", rec.size in 250..ChartHistory.MAX_POINTS + 2)
        assertTrue(rec.none { it.breakBefore })                           // one unbroken recorded line
        assertTrue(out.last().breakBefore)                                // the day-long gap before live
    }

    @Test
    fun shortWindowsAreNotThinned() {
        val pts = (0 until 288).map { live(it * 5, it.toDouble()) }
        assertEquals(288, ChartHistory.build(emptyList(), pts, null, t0 + 288 * 5 * min).size)
    }

    @Test
    fun levelFromDistance() {
        assertEquals(0.0, ChartHistory.levelFromDistance(100.0)!!, 0.0)   // clamped at 0
        assertEquals(30.0, ChartHistory.levelFromDistance(64.0)!!, 1e-9)
        assertNull(ChartHistory.levelFromDistance(0.0))                 // bad echo ignored
        assertNull(ChartHistory.levelFromDistance(-3.0))
    }

    @Test
    fun liveHistoryFileFormatRoundTripsAndPrunes() {
        val now = t0 + 5 * 24 * 60 * min
        val map = java.util.TreeMap<Long, Double>()
        LiveHistoryStore.decode(
            "${now - 4 * 24 * 60 * min},5.0\n${now - 60 * min},12.5\nbad line\n${now - 5 * min},13.0\n", map
        )
        assertEquals(3, map.size)
        LiveHistoryStore.prune(map, now)
        assertEquals(listOf(12.5, 13.0), map.values.toList())          // older than 3 days dropped
        val again = java.util.TreeMap<Long, Double>()
        LiveHistoryStore.decode(LiveHistoryStore.encode(map), again)
        assertEquals(map, again)
        assertFalse(again.isEmpty())
    }
}
