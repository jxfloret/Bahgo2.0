package com.example.bahgo20.ui

import android.content.Context
import com.example.bahgo20.LevelTrend
import com.example.bahgo20.R
import com.example.bahgo20.Risk
import com.example.bahgo20.SensorTracker
import com.example.bahgo20.TrendState
import com.example.bahgo20.ml.RainPredictor
import com.google.firebase.database.DataSnapshot
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Text for the Rise Rate and Rain boxes, shared by Home and Conditions so they always match,
 * plus the words for risk, flood risk and rain in the app's language.
 */
object StatusText {

    /** Rise Rate box: (big value, small line). [trend] null = offline. */
    fun rise(context: Context, trend: LevelTrend?): Pair<String, String> {
        if (trend == null) return "--" to context.getString(R.string.home_no_live_data)
        return when (trend.state) {
            TrendState.COLLECTING -> context.getString(R.string.rise_measuring) to context.getString(R.string.rise_needs_readings)
            TrendState.STEADY -> context.getString(R.string.rise_steady) to context.getString(R.string.rise_not_changing)
            TrendState.FALLING -> "-${number(abs(trend.cmPerHour))} cm/hr" to context.getString(R.string.rise_going_down)
            TrendState.RISING -> {
                val eta = trend.etaMinutes
                val target = trend.etaTo
                "+${number(trend.cmPerHour)} cm/hr" to
                        if (eta != null && target != null) context.getString(R.string.rise_reach_in, riskLabel(context, target), eta(context, eta))
                        else context.getString(R.string.rise_rising)
            }
        }
    }

    /** Rain box value: the sensor's Rain_Status (e.g. "Heavy Rain"), or an older numeric field. */
    fun rain(context: Context, snapshot: DataSnapshot?): String {
        if (snapshot == null) return "--"
        val status = snapshot.child("Rain_Status").value?.toString()?.trim()
        if (!status.isNullOrEmpty()) return rainStatus(context, status)
        for (key in listOf("precipitation", "precipitationMmPerHr", "rain", "rainfall")) {
            val raw = snapshot.child(key).value?.toString()?.trim() ?: continue
            if (raw.isEmpty()) continue
            val n = raw.toDoubleOrNull()
            return if (n != null) "${number(n)} mm/hr" else raw
        }
        return "--"
    }

    /** Lower-case risk word used on the cards ("safe", "warning", ...). */
    fun riskLabel(context: Context, risk: Risk): String = context.getString(
        when (risk) {
            Risk.SAFE -> R.string.status_label_safe
            Risk.MEDIUM -> R.string.status_label_warning
            Risk.CRITICAL -> R.string.status_label_dangerous
            Risk.OFFLINE -> R.string.status_offline
        }
    )

    /** Capitalized risk word for sentences ("Safe", "Warning", "Critical", "Offline"). */
    fun riskName(context: Context, risk: Risk): String = context.getString(
        when (risk) {
            Risk.SAFE -> R.string.risk_safe
            Risk.MEDIUM -> R.string.risk_warning
            Risk.CRITICAL -> R.string.risk_critical
            Risk.OFFLINE -> R.string.risk_offline
        }
    )

    /** Flood risk word for a Lasso score: Low, Warning or Critical (see SensorTracker.scoreLabel). */
    fun scoreLabel(context: Context, score: Double): String = context.getString(
        when {
            score >= SensorTracker.SCORE_HIGH -> R.string.risk_critical
            score >= SensorTracker.SCORE_MEDIUM -> R.string.risk_warning
            else -> R.string.flood_risk_low
        }
    )

    /** The sensor's rain words ("No Rain", "Light Rain", ...) in the app's language. */
    fun rainStatus(context: Context, raw: String): String {
        val i = RainPredictor.STATUS_NAMES.indexOfFirst { it.equals(raw.trim(), ignoreCase = true) }
        return if (i < 0) raw else rainStatus(context, i)
    }

    fun rainStatus(context: Context, index: Int): String =
        context.resources.getStringArray(R.array.rain_status_names)[index.coerceIn(0, 3)]

    /** Short rain word for small spaces: Dry, Light, Moderate, Heavy. */
    fun rainShort(context: Context, rainValue: Double): String =
        context.resources.getStringArray(R.array.rain_short_names)[RainPredictor.statusIndex(rainValue)]

    /** One plain sentence from the rain now and the 30-minute forecast (see RainPredictor.summary). */
    fun rainSummary(context: Context, f: RainPredictor.RainForecast): String {
        val nowI = RainPredictor.statusIndex(f.nowValue)
        val nextI = RainPredictor.statusIndex(f.lassoValue)
        val now = rainStatus(context, nowI)
        val next = rainStatus(context, nextI)
        return when {
            nowI == 0 && nextI == 0 -> context.getString(R.string.rain_sum_none)
            nowI == 0 -> context.getString(R.string.rain_sum_starting, next)
            nextI == 0 -> context.getString(R.string.rain_sum_stopping, now)
            f.change == RainPredictor.Change.HEAVIER -> context.getString(R.string.rain_sum_heavier, now, next)
            f.change == RainPredictor.Change.LIGHTER -> context.getString(R.string.rain_sum_lighter, now, next.lowercase())
            else -> context.getString(R.string.rain_sum_same, now)
        }
    }

    private fun eta(context: Context, minutes: Double): String {
        if (minutes < 1) return context.getString(R.string.eta_under_minute)
        val total = minutes.roundToInt()
        val h = total / 60
        val m = total % 60
        return when {
            h == 0 -> context.getString(R.string.eta_min, m)
            m == 0 -> context.getString(R.string.eta_hr, h)
            else -> context.getString(R.string.eta_hr_min, h, m)
        }
    }

    private fun number(n: Double): String =
        if (n % 1.0 == 0.0) n.toInt().toString() else String.format(Locale.US, "%.1f", n)
}
