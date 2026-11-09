package com.example.bahgo20.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.example.bahgo20.R
import com.example.bahgo20.SensorTracker
import com.example.bahgo20.databinding.ItemHourForecastBinding
import com.example.bahgo20.ml.RainPredictor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The "Rain Forecast" card on the Conditions screen, from this street's live rain sensor only:
 *   - one plain sentence ("Light Rain now, getting heavier: Heavy Rain expected in 30 minutes.")
 *   - a strip like a weather app: 30, 20 and 10 minutes ago, Now (what the sensor read)
 *     and the forecast for 30 minutes from now (Lasso model, see RainPredictor)
 */
class RainForecastSection(
    private val summary: TextView,
    private val divider: View,
    private val strip: LinearLayout
) {
    private val context = summary.context
    private val inflater = LayoutInflater.from(context)
    private val clock = SimpleDateFormat("h:mm a", Locale.US)

    fun showOffline() {
        summary.text = context.getString(R.string.rain_offline)
        hideStrip()
    }

    fun show(tracker: SensorTracker, sensorId: Int) {
        val recent = tracker.rainRecent(sensorId)
        val forecast = recent?.let { RainPredictor.forecast(sensorId, it.values) }

        if (recent == null || forecast == null) {
            val now = tracker.rainNow(sensorId)
            if (now == null) {
                summary.text = context.getString(R.string.rain_no_readings)
                hideStrip()
                return
            }
            summary.text = context.getString(R.string.rain_forecast_getting_ready,
                StatusText.rainStatus(context, RainPredictor.statusIndex(now)), tracker.rainMinutesLeft(sensorId))
            strip.removeAllViews()
            addItem(context.getString(R.string.now), now, null)
            showStrip()
            return
        }

        summary.text = StatusText.rainSummary(context, forecast)
        strip.removeAllViews()
        val v = recent.values                       // 7 values: 30, 25, 20, 15, 10, 5 min ago, now
        val step = 5 * 60_000L
        for (k in listOf(0, 2, 4)) {
            val stepsAgo = v.size - 1 - k
            addItem(clock.format(Date(recent.endMs - stepsAgo * step)), v[k], null)
        }
        addItem(context.getString(R.string.now), v.last(), null)
        addItem(clock.format(Date(recent.endMs + 6 * step)), forecast.lassoValue, context.getString(R.string.in_30_min))
        showStrip()
    }

    private fun addItem(label: String, rainValue: Double, note: String?) {
        val item = ItemHourForecastBinding.inflate(inflater, strip, false)
        item.tvHourTime.text = label
        item.ivHourIcon.setImageResource(iconFor(rainValue))
        item.tvHourChance.text = note.orEmpty()
        item.tvHourTemp.text = StatusText.rainShort(context, rainValue)
        item.tvHourTemp.textSize = 13f
        // Spread the items evenly across the card
        item.root.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        strip.addView(item.root)
    }

    private fun iconFor(rainValue: Double) = when (RainPredictor.statusIndex(rainValue)) {
        0 -> R.drawable.ic_weather_cloud
        1 -> R.drawable.ic_rain_light
        2 -> R.drawable.ic_weather_rain
        else -> R.drawable.ic_rain_heavy
    }

    private fun showStrip() {
        divider.visibility = View.VISIBLE
        strip.visibility = View.VISIBLE
    }

    private fun hideStrip() {
        divider.visibility = View.GONE
        strip.visibility = View.GONE
        strip.removeAllViews()
    }
}
