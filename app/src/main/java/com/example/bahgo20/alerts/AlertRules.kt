package com.example.bahgo20.alerts

import com.example.bahgo20.Risk
import com.example.bahgo20.SensorTracker

/**
 * When to show a flood notification for a street (plain Kotlin, unit tested).
 *   - Only RISES alert: into Medium (Warning) or Critical (Dangerous).
 *   - Going down is remembered quietly, so the next rise alerts again.
 *   - Water wobbling around 30 / 60 cm doesn't spam: it must drop 3 cm below a line to count as down.
 *   - At most one Medium alert per street per 10 minutes; Critical always goes out.
 *   - Offline sensors never alert (and don't change what was remembered).
 * Same rules as functions/alertLogic.js.
 */
object AlertRules {
    const val HYSTERESIS_CM = 3.0
    const val COOLDOWN_MS = 10 * 60_000L

    /** What was remembered for a street: the last risk and when the last notification went out. */
    data class State(val risk: Risk = Risk.SAFE, val sentAtMs: Long = 0L)

    data class Decision(val notify: Boolean, val risk: Risk, val newState: State?)

    private fun rank(r: Risk) = when (r) {
        Risk.CRITICAL -> 2
        Risk.MEDIUM -> 1
        else -> 0
    }

    /** Risk band with memory: to fall out of a band the level must drop 3 cm below its line. */
    fun riskWithMemory(levelCm: Double, last: Risk): Risk {
        val plain = SensorTracker.riskForLevel(levelCm)
        if (rank(plain) >= rank(last)) return plain
        if (last == Risk.CRITICAL && levelCm > SensorTracker.CRITICAL_CM - HYSTERESIS_CM) return Risk.CRITICAL
        if ((last == Risk.CRITICAL || last == Risk.MEDIUM) && levelCm > SensorTracker.MEDIUM_CM - HYSTERESIS_CM) return Risk.MEDIUM
        return plain
    }

    /**
     * [reading] is what the app shows for the street right now (risk OFFLINE = no live data).
     * Returns whether to notify, and what to remember (null = nothing changed).
     */
    fun decide(levelCm: Double?, readingRisk: Risk, state: State, nowMs: Long): Decision {
        if (readingRisk == Risk.OFFLINE || levelCm == null) return Decision(false, state.risk, null)

        // The app's risk can also come from the device's own status text; take the higher one
        val byLevel = riskWithMemory(levelCm, state.risk)
        val risk = if (rank(readingRisk) > rank(byLevel)) readingRisk else byLevel
        if (risk == state.risk) return Decision(false, risk, null)

        if (rank(risk) < rank(state.risk)) {
            return Decision(false, risk, state.copy(risk = risk))         // went down: quietly
        }
        val coolingDown = nowMs - state.sentAtMs < COOLDOWN_MS
        if (coolingDown && risk != Risk.CRITICAL) {
            return Decision(false, risk, state.copy(risk = risk))         // Medium again too soon
        }
        return Decision(true, risk, State(risk, nowMs))
    }
}
