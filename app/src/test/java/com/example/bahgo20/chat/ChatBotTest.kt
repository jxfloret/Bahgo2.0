package com.example.bahgo20.chat

import com.example.bahgo20.Risk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBotTest {

    private var streets = listOf(
        StreetStatus(1, "Sealion Street", Risk.SAFE, 12.0, "Steady · Water level isn't changing", "No Rain", 13.0),
        StreetStatus(2, "Samaritan Street", Risk.CRITICAL, 64.0, "+6 cm/hr · Water is rising", "Heavy Rain", 67.0),
        StreetStatus(3, "Swingfire Street", Risk.MEDIUM, 35.0, null, "Light Rain", null),
        StreetStatus(4, "Striker Street", Risk.OFFLINE, null)
    )
    private var car = false
    private val bot = ChatBot({ streets }, { car })

    private fun ask(q: String) = bot.reply(q)

    @Test
    fun levelNowListsEveryStreet() {
        val r = ask("What's the water level now?")
        assertTrue(r.text.contains("Sealion Street: 12 cm, Safe"))
        assertTrue(r.text.contains("Samaritan Street: 64 cm, Critical"))
        assertTrue(r.text.contains("Striker Street: offline"))
        assertTrue(r.text.contains("CRITICAL"))
    }

    @Test
    fun streetNameAloneGivesThatStreet() {
        val r = ask("samaritan")
        assertTrue(r.text.startsWith("Samaritan Street is at 64 cm"))
        assertTrue(r.actions.any { it is BotAction.OpenStreet && it.id == 2 })
    }

    @Test
    fun safetyUsesStreetAndMode() {
        val walk = ask("Is Samaritan Street safe?")
        assertTrue(walk.text.contains("Do NOT walk"))
        car = true
        val drive = ask("can i drive through samaritan")
        assertTrue(drive.text.contains("Do NOT drive"))
    }

    @Test
    fun safetyWithoutStreetUsesNearestAndWarnsAboutOthers() {
        val r = ask("is it safe to go out?")
        assertTrue(r.text.startsWith("Sealion Street (your nearest sensor)"))
        assertTrue(r.text.contains("Samaritan Street is Critical"))
        assertTrue(r.text.contains("Swingfire Street is Warning"))
    }

    @Test
    fun offlineStreetIsExplained() {
        assertTrue(ask("is striker street safe").text.contains("offline"))
    }

    @Test
    fun forecastShowsCollectingAndValues() {
        val r = ask("Will it flood soon?")
        assertTrue(r.text.contains("Samaritan Street: about 67 cm in 30 min (rising), Critical"))
        assertTrue(r.text.contains("Swingfire Street: still collecting"))
    }

    @Test
    fun forecastWarnsWhenLevelWillGetWorse() {
        streets = listOf(StreetStatus(1, "Sealion Street", Risk.SAFE, 27.0, null, null, 34.0))
        assertTrue(ask("forecast").text.contains("expected to reach a higher risk level"))
    }

    @Test
    fun emergencyWinsOverOtherWords() {
        val r = ask("help me im trapped in the flood water")
        assertTrue(r.text.contains("911"))
        assertTrue(r.actions.contains(BotAction.CallEmergency))
    }

    @Test
    fun filipinoWordsWork() {
        assertTrue(ask("umuulan ba?").text.startsWith("Rain from the street sensors"))
        assertTrue(ask("ligtas ba sa samaritan").text.contains("Samaritan Street"))
        assertTrue(ask("salamat").text.contains("welcome"))
    }

    @Test
    fun appQuestionsGetTheRightAnswer() {
        assertTrue(ask("What do the colors mean?").text.contains("Warning: over 30 cm"))
        assertTrue(ask("how do i change my password").text.contains("Change password"))
        assertTrue(ask("I forgot my password").text.contains("reset"))
        assertTrue(ask("change phone number").text.contains("Change phone number"))
        assertTrue(ask("not getting notifications").actions.contains(BotAction.OpenSettings))
        assertTrue(ask("why is a sensor offline").text.contains("hasn't sent a reading"))
        assertTrue(ask("delete my account").text.contains("deleted within 30 days"))
        assertTrue(ask("weather this week").text.contains("7-day forecast"))
        assertTrue(ask("who made this app").text.contains("STI College Global City"))
        assertTrue(ask("Flood safety tips").text.contains("go bag"))
    }

    @Test
    fun unknownQuestionFallsBack() {
        val r = ask("what's the best pizza")
        assertTrue(r.text.startsWith("Sorry"))
        assertFalse(r.suggestions.isEmpty())
    }

    @Test
    fun startersAllGetRealAnswers() {
        ChatBot.STARTERS.forEach { q ->
            assertFalse("Starter fell back: $q", ask(q).text.startsWith("Sorry"))
        }
    }
}
