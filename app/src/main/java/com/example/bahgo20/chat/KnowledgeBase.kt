package com.example.bahgo20.chat

/**
 * What the assistant knows about BahGo and how it works: one entry per topic, each with
 * example questions (the training data for [IntentModel]) and an in-depth answer.
 *
 * To teach the assistant something new: add an entry (or more example questions to one),
 * then run the unit tests. ChatBotTest checks that new phrasings still find the right answer.
 *
 * In answers, {MODE} becomes "Person Mode"/"Car Mode" and {EMAIL} the team's email.
 */
object KnowledgeBase {

    class Entry(
        val id: String,
        val questions: List<String>,
        val answer: String,
        val actions: List<BotAction> = emptyList(),
        val suggestions: List<String> = emptyList()
    )

    val entries: List<Entry> = listOf(

        Entry(
            "how_app_works",
            listOf(
                "how does bahgo work", "how does the app work", "what is bahgo", "what does this app do",
                "explain the app", "how does the system work", "what is the purpose of bahgo", "paano gumagana ang bahgo",
                "ano ang bahgo", "overview of the app", "what can the app do", "how does bahgo know about floods", "what is this app", "what is the app used for", "what does bahgo do for residents", "para saan ang app na ito"
            ),
            "BahGo is a flood-watch app for Palar Village, Taguig. Here's how it works, step by step:\n\n" +
                    "1. Sensors: 4 devices on Sealion, Samaritan, Swingfire and Striker Street measure the water depth and the rain every few seconds.\n" +
                    "2. Cloud: each device sends its readings to the BahGo database (Firebase Realtime Database).\n" +
                    "3. App: BahGo reads them live, turns the depth into a level (Safe, Warning, Critical) and colors the map and street cards.\n" +
                    "4. Forecast: a Linear Regression model, trained on recorded flood days, estimates the water level 30 minutes ahead, and a Lasso Regression model gives each street a flood risk score.\n" +
                    "5. Alerts: when a street reaches Warning or Critical, BahGo notifies you, even when the app is closed.\n" +
                    "6. Iwas-Baha Route: plans a walking or driving route that avoids flooded streets and NOAH flood-hazard areas.",
            suggestions = listOf("How do the sensors measure the water?", "How does the forecast work?", "How does Iwas-Baha Route work?")
        ),

        Entry(
            "sensor_measure",
            listOf(
                "how do the sensors measure the water", "how is the water level measured", "how does the sensor work",
                "how does the device measure flood", "what kind of sensor is used", "ultrasonic sensor", "how do you know the water level",
                "paano sinusukat ang tubig", "paano gumagana ang sensor", "where does the water level come from",
                "how often do the sensors update", "what hardware is used", "how is the depth computed", "how does the sensor detect the water depth", "how does the device sense flood water", "how deep is the water measured", "paano nasusukat ang lalim ng tubig", "how does the ultrasonic sensor know the depth"
            ),
            "Each BahGo device has an ultrasonic sensor mounted 94 cm above the street, pointing down. It sends a sound pulse and times the echo to get the distance to the ground or the water.\n\n" +
                    "• Water level = 94 cm − measured distance. Dry street: about 94 cm away, so 0 cm of water. If the distance drops to 54 cm, there are 40 cm of water.\n" +
                    "• A rain sensor on the same device reports how wet it is (from No Rain to Heavy Rain).\n" +
                    "• The device sends a new reading every few seconds, with the time it was taken (Last_Updated).\n" +
                    "• If no new reading arrives for about 2 minutes, the app marks that street Offline (gray).",
            suggestions = listOf("What do the colors mean?", "Why is a sensor offline?", "How accurate is BahGo?")
        ),

        Entry(
            "levels_colors",
            listOf(
                "what do the colors mean", "what does green mean", "what does orange mean", "what does red mean", "what do the levels mean",
                "what is warning", "what is critical", "what is safe", "thresholds", "ano ibig sabihin ng kulay",
                "when is it critical", "how deep is warning", "gray dot meaning", "meaning of safe warning critical", "what level is dangerous", "red meaning", "at what water level is it safe"
            ),
            "BahGo uses three levels, based on how deep the water is on the street:\n\n" +
                    "🟢 Safe: 30 cm or less (ankle to just below the knee)\n" +
                    "🟠 Warning: over 30 cm (around knee level). Walking is risky, small cars can stall.\n" +
                    "🔴 Critical: over 60 cm (waist level and up). Don't walk or drive through.\n" +
                    "⚪ Gray: the sensor is offline, so there's no live reading.\n\n" +
                    "The same colors are used on the map dots, the street cards, the chart lines and the alerts.",
            suggestions = listOf("Water level now", "What is flood risk?", "How do the sensors measure the water?")
        ),

        Entry(
            "offline",
            listOf(
                "why is a sensor offline", "sensor offline", "why is it gray", "not updating", "no data from sensor",
                "why does it say offline", "device not working", "the sensor is on but the app says offline",
                "bakit offline", "walang data", "readings not showing", "sensor not sending data", "why does the street show gray", "the device is working but the app shows no reading"
            ),
            "A sensor shows Offline (gray) when it hasn't sent a reading for about 2 minutes. Common reasons:\n\n" +
                    "• The device lost power or Wi-Fi.\n" +
                    "• Your phone has no internet: if every street is offline at once, check your connection first.\n" +
                    "• The app can't get into the database (for example, after the database rules changed). Updating to the latest BahGo usually fixes this.\n\n" +
                    "The other streets keep working while one is offline. If one stays offline, report it to {EMAIL}.",
            actions = listOf(BotAction.Email),
            suggestions = listOf("Water level now", "How do the sensors measure the water?")
        ),

        Entry(
            "forecast_model",
            listOf(
                "how does the forecast work", "how do you predict the water level", "what model do you use", "linear regression",
                "how is the forecast computed", "is the forecast ai", "machine learning", "how was the model trained",
                "paano nahuhulaan ang baha", "how does bahgo predict floods", "what data trained the model", "how far ahead is the forecast", "how was the model trained", "what algorithm predicts the water level", "what was the forecast trained on", "is the prediction machine learning"
            ),
            "The 30-minute forecast uses Linear Regression, a machine-learning model that learns from past readings.\n\n" +
                    "• Inputs: the water level now and how much it changed over the last 5, 15 and 30 minutes.\n" +
                    "• Output: the expected water level 30 minutes from now, checked against the Safe/Warning/Critical lines.\n" +
                    "• Training data: recorded flood days (Oct 13–28) from all 4 sensors, stored inside the app, so no download is needed.\n" +
                    "• Testing: on Oct 22–28, days it never saw, its average error was about 0.6 cm, better than assuming \"the water stays the same\".\n" +
                    "• A quick version works after only 5 minutes of readings; the full one needs 30 minutes.\n\n" +
                    "It's an estimate, so always look at the actual water too.",
            suggestions = listOf("Will it flood soon?", "What is flood risk?", "How accurate is BahGo?")
        ),

        Entry(
            "flood_risk",
            listOf(
                "what is flood risk", "what is the risk score", "lasso regression", "how is flood risk computed",
                "what does flood risk mean", "risk score meaning", "why is the risk high", "ano ang flood risk",
                "how does the risk score use rain", "flood risk low warning critical", "what is the risk score based on", "how is the flood risk number made"
            ),
            "Flood Risk is a score from 0 to 100 for each street, made by a Lasso Regression model.\n\n" +
                    "• It combines the water level, how fast it's changing, and the rain (rain now and how the rain changed).\n" +
                    "• Lasso automatically drops inputs that don't help, so the model stays simple and explainable.\n" +
                    "• Labels: Low below 50, Warning from 50, Critical from 90.\n" +
                    "• Each street gets its own score, because one street can flood while another stays dry.\n\n" +
                    "You'll find it on Home under Risk Level and in each street's Conditions.",
            suggestions = listOf("How does the forecast work?", "What do the colors mean?")
        ),

        Entry(
            "rise_rate",
            listOf(
                "what is rise rate", "how is rise rate computed", "what does cm hr mean", "how fast is the water rising explained",
                "rise rate meaning", "what does steady mean", "minutes until the next level", "ano ang rise rate", "what does the rise rate box show", "what does falling mean"
            ),
            "Rise Rate shows how fast the water is going up or down, in cm per hour.\n\n" +
                    "• It fits a straight line through the readings from the last 15 minutes (it needs at least 4 readings spread over 3 minutes).\n" +
                    "• Changes smaller than 3 cm/hr, or too small to tell apart from sensor noise, count as Steady.\n" +
                    "• When the water is rising, BahGo also estimates how long until the street reaches the next level (Warning, then Critical), up to 4 hours ahead.",
            suggestions = listOf("How fast is the water rising?", "Will it flood soon?")
        ),

        Entry(
            "rain_explained",
            listOf(
                "how does the rain sensor work", "how is rain measured", "what does light rain mean", "rain forecast",
                "how does the rain forecast work", "rain sensor", "heavy rain meaning", "paano sinusukat ang ulan", "how is rain detected", "how does bahgo know it is raining", "how do the devices sense rain"
            ),
            "Each device has a rain sensor plate: the wetter it gets, the lower its reading.\n\n" +
                    "• BahGo turns the reading into No Rain, Light, Moderate or Heavy Rain.\n" +
                    "• The Rain Forecast in each street's Conditions estimates the rain 30 minutes ahead from recent rain readings. It's simple: it mostly tells whether the rain is likely to continue.\n" +
                    "• For the bigger picture, the 7-day forecast on Home comes from Open-Meteo.",
            suggestions = listOf("Is it raining?", "Weather this week")
        ),

        Entry(
            "alerts",
            listOf(
                "not getting notifications", "how do alerts work", "turn on notifications", "how does bahgo notify me",
                "why didnt i get an alert", "flood alerts", "monitoring notification", "what is bahgo is monitoring floods",
                "walang notif", "paano mag notify", "alerts when app is closed", "notification settings", "i am not receiving notifications", "how do i get flood alerts", "no alerts on my phone", "how can bahgo warn me", "why no notification", "sms alert", "text message alert", "do you send sms"
            ),
            "BahGo sends a notification when a street rises to Warning or Critical, even when the app is closed.\n\n" +
                    "How: a small background monitor keeps listening to the 4 sensors. Android requires a permanent notification for this, so you'll see \"BahGo is monitoring floods\" with the current street status.\n\n" +
                    "SMS: when a street reaches Critical, BahGo also texts your registered mobile number (while you're logged in), at most once per street every 30 minutes.\n\n" +
                    "Not getting alerts?\n" +
                    "• Turn on Flood Alerts in Settings > Notifications.\n" +
                    "• Allow notifications for BahGo in your phone's settings.\n" +
                    "• Turn off battery saver for BahGo so the monitor keeps running.",
            actions = listOf(BotAction.OpenSettings),
            suggestions = listOf("What do the colors mean?", "Why is a sensor offline?")
        ),

        Entry(
            "route_planner",
            listOf(
                "how does iwas baha route work", "safest route", "how do i find a safe route", "route planner",
                "how are routes chosen", "what does the green route mean", "what does the orange route mean", "avoid flooded streets",
                "directions avoiding flood", "paano maghanap ng ligtas na daan", "safe way home", "way out", "best route to avoid flood", "how does bahgo choose the route", "what does a red route mean", "how are the route options ranked"
            ),
            "Iwas-Baha Route is the From/To card at the top of Home. It finds a way around the flood:\n\n" +
                    "1. Pick From (your current location by default) and To (any place in Taguig, or a sensor street), then tap Find safe route.\n" +
                    "2. BahGo gets several walking or driving routes from OpenStreetMap routing (OSRM), matching Person or Car Mode.\n" +
                    "3. Each route is checked against the Project NOAH 5-year flood-hazard map (how many metres run through Low, Medium and High hazard areas) and the live sensors near it (within about 60 m).\n" +
                    "4. Results: green = easiest & safest; orange = passable with caution (with the reason); red = avoid (passes a Critical street). Car Mode is stricter: Warning water of 45 cm or more means Avoid.\n\n" +
                    "If no safe route exists, BahGo says so and gives you a button to call the Southside BERT Rescue Team (0998 975 0368).",
            suggestions = listOf("What is the NOAH flood map?", "What is Person Mode?")
        ),

        Entry(
            "noah_map",
            listOf(
                "what is the noah flood map", "what are the blue areas on the map", "what is project noah", "flood hazard map",
                "5 year flood hazard", "what does dark blue mean on the map", "hazard areas", "ano ang noah", "what are the blue zones", "what do the blue areas mean", "flood prone areas on the map"
            ),
            "The blue areas on the map are Project NOAH's 5-year flood-hazard map (via the BetterGov PH dataset). It shows where flooding is expected in heavy rain that happens about once every 5 years:\n\n" +
                    "• Light blue: Low hazard\n• Blue: Medium hazard\n• Dark blue: High hazard\n\n" +
                    "It's a map of where water usually collects, not live water. Live water comes from the 4 sensors. Iwas-Baha Route uses both.",
            suggestions = listOf("How does Iwas-Baha Route work?", "How do I use the map?")
        ),

        Entry(
            "map_use",
            listOf(
                "how do i use the map", "what are the dots on the map", "how to see my location", "map dots",
                "tap the dot", "center map on me", "paano gamitin ang mapa", "what is the pin on the map", "what are the colored circles on the map", "what does tapping a dot do"
            ),
            "The map on Home shows Palar Village:\n\n" +
                    "• Colored dots are the 4 street sensors (green Safe, orange Warning, red Critical, gray Offline). Tap one to open that street's Conditions.\n" +
                    "• Blue areas are the NOAH flood-hazard zones.\n" +
                    "• The pin button at the top right centers the map on you (if location is allowed).\n" +
                    "• A place picked in Search appears as a red pin. Tap its label to hide it.\n" +
                    "• Iwas-Baha routes are drawn as green, orange or red lines.",
            suggestions = listOf("What is the NOAH flood map?", "How do I search a place?")
        ),

        Entry(
            "search",
            listOf(
                "how do i search a place", "search for a place", "find a location", "how to search",
                "search places in taguig", "maghanap ng lugar", "where is the search button", "look up a place", "search a street or building", "how do i find a location in taguig"
            ),
            "Tap the magnifying glass at the top right of Home. You can pick one of the 4 sensor streets or type any place in Taguig (from OpenStreetMap). Tap a result and the map flies there and drops a pin. Tap the pin label to hide it.",
            suggestions = listOf("How do I use the map?", "How does Iwas-Baha Route work?")
        ),

        Entry(
            "chart",
            listOf(
                "what is the chart", "flood severity chart", "what does the dashed line mean", "what is recorded data",
                "simulated data", "chart history", "what does the dotted line mean", "graph meaning", "all recorded"
            ),
            "The Flood Severity Chart is in each street's Conditions:\n\n" +
                    "• Solid line: live readings from the sensor.\n" +
                    "• Dashed line: recorded sample (simulated) data from Oct 13–28, used for demonstration and to train the forecast. It's stored inside the app.\n" +
                    "• Dotted line: the 30-minute forecast, when it's ready.\n" +
                    "• Orange and red lines mark Warning (30 cm) and Critical (60 cm).\n\n" +
                    "Use 6h, 24h, 3 days or All recorded to change the time window.",
            suggestions = listOf("How does the forecast work?", "What do the colors mean?")
        ),

        Entry(
            "modes",
            listOf(
                "what is person mode", "what is car mode", "person mode vs car mode", "change to car mode",
                "do i have a vehicle setting", "walking or driving mode", "mode setting", "ano ang car mode"
            ),
            "Person Mode gives advice for walking, and Car Mode for driving. You're using {MODE}.\n\n" +
                    "• Safety answers change: water at knee level is \"avoid walking\" on foot, but can stall a small car.\n" +
                    "• Iwas-Baha Route gives walking routes in Person Mode and driving routes in Car Mode, and is stricter for cars.\n\n" +
                    "Change it in Settings > Preferences. In Iwas-Baha Route you can also switch Walk/Car for one search.",
            actions = listOf(BotAction.OpenSettings),
            suggestions = listOf("Is it safe to go out?", "How does Iwas-Baha Route work?")
        ),

        Entry(
            "conditions_screen",
            listOf(
                "what is in conditions", "conditions screen", "what happens when i tap a street", "street details",
                "how do i see a street's details", "conditions page", "detalye ng kalye", "what is shown when i open a street", "what info is in a street page", "what does the conditions screen show"
            ),
            "Tap a street (its card or its dot on the map) to open its Conditions. You'll see:\n\n" +
                    "• Water level and risk level right now\n• Rise rate (how fast it's changing)\n• Rain right now\n" +
                    "• Flood Forecast: the expected level in 30 minutes\n• Flood Risk score\n• Rain Forecast\n" +
                    "• The Flood Severity Chart with live, recorded and forecast lines",
            suggestions = listOf("How does the forecast work?", "What is flood risk?")
        ),

        Entry(
            "home_street",
            listOf(
                "how do i change my street", "your current location", "change location button", "set my street",
                "nearby devices", "why does it show sealion street", "palitan ang kalye ko", "choose my street", "set which street is mine", "how do i select my home street"
            ),
            "\"Your current location\" on Home is the street you follow most. Tap Change (next to it) to pick Sealion, Samaritan, Swingfire or Striker Street. BahGo remembers your choice. The other three appear under Nearby Devices, and safety answers use your street when you don't name one.",
            suggestions = listOf("Is it safe to go out?", "Water level now")
        ),

        Entry(
            "password_change",
            listOf(
                "how do i change my password", "change password", "update my password", "new password",
                "palitan ang password", "set a new password"
            ),
            "To change your password: Settings > Privacy & Security > Change password. Enter your current password, then the new one twice.\n\n" +
                    "Your password is never stored as plain text. BahGo keeps only a scrambled version (a salted PBKDF2 hash), so even the team can't read it.",
            actions = listOf(BotAction.OpenSettings),
            suggestions = listOf("I forgot my password", "How is my data protected?")
        ),

        Entry(
            "password_forgot",
            listOf(
                "i forgot my password", "forgot password", "reset my password", "cant log in", "cannot login",
                "nakalimutan ko ang password", "locked out of my account", "lost my password", "i dont remember my password", "i cant get into my account"
            ),
            "Forgot your password? Email {EMAIL} with your name and registered mobile number, and the team will help you reset it.\n\n" +
                    "If you're still logged in, you can change it in Settings > Privacy & Security > Change password.",
            actions = listOf(BotAction.Email, BotAction.OpenSettings),
            suggestions = listOf("Contact the team")
        ),

        Entry(
            "phone_change",
            listOf(
                "change phone number", "change my mobile number", "new sim", "update my number",
                "palitan ang number", "didnt get the code", "otp not received", "verification code"
            ),
            "To change your mobile number: Settings > Privacy & Security > Change phone number. Enter your password, then the new number. BahGo texts a 6-digit code to it, which expires in 5 minutes.\n\n" +
                    "Didn't get the code? Check the number, wait a minute and tap Resend. After 3 wrong tries, request a new code.",
            actions = listOf(BotAction.OpenSettings)
        ),

        Entry(
            "signup",
            listOf(
                "how do i sign up", "create an account", "register", "how to make an account",
                "why do i need an account", "paano mag register", "sign up steps"
            ),
            "To sign up: enter your mobile number, type the 6-digit code BahGo texts you, then your name, Person or Car Mode, and a password. You'll also agree to the Privacy Policy and Terms.\n\n" +
                    "Your account keeps your settings (like your street and mode) and lets the team help you if something goes wrong.",
            suggestions = listOf("How is my data protected?", "What is Person Mode?")
        ),

        Entry(
            "profile",
            listOf("edit my name", "change my name", "edit profile", "update my account", "palitan ang pangalan"),
            "You can edit your first name and surname in Settings > Account. Changes save to your account right away.",
            actions = listOf(BotAction.OpenSettings)
        ),

        Entry(
            "delete_account",
            listOf("delete my account", "remove my account", "close my account", "deactivate account", "burahin ang account", "delete my data"),
            "To delete your account and data, email {EMAIL} with your registered mobile number. Your account details are deleted within 30 days.\n\n" +
                    "You can also clear what's on your phone: Android Settings > Apps > BahGo > Storage > Clear data.",
            actions = listOf(BotAction.Email)
        ),

        Entry(
            "privacy",
            listOf(
                "how is my data protected", "what data do you collect", "privacy", "is my data safe", "who can see my data",
                "do you track my location", "do you sell my data", "privacy policy", "anong data ang kinukuha", "is the database private", "do you share my data", "who do you give my data to", "is my information shared with others"
            ),
            "BahGo keeps only what it needs: your name, mobile number, a scrambled (hashed) password, your settings and login times.\n\n" +
                    "• Your location stays on your phone. Only when you use Iwas-Baha Route are the start and end points sent to the routing service.\n" +
                    "• Search text goes to OpenStreetMap's place search.\n" +
                    "• The database only answers the BahGo app and the team's accounts, not the public.\n" +
                    "• Usage statistics are off unless you turn them on. No ads, and your data is never sold.\n\n" +
                    "Read the full Privacy Policy, Terms and Cookie Policy in About.",
            actions = listOf(BotAction.OpenAbout, BotAction.OpenSettings)
        ),

        Entry(
            "data_sources",
            listOf(
                "where does the data come from", "data sources", "what apis do you use", "what services does bahgo use",
                "where is the weather from", "which map provider is used", "saan galing ang data"
            ),
            "BahGo's data comes from:\n\n" +
                    "• BahGo street sensors → Firebase Realtime Database (live water and rain)\n" +
                    "• Recorded flood days (Oct 13–28), stored inside the app, for the forecast models\n" +
                    "• Project NOAH 5-year flood-hazard map (via BetterGov PH on Hugging Face)\n" +
                    "• Open-Meteo for the 7-day weather\n" +
                    "• OpenStreetMap (map data, place search) with OpenFreeMap and MapLibre for the map\n" +
                    "• OSRM routing for Iwas-Baha Route\n\n" +
                    "The full list is in About.",
            actions = listOf(BotAction.OpenAbout)
        ),

        Entry(
            "accuracy",
            listOf(
                "how accurate is bahgo", "is the reading accurate", "can i trust the forecast", "how reliable are the sensors",
                "is the water level correct", "wrong reading", "tama ba ang reading", "are the sensor readings reliable", "how precise are the readings"
            ),
            "BahGo is a helpful guide, not a guarantee:\n\n" +
                    "• Sensors: ultrasonic readings are usually within a centimetre or two, but rain, debris or waves can cause jumps. Ignore a single odd reading and watch the trend.\n" +
                    "• Forecast: on test days it missed by about 0.6 cm on average, more while the water is moving fast.\n" +
                    "• Each sensor measures one spot. Nearby low areas can be deeper.\n\n" +
                    "Always look before crossing, and follow your barangay's advisories.",
            suggestions = listOf("How does the forecast work?", "Flood safety tips")
        ),

        Entry(
            "internet",
            listOf(
                "does bahgo work offline", "do i need internet", "without internet", "no internet", "walang internet",
                "does the app use mobile data", "how much data does the app use"
            ),
            "BahGo needs internet for live sensor readings, alerts, the weather, search and routes.\n\n" +
                    "Without internet you can still open the app, and the forecast model still works because its training data is stored inside the app. But the street statuses will show Offline until you're back online. It uses little mobile data: sensor readings are tiny, and the map uses the most.",
            suggestions = listOf("Why is a sensor offline?")
        ),

        Entry(
            "which_streets",
            listOf(
                "which streets have sensors", "what areas are covered", "is my street monitored", "how many sensors",
                "where are the sensors located", "coverage area", "saan ang mga sensor", "palar village"
            ),
            "BahGo monitors 4 streets in Palar Village, Taguig, each with its own sensor:\n\n" +
                    "• Sealion Street (sensor 1)\n• Samaritan Street (sensor 2)\n• Swingfire Street (sensor 3)\n• Striker Street (sensor 4)\n\n" +
                    "Other areas have no live sensor, but the map's NOAH hazard zones and Iwas-Baha Route still cover all of Taguig.",
            suggestions = listOf("Water level now", "How do the sensors measure the water?")
        ),

        Entry(
            "language",
            listOf("change language", "filipino language", "tagalog", "english or filipino", "palitan ang wika", "language settings"),
            "You can switch BahGo between English and Filipino in Settings > Language. Street names, brand names and the legal documents stay in English.",
            actions = listOf(BotAction.OpenSettings)
        ),

        Entry(
            "dark_mode",
            listOf("dark mode", "light mode", "change theme", "night mode", "madilim na theme", "dark theme", "turn on dark mode", "switch to light theme"),
            "Switch between light and dark mode in Settings > Preferences.",
            actions = listOf(BotAction.OpenSettings)
        ),

        Entry(
            "tour",
            listOf("show me the tour", "app tour", "tutorial", "walk me through the screens", "guide me around", "replay the tour", "home screen tour"),
            "I can replay the short Home tour that shows where everything is. You can also find it in Settings > About > App tour.",
            actions = listOf(BotAction.ReplayTour)
        ),

        Entry(
            "chatbot_itself",
            listOf(
                "how do you work", "are you an ai", "are you a real person", "how do you answer", "what are you",
                "how were you trained", "sino ka", "are you chatgpt", "how does the chat work", "are you a robot", "is this a chatbot", "are you artificial intelligence", "am i talking to a person"
            ),
            "I'm the BahGo Assistant, and I run entirely on your phone: no internet AI service, and your questions aren't sent anywhere.\n\n" +
                    "• I was trained on example questions about BahGo (in English and Filipino) with a TF-IDF text-matching model. I compare your question with what I've learned and pick the closest topic.\n" +
                    "• For water, safety, rain and forecasts, I read the live sensor data, the same numbers you see on Home.\n" +
                    "• If I'm not sure what you mean, I'll say so instead of guessing.",
            suggestions = listOf("How does BahGo work?", "Water level now")
        ),

        Entry(
            "about",
            listOf("who made this app", "who are the developers", "about the developers", "thesis", "who created bahgo", "sino gumawa ng app", "sti", "who developed this app", "which school made bahgo"),
            "BahGo is a flood-watch app for Palar Village, Taguig, made by 4th-year BS Computer Science students of STI College Global City: " +
                    "Johanna Vic Duliesco, Sophia Sargado, Kaye Levantino and Christine Jessica Reyes.",
            actions = listOf(BotAction.OpenAbout),
            suggestions = listOf("How does BahGo work?")
        ),

        Entry(
            "contact",
            listOf("contact the team", "how do i contact you", "report a bug", "send feedback", "email the developers", "complaint", "contact support", "where do i report a problem", "the app has an error"),
            "You can reach the BahGo Team at {EMAIL} for questions, bug reports or feedback. If something looks wrong, tell them which street and the time.",
            actions = listOf(BotAction.Email)
        ),

        Entry(
            "prepare",
            listOf(
                "flood safety tips", "how to prepare for a flood", "what should i do during a flood", "go bag",
                "what to bring", "paghahanda sa baha", "safety tips", "before the flood", "what should be in my emergency kit", "how do i stay safe in a flood"
            ),
            "Flood safety tips:\n" +
                    "• Keep a go bag: water, food, flashlight, power bank, medicine, IDs in a plastic bag.\n" +
                    "• Move valuables and appliances to a higher place.\n" +
                    "• Turn off the main power switch if water may enter your home.\n" +
                    "• Never walk or drive through moving water. 15 cm of moving water can knock you down, 60 cm can float a car.\n" +
                    "• Avoid flood water: it can carry leptospirosis. Wear boots and wash well.\n" +
                    "• Follow evacuation orders from your barangay right away.",
            suggestions = listOf("Emergency hotlines", "Is it safe to go out?")
        )
    )

    /**
     * Training data for [IntentModel]: the example questions. (Training on the answer text too
     * was tried and made things worse: answers mention other topics, which confused the model.)
     */
    fun trainingData(): List<Pair<String, Entry>> = entries.flatMap { e -> e.questions.map { it to e } }
}
