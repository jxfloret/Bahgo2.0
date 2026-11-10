package com.example.bahgo20.alerts

import com.example.bahgo20.Risk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsAlertTest {

    @Test
    fun messageNamesStreetLevelAndRescueTeam() {
        val text = SmsAlert.message("Samaritan Street", 64.4)
        assertTrue(text.startsWith("BahGo ALERT: Samaritan Street is CRITICAL (64 cm"))
        assertTrue(text.contains("Southside BERT Rescue Team 0998 975 0368"))
        assertTrue("plain ASCII so it costs one SMS part per 160 chars", text.all { it.code < 128 })
        assertTrue("fits in two SMS parts, was ${text.length}", text.length <= 306)
    }

    @Test
    fun atMostOneTextPerStreetEvery30Minutes() {
        val t0 = 1_000_000_000_000L
        assertTrue(SmsAlert.allowed(0L, t0))                              // never sent
        assertFalse(SmsAlert.allowed(t0, t0 + 10 * 60_000L))              // 10 min later: no
        assertTrue(SmsAlert.allowed(t0, t0 + SmsAlert.MIN_GAP_MS))        // 30 min later: yes
    }

    @Test
    fun onlyRisesIntoCriticalTriggerAText() {
        // The monitor texts when AlertRules says notify AND the risk is Critical
        val t0 = 1_000_000_000_000L
        var state = AlertRules.State()
        val warning = AlertRules.decide(40.0, Risk.MEDIUM, state, t0)
        assertTrue(warning.notify && warning.risk == Risk.MEDIUM)          // notification, no text
        state = warning.newState!!
        val critical = AlertRules.decide(65.0, Risk.CRITICAL, state, t0 + 60_000)
        assertTrue(critical.notify)
        assertEquals(Risk.CRITICAL, critical.risk)                         // this one is texted
        state = critical.newState!!
        val stillCritical = AlertRules.decide(70.0, Risk.CRITICAL, state, t0 + 120_000)
        assertFalse(stillCritical.notify)                                  // staying Critical: no new text
    }
}
