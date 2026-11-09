package com.example.bahgo20.route

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A point on the map. Plain Kotlin (no MapLibre) so the route math can be unit tested. */
data class LatLon(val lat: Double, val lon: Double)

/** Distances and shapes along a route. Accurate enough for a few kilometres in Taguig. */
object RouteGeometry {

    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Straight-line distance in metres. */
    fun distance(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Total length of a line in metres. */
    fun length(points: List<LatLon>): Double =
        points.zipWithNext().sumOf { (a, b) -> distance(a, b) }

    /**
     * Points every [stepM] metres along the line (first and last point included).
     * Each sample stands for about [stepM] metres of road when measuring flood exposure.
     */
    fun sample(points: List<LatLon>, stepM: Double): List<LatLon> {
        if (points.size < 2) return points
        val out = mutableListOf(points.first())
        var carried = 0.0           // metres walked since the last sample
        for ((a, b) in points.zipWithNext()) {
            val seg = distance(a, b)
            if (seg == 0.0) continue
            var along = stepM - carried
            while (along <= seg) {
                val t = along / seg
                out += LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
                along += stepM
            }
            carried = seg - (along - stepM)
        }
        if (out.last() != points.last()) out += points.last()
        return out
    }

    /** Shortest distance in metres from [p] to the line [points]. */
    fun distanceToLine(p: LatLon, points: List<LatLon>): Double {
        if (points.isEmpty()) return Double.MAX_VALUE
        if (points.size == 1) return distance(p, points[0])
        return points.zipWithNext().minOf { (a, b) -> distanceToSegment(p, a, b) }
    }

    /** Distance from [p] to segment a-b, on a flat local grid around [p] (fine for short distances). */
    fun distanceToSegment(p: LatLon, a: LatLon, b: LatLon): Double {
        val mPerDegLat = PI * EARTH_RADIUS_M / 180
        val mPerDegLon = mPerDegLat * cos(Math.toRadians(p.lat))
        val ax = (a.lon - p.lon) * mPerDegLon; val ay = (a.lat - p.lat) * mPerDegLat
        val bx = (b.lon - p.lon) * mPerDegLon; val by = (b.lat - p.lat) * mPerDegLat
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx; val cy = ay + t * dy
        return sqrt(cx * cx + cy * cy)
    }

    /**
     * Cuts out "there and back" spurs: a route forced through a via point sometimes drives
     * into a side street and comes straight back out. Any stretch that returns to within
     * [toleranceM] of an earlier point is removed.
     */
    fun removeSpurs(points: List<LatLon>, toleranceM: Double = 6.0): List<LatLon> {
        if (points.size < 4) return points
        val out = ArrayList<LatLon>(points.size)
        var i = 0
        while (i < points.size) {
            val p = points[i]
            out += p
            // Furthest later point that comes back to p (ignoring the immediate neighbour)
            var jump = -1
            var j = points.size - 1
            while (j > i + 1) {
                if (distance(p, points[j]) <= toleranceM && length(points.subList(i, j + 1)) > toleranceM * 4) {
                    jump = j; break
                }
                j--
            }
            i = if (jump > 0) jump + 1 else i + 1
        }
        if (out.last() != points.last()) out += points.last()
        return out
    }

    /**
     * Share (0..1) of route [a] that runs within [toleranceM] of route [b].
     * Used to drop alternatives that are basically the same road.
     */
    fun overlap(a: List<LatLon>, b: List<LatLon>, toleranceM: Double = 25.0): Double {
        val samples = sample(a, 25.0)
        if (samples.isEmpty()) return 1.0
        return samples.count { distanceToLine(it, b) <= toleranceM }.toDouble() / samples.size
    }

    /** A point [offsetM] metres to the side of the a→b line, at fraction [t] along it. */
    fun sideOffset(a: LatLon, b: LatLon, t: Double, offsetM: Double): LatLon {
        val mPerDegLat = PI * EARTH_RADIUS_M / 180
        val mPerDegLon = mPerDegLat * cos(Math.toRadians((a.lat + b.lat) / 2))
        val dx = (b.lon - a.lon) * mPerDegLon
        val dy = (b.lat - a.lat) * mPerDegLat
        val len = sqrt(dx * dx + dy * dy).takeIf { it > 0 } ?: return a
        // Unit normal (left of the direction of travel)
        val nx = -dy / len; val ny = dx / len
        val mx = a.lon + (b.lon - a.lon) * t
        val my = a.lat + (b.lat - a.lat) * t
        return LatLon(my + ny * offsetM / mPerDegLat, mx + nx * offsetM / mPerDegLon)
    }
}
