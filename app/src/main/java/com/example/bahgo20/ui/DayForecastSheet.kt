package com.example.bahgo20.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.example.bahgo20.R
import com.example.bahgo20.databinding.SheetDayForecastBinding
import com.example.bahgo20.weather.DayForecast
import com.example.bahgo20.weather.WeatherForecast
import com.example.bahgo20.weather.WeatherIcon
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Details for one day of the 7-day forecast (Open-Meteo), opened by tapping the day on Home:
 * temperature now / high and low, an hourly temperature chart (Actual or Feels Like), and
 * the chance of rain by hour. Looks like the Conditions pop-up.
 */
object DayForecastSheet {

    fun show(context: Context, forecast: WeatherForecast, day: DayForecast, now: LocalDateTime) {
        val hours = forecast.hoursOf(day.date)
        if (hours.size < 2) return
        val b = SheetDayForecastBinding.inflate(LayoutInflater.from(context))
        val today = day.date == now.toLocalDate()
        val nowHour = if (today) now.hour.coerceAtMost(hours.size - 1) else -1
        val locale = context.resources.configuration.locales[0]

        b.tvDaySheetTitle.text = day.date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", locale))

        // Big number: the temperature this hour (today) or the day's high
        val main = if (today) hours[nowHour] else null
        b.tvDaySheetTemp.text = degrees(main?.tempC ?: day.maxC)
        b.ivDaySheetIcon.setImageResource(
            iconRes(main?.let { WeatherIcon.of(it.weatherCode, it.rainChance) } ?: WeatherIcon.of(day.weatherCode, day.rainChance))
        )
        b.tvDaySheetHiLo.text = context.getString(R.string.day_sheet_hilo, degrees(day.maxC), degrees(day.minC))

        // Temperature chart: Actual or Feels Like
        fun showTemp(feels: Boolean) {
            b.chartDayTemp.showTemperature(hours, nowHour, feels)
            b.tvDaySheetTempNote.setText(if (feels) R.string.day_sheet_feels_note else R.string.day_sheet_actual_note)
            toggleStyle(b.btnDayActual, !feels)
            toggleStyle(b.btnDayFeels, feels)
        }
        showTemp(false)
        b.btnDayActual.setOnClickListener { showTemp(false) }
        b.btnDayFeels.setOnClickListener { showTemp(true) }

        // Chance of rain
        b.tvDaySheetRainChance.text = context.getString(
            if (today) R.string.day_sheet_rain_today else R.string.day_sheet_rain_day, day.rainChance
        )
        b.chartDayRain.showRain(hours, nowHour)

        // Same size as the Conditions sheet: full height, 48dp below the top of the screen
        val dm = context.resources.displayMetrics
        b.root.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dm.heightPixels - (48 * dm.density).toInt())

        BottomSheetDialog(context).apply {
            setContentView(b.root)
            // Let the gradient's rounded corners show (the default sheet is a plain rectangle)
            (b.root.parent as? ViewGroup)?.setBackgroundColor(Color.TRANSPARENT)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
            b.ivDaySheetClose.setOnClickListener { dismiss() }
            show()
        }
    }

    /** Selected = filled like the Settings toggles; not selected = outlined. */
    private fun toggleStyle(button: MaterialButton, selected: Boolean) {
        val c = button.context
        val bg = if (selected) ContextCompat.getColor(c, R.color.toggle_selected_bg) else Color.TRANSPARENT
        val fg = ContextCompat.getColor(c, if (selected) R.color.toggle_selected_text else R.color.toggle_unselected_text)
        button.backgroundTintList = ColorStateList.valueOf(bg)
        button.setTextColor(fg)
        button.strokeWidth = if (selected) 0 else (1 * c.resources.displayMetrics.density).toInt()
        button.strokeColor = ColorStateList.valueOf(fg)
    }

    private fun degrees(c: Double) = if (c.isNaN()) "--" else "${c.roundToInt()}°"

    private fun iconRes(icon: WeatherIcon) = when (icon) {
        WeatherIcon.SUN -> R.drawable.ic_weather_sun
        WeatherIcon.PARTLY_CLOUDY -> R.drawable.ic_weather_partly
        WeatherIcon.CLOUD -> R.drawable.ic_weather_cloud
        WeatherIcon.RAIN -> R.drawable.ic_weather_rain
        WeatherIcon.STORM -> R.drawable.ic_weather_storm
    }
}
