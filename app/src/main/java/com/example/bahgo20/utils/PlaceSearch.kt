package com.example.bahgo20.utils

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder

/**
 * Places in Taguig from OpenStreetMap's place search (Nominatim).
 * Used by Search and by the Iwas-Baha Route planner on Home.
 */
object PlaceSearch {

    data class Result(val name: String, val area: String, val lat: Double, val lon: Double)

    /** Places in Taguig matching [query], or null if the search failed (e.g. no internet). Call off the main thread. */
    fun search(http: OkHttpClient, query: String, limit: Int = 15): List<Result>? {
        return try {
            val q = URLEncoder.encode(query, "UTF-8")
            // viewbox = around Taguig; bounded=1 keeps results inside it
            val url = "https://nominatim.openstreetmap.org/search?q=$q&format=json&addressdetails=1" +
                    "&limit=$limit&countrycodes=ph&viewbox=121.015,14.565,121.105,14.465&bounded=1"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "BahGo20FloodApp/1.0 (Android)")
                .get().build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val array = JSONArray(response.body?.string() ?: return null)
                val out = mutableListOf<Result>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val display = obj.optString("display_name")
                    val city = obj.optJSONObject("address")?.optString("city").orEmpty()
                    // The box around Taguig also touches Makati and Pateros: keep Taguig only
                    if (!city.contains("Taguig", true) && !display.contains("Taguig", true)) continue
                    val lat = obj.optDouble("lat", Double.NaN)
                    val lon = obj.optDouble("lon", Double.NaN)
                    if (lat.isNaN() || lon.isNaN()) continue
                    val name = obj.optString("name").ifBlank { display.substringBefore(",").trim() }
                    if (name.isBlank()) continue
                    out += Result(name, shortArea(display, name), lat, lon)
                }
                out
            }
        } catch (e: Exception) {
            Log.w("PlaceSearch", "Place search failed", e)
            null
        }
    }

    /** "SM Aura, Carlos P. Garcia Avenue, Ususan, Fort Bonifacio, Taguig District 2, Taguig, ..." -> "Carlos P. Garcia Avenue, Ususan, Taguig" */
    fun shortArea(display: String, name: String): String {
        val skip = Regex("(?i)^(taguig district.*|taguig|southern manila district|metro manila|philippines|\\d{4})$")
        val parts = display.split(",").map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(name, true) && !skip.matches(it) }
            .take(2)
        return (parts + "Taguig").joinToString(", ")
    }
}
