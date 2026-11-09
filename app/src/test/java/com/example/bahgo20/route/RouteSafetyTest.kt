package com.example.bahgo20.route

import com.example.bahgo20.Risk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSafetyTest {

    // A straight east-west street through Palar (about 500 m), and one 300 m further north.
    private val start = LatLon(14.5320, 121.0530)
    private val end = LatLon(14.5320, 121.0576)
    private val southRoute = route(start, end)
    private val northRoute = route(start, LatLon(14.5347, 121.0530), LatLon(14.5347, 121.0576), end)

    private fun route(vararg pts: LatLon) = RouteOption(pts.toList(), RouteGeometry.length(pts.toList()), 600.0)

    /** A sensor right on the south route. */
    private fun sensorOnSouth(risk: Risk, cm: Double?) =
        SensorStatus(2, "Samaritan Street", LatLon(14.5321, 121.0553), risk, cm)

    private fun noFlood(r: RouteOption) = RouteSafety.samples(r).map { Hazard.NONE }

    @Test
    fun clearRouteIsSafe() {
        val a = RouteSafety.assess(southRoute, noFlood(southRoute), listOf(sensorOnSouth(Risk.SAFE, 5.0)), TravelMode.PERSON)
        assertEquals(Verdict.SAFE, a.verdict)
        assertTrue(a.reasons.isEmpty())
    }

    @Test
    fun criticalSensorMeansAvoidInBothModes() {
        val s = listOf(sensorOnSouth(Risk.CRITICAL, 72.0))
        TravelMode.entries.forEach { mode ->
            val a = RouteSafety.assess(southRoute, noFlood(southRoute), s, mode)
            assertEquals(Verdict.AVOID, a.verdict)
            assertEquals(Reason.SensorCritical("Samaritan Street", 72.0), a.reasons.first())
        }
    }

    @Test
    fun warningSensorMeansCautionForPerson() {
        val a = RouteSafety.assess(southRoute, noFlood(southRoute), listOf(sensorOnSouth(Risk.MEDIUM, 38.0)), TravelMode.PERSON)
        assertEquals(Verdict.CAUTION, a.verdict)
        assertEquals(Reason.SensorWarning("Samaritan Street", 38.0), a.reasons.first())
    }

    @Test
    fun carIsStricterAtKneeDeepWater() {
        val shallow = listOf(sensorOnSouth(Risk.MEDIUM, 38.0))
        val deep = listOf(sensorOnSouth(Risk.MEDIUM, 50.0))
        assertEquals(Verdict.CAUTION, RouteSafety.assess(southRoute, noFlood(southRoute), shallow, TravelMode.CAR).verdict)
        val a = RouteSafety.assess(southRoute, noFlood(southRoute), deep, TravelMode.CAR)
        assertEquals(Verdict.AVOID, a.verdict)
        assertTrue(a.reasons.first() is Reason.SensorTooDeepForCar)
        // Same water on foot: still passable with caution
        assertEquals(Verdict.CAUTION, RouteSafety.assess(southRoute, noFlood(southRoute), deep, TravelMode.PERSON).verdict)
    }

    @Test
    fun farSensorIsIgnored() {
        val a = RouteSafety.assess(northRoute, noFlood(northRoute), listOf(sensorOnSouth(Risk.CRITICAL, 80.0)), TravelMode.PERSON)
        assertEquals(Verdict.SAFE, a.verdict)
    }

    @Test
    fun offlineSensorIsOnlyANote() {
        val a = RouteSafety.assess(southRoute, noFlood(southRoute), listOf(sensorOnSouth(Risk.OFFLINE, null)), TravelMode.PERSON)
        assertEquals(Verdict.SAFE, a.verdict)
        assertEquals(listOf(Reason.SensorOffline("Samaritan Street")), a.reasons)
    }

    @Test
    fun noahHighStretchMeansCaution() {
        val samples = RouteSafety.samples(southRoute)
        // First 10 sample points (~135 m) in high hazard
        val hazards = samples.indices.map { if (it < 10) Hazard.HIGH else Hazard.NONE }
        val a = RouteSafety.assess(southRoute, hazards, emptyList(), TravelMode.PERSON)
        assertEquals(Verdict.CAUTION, a.verdict)
        val high = (a.reasons.single() as Reason.NoahHigh).meters
        assertTrue("high metres was $high", high in 120.0..150.0)
    }

    @Test
    fun noahAloneNeverMeansAvoid() {
        val hazards = RouteSafety.samples(southRoute).map { Hazard.HIGH }
        assertEquals(Verdict.CAUTION, RouteSafety.assess(southRoute, hazards, emptyList(), TravelMode.CAR).verdict)
    }

    @Test
    fun carHasStricterMediumHazardLimit() {
        val samples = RouteSafety.samples(southRoute)
        // ~170 m of medium hazard: over the car limit (150 m), under the walking limit (200 m)
        val hazards = samples.indices.map { if (it < 13) Hazard.MEDIUM else Hazard.NONE }
        assertEquals(Verdict.CAUTION, RouteSafety.assess(southRoute, hazards, emptyList(), TravelMode.CAR).verdict)
        assertEquals(Verdict.SAFE, RouteSafety.assess(southRoute, hazards, emptyList(), TravelMode.PERSON).verdict)
    }

    @Test
    fun missingFloodDataIsNotedAndJudgedOnSensors() {
        val a = RouteSafety.assess(southRoute, null, emptyList(), TravelMode.PERSON)
        assertEquals(Verdict.SAFE, a.verdict)
        assertEquals(listOf(Reason.FloodDataMissing), a.reasons)
    }

    @Test
    fun planPicksSafestThenCautionThenAvoid() {
        val warning = listOf(sensorOnSouth(Risk.MEDIUM, 35.0))
        val south = RouteSafety.assess(southRoute, noFlood(southRoute), warning, TravelMode.PERSON)
        val north = RouteSafety.assess(northRoute, noFlood(northRoute), warning, TravelMode.PERSON)
        val plan = RouteSafety.plan(listOf(south, north))
        // The longer north route avoids the Warning sensor, so it is recommended
        assertEquals(northRoute, plan.best?.route)
        assertEquals(listOf(southRoute), plan.others.map { it.route })
        assertTrue(plan.avoid.isEmpty())
    }

    @Test
    fun shorterRouteWinsWhenBothClear() {
        val south = RouteSafety.assess(southRoute, noFlood(southRoute), emptyList(), TravelMode.CAR)
        val north = RouteSafety.assess(northRoute, noFlood(northRoute), emptyList(), TravelMode.CAR)
        assertEquals(southRoute, RouteSafety.plan(listOf(north, south)).best?.route)
    }

    @Test
    fun noBestRouteWhenAllAvoid() {
        val s = listOf(sensorOnSouth(Risk.CRITICAL, 70.0))
        val plan = RouteSafety.plan(listOf(RouteSafety.assess(southRoute, noFlood(southRoute), s, TravelMode.PERSON)))
        assertNull(plan.best)
        assertEquals(1, plan.avoid.size)
    }

    @Test
    fun hazardMetresAddUpToRouteLength() {
        val samples = RouteSafety.samples(southRoute)
        val hazards = samples.indices.map { if (it % 2 == 0) Hazard.LOW else Hazard.MEDIUM }
        val m = RouteSafety.measureHazard(samples, hazards)
        assertEquals(southRoute.distanceM, m.values.sum(), 1.0)
    }

    @Test
    fun hazardCodesMapToClasses() {
        assertEquals(Hazard.LOW, Hazard.of(1))
        assertEquals(Hazard.HIGH, Hazard.of(3))
        assertEquals(Hazard.NONE, Hazard.of(0))
    }
}
