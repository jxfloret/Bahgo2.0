package com.example.bahgo20.weather

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Downloads the forecast from Open-Meteo (free, no account or key) and keeps the last one on
 * the phone, so the screen still shows a forecast when there's no internet.
 * Weather data by Open-Meteo.com (CC BY 4.0).
 */
object WeatherRepository {
    private const val TAG = "WeatherRepository"
    private const val PREFS = "bahgo_weather"
    private const val KEY_JSON = "forecast_json_v2"   // v2: also has the "feels like" temperature
    private const val KEY_TIME = "forecast_time"
    private const val REFRESH_MS = 30 * 60_000L     // download again after 30 minutes

    // Middle of the four street sensors (Palar, Taguig)
    private const val LAT = 14.5325
    private const val LON = 121.0554

    private val URL = "https://api.open-meteo.com/v1/forecast?latitude=$LAT&longitude=$LON" +
            "&hourly=temperature_2m,apparent_temperature,precipitation_probability,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=Asia%2FManila&forecast_days=7"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile private var cached: WeatherForecast? = null
    @Volatile private var cachedAt = 0L

    /** The saved forecast, if any (no network). */
    fun saved(context: Context): WeatherForecast? {
        cached?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_JSON, null) ?: return null
        return try {
            WeatherForecast.parse(json).also {
                cached = it
                cachedAt = prefs.getLong(KEY_TIME, 0L)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** A fresh forecast if the saved one is older than 30 min; otherwise (or if offline) the saved one. */
    suspend fun load(context: Context): WeatherForecast? = withContext(Dispatchers.IO) {
        val saved = saved(context)
        if (saved != null && System.currentTimeMillis() - cachedAt < REFRESH_MS) return@withContext saved
        try {
            val json = client.newCall(Request.Builder().url(URL).build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                resp.body?.string() ?: error("Empty response")
            }
            val forecast = WeatherForecast.parse(json)
            val now = System.currentTimeMillis()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_JSON, json).putLong(KEY_TIME, now).apply()
            cached = forecast
            cachedAt = now
            forecast
        } catch (e: Exception) {
            Log.w(TAG, "Forecast download failed, using the saved one", e)
            saved
        }
    }
}
