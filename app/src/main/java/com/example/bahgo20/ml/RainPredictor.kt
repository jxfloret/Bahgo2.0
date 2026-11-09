package com.example.bahgo20.ml

/**
 * Short-term rain forecast: the rain sensor reading (Rain_Value) 30 minutes from now,
 * from Linear and Lasso weights trained in Python (train_rain_models.py -> RainModelWeights.kt).
 *
 * Works on the 10-bit Rain_Value scale the models were trained on: 1023 = dry, lower = wetter.
 * (The live ESP32 sensors read 0..4095; SensorTracker converts them to this scale first.)
 *
 * Honest note: from the rain sensor alone the models mostly say "rain continues". On the test
 * days they did not beat "rain stays the same" on average error (see rain_model_report.md).
 */
object RainPredictor {

    const val DRY_VALUE = 1023.0

    /** How many 5-minute steps back the features reach (30 minutes). */
    const val HISTORY_STEPS = 6

    /** Rain_Status words and cut-offs, as used everywhere in the app (match the recorded data). */
    val STATUS_NAMES = listOf("No Rain", "Light Rain", "Moderate Rain", "Heavy Rain")

    fun statusIndex(rainValue: Double): Int = when {
        rainValue >= 900 -> 0
        rainValue >= 600 -> 1
        rainValue >= 300 -> 2
        else -> 3
    }

    fun status(rainValue: Double): String = STATUS_NAMES[statusIndex(rainValue)]

    /**
     * The 5 features, in RainModelWeights.FEATURES order, from Rain_Value on a 5-minute grid
     * (oldest first) at index [i]:
     *   rain_now, rain_lag_5min, rain_lag_15min, rain_lag_30min, rain_avg_30min (mean of the last 6 readings)
     */
    fun features(rain: DoubleArray, i: Int): DoubleArray {
        require(i >= HISTORY_STEPS) { "Need $HISTORY_STEPS earlier readings" }
        var sum = 0.0
        for (k in i - 5..i) sum += rain[k]
        return doubleArrayOf(rain[i], rain[i - 1], rain[i - 3], rain[i - 6], sum / 6)
    }

    /** Applies one set of weights and clips to 0..1023. */
    fun apply(w: RainModelWeights.Weights, x: DoubleArray): Double {
        var y = w.intercept
        for (j in x.indices) y += w.coef[j] * x[j]
        return y.coerceIn(0.0, DRY_VALUE)
    }

    enum class Change { HEAVIER, LIGHTER, SAME }

    /** Rain expected in 30 minutes. Values are Rain_Value (10-bit). */
    data class RainForecast(
        val lassoValue: Double,
        val linearValue: Double,
        val nowValue: Double
    ) {
        val lassoStatus: String get() = status(lassoValue)
        val linearStatus: String get() = status(linearValue)
        val nowStatus: String get() = status(nowValue)

        /** Compared with the rain now, by Rain_Status word (heavier = a wetter word). */
        val change: Change
            get() {
                val now = statusIndex(nowValue)
                val next = statusIndex(lassoValue)
                return when {
                    next > now -> Change.HEAVIER
                    next < now -> Change.LIGHTER
                    else -> Change.SAME
                }
            }
    }

    /** Short word for small spaces: Dry, Light, Moderate, Heavy. */
    fun shortWord(rainValue: Double): String =
        listOf("Dry", "Light", "Moderate", "Heavy")[statusIndex(rainValue)]

    /** One plain sentence for users, from the rain now and the 30-minute forecast. */
    fun summary(f: RainForecast): String {
        val now = f.nowStatus
        val next = f.lassoStatus
        val raining = statusIndex(f.nowValue) > 0
        val willRain = statusIndex(f.lassoValue) > 0
        return when {
            !raining && !willRain -> "No rain now, and none expected in the next 30 minutes."
            !raining -> "No rain now. $next possible within 30 minutes."
            !willRain -> "$now now. Expected to stop within 30 minutes."
            f.change == Change.HEAVIER -> "$now now, getting heavier: $next expected in 30 minutes."
            f.change == Change.LIGHTER -> "$now now, easing to ${next.lowercase()} in 30 minutes."
            else -> "$now now. Expected to continue for the next 30 minutes."
        }
    }

    /**
     * Forecast for [sensorId] from Rain_Value on a 5-minute grid ending "now" (oldest first,
     * at least HISTORY_STEPS + 1 values). Null if there are no weights for this sensor.
     */
    fun forecast(sensorId: Int, rainGrid: DoubleArray): RainForecast? {
        val lin = RainModelWeights.LINEAR[sensorId] ?: return null
        val lasso = RainModelWeights.LASSO[sensorId] ?: return null
        val i = rainGrid.size - 1
        if (i < HISTORY_STEPS) return null
        val x = features(rainGrid, i)
        return RainForecast(apply(lasso, x), apply(lin, x), rainGrid[i])
    }
}
