package com.example.bahgo20.ml

import com.example.bahgo20.RecordedDataset
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.OffsetDateTime

/**
 * Trains on the real recorded data (the CSV shipped in the app, the same readings as
 * Firebase /RecordedData) and checks the forecasts on the days the models never saw
 * (Oct 22-28), including Swingfire on Oct 26.
 */
class RecordedDataForecastTest {

    private fun ms(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    /** The bundled CSV, read with the same parser the app uses. */
    private fun loadSensors(): Map<Int, SensorSeries> {
        val file = File("src/main/assets/" + RecordedDataset.ASSET)
        assertTrue("Bundled recorded data not found at ${file.absolutePath}", file.isFile)
        return file.bufferedReader().use { RecordedDataset.parse(it) }
            .mapValues { (_, rows) -> RecordedDataset.toSeries(rows) }
    }

    private fun train(sensors: Map<Int, SensorSeries>, features: FeatureSet, target: Target) =
        FloodForecaster.train(
            sensors.values.toList(),
            validateFromMs = ms("2026-10-20T00:00:00+08:00"),
            testFromMs = ms("2026-10-22T00:00:00+08:00"),
            features = features,
            target = target
        )

    private fun printReport(name: String, r: ForecastReport, unit: String) {
        println("$name  alpha=${r.alpha} kept=${r.lassoKept} dropped=${r.lassoDropped}")
        println("$name  test rows=${r.testRows}  MAE lasso=%.2f linear=%.2f no-change=%.2f $unit"
            .format(r.lasso.mae, r.linear.mae, r.noChange.mae))
        println("$name  while water moves (${r.lassoFlood.count} rows): lasso=%.2f linear=%.2f no-change=%.2f $unit"
            .format(r.lassoFlood.mae, r.linearFlood.mae, r.noChangeFlood.mae))
    }

    /** Objective 2: Linear Regression forecasts the water level 30 minutes ahead. */
    @Test
    fun linearWaterLevelForecastWorksOnUnseenDays() {
        val sensors = loadSensors()
        assertTrue(sensors.size == 4)

        val level = train(sensors, LevelFeatures, Target.LEVEL)
        val quick = train(sensors, LevelEarlyFeatures, Target.LEVEL)
        printReport("LEVEL", level.report, "cm")
        printReport("LEVEL-QUICK", quick.report, "cm")

        // Linear must clearly beat "the level stays the same" while the water is moving
        assertTrue(level.report.linearFlood.mae < level.report.noChangeFlood.mae * 0.5)
        assertTrue(quick.report.linearFlood.mae < quick.report.noChangeFlood.mae)   // quick model: only 2 inputs, weaker

        // Swingfire (sensor 3), Oct 26: Linear should forecast Critical before the water gets there
        val s = sensors.getValue(3)
        val from = s.timesMs.indexOfFirst { it >= ms("2026-10-26T04:30:00+08:00") }
        var warnedEarly = false
        for (i in from..from + 30 step 3) {
            val p = level.predict(s.levels, s.rain, i)
            val actual = s.levels[i + FloodFeatures.HORIZON_STEPS]
            println("Swingfire %s now=%.1f  linear30=%.1f  actual30=%.1f".format(
                java.time.Instant.ofEpochMilli(s.timesMs[i]).atOffset(java.time.ZoneOffset.ofHours(8)).toLocalTime(),
                s.levels[i], p.linear, actual))
            if (s.levels[i] <= 60.0 && p.linear > 60.0) warnedEarly = true
        }
        assertTrue("Linear never forecast Critical ahead of time", warnedEarly)
    }

    /** Objective 3: Lasso combines water level and rain into a flood risk score per area. */
    @Test
    fun lassoRiskScoreWorksOnUnseenDays() {
        val sensors = loadSensors()

        val risk = train(sensors, FloodFeatures, Target.RISK_SCORE)
        val quick = train(sensors, EarlyFeatures, Target.RISK_SCORE)
        printReport("RISK", risk.report, "points")
        printReport("RISK-QUICK", quick.report, "points")

        // Lasso must beat "risk stays where it is now" while the water is moving
        assertTrue(risk.report.lassoFlood.mae < risk.report.noChangeFlood.mae * 0.6)
        assertTrue(quick.report.lassoFlood.mae < quick.report.noChangeFlood.mae * 0.8)
        // It should use rain as well as the water level
        assertTrue(risk.report.lassoKept.any { it.contains("rain", ignoreCase = true) })

        // Each area gets its own score; Swingfire should reach a high score before it goes Critical
        val s = sensors.getValue(3)
        val from = s.timesMs.indexOfFirst { it >= ms("2026-10-26T04:30:00+08:00") }
        var highEarly = false
        for (i in from..from + 30 step 3) {
            val score = risk.predict(s.levels, s.rain, i).lasso
            println("Swingfire %s now=%.1f cm  score=%.0f".format(
                java.time.Instant.ofEpochMilli(s.timesMs[i]).atOffset(java.time.ZoneOffset.ofHours(8)).toLocalTime(),
                s.levels[i], score))
            if (s.levels[i] <= 60.0 && score >= 90) highEarly = true
        }
        assertTrue("Risk score never went high before Critical", highEarly)
        for (id in 1..4) {
            val sx = sensors.getValue(id)
            val peak = (FloodFeatures.HISTORY_STEPS until sx.size - FloodFeatures.HORIZON_STEPS)
                .maxOf { risk.predict(sx.levels, sx.rain, it).lasso }
            println("Sensor $id highest score on the recorded days: %.0f".format(peak))
        }
    }
}
