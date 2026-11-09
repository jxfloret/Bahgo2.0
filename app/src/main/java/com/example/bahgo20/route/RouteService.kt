package com.example.bahgo20.route

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale

/**
 * Candidate routes from OpenStreetMap routing (OSRM). Free, no API key:
 *  - routing.openstreetmap.de (FOSSGIS): real walking and driving profiles,
 *  - router.project-osrm.org (OSRM demo): driving only, used if the first one fails.
 * The services usually give one or two routes, so detours through a point to the left and
 * right of the straight line are added to get more options to compare.
 */
class RouteService(private val http: OkHttpClient) {

    /** Up to [MAX_ROUTES] different routes, shortest first. Throws if no routing service answers. */
    fun routes(from: LatLon, to: LatLon, mode: TravelMode): List<RouteOption> {
        val found = mutableListOf<RouteOption>()
        fun addIfNew(r: RouteOption) {
            if (r.points.size < 2) return
            val shortest = found.minOfOrNull { it.distanceM }
            if (shortest != null && r.distanceM > shortest * MAX_DETOUR_RATIO + 300) return
            if (found.any { RouteGeometry.overlap(r.points, it.points) > SAME_ROAD_SHARE }) return
            found += r
        }

        request(listOf(from, to), mode).forEach(::addIfNew)
        if (found.isEmpty()) return emptyList()

        // Detours: halfway along, to the left and right, a bit further out each time
        val straight = RouteGeometry.distance(from, to)
        for (share in VIA_OFFSETS) {
            if (found.size >= MAX_ROUTES) break
            for (side in listOf(1.0, -1.0)) {
                if (found.size >= MAX_ROUTES) break
                val offset = side * maxOf(MIN_VIA_OFFSET_M, straight * share)
                val via = RouteGeometry.sideOffset(from, to, 0.5, offset)
                try {
                    request(listOf(from, via, to), mode).firstOrNull()?.let { addIfNew(cleanDetour(it)) }
                } catch (e: Exception) {
                    Log.w(TAG, "Detour route failed", e)
                }
            }
        }
        return found.sortedBy { it.distanceM }
    }

    /** Removes "drive in and back out" spurs at the via point and fixes the length and time. */
    private fun cleanDetour(r: RouteOption): RouteOption {
        val points = RouteGeometry.removeSpurs(r.points)
        val length = RouteGeometry.length(points)
        val oldLength = RouteGeometry.length(r.points).takeIf { it > 0 } ?: return r
        return RouteOption(points, length, r.durationS * length / oldLength)
    }

    /** Routes through [stops] in order. Tries each server for the mode until one answers. */
    private fun request(stops: List<LatLon>, mode: TravelMode): List<RouteOption> {
        var lastError: Exception? = null
        for (base in servers(mode)) {
            try {
                return fetch(base, stops)
            } catch (e: Exception) {
                Log.w(TAG, "Routing via $base failed", e)
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("no routing server")
    }

    private fun fetch(base: String, stops: List<LatLon>): List<RouteOption> {
        val coords = stops.joinToString(";") { String.format(Locale.US, "%.6f,%.6f", it.lon, it.lat) }
        val alternatives = if (stops.size == 2) "3" else "false"
        val url = "$base$coords?alternatives=$alternatives&overview=full&geometries=geojson&steps=false"
        val request = Request.Builder().url(url)
            .header("User-Agent", "BahGo20FloodApp/1.0 (Android; flood-safe routes in Taguig)")
            .get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return parse(response.body?.string() ?: error("empty response"))
        }
    }

    companion object {
        private const val TAG = "RouteService"
        const val MAX_ROUTES = 4
        /** Routes sharing more than this much road count as the same route. */
        const val SAME_ROAD_SHARE = 0.8
        /** Ignore detours much longer than the shortest route. */
        const val MAX_DETOUR_RATIO = 1.8
        const val MIN_VIA_OFFSET_M = 250.0
        /** Detour offsets, as a share of the straight-line distance. */
        val VIA_OFFSETS = listOf(0.3, 0.55)

        fun servers(mode: TravelMode): List<String> = when (mode) {
            TravelMode.PERSON -> listOf("https://routing.openstreetmap.de/routed-foot/route/v1/driving/")
            TravelMode.CAR -> listOf(
                "https://routing.openstreetmap.de/routed-car/route/v1/driving/",
                "https://router.project-osrm.org/route/v1/driving/"
            )
        }

        /** OSRM JSON → routes (GeoJSON coordinates are lon,lat). */
        fun parse(json: String): List<RouteOption> {
            val root = JSONObject(json)
            if (root.optString("code") != "Ok") return emptyList()
            val routes = root.optJSONArray("routes") ?: return emptyList()
            return (0 until routes.length()).mapNotNull { i ->
                val r = routes.getJSONObject(i)
                val coords = r.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
                val points = (0 until coords.length()).map { k ->
                    val c = coords.getJSONArray(k)
                    LatLon(c.getDouble(1), c.getDouble(0))
                }
                RouteOption(points, r.optDouble("distance", RouteGeometry.length(points)), r.optDouble("duration", 0.0))
            }
        }
    }
}
