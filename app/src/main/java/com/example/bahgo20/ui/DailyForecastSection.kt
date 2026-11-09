package com.example.bahgo20.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.example.bahgo20.R
import com.example.bahgo20.databinding.ItemDayForecastBinding
import com.example.bahgo20.weather.WeatherForecast
import com.example.bahgo20.weather.WeatherIcon
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import kotlin.math.roundToInt

/**
 * The "7-DAY FORECAST" card on the Home screen, laid out like a phone weather app:
 * day, icon + chance of rain, low, temperature bar, high.
 * Uses weather-forecast data (Open-Meteo): the street sensors can't see days ahead.
 */
class DailyForecastSection(
    private val status: TextView,
    private val daily: LinearLayout
) {
    private val inflater = LayoutInflater.from(daily.context)
    private val selectable = android.util.TypedValue().also {
        daily.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
    }.resourceId

    fun showLoading() {
        status.text = status.context.getString(R.string.home_loading_forecast)
        status.visibility = View.VISIBLE
    }

    fun showUnavailable() {
        status.text = status.context.getString(R.string.daily_unavailable)
        status.visibility = View.VISIBLE
        daily.removeAllViews()
    }

    fun show(forecast: WeatherForecast) {
        val now = LocalDateTime.now(ZONE)
        val today = now.toLocalDate()
        val days = forecast.nextDays(today, 7)
        if (days.isEmpty()) {
            showUnavailable()
            return
        }
        status.visibility = View.GONE

        daily.removeAllViews()
        val weekMin = days.minOf { it.minC }
        val weekMax = days.maxOf { it.maxC }
        val tempNow = forecast.nextHours(now, 1).firstOrNull()?.tempC
        days.forEachIndexed { i, d ->
            val row = ItemDayForecastBinding.inflate(inflater, daily, false)
            row.vDayDivider.visibility = if (i == 0) View.GONE else View.VISIBLE
            row.tvDayName.text = if (d.date == today) daily.context.getString(R.string.today)
                else d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, daily.context.resources.configuration.locales[0])
            row.ivDayIcon.setImageResource(iconRes(WeatherIcon.of(d.weatherCode, d.rainChance)))
            row.tvDayChance.text = daily.context.getString(R.string.rain_chance_format, d.rainChance)
            row.tvDayChance.visibility = if (d.rainChance >= WeatherForecast.SHOW_CHANCE) View.VISIBLE else View.GONE
            row.tvDayMin.text = degrees(d.minC)
            row.tvDayMax.text = degrees(d.maxC)
            row.barDayTemp.setRange(weekMin, weekMax, d.minC, d.maxC, if (d.date == today) tempNow else null)
            // Tap a day: hourly temperature and chance of rain for that day
            row.root.isClickable = true
            row.root.isFocusable = true
            row.root.setBackgroundResource(selectable)
            row.root.setOnClickListener { DayForecastSheet.show(daily.context, forecast, d, LocalDateTime.now(ZONE)) }
            daily.addView(row.root)
        }
    }

    private fun degrees(c: Double) = if (c.isNaN()) "--" else "${c.roundToInt()}°"

    private fun iconRes(icon: WeatherIcon) = when (icon) {
        WeatherIcon.SUN -> R.drawable.ic_weather_sun
        WeatherIcon.PARTLY_CLOUDY -> R.drawable.ic_weather_partly
        WeatherIcon.CLOUD -> R.drawable.ic_weather_cloud
        WeatherIcon.RAIN -> R.drawable.ic_weather_rain
        WeatherIcon.STORM -> R.drawable.ic_weather_storm
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Manila")
    }
}
