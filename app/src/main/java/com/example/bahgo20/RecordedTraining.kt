package com.example.bahgo20

import android.util.Log
import com.example.bahgo20.ml.EarlyFeatures
import com.example.bahgo20.ml.FeatureSet
import com.example.bahgo20.ml.FloodFeatures
import com.example.bahgo20.ml.FloodForecaster
import com.example.bahgo20.ml.ForecastReport
import com.example.bahgo20.ml.LevelEarlyFeatures
import com.example.bahgo20.ml.LevelFeatures
import com.example.bahgo20.ml.Target
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Training data for the forecast: the recorded flood days (Oct 13-28), shipped inside the app
 * (see RecordedDataset; the same readings as Firebase /RecordedData, without downloading them).
 *
 * The screens always show the LIVE sensors (/Sensordata1..4). This reads the recorded
 * days once per app run and trains the Linear and Lasso models on them:
 *   - train on the days before Oct 20
 *   - pick Lasso's alpha on Oct 20-21
 *   - retrain on everything before Oct 22, then score on Oct 22-28 (days the models never saw)
 *
 * What the app uses (each with a quick version that needs only 5 minutes of readings):
 *   - LINEAR REGRESSION -> water level in 30 minutes (objective 2), from water-level inputs only
 *     (level now, change over 5/15/30 min); checked against the Safe/Warning/Critical thresholds
 *   - LASSO REGRESSION  -> flood risk score 0..100 per area (objective 3), from water level AND
 *     rain inputs (lags, rate of change, cumulative rainfall)
 * The other model of each pair is trained too, only to compare them in the log.
 */
object RecordedTraining {

    private const val TAG = "RecordedTraining"

    private const val VALIDATE_FROM = "2026-10-20T00:00:00+08:00"
    private const val TEST_FROM = "2026-10-22T00:00:00+08:00"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private class Models(
        val level: FloodForecaster,         // use .linear
        val levelEarly: FloodForecaster?,
        val risk: FloodForecaster,          // use .lasso
        val riskEarly: FloodForecaster?
    )

    @Volatile private var models: Models? = null
    private var loading: Deferred<Models?>? = null

    @Volatile var loadError: String? = null
        private set

    /** Water level forecast models (use .linear), or null until [load] has finished. */
    fun levelForecaster(): FloodForecaster? = models?.level

    /** Quick water level models (5 minutes of readings), or null. */
    fun levelEarlyForecaster(): FloodForecaster? = models?.levelEarly

    /** Flood risk score models (use .lasso), or null until [load] has finished. */
    fun riskForecaster(): FloodForecaster? = models?.risk

    /** Quick flood risk score models (5 minutes of readings), or null. */
    fun riskEarlyForecaster(): FloodForecaster? = models?.riskEarly

    /** Test results on the held-out days Oct 22-28. */
    fun levelReport(): ForecastReport? = models?.level?.report
    fun riskReport(): ForecastReport? = models?.risk?.report

    /** Reads the recorded data once and trains the models. Safe to call from several screens. */
    suspend fun load(): Boolean {
        if (models != null) return true
        val job = synchronized(this) {
            loading ?: scope.async { fetchAndTrain() }.also { loading = it }
        }
        val result = job.await()
        if (result == null) synchronized(this) { if (loading === job) loading = null }   // allow a retry
        return result != null
    }

    private fun fetchAndTrain(): Models? = try {
        val series = RecordedDataset.series()
        if (series.isEmpty()) error("No recorded readings")

        val validateFrom = parseIso(VALIDATE_FROM)!!
        val testFrom = parseIso(TEST_FROM)!!
        fun train(name: String, features: FeatureSet, target: Target) =
            FloodForecaster.train(series, validateFrom, testFrom, features = features, target = target)
                .also { logReport(name, it.report, target) }
        fun tryTrain(name: String, features: FeatureSet, target: Target) = try {
            train(name, features, target)
        } catch (e: Exception) {
            Log.e(TAG, "$name training failed", e)
            null
        }

        val level = train("Water level (Linear)", LevelFeatures, Target.LEVEL)
        val levelEarly = tryTrain("Water level, quick (Linear)", LevelEarlyFeatures, Target.LEVEL)
        val risk = train("Flood risk score (Lasso)", FloodFeatures, Target.RISK_SCORE)
        val riskEarly = tryTrain("Flood risk score, quick (Lasso)", EarlyFeatures, Target.RISK_SCORE)
        loadError = null
        Models(level, levelEarly, risk, riskEarly).also { models = it }
    } catch (e: Exception) {
        Log.e(TAG, "Training on the recorded data failed", e)
        loadError = e.message ?: "Could not load the training data"
        null
    }

    private fun parseIso(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(text.trim())?.time
        } catch (_: Exception) {
            null
        }
    }

    private fun logReport(which: String, r: ForecastReport, target: Target) {
        val unit = if (target == Target.LEVEL) "cm" else "score points"
        Log.i(TAG, "$which:")
        Log.i(TAG, "Trained on ${r.trainRows} rows, tested on ${r.testRows} (Oct 22-28)")
        Log.i(TAG, "Lasso alpha=${r.alpha}, kept=${r.lassoKept}, dropped=${r.lassoDropped}")
        Log.i(TAG, "MAE all:   lasso=%.2f linear=%.2f no-change=%.2f $unit".format(r.lasso.mae, r.linear.mae, r.noChange.mae))
        Log.i(TAG, "MAE flood: lasso=%.2f linear=%.2f no-change=%.2f $unit".format(r.lassoFlood.mae, r.linearFlood.mae, r.noChangeFlood.mae))
    }
}
