package com.example.bahgo20.weather

import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Hourly and 7-day weather forecast for the sensor area, from Open-Meteo (weather-model data).
 * This is separate from the street sensors: the sensors can't see days ahead.
 */
data class HourForecast(
    val time: LocalDateTime,
    val tempC: Double,
    val rainChance: Int,      // 0..100 %
    val weatherCode: Int,     // WMO weather code
    val feelsC: Double = Double.NaN   // "feels like" (apparent) temperature
)

data class DayForecast(
    val date: LocalDate,
    val minC: Double,
    val maxC: Double,
    val rainChance: Int,      // highest chance of rain that day, 0..100 %
    val weatherCode: Int
)

data class WeatherForecast(val hours: List<HourForecast>, val days: List<DayForecast>) {

    /** "Now" plus the next [count] - 1 hours, starting at the current hour. */
    fun nextHours(now: LocalDateTime, count: Int = 12): List<HourForecast> {
        val thisHour = now.withMinute(0).withSecond(0).withNano(0)
        return hours.filter { !it.time.isBefore(thisHour) }.take(count)
    }

    /** The 24 hours of [date] (midnight to 11 PM), for the day details sheet. */
    fun hoursOf(date: java.time.LocalDate): List<HourForecast> = hours.filter { it.time.toLocalDate() == date }

    /** Today and the following days, at most [count]. */
    fun nextDays(today: LocalDate, count: Int = 7): List<DayForecast> =
        days.filter { !it.date.isBefore(today) }.take(count)

    /** One plain sentence about rain in the next 12 hours, like a weather app's headline. */
    fun summary(now: LocalDateTime): String {
        val next = nextHours(now, 12)
        if (next.isEmpty()) return "No forecast available right now."
        val likely = next.firstOrNull { it.rainChance >= LIKELY }
        val first = next.first()
        return when {
            likely != null && likely == first -> "Rain is likely right now (${first.rainChance}%)."
            likely != null -> "Rain likely around ${hourLabel(likely.time)} (${likely.rainChance}%)."
            else -> {
                val best = next.maxBy { it.rainChance }
                if (best.rainChance >= POSSIBLE) {
                    "Some chance of rain around ${hourLabel(best.time)} (${best.rainChance}%)."
                } else {
                    "No rain expected in the next 12 hours."
                }
            }
        }
    }

    companion object {
        const val LIKELY = 50       // % at or above this: "Rain likely"
        const val POSSIBLE = 30     // % at or above this: "Some chance of rain"
        const val SHOW_CHANCE = 20  // the blue % is shown from this chance up

        /** Parses an Open-Meteo /v1/forecast response (hourly + daily, local timezone). */
        fun parse(json: String): WeatherForecast {
            val root = JSONObject(json)
            val h = root.getJSONObject("hourly")
            val hTime = h.getJSONArray("time")
            val hTemp = h.getJSONArray("temperature_2m")
            val hRain = h.getJSONArray("precipitation_probability")
            val hCode = h.getJSONArray("weather_code")
            val hFeels = h.optJSONArray("apparent_temperature")   // missing in older saved forecasts
            val hours = (0 until hTime.length()).map { i ->
                HourForecast(
                    time = LocalDateTime.parse(hTime.getString(i)),
                    tempC = hTemp.optDouble(i, Double.NaN),
                    rainChance = if (hRain.isNull(i)) 0 else hRain.getInt(i),
                    weatherCode = if (hCode.isNull(i)) 0 else hCode.getInt(i),
                    feelsC = hFeels?.optDouble(i, Double.NaN) ?: Double.NaN
                )
            }
            val d = root.getJSONObject("daily")
            val dTime = d.getJSONArray("time")
            val dMax = d.getJSONArray("temperature_2m_max")
            val dMin = d.getJSONArray("temperature_2m_min")
            val dRain = d.getJSONArray("precipitation_probability_max")
            val dCode = d.getJSONArray("weather_code")
            val days = (0 until dTime.length()).map { i ->
                DayForecast(
                    date = LocalDate.parse(dTime.getString(i)),
                    minC = dMin.optDouble(i, Double.NaN),
                    maxC = dMax.optDouble(i, Double.NaN),
                    rainChance = if (dRain.isNull(i)) 0 else dRain.getInt(i),
                    weatherCode = if (dCode.isNull(i)) 0 else dCode.getInt(i)
                )
            }
            return WeatherForecast(hours, days)
        }

        fun hourLabel(t: LocalDateTime): String =
            t.format(DateTimeFormatter.ofPattern("h a", Locale.US))

        fun dayLabel(date: LocalDate, today: LocalDate): String =
            if (date == today) "Today" else date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    }
}

/** Which picture to show for a WMO weather code (and chance of rain). */
enum class WeatherIcon { SUN, PARTLY_CLOUDY, CLOUD, RAIN, STORM;

    companion object {
        fun of(code: Int, rainChance: Int): WeatherIcon = when {
            code >= 95 -> STORM                                     // thunderstorm
            code in 51..67 || code in 80..82 -> RAIN                // drizzle, rain, showers
            rainChance >= WeatherForecast.LIKELY -> RAIN
            code == 0 || code == 1 -> SUN                           // clear, mainly clear
            code == 2 -> PARTLY_CLOUDY
            else -> CLOUD                                           // overcast, fog
        }
    }
}
