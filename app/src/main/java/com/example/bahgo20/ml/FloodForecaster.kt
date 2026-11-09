package com.example.bahgo20.ml

import kotlin.math.abs

/**
 * One sensor's readings on a fixed 5-minute grid (oldest first).
 *   levels: water level in cm
 *   rain:   rain intensity 0..1 (0 = dry, 1 = heaviest), from the rain sensor's Rain_Value
 */
class SensorSeries(
    val timesMs: LongArray,
    val levels: DoubleArray,
    val rain: DoubleArray
) {
    val size: Int get() = levels.size
}

/** A set of inputs the models look at, worked out from the readings up to "now" (index i). */
interface FeatureSet {
    val names: List<String>
    /** How many 5-minute steps back the oldest reading used is. */
    val historySteps: Int
    fun at(levels: DoubleArray, rain: DoubleArray, i: Int): DoubleArray
    fun hasHistory(i: Int): Boolean = i >= historySteps
}

/**
 * Lasso's inputs for the flood risk score (objective 3): water level AND rain.
 * Lags / rate of change of the level, rain now, cumulative rainfall. Needs 55 minutes of readings.
 */
object FloodFeatures : FeatureSet {
    const val STEP_MIN = 5          // the readings are 5 minutes apart
    const val HORIZON_STEPS = 6     // look 6 steps = 30 minutes ahead
    const val HISTORY_STEPS = 11    // the oldest reading used is 11 steps = 55 minutes back
    const val MAX_LEVEL_CM = 94.0   // the sensor can't read water higher than its own height

    val NAMES = listOf(
        "Water level now",
        "Change, last 5 min",
        "Change, last 15 min",
        "Change, last 30 min",
        "Rain now",
        "Average rain, last 30 min",
        "Cumulative rain, last hour",
        "Rain change, last 15 min",
        "Rain × 30-min change"
    )

    override val names: List<String> get() = NAMES
    override val historySteps: Int get() = HISTORY_STEPS

    override fun at(levels: DoubleArray, rain: DoubleArray, i: Int): DoubleArray {
        require(hasHistory(i)) { "Need $HISTORY_STEPS earlier readings" }
        val l = levels[i]
        val d30 = l - levels[i - 6]
        return doubleArrayOf(
            l,
            l - levels[i - 1],
            l - levels[i - 3],
            d30,
            rain[i],
            sum(rain, i - 5, i) / 6,
            sum(rain, i - 11, i),          // 12 readings = the last hour
            rain[i] - rain[i - 3],
            rain[i] * d30
        )
    }

    private fun sum(a: DoubleArray, from: Int, to: Int): Double {
        var s = 0.0
        for (k in from..to) s += a[k]
        return s
    }
}

/** Lasso's quick inputs (5 minutes of readings): level now, change over 5 min, rain now. */
object EarlyFeatures : FeatureSet {
    const val HISTORY_STEPS = 1

    override val names = listOf("Water level now", "Change, last 5 min", "Rain now")
    override val historySteps: Int get() = HISTORY_STEPS

    override fun at(levels: DoubleArray, rain: DoubleArray, i: Int): DoubleArray {
        require(hasHistory(i)) { "Need $HISTORY_STEPS earlier reading" }
        return doubleArrayOf(levels[i], levels[i] - levels[i - 1], rain[i])
    }
}

/**
 * Linear Regression's inputs for the water level forecast (objective 2): water level only.
 * Level now and its change over 5, 15 and 30 minutes. Needs 30 minutes of readings.
 */
object LevelFeatures : FeatureSet {
    const val HISTORY_STEPS = 6

    override val names = listOf("Water level now", "Change, last 5 min", "Change, last 15 min", "Change, last 30 min")
    override val historySteps: Int get() = HISTORY_STEPS

    override fun at(levels: DoubleArray, rain: DoubleArray, i: Int): DoubleArray {
        require(hasHistory(i)) { "Need $HISTORY_STEPS earlier readings" }
        val l = levels[i]
        return doubleArrayOf(l, l - levels[i - 1], l - levels[i - 3], l - levels[i - 6])
    }
}

/** Linear's quick inputs (5 minutes of readings): level now and change over 5 min. */
object LevelEarlyFeatures : FeatureSet {
    const val HISTORY_STEPS = 1

    override val names = listOf("Water level now", "Change, last 5 min")
    override val historySteps: Int get() = HISTORY_STEPS

    override fun at(levels: DoubleArray, rain: DoubleArray, i: Int): DoubleArray {
        require(hasHistory(i)) { "Need $HISTORY_STEPS earlier reading" }
        return doubleArrayOf(levels[i], levels[i] - levels[i - 1])
    }
}

/** What the models learn to predict. */
enum class Target {
    /** Water level in 30 minutes, in cm (learned as the change from now). */
    LEVEL,

    /**
     * Flood risk score 0..100: the highest water level in the next 30 minutes as a % of the
     * critical level (60 cm), capped at 100. 50 = Warning level, 100 = Critical.
     */
    RISK_SCORE;

    companion object {
        const val CRITICAL_CM = 60.0

        fun score(levelCm: Double): Double = (levelCm / CRITICAL_CM * 100).coerceIn(0.0, 100.0)
    }
}

/**
 * Prediction from both models. Units depend on the target:
 * cm for Target.LEVEL, 0..100 for Target.RISK_SCORE. early = from the quick 5-minute inputs.
 */
data class Forecast(
    val lasso: Double,
    val linear: Double,
    val horizonMin: Int,
    val early: Boolean = false
)

/** How well the models did on days they were never trained on (cm, or score points). */
data class ForecastReport(
    val alpha: Double,
    val lassoKept: List<String>,
    val lassoDropped: List<String>,
    val trainRows: Int,
    val testRows: Int,
    val lasso: ErrorStats,          // all held-out readings
    val linear: ErrorStats,
    val noChange: ErrorStats,       // baseline: "stays where it is now"
    val lassoFlood: ErrorStats,     // only moments where the water moved more than 2 cm in 30 min
    val linearFlood: ErrorStats,
    val noChangeFlood: ErrorStats
) {
    val inputCount: Int get() = lassoKept.size + lassoDropped.size
}

/**
 * Trains Linear and Lasso on the same inputs and target. The app uses
 *   - Linear on LevelFeatures with Target.LEVEL       -> water level forecast (objective 2)
 *   - Lasso  on FloodFeatures with Target.RISK_SCORE  -> flood risk score (objective 3)
 * The other model of each pair is trained too, for comparison in the test report.
 */
class FloodForecaster(
    val lasso: RegressionModel,
    val linear: RegressionModel,
    val report: ForecastReport,
    val features: FeatureSet = FloodFeatures,
    val target: Target = Target.LEVEL
) {
    val isEarly: Boolean get() = features.historySteps == 1

    fun predict(levels: DoubleArray, rain: DoubleArray, i: Int): Forecast {
        val x = features.at(levels, rain, i)
        return Forecast(
            lasso = output(levels[i], lasso.predict(x)),
            linear = output(levels[i], linear.predict(x)),
            horizonMin = FloodFeatures.HORIZON_STEPS * FloodFeatures.STEP_MIN,
            early = isEarly
        )
    }

    private fun output(levelNow: Double, raw: Double): Double = when (target) {
        Target.LEVEL -> (levelNow + raw).coerceIn(0.0, FloodFeatures.MAX_LEVEL_CM)
        Target.RISK_SCORE -> raw.coerceIn(0.0, 100.0)
    }

    companion object {
        val DEFAULT_ALPHAS = doubleArrayOf(0.001, 0.003, 0.01, 0.03, 0.1, 0.3)

        /** What the model learns at index i: the level change, or the risk score. */
        fun targetAt(s: SensorSeries, i: Int, target: Target): Double = when (target) {
            Target.LEVEL -> s.levels[i + FloodFeatures.HORIZON_STEPS] - s.levels[i]
            Target.RISK_SCORE -> {
                var peak = s.levels[i + 1]
                for (k in i + 2..i + FloodFeatures.HORIZON_STEPS) peak = maxOf(peak, s.levels[k])
                Target.score(peak)
            }
        }

        /** "Stays where it is now" in the target's units. */
        private fun baselineAt(s: SensorSeries, i: Int, target: Target): Double = when (target) {
            Target.LEVEL -> 0.0
            Target.RISK_SCORE -> Target.score(s.levels[i])
        }

        private class Rows(
            val x: MutableList<DoubleArray> = mutableListOf(),
            val y: MutableList<Double> = mutableListOf(),
            val base: MutableList<Double> = mutableListOf()
        ) {
            fun yArray() = y.toDoubleArray()
        }

        /**
         * Every usable (inputs, target) pair whose time is in [fromMs, untilMs).
         * Rows start at the longest history for every input set, so all models are trained
         * and scored on exactly the same moments.
         */
        private fun rows(series: List<SensorSeries>, fromMs: Long, untilMs: Long, features: FeatureSet, target: Target): Rows {
            val out = Rows()
            for (s in series) {
                for (i in FloodFeatures.HISTORY_STEPS until s.size - FloodFeatures.HORIZON_STEPS) {
                    val t = s.timesMs[i]
                    if (t < fromMs || t >= untilMs) continue
                    out.x.add(features.at(s.levels, s.rain, i))
                    out.y.add(targetAt(s, i, target))
                    out.base.add(baselineAt(s, i, target))
                }
            }
            return out
        }

        /**
         * Trains on readings before [testFromMs] and tests on readings from then on.
         *
         * Lasso's alpha (how hard it pushes inputs to 0) is picked on a validation stretch
         * [validateFromMs, testFromMs): we take the LARGEST alpha whose error is within 2% of
         * the best one, which gives a simpler model for almost the same accuracy.
         */
        fun train(
            series: List<SensorSeries>,
            validateFromMs: Long,
            testFromMs: Long,
            alphas: DoubleArray = DEFAULT_ALPHAS,
            features: FeatureSet = FloodFeatures,
            target: Target = Target.LEVEL
        ): FloodForecaster {
            // 1) Pick alpha
            val fit = rows(series, Long.MIN_VALUE, validateFromMs, features, target)
            val valid = rows(series, validateFromMs, testFromMs, features, target)
            var alpha = alphas.first()
            if (fit.x.isNotEmpty() && valid.x.isNotEmpty()) {
                val validY = valid.yArray()
                val maes = alphas.map { a ->
                    val m = LassoRegression.fit(fit.x, fit.yArray(), a)
                    errorStats(DoubleArray(valid.x.size) { m.predict(valid.x[it]) }, validY).mae
                }
                val best = maes.min()
                alpha = alphas.indices.filter { maes[it] <= best * 1.02 }.maxOf { alphas[it] }
            }

            // 2) Train both models on everything before the test days
            val train = rows(series, Long.MIN_VALUE, testFromMs, features, target)
            val trainY = train.yArray()
            val lasso = LassoRegression.fit(train.x, trainY, alpha)
            val linear = LinearRegression.fit(train.x, trainY)

            // 3) Score on the held-out test days
            val test = rows(series, testFromMs, Long.MAX_VALUE, features, target)
            val actual = test.yArray()
            val clip = if (target == Target.RISK_SCORE) 0.0..100.0 else -1e9..1e9
            val pLasso = DoubleArray(test.x.size) { lasso.predict(test.x[it]).coerceIn(clip) }
            val pLinear = DoubleArray(test.x.size) { linear.predict(test.x[it]).coerceIn(clip) }
            val base = test.base.toDoubleArray()
            // "Water moving": more than 2 cm away from now (2 cm = 3.3 score points)
            val moving = if (target == Target.RISK_SCORE) 2.0 / Target.CRITICAL_CM * 100 else 2.0
            val flood = actual.indices.filter { abs(actual[it] - base[it]) > moving }
            fun pick(a: DoubleArray) = DoubleArray(flood.size) { a[flood[it]] }

            val kept = lasso.usedFeatures()
            val report = ForecastReport(
                alpha = alpha,
                lassoKept = kept.map { features.names[it] },
                lassoDropped = features.names.indices.filter { it !in kept }.map { features.names[it] },
                trainRows = train.x.size,
                testRows = test.x.size,
                lasso = errorStats(pLasso, actual),
                linear = errorStats(pLinear, actual),
                noChange = errorStats(base, actual),
                lassoFlood = errorStats(pick(pLasso), pick(actual)),
                linearFlood = errorStats(pick(pLinear), pick(actual)),
                noChangeFlood = errorStats(pick(base), pick(actual))
            )
            return FloodForecaster(lasso, linear, report, features, target)
        }
    }
}
