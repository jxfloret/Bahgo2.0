package com.example.bahgo20.alerts

import com.example.bahgo20.Risk
import com.example.bahgo20.SensorTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertRulesTest {

    private val min = 60_000L
    private val t0 = 1_800_000_000_000L

    private fun live(level: Double) = SensorTracker.riskForLevel(level)

    @Test
    fun risingIntoMediumThenCriticalNotifiesTwice() {
        var state = AlertRules.State()
        val a = AlertRules.decide(35.0, live(35.0), state, t0)
        assertTrue(a.notify); assertEquals(Risk.MEDIUM, a.risk)
        state = a.newState!!
        val b = AlertRules.decide(45.0, live(45.0), state, t0 + min)
        assertFalse(b.notify); assertNull(b.newState)
        val c = AlertRules.decide(62.0, live(62.0), state, t0 + 2 * min)     // inside cooldown, but Critical
        assertTrue(c.notify); assertEquals(Risk.CRITICAL, c.risk)
    }

    @Test
    fun wobblingAroundThirtyDoesNotSpam() {
        var state = AlertRules.decide(31.0, live(31.0), AlertRules.State(), t0).newState!!
        listOf(29.5, 30.5, 28.0, 31.0, 29.0).forEachIndexed { i, level ->
            val d = AlertRules.decide(level, live(level), state, t0 + (i + 1) * min)
            assertFalse("no alert at $level", d.notify)
            d.newState?.let { state = it }
        }
        assertEquals(Risk.MEDIUM, state.risk)
    }

    @Test
    fun afterGoingDownANewRiseAlertsAgain() {
        var state = AlertRules.decide(35.0, live(35.0), AlertRules.State(), t0).newState!!
        val down = AlertRules.decide(20.0, live(20.0), state, t0 + 30 * min)
        assertFalse(down.notify); assertEquals(Risk.SAFE, down.newState!!.risk)
        state = down.newState!!
        assertTrue(AlertRules.decide(35.0, live(35.0), state, t0 + 31 * min).notify)
    }

    @Test
    fun quickSecondMediumIsHeldBack() {
        var state = AlertRules.decide(35.0, live(35.0), AlertRules.State(), t0).newState!!
        state = AlertRules.decide(20.0, live(20.0), state, t0 + 2 * min).newState!!
        val again = AlertRules.decide(35.0, live(35.0), state, t0 + 4 * min)
        assertFalse(again.notify)
        assertEquals(Risk.MEDIUM, again.newState!!.risk)
    }

    @Test
    fun offlineSensorNeverAlertsAndKeepsMemory() {
        val state = AlertRules.State(Risk.MEDIUM, t0)
        val d = AlertRules.decide(70.0, Risk.OFFLINE, state, t0 + 60 * min)
        assertFalse(d.notify); assertNull(d.newState)
        assertFalse(AlertRules.decide(null, Risk.SAFE, state, t0).notify)
    }

    @Test
    fun deviceStatusCanRaiseTheRisk() {
        // The app also trusts a device that reports CRITICAL in its status text
        val d = AlertRules.decide(10.0, Risk.CRITICAL, AlertRules.State(), t0)
        assertTrue(d.notify); assertEquals(Risk.CRITICAL, d.risk)
    }
}
