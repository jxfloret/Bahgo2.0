package com.example.bahgo20.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.OffsetDateTime

/**
 * Checks the app's rain forecast gives the same numbers as the Python models (train_rain_models.py).
 *
 * rain_parity.csv (test resources) is written by the Python script: features + its Linear and Lasso
 * predictions from the final weights, for every 5th row of the 5-minute data.
 */
class RainParityTest {

    private class ParityRow(
        val sensor: Int, val time: String, val features: DoubleArray, val linear: Double, val lasso: Double
    )

    private fun parityRows(): List<ParityRow> {
        val text = javaClass.classLoader!!.getResource("rain_parity.csv")!!.readText()
        return text.lines().drop(1).filter { it.isNotBlank() }.map { line ->
            val c = line.split(',')
            ParityRow(
                sensor = c[0].toInt(),
                time = c[1],
                features = DoubleArray(5) { c[2 + it].toDouble() },
                linear = c[7].toDouble(),
                lasso = c[8].toDouble()
            )
        }
    }

    /** Rain_Value per sensor id, in time order, from the training CSV (skipped if it isn't there). */
    private fun csvSeries(): Map<Int, Pair<List<String>, DoubleArray>>? {
        val path = System.getenv("BAHGO_RAIN_CSV")
            ?: (System.getProperty("user.home") + "/Downloads/taguig_rain_4sensors_data.csv")
        val f = File(path).takeIf { it.isFile } ?: return null
        val lines = f.readLines()
        val header = lines.first().split(',')
        val iSensor = header.indexOf("sensor")
        val iTime = header.indexOf("Last_Updated")
        val iRain = header.indexOf("Rain_Value")
        return lines.drop(1).filter { it.isNotBlank() }.map { it.split(',') }
            .groupBy { it[iSensor] }
            .toSortedMap()
            .entries.mapIndexed { k, (_, rows) ->
                val sorted = rows.sortedBy { it[iTime] }
                (k + 1) to (sorted.map { it[iTime] } to DoubleArray(sorted.size) { sorted[it][iRain].toDouble() })
            }.toMap()
    }

    @Test
    fun weights_matchPython() {
        val rows = parityRows()
        assertTrue(rows.size > 1000)
        for (r in rows) {
            val lin = RainPredictor.apply(RainModelWeights.LINEAR.getValue(r.sensor), r.features)
            val las = RainPredictor.apply(RainModelWeights.LASSO.getValue(r.sensor), r.features)
            assertEquals("linear ${r.sensor} ${r.time}", r.linear, lin, 1e-6)
            assertEquals("lasso ${r.sensor} ${r.time}", r.lasso, las, 1e-6)
        }
    }

    @Test
    fun featuresFromCsv_matchPython() {
        val series = csvSeries()
        assumeTrue("Training CSV not found, skipping", series != null)
        var checked = 0
        for (r in parityRows()) {
            val (times, rain) = series!!.getValue(r.sensor)
            val i = times.indexOf(r.time)
            assertTrue("time ${r.time} missing in CSV", i >= 0)
            val x = RainPredictor.features(rain, i)
            for (j in x.indices) assertEquals("feature $j at ${r.time}", r.features[j], x[j], 1e-6)
            val f = RainPredictor.forecast(r.sensor, rain.copyOfRange(0, i + 1))!!
            assertEquals(r.lasso, f.lassoValue, 1e-6)
            assertEquals(r.linear, f.linearValue, 1e-6)
            checked++
        }
        println("Rain parity: $checked rows match Python")
    }

    @Test
    fun liveReadingsEverySecondsGiveSamePrediction() {
        // Live path: readings every 5 s, resampled by LiveGrid to 5-minute steps, as SensorTracker does
        val series = csvSeries()
        assumeTrue("Training CSV not found, skipping", series != null)
        val (times, rain) = series!!.getValue(3)
        val start = times.indexOf(parityRows().first { it.sensor == 3 && it.time >= "2026-10-26T04:00" }.time)
        val step = 5 * 60_000L
        val t0 = OffsetDateTime.parse(times[start - 6]).toInstant().toEpochMilli()

        // Each 5-second reading carries the value of the 5-minute row it leads up to
        val liveTimes = mutableListOf<Long>()
        val liveRain = mutableListOf<Double>()
        var t = t0 - step + 5_000L
        while (t <= t0 + 6 * step) {
            val row = start - 6 + ((t - t0 + step - 1) / step).toInt()
            liveTimes += t
            liveRain += rain[row]
            t += 5_000L
        }
        val grid = LiveGrid.resample(
            liveTimes.toLongArray(), liveRain.toDoubleArray(), liveRain.toDoubleArray(),
            liveTimes.last(), RainPredictor.HISTORY_STEPS
        )
        assertNotNull(grid)
        val live = RainPredictor.forecast(3, grid!!.levels)!!
        val direct = RainPredictor.forecast(3, rain.copyOfRange(0, start + 1))!!
        assertEquals(direct.lassoValue, live.lassoValue, 1e-9)
        assertEquals(direct.linearValue, live.linearValue, 1e-9)
    }

    @Test
    fun esp32ScaleConvertsToTenBit() {
        // SensorTracker stores intensity = (4095 - v) / 4095 and converts back as 1023 * (1 - intensity)
        for (v in listOf(0.0, 1000.0, 2000.0, 4095.0)) {
            val intensity = (4095.0 - v) / 4095.0
            assertEquals(v * 1023.0 / 4095.0, RainPredictor.DRY_VALUE * (1 - intensity), 1e-9)
        }
    }

    @Test
    fun statusWordsAndChange() {
        assertEquals("No Rain", RainPredictor.status(950.0))
        assertEquals("Light Rain", RainPredictor.status(600.0))
        assertEquals("Moderate Rain", RainPredictor.status(300.0))
        assertEquals("Heavy Rain", RainPredictor.status(299.0))
        val f = RainPredictor.RainForecast(lassoValue = 500.0, linearValue = 520.0, nowValue = 700.0)
        assertEquals(RainPredictor.Change.HEAVIER, f.change)
        assertEquals(RainPredictor.Change.SAME, f.copy(lassoValue = 650.0).change)
        assertEquals(RainPredictor.Change.LIGHTER, f.copy(lassoValue = 950.0).change)
    }

    @Test
    fun summaryInPlainWords() {
        fun s(now: Double, next: Double) =
            RainPredictor.summary(RainPredictor.RainForecast(lassoValue = next, linearValue = next, nowValue = now))
        assertEquals("No rain now, and none expected in the next 30 minutes.", s(1000.0, 980.0))
        assertEquals("No rain now. Light Rain possible within 30 minutes.", s(950.0, 700.0))
        assertEquals("Light Rain now. Expected to stop within 30 minutes.", s(700.0, 950.0))
        assertEquals("Light Rain now, getting heavier: Heavy Rain expected in 30 minutes.", s(700.0, 100.0))
        assertEquals("Heavy Rain now, easing to light rain in 30 minutes.", s(100.0, 700.0))
        assertEquals("Heavy Rain now. Expected to continue for the next 30 minutes.", s(50.0, 97.0))
        assertEquals("Dry", RainPredictor.shortWord(1023.0))
        assertEquals("Heavy", RainPredictor.shortWord(0.0))
    }
}
