package com.example.bahgo20.route

import com.example.bahgo20.Risk
import com.example.bahgo20.SensorTracker

/** Walking (Person Mode) or driving a small car (Car Mode). */
enum class TravelMode { PERSON, CAR }

/** How a route is judged: clear, passable with care, or don't go this way. */
enum class Verdict { SAFE, CAUTION, AVOID }

/** Project NOAH 5-year flood hazard class (the "Var" field of the flood layer). */
enum class Hazard(val code: Int) {
    NONE(0), LOW(1), MEDIUM(2), HIGH(3);

    companion object {
        fun of(code: Int): Hazard = entries.firstOrNull { it.code == code } ?: if (code > 3) HIGH else NONE
    }
}

/** One street sensor and its live reading. */
data class SensorStatus(
    val id: Int,
    val street: String,
    val position: LatLon,
    val risk: Risk,
    val levelCm: Double?
)

/** A route from the routing service (before it is judged). */
data class RouteOption(
    val points: List<LatLon>,
    val distanceM: Double,
    val durationS: Double
)

/** Why a route got its verdict. The screen turns these into sentences. */
sealed class Reason {
    /** Passes a sensor reading Critical (> 60 cm). Always Avoid. */
    data class SensorCritical(val street: String, val levelCm: Double?) : Reason()
    /** Car Mode only: Warning sensor, but already too deep for a small car. Avoid. */
    data class SensorTooDeepForCar(val street: String, val levelCm: Double?) : Reason()
    /** Passes a sensor reading Warning (> 30 cm). Caution. */
    data class SensorWarning(val street: String, val levelCm: Double?) : Reason()
    /** Passes a sensor with no live reading: just a note, the verdict doesn't change. */
    data class SensorOffline(val street: String) : Reason()
    /** Long stretch inside NOAH high-hazard area. Caution. */
    data class NoahHigh(val meters: Double) : Reason()
    /** Long stretch inside NOAH medium-hazard area. Caution. */
    data class NoahMedium(val meters: Double) : Reason()
    /** The NOAH map couldn't be read (e.g. offline): judged on the sensors only. */
    data object FloodDataMissing : Reason()
}

/** A judged route. [cost] ranks routes of the same verdict (lower = easier and drier). */
data class Assessment(
    val route: RouteOption,
    val verdict: Verdict,
    val metersByHazard: Map<Hazard, Double>,
    val reasons: List<Reason>,
    val cost: Double
) {
    fun meters(h: Hazard): Double = metersByHazard[h] ?: 0.0
}

/** What the screen shows: the recommended route, other passable ones, and the ones to avoid. */
data class RoutePlan(
    val best: Assessment?,
    val others: List<Assessment>,
    val avoid: List<Assessment>
)

/**
 * Flood-safety scoring for routes, from two sources:
 *  - the live street sensors (Warning > 30 cm, Critical > 60 cm, see [SensorTracker]),
 *  - the Project NOAH 5-year flood hazard map (how many metres run through Low/Medium/High areas).
 * NOAH is a "what usually floods" map, not live water, so on its own it can only make a route
 * "passable with caution". Only a live sensor reading can make a route "avoid".
 */
object RouteSafety {

    /** A sensor counts as "on the route" when the route passes this close to it. */
    const val SENSOR_NEAR_M = 60.0

    /** Spacing of the points checked against the NOAH map. */
    const val SAMPLE_STEP_M = 15.0

    /** Small cars risk stalling from about this depth, so in Car Mode a Warning this deep means Avoid. */
    const val CAR_TOO_DEEP_CM = 45.0

    /** NOAH high-hazard stretch that makes a route "caution" (both modes). */
    const val HIGH_CAUTION_M = 50.0

    /** NOAH medium-hazard stretch that makes a route "caution". Cars get the stricter limit. */
    const val MEDIUM_CAUTION_M_PERSON = 200.0
    const val MEDIUM_CAUTION_M_CAR = 150.0

    /** Most "passable with caution" options to show under the recommended route. */
    const val MAX_OTHERS = 3

    /** The points to look up on the NOAH map for [route] (pass their hazards to [assess]). */
    fun samples(route: RouteOption): List<LatLon> = RouteGeometry.sample(route.points, SAMPLE_STEP_M)

    /**
     * Judges one route.
     * @param hazards NOAH hazard at each point of [samples] (same order), or null if the map couldn't be read.
     */
    fun assess(
        route: RouteOption,
        hazards: List<Hazard>?,
        sensors: List<SensorStatus>,
        mode: TravelMode
    ): Assessment {
        val reasons = mutableListOf<Reason>()
        var verdict = Verdict.SAFE
        fun worsen(v: Verdict) { if (v.ordinal > verdict.ordinal) verdict = v }

        // ── Live sensors near the route ──
        var sensorCost = 0.0
        sensors
            .filter { RouteGeometry.distanceToLine(it.position, route.points) <= SENSOR_NEAR_M }
            .sortedByDescending { it.risk.severity() }
            .forEach { s ->
                when (s.risk) {
                    Risk.CRITICAL -> {
                        reasons += Reason.SensorCritical(s.street, s.levelCm)
                        worsen(Verdict.AVOID); sensorCost += 100_000.0
                    }
                    Risk.MEDIUM -> {
                        val deep = (s.levelCm ?: SensorTracker.MEDIUM_CM) >= CAR_TOO_DEEP_CM
                        if (mode == TravelMode.CAR && deep) {
                            reasons += Reason.SensorTooDeepForCar(s.street, s.levelCm)
                            worsen(Verdict.AVOID); sensorCost += 100_000.0
                        } else {
                            reasons += Reason.SensorWarning(s.street, s.levelCm)
                            worsen(Verdict.CAUTION); sensorCost += 1_500.0
                        }
                    }
                    Risk.OFFLINE -> reasons += Reason.SensorOffline(s.street)
                    Risk.SAFE -> Unit
                }
            }

        // ── NOAH flood hazard along the route ──
        val meters = measureHazard(samples(route), hazards)
        if (hazards == null) {
            reasons += Reason.FloodDataMissing
        } else {
            val high = meters[Hazard.HIGH] ?: 0.0
            val medium = meters[Hazard.MEDIUM] ?: 0.0
            val mediumLimit = if (mode == TravelMode.CAR) MEDIUM_CAUTION_M_CAR else MEDIUM_CAUTION_M_PERSON
            if (high >= HIGH_CAUTION_M) { reasons += Reason.NoahHigh(high); worsen(Verdict.CAUTION) }
            if (medium >= mediumLimit) { reasons += Reason.NoahMedium(medium); worsen(Verdict.CAUTION) }
        }

        // Ranking: length plus extra "metres" for every metre in a flood area (deeper = heavier).
        val (wLow, wMed, wHigh) = if (mode == TravelMode.CAR) Triple(1.0, 4.0, 10.0) else Triple(0.5, 3.0, 8.0)
        val cost = route.distanceM + sensorCost +
                wLow * (meters[Hazard.LOW] ?: 0.0) +
                wMed * (meters[Hazard.MEDIUM] ?: 0.0) +
                wHigh * (meters[Hazard.HIGH] ?: 0.0)

        return Assessment(route, verdict, meters, reasons, cost)
    }

    /**
     * Metres of the route in each hazard class. Each stretch between two neighbouring
     * sample points counts half towards each end's class.
     */
    fun measureHazard(samples: List<LatLon>, hazards: List<Hazard>?): Map<Hazard, Double> {
        if (hazards == null || samples.size < 2) return emptyMap()
        require(hazards.size == samples.size) { "one hazard per sample point" }
        val out = mutableMapOf<Hazard, Double>()
        for (i in 0 until samples.size - 1) {
            val half = RouteGeometry.distance(samples[i], samples[i + 1]) / 2
            out.merge(hazards[i], half, Double::plus)
            out.merge(hazards[i + 1], half, Double::plus)
        }
        out.remove(Hazard.NONE)
        return out
    }

    /** Safest first: by verdict, then by [Assessment.cost]. */
    fun rank(assessments: List<Assessment>): List<Assessment> =
        assessments.sortedWith(compareBy<Assessment>({ it.verdict.ordinal }, { it.cost }))

    /** Splits ranked routes into what the screen shows. [RoutePlan.best] is null when every route is Avoid. */
    fun plan(assessments: List<Assessment>): RoutePlan {
        val ranked = rank(assessments)
        val passable = ranked.filter { it.verdict != Verdict.AVOID }
        return RoutePlan(
            best = passable.firstOrNull(),
            others = passable.drop(1).take(MAX_OTHERS),
            avoid = ranked.filter { it.verdict == Verdict.AVOID }
        )
    }

    private fun Risk.severity() = when (this) {
        Risk.CRITICAL -> 3; Risk.MEDIUM -> 2; Risk.OFFLINE -> 1; Risk.SAFE -> 0
    }
}
