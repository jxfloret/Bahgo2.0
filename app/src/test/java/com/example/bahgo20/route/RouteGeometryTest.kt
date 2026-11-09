package com.example.bahgo20.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteGeometryTest {

    private val a = LatLon(14.5320, 121.0530)
    private val b = LatLon(14.5320, 121.0576)

    @Test
    fun distanceIsAboutRight() {
        // 0.0046° of longitude at 14.5° N ≈ 495 m
        assertEquals(495.0, RouteGeometry.distance(a, b), 5.0)
    }

    @Test
    fun sampleSpacing() {
        val s = RouteGeometry.sample(listOf(a, b), 15.0)
        assertEquals(a, s.first())
        assertEquals(b, s.last())
        s.zipWithNext().dropLast(1).forEach { (p, q) -> assertEquals(15.0, RouteGeometry.distance(p, q), 0.5) }
    }

    @Test
    fun distanceToLine() {
        val p = LatLon(14.5325, 121.0553)  // ~55 m north of the middle
        assertEquals(55.0, RouteGeometry.distanceToLine(p, listOf(a, b)), 3.0)
        // Beyond the end: distance to the end point
        val q = LatLon(14.5320, 121.0586)
        assertEquals(RouteGeometry.distance(q, b), RouteGeometry.distanceToLine(q, listOf(a, b)), 1.0)
    }

    @Test
    fun removeSpursCutsThereAndBack() {
        val mid = LatLon(14.5320, 121.0553)
        val side = LatLon(14.5335, 121.0553)  // 165 m up a side street and back
        val withSpur = listOf(a, mid, side, LatLon(14.53200001, 121.0553), b)
        val cleaned = RouteGeometry.removeSpurs(withSpur)
        assertTrue(side !in cleaned)
        assertEquals(RouteGeometry.distance(a, b), RouteGeometry.length(cleaned), 2.0)
    }

    @Test
    fun overlapOfSameAndDifferentRoads() {
        val line = listOf(a, b)
        assertEquals(1.0, RouteGeometry.overlap(line, line), 1e-9)
        val other = listOf(LatLon(14.5350, 121.0530), LatLon(14.5350, 121.0576))  // ~330 m away
        assertEquals(0.0, RouteGeometry.overlap(line, other), 1e-9)
    }

    @Test
    fun sideOffsetIsPerpendicular() {
        val p = RouteGeometry.sideOffset(a, b, 0.5, 200.0)
        assertEquals(200.0, RouteGeometry.distanceToLine(p, listOf(a, b)), 3.0)
    }
}
