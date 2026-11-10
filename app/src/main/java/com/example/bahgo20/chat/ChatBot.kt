package com.example.bahgo20.chat

import com.example.bahgo20.Risk
import com.example.bahgo20.utils.EmergencyContact
import java.util.Locale
import kotlin.math.roundToInt

/** One street as the assistant sees it right now. */
data class StreetStatus(
    val id: Int,
    val street: String,
    val risk: Risk,                 // OFFLINE when the sensor isn't sending
    val levelCm: Double?,
    val trend: String? = null,      // e.g. "+4 cm/hr · Warning in ~25 min", null while offline
    val rain: String? = null,       // e.g. "Light Rain", null while offline
    val forecastCm: Double? = null  // expected level in 30 minutes, null if not ready
)

/** Something the user can tap under an answer. */
sealed class BotAction(val label: String) {
    class OpenStreet(val id: Int, street: String) : BotAction("Open $street")
    object OpenSettings : BotAction("Open Settings")
    object OpenAbout : BotAction("Open About")
    object CallEmergency : BotAction("Call ${EmergencyContact.SHORT_NAME}")
    object Email : BotAction("Email the BahGo Team")
    object ReplayTour : BotAction("Replay the Home tour")
}

data class BotReply(
    val text: String,
    val actions: List<BotAction> = emptyList(),
    val suggestions: List<String> = emptyList()
)

/**
 * BahGo Assistant: answers questions on the phone, with no online AI service.
 *
 *  - Questions about the water, safety, rain or the forecast are recognised by keywords
 *    (English and common Filipino words) and answered from the live sensor readings.
 *  - Questions about the app and how it works go to [IntentModel], a TF-IDF model trained on the
 *    example questions in [KnowledgeBase], which gives the in-depth answers there.
 */
class ChatBot(
    private val live: () -> List<StreetStatus>,
    private val hasVehicle: () -> Boolean = { false },
    private val homeStreet: () -> Int = { 1 }     // "your current location" picked on Home
) {

    companion object {
        const val CONTACT_EMAIL = "hannaduliesco@gmail.com"

        /** How close a question must be to a learned example (cosine similarity, 0..1). */
        const val MIN_SCORE = 0.30
        /** Closer match needed to override a live answer when the user asks how something works. */
        const val EXPLAIN_SCORE = 0.35
        /** Very close match: wins over a greeting or a bare street name. */
        const val STRONG_SCORE = 0.55

        val STARTERS = listOf(
            "Is it safe to go out?",
            "Water level now",
            "Will it flood soon?",
            "Is it raining?",
            "What do the colors mean?",
            "How does BahGo work?",
            "Emergency hotlines"
        )

        val WELCOME = BotReply(
            "Hi! I'm the BahGo Assistant. I answer questions about flooding in Palar Village and how to use the app.\n\n" +
                    "Ask me something like \"Is Samaritan Street safe?\" or tap a question below.",
            suggestions = STARTERS
        )
    }

    private enum class Topic {
        EMERGENCY, GREETING, THANKS, SAFE, LEVEL, FORECAST, RISE, RAIN, LEVELS_MEANING, FLOOD_RISK,
        OFFLINE, ALERTS, PASSWORD, PHONE, PROFILE, DELETE, PRIVACY, MAP, SEARCH, CHART, MODE,
        DARK_MODE, TOUR, SENSORS, PREPARE, ABOUT, CONTACT, HELP, ROUTE
    }

    private val LIVE_TOPICS = setOf(Topic.SAFE, Topic.LEVEL, Topic.FORECAST, Topic.RISE, Topic.RAIN)

    /** Words that mean "explain how it works" rather than "what is it right now". */
    private val EXPLAIN_CUES = listOf(
        "how does", "how do the", "how do you", "how is", "how are", "how was", "how accurate", "how reliable",
        "explain", "why", "work", "works", "paano", "bakit", "what model", "trained", "measured", "computed", "calculated"
    )

    /** Keywords per topic. Weight 3 = strong sign, 1 = weak hint. Checked on whole words/phrases. */
    private val keywords: Map<Topic, List<Pair<String, Int>>> = mapOf(
        Topic.EMERGENCY to listOf(
            "emergency" to 5, "911" to 5, "hotline" to 5, "hotlines" to 5, "rescue" to 5, "trapped" to 5,
            "drowning" to 5, "evacuate" to 4, "evacuation" to 4, "saklolo" to 5, "help me" to 5,
            "tulong" to 4, "red cross" to 5, "stuck" to 3, "bert" to 5, "southside" to 4, "rescue team" to 5
        ),
        Topic.GREETING to listOf(
            "hi" to 2, "hello" to 2, "hey" to 2, "good morning" to 2, "good afternoon" to 2,
            "good evening" to 2, "kumusta" to 2, "musta" to 2, "yo" to 1
        ),
        Topic.THANKS to listOf("thanks" to 3, "thank you" to 3, "ty" to 2, "salamat" to 3, "ok thanks" to 3),
        Topic.SAFE to listOf(
            "safe" to 3, "ligtas" to 3, "walk" to 2, "drive" to 2, "driving" to 2, "go out" to 3,
            "pass" to 2, "passable" to 3, "cross" to 2, "commute" to 2, "travel" to 2, "lakad" to 2,
            "dumaan" to 3, "pwede" to 1, "can i" to 1, "should i" to 1, "danger" to 2, "dangerous" to 2
        ),
        Topic.LEVEL to listOf(
            "water level" to 4, "level" to 2, "how high" to 3, "how deep" to 3, "deep" to 2,
            "tubig" to 2, "baha" to 2, "flooded" to 2, "flooding" to 2, "flood" to 1, "status" to 2,
            "now" to 1, "current" to 1, "right now" to 1, "update" to 1, "ngayon" to 1, "kamusta baha" to 3
        ),
        Topic.FORECAST to listOf(
            "forecast" to 4, "predict" to 3, "prediction" to 3, "later" to 2, "soon" to 2,
            "30 minutes" to 3, "next" to 1, "will it flood" to 4, "mamaya" to 2, "will the water" to 3
        ),
        Topic.RISE to listOf(
            "rise rate" to 5, "rising" to 3, "going up" to 3, "cm hr" to 3, "how fast" to 3,
            "tumataas" to 3, "falling" to 2, "going down" to 3, "bumababa" to 3
        ),
        Topic.RAIN to listOf(
            "rain" to 3, "raining" to 3, "ulan" to 3, "umuulan" to 3, "weather" to 2, "storm" to 2,
            "bagyo" to 2, "7 day" to 3, "week" to 2, "temperature" to 2, "typhoon" to 2
        ),
        Topic.LEVELS_MEANING to listOf(
            "mean" to 3, "meaning" to 3, "colors" to 4, "color" to 3, "green" to 2, "orange" to 2,
            "red" to 2, "gray" to 1, "levels" to 2, "warning" to 2, "critical" to 2, "threshold" to 3,
            "ibig sabihin" to 3
        ),
        Topic.FLOOD_RISK to listOf("flood risk" to 5, "risk" to 2, "lasso" to 4, "low" to 1),
        Topic.OFFLINE to listOf(
            "offline" to 5, "not updating" to 5, "no data" to 4, "not working" to 2, "gray" to 2,
            "grey" to 2, "stuck" to 1, "wrong reading" to 4, "dashes" to 3
        ),
        Topic.ALERTS to listOf(
            "notification" to 4, "notifications" to 4, "notif" to 4, "alert" to 3, "alerts" to 3,
            "notify" to 4, "warn me" to 3, "sound" to 1
        ),
        Topic.PASSWORD to listOf("password" to 5, "forgot" to 3, "reset" to 2, "log in" to 1, "login" to 1),
        Topic.PHONE to listOf("phone number" to 5, "mobile number" to 5, "number" to 2, "sim" to 2, "otp" to 3, "code" to 2),
        Topic.PROFILE to listOf("name" to 2, "profile" to 3, "account" to 2, "edit" to 1, "change my" to 1),
        Topic.DELETE to listOf("delete" to 5, "remove my account" to 5, "close my account" to 5, "deactivate" to 5),
        Topic.PRIVACY to listOf(
            "privacy" to 5, "my data" to 4, "data" to 1, "collect" to 3, "analytics" to 4,
            "cookies" to 4, "cookie" to 4, "location" to 2, "terms" to 3, "consent" to 3
        ),
        Topic.MAP to listOf("map" to 4, "dots" to 3, "dot" to 2, "hazard map" to 4, "my location" to 3),
        Topic.SEARCH to listOf("search" to 4, "find a place" to 4, "find" to 1),
        Topic.CHART to listOf("chart" to 5, "graph" to 5, "history" to 3, "simulated" to 4, "recorded" to 3, "dashed" to 4),
        Topic.MODE to listOf("person mode" to 5, "car mode" to 5, "vehicle" to 3, "car" to 2, "person" to 1),
        Topic.DARK_MODE to listOf("dark mode" to 5, "light mode" to 5, "theme" to 3, "dark" to 2),
        Topic.TOUR to listOf("tour" to 5, "tutorial" to 5, "how to use" to 4, "guide" to 2),
        Topic.SENSORS to listOf(
            "sensor" to 2, "sensors" to 2, "how does it work" to 4, "how it works" to 4,
            "measure" to 3, "ultrasonic" to 4, "device" to 2, "which streets" to 4, "streets" to 2,
            "accurate" to 3, "accuracy" to 3
        ),
        Topic.PREPARE to listOf(
            "prepare" to 4, "tips" to 4, "go bag" to 5, "what should i do" to 3, "what to do" to 3,
            "ready" to 2, "paghahanda" to 4
        ),
        Topic.ABOUT to listOf("who made" to 5, "developers" to 5, "developer" to 4, "about" to 2, "bahgo" to 1, "thesis" to 3, "sti" to 3),
        Topic.CONTACT to listOf("contact" to 5, "email" to 4, "report" to 3, "feedback" to 4, "bug" to 4, "complain" to 4),
        Topic.HELP to listOf("help" to 2, "what can you do" to 5, "options" to 2, "menu" to 2),
        Topic.ROUTE to listOf(
            "route" to 5, "routes" to 5, "iwas baha" to 6, "way out" to 5, "directions" to 4, "safest way" to 4,
            "best way" to 3, "daan papunta" to 4, "ruta" to 5
        )
    )

    // Street names people may type, for each sensor
    private val streetWords = mapOf(
        1 to listOf("sealion", "sea lion", "sensor 1", "device 1", "street 1"),
        2 to listOf("samaritan", "sensor 2", "device 2", "street 2"),
        3 to listOf("swingfire", "swing fire", "sensor 3", "device 3", "street 3"),
        4 to listOf("striker", "sensor 4", "device 4", "street 4")
    )

    /** Trained once, on the example questions in [KnowledgeBase]. */
    private val model by lazy { IntentModel(KnowledgeBase.trainingData()) }

    fun reply(input: String): BotReply {
        val text = normalize(input)
        if (text.isBlank()) return help()

        val street = streetIn(text)
        val topic = topicOf(text)
        if (topic == Topic.EMERGENCY) return emergency()

        val learned = model.match(input)?.takeIf { it.score >= MIN_SCORE }
        val explaining = EXPLAIN_CUES.any { has(text, it) }

        return when {
            // Live questions (water, safety, rain, forecast) use the sensors, unless the user
            // asks how something works ("how does the forecast work?")
            topic in LIVE_TOPICS ->
                if (explaining && learned != null && learned.score >= EXPLAIN_SCORE) knowledge(learned.label)
                else answer(topic!!, street, text)
            topic == Topic.GREETING || topic == Topic.THANKS ->
                if (learned != null && learned.score >= STRONG_SCORE) knowledge(learned.label) else answer(topic, street, text)
            // Just a street name: that street's water level
            topic == null && street != null && (learned == null || learned.score < STRONG_SCORE) -> level(street)
            learned != null -> knowledge(learned.label)
            topic != null -> answer(topic, street, text)
            street != null -> level(street)
            else -> fallback()
        }
    }

    /** An in-depth answer from the knowledge base. */
    private fun knowledge(e: KnowledgeBase.Entry) = BotReply(
        e.answer
            .replace("{MODE}", if (hasVehicle()) "Car Mode" else "Person Mode")
            .replace("{EMAIL}", CONTACT_EMAIL),
        e.actions,
        e.suggestions
    )

    private fun answer(topic: Topic, street: Int?, text: String): BotReply =
        when (topic) {
            Topic.EMERGENCY -> emergency()
            Topic.GREETING -> BotReply("Hello! How can I help? You can ask about the water level, safety, rain or the app.", suggestions = STARTERS)
            Topic.THANKS -> BotReply("You're welcome! Stay safe. 💙", suggestions = listOf("Water level now", "Is it safe to go out?"))
            Topic.SAFE -> safety(street)
            Topic.LEVEL -> level(street)
            Topic.FORECAST -> forecast(street)
            Topic.RISE -> rise(street)
            Topic.RAIN -> rain(street, text)
            Topic.LEVELS_MEANING -> levelsMeaning()
            Topic.FLOOD_RISK -> floodRisk()
            Topic.OFFLINE -> offline()
            Topic.ALERTS -> alerts()
            Topic.PASSWORD -> password(text)
            Topic.PHONE -> phone()
            Topic.PROFILE -> profile()
            Topic.DELETE -> deleteAccount()
            Topic.PRIVACY -> privacy()
            Topic.MAP -> map()
            Topic.SEARCH -> search()
            Topic.CHART -> chart()
            Topic.MODE -> mode()
            Topic.DARK_MODE -> darkMode()
            Topic.TOUR -> tour()
            Topic.SENSORS -> sensors()
            Topic.PREPARE -> prepare()
            Topic.ABOUT -> about()
            Topic.CONTACT -> contact()
            Topic.HELP -> help()
            Topic.ROUTE -> knowledge(KnowledgeBase.entries.first { it.id == "route_planner" })
        }

    // ───────────────────────── Understanding the question ─────────────────────────

    private fun normalize(s: String): String =
        " " + s.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9ñ ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim() + " "

    private fun has(text: String, phrase: String) = text.contains(" $phrase ")

    private fun topicOf(text: String): Topic? {
        var best: Topic? = null
        var bestScore = 0
        for ((topic, words) in keywords) {          // map keeps the order above, so ties go to the earlier topic
            val score = words.sumOf { (w, weight) -> if (has(text, w)) weight else 0 }
            if (score > bestScore) { best = topic; bestScore = score }
        }
        return best
    }

    private fun streetIn(text: String): Int? =
        streetWords.entries.firstOrNull { (_, words) -> words.any { has(text, it) } }?.key

    // ───────────────────────── Live answers ─────────────────────────

    private fun streets(): List<StreetStatus> = live().sortedBy { it.id }

    private fun pick(id: Int?): StreetStatus? = id?.let { i -> streets().firstOrNull { it.id == i } }

    private fun label(r: Risk) = when (r) {
        Risk.SAFE -> "Safe"
        Risk.MEDIUM -> "Warning"
        Risk.CRITICAL -> "Critical"
        Risk.OFFLINE -> "Offline"
    }

    private fun cm(v: Double) = "${v.roundToInt().coerceAtLeast(0)} cm"

    private fun line(s: StreetStatus): String =
        if (s.risk == Risk.OFFLINE) "• ${s.street}: offline (no live reading)"
        else "• ${s.street}: ${s.levelCm?.let { cm(it) } ?: "--"}, ${label(s.risk)}"

    private fun streetChips(exclude: Int? = null) =
        streets().filter { it.id != exclude }.take(2).map { "Is ${it.street} safe?" }

    private fun level(id: Int?): BotReply {
        val one = pick(id)
        if (one != null) {
            if (one.risk == Risk.OFFLINE) return offlineStreet(one)
            val text = "${one.street} is at ${one.levelCm?.let { cm(it) } ?: "--"} right now: ${label(one.risk)}." +
                    (one.trend?.let { "\nTrend: $it." } ?: "") +
                    "\n\n" + meaningOf(one.risk)
            return BotReply(text, listOf(BotAction.OpenStreet(one.id, one.street)),
                listOf("Will it flood soon?", "Is it safe to go out?"))
        }
        val all = streets()
        if (all.isEmpty()) return noData()
        val worst = all.filter { it.risk != Risk.OFFLINE }.maxByOrNull { it.risk.ordinal }
        val summary = when (worst?.risk) {
            null -> "None of the sensors are sending live readings right now."
            Risk.CRITICAL -> "⚠️ At least one street is CRITICAL. Avoid it."
            Risk.MEDIUM -> "Some water on the streets: be careful."
            else -> "All online streets are safe right now."
        }
        return BotReply(
            "Water levels right now:\n" + all.joinToString("\n") { line(it) } + "\n\n" + summary,
            suggestions = listOf("Will it flood soon?", "What do the colors mean?") + streetChips()
        )
    }

    private fun safety(id: Int?): BotReply {
        val car = hasVehicle()
        val one = pick(id)
        val all = streets()
        val target = one ?: all.firstOrNull { it.id == homeStreet() } ?: all.firstOrNull() ?: return noData()
        if (target.risk == Risk.OFFLINE) return offlineStreet(target)

        val lvl = target.levelCm?.let { cm(it) } ?: "--"
        val advice = when (target.risk) {
            Risk.SAFE -> if (car) "It's safe to drive through. Still slow down near low spots."
            else "It's safe to walk through. Watch your step if it's raining."
            Risk.MEDIUM -> if (car) "Drive only if you must, slowly, and only with a high vehicle. Small cars can stall in knee-deep water."
            else "Water is around knee level. Avoid walking through if you can, and never let children cross."
            Risk.CRITICAL -> if (car) "Do NOT drive through. Water this deep can stall or float a car. Take another route."
            else "Do NOT walk through. Waist-deep water can sweep you off your feet and may hide open drains. Stay indoors or on higher ground."
            Risk.OFFLINE -> ""
        }
        val others = all.filter { it.id != target.id && (it.risk == Risk.MEDIUM || it.risk == Risk.CRITICAL) }
        val othersText = if (others.isEmpty()) "" else
            "\n\nAlso note: " + others.joinToString("; ") { "${it.street} is ${label(it.risk)} (${it.levelCm?.let { v -> cm(v) } ?: "--"})" } + "."
        val who = if (one == null) " (your nearest sensor)" else ""
        val rising = target.trend?.takeIf { it.startsWith("+") }?.let { "\nThe water is rising: $it." } ?: ""
        return BotReply(
            "${target.street}$who: $lvl, ${label(target.risk)}.$rising\n\n$advice$othersText\n\n" +
                    "Always follow official advisories from your barangay and Taguig City.",
            listOf(BotAction.OpenStreet(target.id, target.street)),
            listOf("Will it flood soon?") + streetChips(target.id)
        )
    }

    private fun forecast(id: Int?): BotReply {
        val targets = pick(id)?.let { listOf(it) } ?: streets()
        if (targets.isEmpty()) return noData()
        val lines = targets.map { s ->
            when {
                s.risk == Risk.OFFLINE -> "• ${s.street}: offline, no forecast"
                s.forecastCm == null -> "• ${s.street}: still collecting readings (ready a few minutes after the app opens)"
                else -> {
                    val now = s.levelCm ?: 0.0
                    val diff = s.forecastCm - now
                    val dir = when {
                        diff > 2 -> "rising"
                        diff < -2 -> "going down"
                        else -> "about the same"
                    }
                    "• ${s.street}: about ${cm(s.forecastCm)} in 30 min ($dir), ${label(levelRisk(s.forecastCm))}"
                }
            }
        }
        val worse = targets.any { s ->
            s.forecastCm != null && s.risk != Risk.OFFLINE && levelRisk(s.forecastCm).ordinal > s.risk.ordinal
        }
        return BotReply(
            "Forecast for the next 30 minutes:\n" + lines.joinToString("\n") +
                    (if (worse) "\n\n⚠️ A street is expected to reach a higher risk level. Plan ahead." else "") +
                    "\n\nForecasts are estimates and can be wrong.",
            pick(id)?.let { listOf(BotAction.OpenStreet(it.id, it.street)) } ?: emptyList(),
            listOf("Is it safe to go out?", "Is it raining?")
        )
    }

    private fun rise(id: Int?): BotReply {
        val targets = pick(id)?.let { listOf(it) } ?: streets()
        if (targets.isEmpty()) return noData()
        val lines = targets.map { s ->
            "• ${s.street}: " + when {
                s.risk == Risk.OFFLINE -> "offline"
                s.trend == null -> "measuring…"
                else -> s.trend
            }
        }
        return BotReply(
            "How fast the water is changing:\n" + lines.joinToString("\n") +
                    "\n\nRise rate compares the last 15 minutes of readings. \"Measuring…\" means it needs a few more minutes.",
            suggestions = listOf("Will it flood soon?", "Water level now")
        )
    }

    private fun rain(id: Int?, text: String): BotReply {
        val wantsWeek = has(text, "week") || has(text, "7 day") || has(text, "temperature") ||
                has(text, "tomorrow") || has(text, "bukas")
        if (wantsWeek) return BotReply(
            "The 7-day forecast is on the Home screen, under the street boxes. It shows the chance of rain and the high/low temperature for each day (from Open-Meteo).",
            suggestions = listOf("Is it raining?", "Will it flood soon?")
        )
        val targets = pick(id)?.let { listOf(it) } ?: streets()
        if (targets.isEmpty()) return noData()
        val lines = targets.map { s ->
            "• ${s.street}: " + if (s.risk == Risk.OFFLINE || s.rain == null) "no live rain reading" else s.rain
        }
        return BotReply(
            "Rain from the street sensors right now:\n" + lines.joinToString("\n") +
                    "\n\nFor the next 30 minutes, open a street's Conditions and look at Rain Forecast. The 7-day outlook is on the Home screen.",
            suggestions = listOf("Will it flood soon?", "Weather this week")
        )
    }

    private fun levelRisk(v: Double) = when {
        v > 60 -> Risk.CRITICAL
        v > 30 -> Risk.MEDIUM
        else -> Risk.SAFE
    }

    private fun meaningOf(r: Risk) = when (r) {
        Risk.SAFE -> "Safe means 30 cm or less (around ankle to below the knee)."
        Risk.MEDIUM -> "Warning means over 30 cm (around knee level). Be careful."
        Risk.CRITICAL -> "Critical means over 60 cm (waist level and up). Stay away."
        Risk.OFFLINE -> ""
    }

    private fun offlineStreet(s: StreetStatus) = BotReply(
        "The ${s.street} sensor is offline, so I don't have a live reading for it. " +
                "It may have lost power or internet. Check the other streets or look outside carefully.",
        suggestions = listOf("Water level now", "Why is a sensor offline?")
    )

    private fun noData() = BotReply(
        "I don't have readings from the sensors yet. Check your internet connection and try again in a moment.",
        suggestions = listOf("Water level now")
    )

    // ───────────────────────── App and help answers ─────────────────────────

    private fun emergency() = BotReply(
        "If you or someone is in danger, call the ${EmergencyContact.NAME} now: ${EmergencyContact.NUMBER_DISPLAY}.\n\n" +
                "• National emergency hotline: 911\n" +
                "• Philippine Red Cross: 143\n" +
                "• Your barangay hall and the Taguig City rescue office can also help.\n\n" +
                "While waiting: move to higher ground, stay away from flood water and electrical wires, and don't try to cross moving water.",
        listOf(BotAction.CallEmergency),
        listOf("Is it safe to go out?", "Flood safety tips")
    )

    private fun levelsMeaning() = BotReply(
        "BahGo uses three levels, based on how deep the water is on the street:\n\n" +
                "🟢 Safe: 30 cm or less\n🟠 Warning: over 30 cm (around knee level)\n🔴 Critical: over 60 cm (waist level and up)\n⚪ Gray: the sensor is offline\n\n" +
                "The same colors are used on the map dots, the street cards and the alerts.",
        suggestions = listOf("Water level now", "What is flood risk?")
    )

    private fun floodRisk() = BotReply(
        "Flood Risk (Low, Warning or Critical) looks at both the water level and the rain on a street to tell how likely it is to flood. " +
                "It's in each street's Conditions screen.\n\nIt's an estimate, so always check the actual water level too.",
        suggestions = listOf("Water level now", "Will it flood soon?")
    )

    private fun offline() = BotReply(
        "A sensor shows Offline (gray) when it hasn't sent a reading for about 2 minutes. Usually it lost power or Wi-Fi.\n\n" +
                "• Check your own internet first: if every street is offline, it may be your connection.\n" +
                "• If one street stays offline, the other streets are still live.\n" +
                "Report a sensor that stays offline to $CONTACT_EMAIL.",
        listOf(BotAction.Email),
        listOf("Water level now")
    )

    private fun alerts() = BotReply(
        "BahGo sends a notification when a street rises to Warning or Critical, even when the app is closed.\n\n" +
                "Not getting them?\n• Turn on Flood Alerts in Settings > Notifications.\n" +
                "• Allow notifications for BahGo in your phone's settings.\n" +
                "• Turn off battery saver for BahGo so the monitor keeps running.",
        listOf(BotAction.OpenSettings),
        listOf("What do the colors mean?")
    )

    private fun password(text: String) =
        if (has(text, "forgot") || has(text, "reset")) BotReply(
            "Forgot your password? Email $CONTACT_EMAIL with your name and registered mobile number, and the team will help you reset it.\n\n" +
                    "If you're logged in, you can change it in Settings > Privacy & Security > Change password.",
            listOf(BotAction.Email, BotAction.OpenSettings)
        ) else BotReply(
            "To change your password: Settings > Privacy & Security > Change password. Enter your current password, then the new one twice.",
            listOf(BotAction.OpenSettings)
        )

    private fun phone() = BotReply(
        "To change your mobile number: Settings > Privacy & Security > Change phone number. We'll send a one-time code by SMS to the new number.\n\n" +
                "Didn't get the code? Check the number, wait a minute, and tap Resend.",
        listOf(BotAction.OpenSettings)
    )

    private fun profile() = BotReply(
        "You can edit your first name and surname in Settings > Account. Changes save to your account right away.",
        listOf(BotAction.OpenSettings)
    )

    private fun deleteAccount() = BotReply(
        "To delete your account and data, email $CONTACT_EMAIL with your registered mobile number. Your account details are deleted within 30 days.\n\n" +
                "You can also clear what's on your phone: Android Settings > Apps > BahGo > Storage > Clear data.",
        listOf(BotAction.Email)
    )

    private fun privacy() = BotReply(
        "BahGo keeps only what it needs: your name, mobile number, a scrambled (hashed) password, your settings and login times.\n\n" +
                "• Your location stays on your phone.\n• Usage statistics are off unless you turn them on.\n• No ads, and your data is never sold.\n\n" +
                "Read the full Privacy Policy, Terms and Cookie Policy in About.",
        listOf(BotAction.OpenAbout, BotAction.OpenSettings)
    )

    private fun map() = BotReply(
        "The map on Home shows Palar Village with the flood hazard areas from Project NOAH.\n\n" +
                "• Colored dots are the 4 street sensors (green Safe, orange Warning, red Critical, gray Offline). Tap one to open that street.\n" +
                "• The target button centers the map on you (if location is allowed).",
        suggestions = listOf("How do I search a place?", "What do the colors mean?")
    )

    private fun search() = BotReply(
        "Tap the magnifying glass at the top right of Home. You can pick one of the 4 sensors or type any place in Taguig. Tap a result and it's shown on the map. Tap the pin label to hide it.",
        suggestions = listOf("How do I use the map?")
    )

    private fun chart() = BotReply(
        "The Flood Severity Chart is in each street's Conditions. The solid line is live readings from the sensor. " +
                "The dashed part is recorded sample (simulated) data used for demonstration, not real history.",
        suggestions = listOf("Will it flood soon?")
    )

    private fun mode(): BotReply {
        val now = if (hasVehicle()) "Car Mode" else "Person Mode"
        return BotReply(
            "Person Mode gives advice for walking; Car Mode gives advice for driving. You're using $now.\n\nChange it in Settings > Preferences.",
            listOf(BotAction.OpenSettings),
            listOf("Is it safe to go out?")
        )
    }

    private fun darkMode() = BotReply(
        "Switch between light and dark mode in Settings > Preferences.",
        listOf(BotAction.OpenSettings)
    )

    private fun tour() = BotReply(
        "I can replay the short Home tour that shows where everything is. You can also find it in Settings > About > App tour.",
        listOf(BotAction.ReplayTour)
    )

    private fun sensors() = BotReply(
        "BahGo has 4 street sensors in Palar Village: Sealion, Samaritan, Swingfire and Striker Street.\n\n" +
                "Each one has an ultrasonic sensor mounted 94 cm above the street. It measures the distance down to the ground or water, so less distance means deeper water. " +
                "A rain sensor reports how hard it's raining. Readings go to the app every few seconds.",
        suggestions = listOf("Water level now", "What do the colors mean?")
    )

    private fun prepare() = BotReply(
        "Flood safety tips:\n" +
                "• Keep a go bag: water, food, flashlight, power bank, medicine, IDs in a plastic bag.\n" +
                "• Move valuables and appliances to a higher place.\n" +
                "• Turn off the main power switch if water may enter your home.\n" +
                "• Never walk or drive through moving water.\n" +
                "• Avoid flood water: it can carry leptospirosis. Wear boots and wash well.\n" +
                "• Follow evacuation orders from your barangay right away.",
        suggestions = listOf("Emergency hotlines", "Is it safe to go out?")
    )

    private fun about() = BotReply(
        "BahGo is a flood-watch app for Palar Village, Taguig, made by 4th-year BS Computer Science students of STI College Global City: " +
                "Johanna Vic Duliesco, Sophia Sargado, Kaye Levantino and Christine Jessica Reyes.",
        listOf(BotAction.OpenAbout)
    )

    private fun contact() = BotReply(
        "You can reach the BahGo Team at $CONTACT_EMAIL for questions, bug reports or feedback.",
        listOf(BotAction.Email)
    )

    private fun help() = BotReply(
        "I can help with:\n• Water levels, safety and the 30-minute forecast for each street\n• Rain now and the 7-day outlook\n" +
                "• How BahGo works: the sensors, the forecast and flood risk models, the NOAH map and Iwas-Baha Route\n" +
                "• What the colors and levels mean\n• Alerts, account, password and privacy\n• Emergency hotlines and flood safety tips",
        suggestions = STARTERS
    )

    private fun fallback() = BotReply(
        "Sorry, I didn't get that. Try asking about the water level, safety, rain, alerts or your account. " +
                "For anything else, email $CONTACT_EMAIL.",
        listOf(BotAction.Email),
        STARTERS.take(4)
    )
}
