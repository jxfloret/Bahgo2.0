package com.example.bahgo20.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class WeatherForecastTest {

    private fun sample(): WeatherForecast {
        val json = javaClass.classLoader!!.getResource("open_meteo_sample.json")!!.readText()
        return WeatherForecast.parse(json)
    }

    @Test
    fun parsesSevenDaysAndHours() {
        val f = sample()
        assertEquals(7, f.days.size)
        assertEquals(168, f.hours.size)
        val first = f.days.first()
        assertTrue(first.maxC >= first.minC)
        assertTrue(first.rainChance in 0..100)
    }

    @Test
    fun hourlyStripStartsAtCurrentHour() {
        val f = sample()
        val start = f.hours[5].time.plusMinutes(40)        // 40 minutes into hour 5
        val next = f.nextHours(start, 12)
        assertEquals(12, next.size)
        assertEquals(f.hours[5].time, next.first().time)
        assertEquals(7, f.nextDays(f.days.first().date).size)
    }

    private fun forecastWithChances(vararg chances: Int): WeatherForecast {
        val t0 = LocalDateTime.of(2026, 10, 29, 7, 0)
        val hours = chances.mapIndexed { i, c -> HourForecast(t0.plusHours(i.toLong()), 28.0, c, 3) }
        return WeatherForecast(hours, listOf(DayForecast(LocalDate.of(2026, 10, 29), 26.0, 32.0, chances.max(), 3)))
    }

    @Test
    fun summaryInPlainWords() {
        val now = LocalDateTime.of(2026, 10, 29, 7, 10)
        assertEquals("Rain likely around 1 PM (60%).",
            forecastWithChances(10, 10, 20, 20, 30, 40, 60, 70).summary(now))
        assertEquals("Rain is likely right now (80%).",
            forecastWithChances(80, 20).summary(now))
        assertEquals("Some chance of rain around 9 AM (40%).",
            forecastWithChances(10, 20, 40, 20).summary(now))
        assertEquals("No rain expected in the next 12 hours.",
            forecastWithChances(0, 5, 10).summary(now))
    }

    @Test
    fun iconsFollowWeatherCodes() {
        assertEquals(WeatherIcon.SUN, WeatherIcon.of(0, 0))
        assertEquals(WeatherIcon.PARTLY_CLOUDY, WeatherIcon.of(2, 10))
        assertEquals(WeatherIcon.CLOUD, WeatherIcon.of(3, 10))
        assertEquals(WeatherIcon.RAIN, WeatherIcon.of(3, 60))      // cloudy but rain likely
        assertEquals(WeatherIcon.RAIN, WeatherIcon.of(61, 30))
        assertEquals(WeatherIcon.STORM, WeatherIcon.of(95, 80))
    }
}
