package com.example.bahgo20.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import kotlin.random.Random

class RegressionTest {

    /** y = 3 + 2*x1 - x2, exactly. */
    private fun exactData(n: Int = 200): Pair<List<DoubleArray>, DoubleArray> {
        val rnd = Random(1)
        val xs = List(n) { doubleArrayOf(rnd.nextDouble(-5.0, 5.0), rnd.nextDouble(0.0, 10.0)) }
        val ys = DoubleArray(n) { 3 + 2 * xs[it][0] - xs[it][1] }
        return xs to ys
    }

    @Test
    fun linear_recoversExactLine() {
        val (xs, ys) = exactData()
        val m = LinearRegression.fit(xs, ys)
        assertEquals(3.0 + 2 * 1.5 - 4.0, m.predict(doubleArrayOf(1.5, 4.0)), 1e-6)
        assertEquals(3.0, m.predict(doubleArrayOf(0.0, 0.0)), 1e-6)
    }

    @Test
    fun linear_handlesDuplicateColumn() {
        // Second column is a copy of the first: plain OLS would be unsolvable
        val xs = List(50) { doubleArrayOf(it.toDouble(), it.toDouble()) }
        val ys = DoubleArray(50) { 1 + 4.0 * it }
        val m = LinearRegression.fit(xs, ys)
        assertEquals(1 + 4.0 * 10, m.predict(doubleArrayOf(10.0, 10.0)), 1e-4)
    }

    @Test
    fun lasso_withZeroAlpha_matchesLinear() {
        val (xs, ys) = exactData()
        val lasso = LassoRegression.fit(xs, ys, alpha = 0.0)
        val linear = LinearRegression.fit(xs, ys)
        for (x in listOf(doubleArrayOf(2.0, 3.0), doubleArrayOf(-4.0, 9.0))) {
            assertEquals(linear.predict(x), lasso.predict(x), 1e-6)
        }
    }

    @Test
    fun lasso_withHugeAlpha_predictsTheMean() {
        val (xs, ys) = exactData()
        val m = LassoRegression.fit(xs, ys, alpha = 1e6)
        assertTrue(m.usedFeatures().isEmpty())
        assertEquals(ys.average(), m.predict(doubleArrayOf(4.0, 1.0)), 1e-9)
    }

    @Test
    fun lasso_dropsNoiseFeature() {
        val rnd = Random(7)
        val xs = List(500) { doubleArrayOf(rnd.nextDouble(), rnd.nextDouble(), rnd.nextDouble()) }
        // Only the first feature matters; the other two are noise
        val ys = DoubleArray(500) { 10 * xs[it][0] + rnd.nextDouble(-0.05, 0.05) }
        val m = LassoRegression.fit(xs, ys, alpha = 0.1)
        assertEquals(listOf(0), m.usedFeatures())
    }

    @Test
    fun softThreshold_shrinksTowardZero() {
        assertEquals(2.0, LassoRegression.softThreshold(3.0, 1.0), 0.0)
        assertEquals(-2.0, LassoRegression.softThreshold(-3.0, 1.0), 0.0)
        assertEquals(0.0, LassoRegression.softThreshold(0.5, 1.0), 0.0)
    }

    @Test
    fun errorStats_mathIsRight() {
        val e = errorStats(doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(1.0, 4.0, 0.0))
        assertEquals((0 + 2 + 3) / 3.0, e.mae, 1e-12)
        assertEquals(Math.sqrt((0 + 4 + 9) / 3.0), e.rmse, 1e-12)
    }

    @Test
    fun features_areComputedFromHistory() {
        val levels = DoubleArray(12) { it.toDouble() }       // rises 1 cm every 5 min
        val rain = DoubleArray(12) { if (it >= 9) 1.0 else 0.0 }
        val f = FloodFeatures.at(levels, rain, 11)
        assertEquals(11.0, f[0], 0.0)   // level now
        assertEquals(1.0, f[1], 0.0)    // last 5 min
        assertEquals(3.0, f[2], 0.0)    // last 15 min
        assertEquals(6.0, f[3], 0.0)    // last 30 min
        assertEquals(1.0, f[4], 0.0)    // rain now
        assertEquals(0.5, f[5], 1e-12)  // 3 of the last 6 readings were rainy
        assertEquals(3.0, f[6], 1e-12)  // cumulative rain over the last hour (12 readings)
        assertEquals(1.0, f[7], 0.0)    // rain went 0 -> 1 over 15 min
        assertEquals(6.0, f[8], 0.0)    // rain * 30-min change
    }

    @Test
    fun earlyFeatures_needOnlyFiveMinutes() {
        val levels = doubleArrayOf(20.0, 23.0)
        val rain = doubleArrayOf(0.0, 0.8)
        assertTrue(EarlyFeatures.hasHistory(1))
        val f = EarlyFeatures.at(levels, rain, 1)
        assertEquals(listOf(23.0, 3.0, 0.8), f.toList())
    }

    @Test
    fun riskScoreTarget_isPeakLevelAsPercentOfCritical() {
        val levels = doubleArrayOf(10.0, 20.0, 45.0, 30.0, 70.0, 25.0, 0.0)
        val s = SensorSeries(LongArray(levels.size) { it * 300_000L }, levels, DoubleArray(levels.size))
        // Peak over the next 6 readings after index 0 is 70 cm, above 60 cm -> capped at 100
        assertEquals(100.0, FloodForecaster.targetAt(s, 0, Target.RISK_SCORE), 1e-9)
        assertEquals(50.0, Target.score(30.0), 1e-9)
        assertEquals(0.0, Target.score(-5.0), 1e-9)
    }

    @Test
    fun levelFeatures_useWaterOnly() {
        val levels = DoubleArray(7) { it * 2.0 }
        val f = LevelFeatures.at(levels, DoubleArray(7) { 1.0 }, 6)
        assertEquals(listOf(12.0, 2.0, 6.0, 12.0), f.toList())
    }

    @Test
    fun forecaster_beatsNoChangeGuessOnSyntheticFloods() {
        // Rain bursts that raise the water, which then drains away
        val n = 3000
        val step = 5 * 60_000L
        val rnd = Random(3)
        val rain = DoubleArray(n) { if (sin(it / 90.0) > 0.6) 1.0 else 0.0 }
        val levels = DoubleArray(n)
        for (i in 1 until n) {
            levels[i] = (levels[i - 1] * 0.97 + rain[i] * 2.0 + rnd.nextDouble(-0.2, 0.2)).coerceAtLeast(0.0)
        }
        val series = SensorSeries(LongArray(n) { it * step }, levels, rain)
        val f = FloodForecaster.train(listOf(series), validateFromMs = 1800 * step, testFromMs = 2400 * step)
        val r = f.report
        assertTrue("lasso ${r.lasso.mae} vs no-change ${r.noChange.mae}", r.lasso.mae < r.noChange.mae * 0.7)
        assertTrue("linear ${r.linear.mae} vs no-change ${r.noChange.mae}", r.linear.mae < r.noChange.mae * 0.7)
    }
}
