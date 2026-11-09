package com.example.bahgo20

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.bahgo20.chart.LiveHistoryStore
import com.example.bahgo20.ml.FloodFeatures
import com.example.bahgo20.ml.FloodForecaster
import com.example.bahgo20.ml.Forecast
import com.example.bahgo20.ml.LiveGrid
import com.example.bahgo20.ml.RainPredictor
import com.google.firebase.FirebaseApp
import com.google.firebase.database.DataSnapshot
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

enum class Risk { SAFE, MEDIUM, CRITICAL, OFFLINE }

data class SensorReading(
    val level: Double?,     // water level in cm (null = no data)
    val risk: Risk,
    val updatedMs: Long?    // when the device last updated (epoch millis), if known
)

enum class TrendState { COLLECTING, STEADY, RISING, FALLING }

/** How fast the water level is changing, worked out from the recent readings. */
data class LevelTrend(
    val state: TrendState,
    val cmPerHour: Double = 0.0,     // positive = rising, negative = falling
    val etaMinutes: Double? = null,  // minutes until the next level, if rising
    val etaTo: Risk? = null          // which level that is (MEDIUM or CRITICAL)
)

/** One water-level reading at a point in time. rain = intensity 0..1, NaN if the device didn't send it. */
private class Sample(var timeMs: Long, var level: Double, var rain: Double = Double.NaN)

/**
 * Recent readings per sensor. Shared by the whole app, so the Conditions screen
 * can use what the Home screen has already collected.
 *
 * The readings are also saved on the phone (SharedPreferences), so the rate of rise and
 * the forecast are ready right away when the app is opened again within [KEEP_MS] of closing.
 * Older readings are dropped: after a long break they no longer describe the water now.
 */
private object LevelHistory {
    private const val TAG = "LevelHistory"
    private const val PREFS_NAME = "bahgo_level_history"
    // 90 min: the forecast needs 55 min of history, plus 30 min more to check the forecast made back then
    private const val KEEP_MS = 90 * 60 * 1000L      // forget readings older than this
    private const val MAX_SAMPLES = 1500             // safety cap per sensor (devices write about every 5 s)
    private const val SAVE_EVERY_MS = 15_000L        // don't write to storage more often than this

    private val samples = mutableMapOf<Int, MutableList<Sample>>()
    private val lastSavedAt = mutableMapOf<Int, Long>()
    private var prefs: SharedPreferences? = null
    private var prefsReady = false

    /** App context from Firebase (it is always set up before any screen opens). */
    private fun prefs(): SharedPreferences? {
        if (!prefsReady) {
            prefsReady = true
            prefs = try {
                FirebaseApp.getInstance().applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            } catch (e: Exception) {
                Log.e(TAG, "Saving readings is not available", e)
                null
            }
        }
        return prefs
    }

    private fun listFor(id: Int): MutableList<Sample> = samples.getOrPut(id) { load(id) }

    fun record(id: Int, timeMs: Long, level: Double, rain: Double = Double.NaN) {
        val list = listFor(id)
        val last = list.lastOrNull()
        when {
            last == null -> list.add(Sample(timeMs, level, rain))
            timeMs < last.timeMs - 1000 -> {          // clock went backwards: start over
                list.clear()
                list.add(Sample(timeMs, level, rain))
            }
            timeMs - last.timeMs < 1000 -> {          // same update delivered twice
                last.timeMs = maxOf(last.timeMs, timeMs)
                last.level = level
                last.rain = rain
            }
            else -> list.add(Sample(timeMs, level, rain))
        }

        val cutoff = list.last().timeMs - KEEP_MS
        list.removeAll { it.timeMs < cutoff }
        while (list.size > MAX_SAMPLES) list.removeAt(0)

        saveIfDue(id)
    }

    /** Readings from the last [windowMs] before the newest one. */
    fun recent(id: Int, windowMs: Long): List<Sample> {
        val list = listFor(id)
        val last = list.lastOrNull() ?: return emptyList()
        val from = last.timeMs - windowMs
        return list.filter { it.timeMs >= from }
    }

    // ───────────── Saving and loading ─────────────
    // Stored as one text per sensor: "timeMs:level:rain,..." (older saves have no ":rain")

    /** Writes every sensor's readings now (call when a screen closes, so the last seconds aren't lost). */
    fun saveAll() {
        for (id in samples.keys.toList()) saveIfDue(id, force = true)
    }

    private fun saveIfDue(id: Int, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - (lastSavedAt[id] ?: 0L) < SAVE_EVERY_MS) return
        val list = samples[id] ?: return
        val text = list.joinToString(",") { "${it.timeMs}:${it.level}:${it.rain}" }
        prefs()?.edit()?.putString("s$id", text)?.apply()
        lastSavedAt[id] = now
    }

    private fun load(id: Int): MutableList<Sample> {
        val list = mutableListOf<Sample>()
        val text = prefs()?.getString("s$id", null) ?: return list
        val cutoff = System.currentTimeMillis() - KEEP_MS   // drop anything older than KEEP_MS
        for (part in text.split(',')) {
            val bits = part.split(':')
            if (bits.size != 2 && bits.size != 3) continue
            val time = bits[0].toLongOrNull() ?: continue
            val level = bits[1].toDoubleOrNull() ?: continue
            val rain = bits.getOrNull(2)?.toDoubleOrNull() ?: Double.NaN
            if (time >= cutoff) list.add(Sample(time, level, rain))
        }
        list.sortBy { it.timeMs }
        Log.d(TAG, "Loaded ${list.size} saved readings for sensor $id")
        return list
    }
}

/**
 * Turns Firebase snapshots into a SensorReading and decides online/offline from Last_Updated.
 *
 * The ultrasonic sensor looks down at the water: a LOWER Distance_cm means the water is
 * closer, which is MORE dangerous. A HIGHER Distance_cm is safe.
 *   water level = SENSOR_HEIGHT_CM - Distance_cm
 *
 * With the device 94 cm tall (37 inches):
 *   SAFE      water up to 30 cm      -> Distance_cm 64 or more
 *   MEDIUM    water 30 to 60 cm      -> Distance_cm 34 up to 64
 *   CRITICAL  water above 60 cm      -> Distance_cm below 34
 *
 * A device is ONLINE if:
 *   - its Last_Updated is a readable time that is less than OFFLINE_AFTER_MS old, or
 *   - the app has seen its Last_Updated value change less than OFFLINE_AFTER_MS ago
 *     (this covers formats we can't read, and phone/device clock or timezone differences).
 */
class SensorTracker {

    companion object {
        // Device height: 37 inches = 94 cm. This is the Distance_cm the sensor reads on a DRY street.
        // Keep identical to SENSOR_HEIGHT_CM in functions/index.js.
        const val SENSOR_HEIGHT_CM = 94.0

        // Risk bands on the water level (same as the ankle / knee / waist guide in Settings).
        // Keep identical to MEDIUM_CM / CRITICAL_CM in functions/index.js.
        const val MEDIUM_CM = 30.0      // above this: MEDIUM (knee level)
        const val CRITICAL_CM = 60.0    // above this: CRITICAL (waist level and up)

        // "% of threshold" on the cards: 100% means the start of CRITICAL
        const val THRESHOLD_CM = CRITICAL_CM

        // Rain sensor on the ESP32: analogRead gives 0..4095, 4095 = completely dry
        const val RAIN_ADC_MAX = 4095.0

        // Offline if Last_Updated is older than this. Tune to how often your devices write.
        const val OFFLINE_AFTER_MS = 2 * 60 * 1000L

        // Rate of rise: looks at the last 15 minutes of readings
        const val TREND_WINDOW_MS = 15 * 60 * 1000L
        const val TREND_MIN_SAMPLES = 4             // need at least this many readings
        const val TREND_MIN_SPAN_MS = 3 * 60 * 1000L // ...spread over at least this long
        const val STEADY_CM_PER_HR = 3.0            // changes smaller than this count as "steady"
        const val TREND_MAX_ETA_MIN = 240.0         // don't predict further than 4 hours ahead

        /**
         * Rate of rise from readings (oldest first). Used for live sensors and for the recorded data.
         */
        fun trendOf(times: LongArray, levels: DoubleArray): LevelTrend {
            val n = times.size
            if (n < TREND_MIN_SAMPLES || times[n - 1] - times[0] < TREND_MIN_SPAN_MS) {
                return LevelTrend(TrendState.COLLECTING)
            }

            // Least-squares line: level (cm) against time (hours)
            val t0 = times[0]
            val xs = DoubleArray(n) { (times[it] - t0) / 3_600_000.0 }
            val ys = levels
            val xMean = xs.average()
            val yMean = ys.average()

            var sxx = 0.0
            var sxy = 0.0
            for (i in 0 until n) {
                val dx = xs[i] - xMean
                sxx += dx * dx
                sxy += dx * (ys[i] - yMean)
            }
            if (sxx <= 0.0) return LevelTrend(TrendState.COLLECTING)

            val slope = sxy / sxx                  // cm per hour
            val intercept = yMean - slope * xMean

            // How much the readings scatter around the line -> uncertainty of the slope
            var sse = 0.0
            for (i in 0 until n) {
                val r = ys[i] - (intercept + slope * xs[i])
                sse += r * r
            }
            val slopeError = sqrt(sse / (n - 2) / sxx)

            if (abs(slope) < maxOf(STEADY_CM_PER_HR, 2 * slopeError)) {
                return LevelTrend(TrendState.STEADY)
            }
            if (slope < 0) return LevelTrend(TrendState.FALLING, slope)

            // Rising: predict how long until the next level if the rate stays the same
            val level = levels[n - 1]
            var eta: Double? = null
            var etaTo: Risk? = null
            if (level <= MEDIUM_CM) {
                etaTo = Risk.MEDIUM
                eta = (MEDIUM_CM - level) / slope * 60
            } else if (level <= CRITICAL_CM) {
                etaTo = Risk.CRITICAL
                eta = (CRITICAL_CM - level) / slope * 60
            }
            if (eta != null && eta > TREND_MAX_ETA_MIN) {
                eta = null
                etaTo = null
            }
            return LevelTrend(TrendState.RISING, slope, eta, etaTo)
        }

        /**
         * Flood risk score bands (score = % of the critical level expected in the next 30 min):
         * below 50 Low (under the Warning level), 50-89 Warning, 90 and up Critical (near the critical level).
         * Users only see the word, not the number.
         */
        const val SCORE_MEDIUM = 50.0
        const val SCORE_HIGH = 90.0

        fun scoreLabel(score: Double): String = when {
            score >= SCORE_HIGH -> "Critical"
            score >= SCORE_MEDIUM -> "Warning"
            else -> "Low"
        }

        /** Color band for a score, using the same colors as the water level risk. */
        fun riskForScore(score: Double): Risk = when {
            score >= SCORE_HIGH -> Risk.CRITICAL
            score >= SCORE_MEDIUM -> Risk.MEDIUM
            else -> Risk.SAFE
        }

        /** Risk band for a water level (same bands as reading()). */
        fun riskForLevel(level: Double): Risk = when {
            level > CRITICAL_CM -> Risk.CRITICAL
            level > MEDIUM_CM -> Risk.MEDIUM
            else -> Risk.SAFE
        }

        // Field that holds the reading inside each SensorDataN node, in this order
        private val LEVEL_KEYS = listOf("Distance_cm", "waterLevel", "level")

        // Field names checked for the device's last update time, in this order
        private val UPDATED_KEYS = listOf(
            "Last_Updated", "lastUpdated", "timestamp", "updatedAt", "time", "lastSeen"
        )

        private val DATE_PATTERNS = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd hh:mm:ss a",
            "yyyy/MM/dd HH:mm:ss",
            "dd-MM-yyyy HH:mm:ss",
            "dd/MM/yyyy HH:mm:ss",
            "MM/dd/yyyy HH:mm:ss",
            "MM/dd/yyyy h:mm:ss a",
            "MMM d, yyyy h:mm:ss a"
        )

        private val TIME_PATTERNS = listOf("HH:mm:ss", "H:mm:ss", "h:mm:ss a", "HH:mm")

        // Shared time parser: Last_Updated of live readings, and the recorded data for the chart
        /** Epoch seconds/millis, a date-time text like "2026-10-28T09:44:47+08:00", or a time of day like "15:44:12". */
        fun parseTimeMs(raw: String?): Long? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null

            text.toDoubleOrNull()?.let { n ->
                val v = n.toLong()
                return when {
                    v >= 100_000_000_000L -> v          // millis
                    v >= 1_000_000_000L -> v * 1000     // seconds
                    else -> null                        // not a Unix time (e.g. millis since boot)
                }
            }

            parseWith(DATE_PATTERNS, text)?.let { return it }

            // Time of day only: assume today (or yesterday if that would be in the future)
            val timeOnly = parseWith(TIME_PATTERNS, text) ?: return null
            val t = Calendar.getInstance().apply { time = Date(timeOnly) }
            val today = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, t.get(Calendar.HOUR_OF_DAY))
                set(Calendar.MINUTE, t.get(Calendar.MINUTE))
                set(Calendar.SECOND, t.get(Calendar.SECOND))
                set(Calendar.MILLISECOND, 0)
            }
            var ms = today.timeInMillis
            if (ms > System.currentTimeMillis() + 5 * 60 * 1000L) ms -= 24 * 60 * 60 * 1000L
            return ms
        }

        private fun parseWith(patterns: List<String>, text: String): Long? {
            for (pattern in patterns) {
                val format = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
                val pos = ParsePosition(0)
                val date = format.parse(text, pos)
                if (date != null && pos.index == text.length) return date.time
            }
            return null
        }
    }

    private class Seen(var raw: String, var changedAtMs: Long?)

    private val snapshots = mutableMapOf<Int, DataSnapshot>()
    private val seen = mutableMapOf<Int, Seen>()
    private var rootUpdated: DataSnapshot? = null

    // ───────────────────────── Feed it Firebase data ─────────────────────────

    fun snapshot(id: Int): DataSnapshot? = snapshots[id]

    fun setSensor(id: Int, snapshot: DataSnapshot) {
        snapshots[id] = snapshot
        trackChanges()

        // Remember this reading so the rate of rise can be worked out
        levelOf(snapshot)?.let { level ->
            val time = parseTimeMs(updatedRaw(id)) ?: System.currentTimeMillis()
            LevelHistory.record(id, time, level, rainOf(snapshot))
            LiveHistoryStore.record(id, time, level)   // 3-day history for the chart
        }
    }

    fun clearSensor(id: Int) {
        snapshots.remove(id)
        seen.remove(id)
    }

    /** The root-level /Last_Updated node (one value for all devices, or one child per device). */
    fun setRootUpdated(snapshot: DataSnapshot?) {
        rootUpdated = snapshot
        trackChanges()
    }

    // ───────────────────────── Reading ─────────────────────────

    fun reading(id: Int): SensorReading {
        val snapshot = snapshots[id]
        if (snapshot == null || !snapshot.exists() || snapshot.value == null) {
            return SensorReading(null, Risk.OFFLINE, null)
        }

        val level = levelOf(snapshot)
        val statusText = snapshot.child("status").value?.toString()?.trim()?.uppercase().orEmpty()

        val updated = updatedMs(id)
        val stale = when {
            updated != null -> System.currentTimeMillis() - updated > OFFLINE_AFTER_MS
            updatedRaw(id) != null -> true   // has Last_Updated but we can't read it yet: wait for its first change
            else -> false                    // no Last_Updated anywhere: judge by the level only
        }

        if (level == null || stale || statusText.contains("OFFLINE")) {
            return SensorReading(level, Risk.OFFLINE, updated)
        }

        val byLevel = when {
            level > CRITICAL_CM -> Risk.CRITICAL
            level > MEDIUM_CM -> Risk.MEDIUM
            else -> Risk.SAFE
        }
        val byStatus = when {
            "CRITICAL" in statusText || "DANGER" in statusText -> Risk.CRITICAL
            "HIGH" in statusText || "MEDIUM" in statusText ||
                    "WARNING" in statusText || "CAUTION" in statusText -> Risk.MEDIUM
            else -> Risk.SAFE
        }
        // Take the more severe of the two
        val risk = if (byStatus.ordinal > byLevel.ordinal) byStatus else byLevel
        return SensorReading(level, risk, updated)
    }

    /** Water level in cm from a sensor node, or null if there is no usable reading. */
    private fun levelOf(snapshot: DataSnapshot): Double? {
        // Find the reading: Distance_cm (preferred), waterLevel, level, or a bare number
        val levelKey = LEVEL_KEYS.firstOrNull { snapshot.hasChild(it) }
        val rawLevel: Any? = when {
            levelKey != null -> snapshot.child(levelKey).value
            snapshot.hasChildren() -> null
            else -> snapshot.value
        }
        val number = rawLevel?.toString()?.trim()?.toDoubleOrNull() ?: return null
        return if (levelKey == "Distance_cm") distanceToLevel(number) else number.takeIf { it >= 0 }
    }

    /** The newest rain intensity 0..1 for a sensor (0 = dry), or null if it sent no rain value. */
    fun rainIntensity(id: Int): Double? {
        val snap = snapshots[id] ?: return null
        return rainOf(snap).takeIf { !it.isNaN() }
    }

    /**
     * Rain intensity 0..1 (0 = dry). The ESP32 rain sensor reads 0..4095 and 4095 means dry.
     * Falls back to Rain_Status text if there is no Rain_Value. NaN if neither is there.
     */
    private fun rainOf(snapshot: DataSnapshot): Double {
        snapshot.child("Rain_Value").value?.toString()?.trim()?.toDoubleOrNull()?.let { v ->
            return ((RAIN_ADC_MAX - v) / RAIN_ADC_MAX).coerceIn(0.0, 1.0)
        }
        val status = snapshot.child("Rain_Status").value?.toString()?.uppercase() ?: return Double.NaN
        return when {
            "HEAVY" in status -> 0.9
            "MODERATE" in status -> 0.53
            "LIGHT" in status -> 0.25
            else -> 0.03
        }
    }

    /** Distance from the sensor to the water -> water level. Lower distance = more water. */
    private fun distanceToLevel(distance: Double): Double? {
        if (distance <= 0) return null   // invalid echo, not a real reading
        return maxOf(0.0, SENSOR_HEIGHT_CM - distance)
    }

    // ───────────────────────── Rate of rise ─────────────────────────

    /**
     * How fast the water level is changing, from a best-fit line through the recent readings.
     * Small wobbles of the sensor are ignored: a rise only counts if it is clearly
     * bigger than the noise in the readings.
     */
    fun trend(id: Int): LevelTrend {
        val pts = LevelHistory.recent(id, TREND_WINDOW_MS)
        return trendOf(
            LongArray(pts.size) { pts[it].timeMs },
            DoubleArray(pts.size) { pts[it].level }
        )
    }

    // ───────────────────────── Forecast (Linear + Lasso) ─────────────────────────

    /** Saves the recent readings to the phone right away. */
    fun saveHistory() {
        LevelHistory.saveAll()
        LiveHistoryStore.saveAll()
    }

    /** Minutes of readings collected so far for this sensor. */
    fun historyMinutes(id: Int): Int {
        val pts = LevelHistory.recent(id, Long.MAX_VALUE)
        if (pts.size < 2) return 0
        return ((pts.last().timeMs - pts.first().timeMs) / 60_000L).toInt()
    }

    /**
     * Expected level in 30 min. Uses the [full] model once there are about 55 minutes of
     * readings; before that the [early] model (5 minutes of readings), marked Forecast.early.
     * Null if there isn't enough for either yet.
     */
    fun forecast(id: Int, full: FloodForecaster, early: FloodForecaster?): Forecast? {
        val pts = LevelHistory.recent(id, Long.MAX_VALUE)
        if (pts.isEmpty()) return null
        return forecastAt(pts, pts.last().timeMs, full, early)
    }

    /** The forecast the models would have made 30 min ago (full or early), and the level now. */
    fun forecastCheck(id: Int, full: FloodForecaster, early: FloodForecaster?): Pair<Forecast, Double>? {
        val pts = LevelHistory.recent(id, Long.MAX_VALUE)
        if (pts.isEmpty()) return null
        val end = pts.last().timeMs
        val back = FloodFeatures.HORIZON_STEPS * FloodFeatures.STEP_MIN * 60_000L
        val past = forecastAt(pts, end - back, full, early) ?: return null
        return past to pts.last().level
    }

    private fun forecastAt(pts: List<Sample>, endMs: Long, full: FloodForecaster, early: FloodForecaster?): Forecast? {
        val times = LongArray(pts.size) { pts[it].timeMs }
        val levels = DoubleArray(pts.size) { pts[it].level }
        val rain = DoubleArray(pts.size) { pts[it].rain }
        for (model in listOfNotNull(full, early)) {
            val steps = model.features.historySteps
            val grid = LiveGrid.resample(times, levels, rain, endMs, steps) ?: continue
            return model.predict(grid.levels, grid.rain, steps)
        }
        return null
    }

    /**
     * Rain expected in 30 min, from RainPredictor. Needs about 30 minutes of rain readings;
     * null until then (or if this sensor sends no rain value).
     *
     * Saved readings keep rain as intensity 0..1, worked out from the ESP32's 0..4095 Rain_Value
     * (see rainOf). That converts back exactly to the 10-bit scale the rain models use:
     *   Rain_Value (0..1023) = 1023 * (1 - intensity)   (= the ESP32 value * 1023 / 4095)
     */
    fun rainForecast(id: Int): RainPredictor.RainForecast? {
        val recent = rainRecent(id) ?: return null
        return RainPredictor.forecast(id, recent.values)
    }

    /** Rain_Value (0..1023) on 5-minute steps for the last 30 minutes, oldest first. */
    class RainRecent(val endMs: Long, val values: DoubleArray)

    /** The last 30 minutes of rain on 5-minute steps, or null until there are enough readings. */
    fun rainRecent(id: Int): RainRecent? {
        val pts = LevelHistory.recent(id, Long.MAX_VALUE)
        if (pts.isEmpty()) return null
        val times = LongArray(pts.size) { pts[it].timeMs }
        val rain10 = DoubleArray(pts.size) { rain10(pts[it].rain) }
        // Rain goes in the "levels" slot so missing rain makes the grid fail (instead of counting as dry)
        val grid = LiveGrid.resample(times, rain10, rain10, pts.last().timeMs, RainPredictor.HISTORY_STEPS)
            ?: return null
        return RainRecent(pts.last().timeMs, grid.levels)
    }

    /** The newest rain reading as Rain_Value (0..1023), or null if this sensor sent none. */
    fun rainNow(id: Int): Double? {
        val last = LevelHistory.recent(id, 0L).lastOrNull() ?: return null
        return rain10(last.rain).takeIf { !it.isNaN() }
    }

    /** Minutes of rain readings still needed before the rain forecast can run. */
    fun rainMinutesLeft(id: Int): Int =
        maxOf(1, RainPredictor.HISTORY_STEPS * FloodFeatures.STEP_MIN - historyMinutes(id))

    private fun rain10(intensity: Double): Double =
        if (intensity.isNaN()) Double.NaN else RainPredictor.DRY_VALUE * (1 - intensity)

    // ───────────────────────── Last_Updated handling ─────────────────────────

    /** The raw Last_Updated value for a device: its own field first, then the root node. */
    private fun updatedRaw(id: Int): String? {
        val snap = snapshots[id]
        if (snap != null) {
            for (key in UPDATED_KEYS) {
                val v = snap.child(key).value
                if (v != null) return v.toString().trim()
            }
        }
        val root = rootUpdated
        if (root != null && root.exists()) {
            val v = if (root.hasChildren()) {
                root.child("Sensordata$id").value ?: root.child("$id").value
            } else {
                root.value
            }
            if (v != null) return v.toString().trim()
        }
        return null
    }

    /** Remember when Last_Updated last CHANGED, measured on this phone's clock. */
    private fun trackChanges() {
        for (id in 1..4) {
            if (!snapshots.containsKey(id)) continue
            val raw = updatedRaw(id) ?: continue
            val s = seen[id]
            if (s == null) {
                seen[id] = Seen(raw, null)   // first sighting is not proof of life
            } else if (s.raw != raw) {
                s.raw = raw
                s.changedAtMs = System.currentTimeMillis()
            }
        }
    }

    private fun updatedMs(id: Int): Long? {
        val absolute = parseTimeMs(updatedRaw(id))
        val observed = seen[id]?.changedAtMs
        return when {
            absolute == null -> observed
            observed == null -> absolute
            else -> maxOf(absolute, observed)
        }
    }

}