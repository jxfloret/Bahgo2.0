package com.example.bahgo20.chat

import com.example.bahgo20.Risk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the trained question matcher on questions it never saw in training (held-out set),
 * and how the assistant combines it with the live answers.
 */
class KnowledgeModelTest {

    private val model = IntentModel(KnowledgeBase.trainingData().map { (q, e) -> q to e.id })

    /** Phrasings that are NOT in KnowledgeBase's examples, with the entry they should reach. */
    private val heldOut = listOf(
        "can you explain how bahgo works" to "how_app_works",
        "what is this app for" to "how_app_works",
        "how does the device know how deep the water is" to "sensor_measure",
        "how often does the sensor send readings" to "sensor_measure",
        "what does the red color mean" to "levels_colors",
        "at what depth is it critical" to "levels_colors",
        "the device is on but bahgo shows offline" to "offline",
        "why is striker gray" to "offline",
        "what machine learning model predicts the flood" to "forecast_model",
        "how was the forecast trained" to "forecast_model",
        "what does the lasso risk score mean" to "flood_risk",
        "explain the flood risk score" to "flood_risk",
        "what does steady mean in rise rate" to "rise_rate",
        "how do you detect rain" to "rain_explained",
        "why am i not receiving alerts" to "alerts",
        "how can i get flood notifications" to "alerts",
        "how does the safe route planner pick a route" to "route_planner",
        "what does an orange route mean" to "route_planner",
        "what are the blue zones on the map" to "noah_map",
        "what is project noah hazard" to "noah_map",
        "what do the dots on the map mean" to "map_use",
        "how do i look up a place" to "search",
        "what is the dashed line in the chart" to "chart",
        "difference between person mode and car mode" to "modes",
        "what can i see when i open a street" to "conditions_screen",
        "how do i pick my own street" to "home_street",
        "i want a new password" to "password_change",
        "i cant remember my password" to "password_forgot",
        "i changed my sim how do i update my number" to "phone_change",
        "how do i create a bahgo account" to "signup",
        "how do i delete all my data" to "delete_account",
        "do you share my data with anyone" to "privacy",
        "where do you get your weather data" to "data_sources",
        "are the readings reliable" to "accuracy",
        "can i use bahgo with no internet" to "internet",
        "which streets are monitored" to "which_streets",
        "can i switch to filipino" to "language",
        "turn on dark theme" to "dark_mode",
        "are you a bot" to "chatbot_itself",
        "who built bahgo" to "about",
        "where can i send a bug report" to "contact",
        "what should i pack in a go bag" to "prepare",
        "paano gumagana ang app" to "how_app_works",
        "bakit offline ang sensor" to "offline",
        "paano sinusukat ng sensor ang baha" to "sensor_measure"
    )

    @Test
    fun heldOutQuestionsReachTheRightAnswer() {
        val misses = mutableListOf<String>()
        heldOut.forEach { (q, want) ->
            val m = model.match(q)
            val got = m?.takeIf { it.score >= ChatBot.MIN_SCORE }?.label
            if (got != want) misses += "\"$q\" -> $got (%.2f), wanted $want".format(m?.score ?: 0.0)
        }
        val accuracy = 1.0 - misses.size.toDouble() / heldOut.size
        println("Held-out accuracy: %.0f%% (%d/%d)".format(accuracy * 100, heldOut.size - misses.size, heldOut.size))
        misses.forEach { println("  miss: $it") }
        assertTrue("Accuracy %.0f%% is below 85%%".format(accuracy * 100), accuracy >= 0.85)
    }

    /**
     * A second set, written after the model was tuned on the first one, so it shows how well the
     * model handles questions nobody adjusted it for. Lower accuracy here is expected: when the
     * model isn't sure, the chat uses its keyword rules or says it didn't understand.
     */
    private val fresh = listOf(
        "what does bahgo stand for and what does it do" to "how_app_works",
        "how far above the street is the sensor" to "sensor_measure",
        "is 40 cm warning or critical" to "levels_colors",
        "my whole map is gray" to "offline",
        "does the forecast use machine learning" to "forecast_model",
        "what inputs does the risk model use" to "flood_risk",
        "how long until the water reaches critical" to "rise_rate",
        "can you sense how heavy the rain is" to "rain_explained",
        "bahgo never warns me when it floods" to "alerts",
        "how do i plan a trip around the flood" to "route_planner",
        "what is the hazard layer on the map" to "noah_map",
        "how do i see where i am on the map" to "map_use",
        "the chart has a dotted line what is it" to "chart",
        "i drive a car which mode should i use" to "modes",
        "how do i change my password in settings" to "password_change",
        "is my location sent to your servers" to "privacy",
        "what weather service do you use" to "data_sources",
        "is samaritan the only street with a sensor" to "which_streets",
        "who is behind the bahgo chat" to "chatbot_itself",
        "what do i do to get ready before a typhoon" to "prepare"
    )

    @Test
    fun freshQuestionsMostlyReachTheRightAnswer() {
        val misses = fresh.filter { (q, want) -> model.match(q)?.takeIf { it.score >= ChatBot.MIN_SCORE }?.label != want }
        val accuracy = 1.0 - misses.size.toDouble() / fresh.size
        println("Fresh accuracy: %.0f%% (%d/%d)".format(accuracy * 100, fresh.size - misses.size, fresh.size))
        misses.forEach { (q, want) ->
            val m = model.match(q)
            println("  miss: \"$q\" -> ${m?.label} (%.2f), wanted $want".format(m?.score ?: 0.0))
        }
        // Measured, not tuned: about half are matched confidently. The rest fall back to the
        // keyword rules or "Sorry, I didn't get that". Fails if a change makes it clearly worse.
        assertTrue("Fresh accuracy %.0f%% is below 45%%".format(accuracy * 100), accuracy >= 0.45)
    }

    @Test
    fun unrelatedQuestionsDontMatch() {
        listOf("what's the best pizza", "who won the basketball game", "sing me a song", "asdfgh qwerty",
            "what time is it", "tell me a joke", "how old are you", "what is the capital of france").forEach { q ->
            val score = model.match(q)?.score ?: 0.0
            assertTrue("\"$q\" matched with $score", score < ChatBot.MIN_SCORE)
        }
    }

    @Test
    fun everyEntryAnswersItsOwnExamples() {
        val wrong = KnowledgeBase.entries.flatMap { e ->
            e.questions.mapNotNull { q -> model.match(q)?.label?.takeIf { it != e.id }?.let { "\"$q\" -> $it, wanted ${e.id}" } }
        }
        assertEquals("Examples that match another topic:\n" + wrong.joinToString("\n"), 0, wrong.size)
    }

    // ── With the live answers ──

    private val bot = ChatBot({
        listOf(
            StreetStatus(1, "Sealion Street", Risk.SAFE, 12.0, null, "No Rain", 13.0),
            StreetStatus(2, "Samaritan Street", Risk.MEDIUM, 38.0, "+4 cm/hr · Water is rising", "Light Rain", 41.0)
        )
    })

    @Test
    fun howItWorksQuestionsGetTheExplanationNotTheLiveNumbers() {
        assertTrue(bot.reply("How does the forecast work?").text.contains("Linear Regression"))
        assertTrue(bot.reply("how is the water level measured").text.contains("ultrasonic"))
        assertTrue(bot.reply("how does the rain sensor work").text.contains("rain sensor plate"))
    }

    @Test
    fun liveQuestionsStillUseTheSensors() {
        assertTrue(bot.reply("Will it flood soon?").text.startsWith("Forecast for the next 30 minutes"))
        assertTrue(bot.reply("water level now").text.contains("Samaritan Street: 38 cm, Warning"))
        assertTrue(bot.reply("is samaritan safe").text.contains("Samaritan Street"))
    }

    @Test
    fun newTopicsAreAnswered() {
        assertTrue(bot.reply("what is the safest route home").text.contains("Iwas-Baha Route"))
        assertTrue(bot.reply("what are the blue areas on the map").text.contains("Project NOAH"))
        assertTrue(bot.reply("are you an AI?").text.contains("TF-IDF"))
        assertTrue(bot.reply("How does BahGo work?").text.contains("step by step"))
    }

    @Test
    fun answersFillInModeAndEmail() {
        val r = bot.reply("what is car mode")
        assertTrue(r.text.contains("You're using Person Mode"))
        assertTrue(bot.reply("forgot my password").text.contains(ChatBot.CONTACT_EMAIL))
    }
}
