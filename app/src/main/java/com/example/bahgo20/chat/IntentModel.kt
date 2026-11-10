package com.example.bahgo20.chat

import java.util.Locale
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The assistant's question matcher: a TF-IDF model trained on example questions, on the phone.
 *
 * Training: every example question becomes a vector of word weights. Words and two-word phrases
 * that appear in only a few topics' examples get a high weight (IDF), common ones a low weight.
 * Asking: the question is turned into the same kind of vector and compared with every example
 * (cosine similarity, 0 = nothing in common, 1 = same words). The topic of the closest example
 * wins, if it is close enough. Words it never saw count against the match, so unrelated
 * questions ("best pizza?") score low instead of matching by accident.
 *
 * English and common Filipino words are mapped to the same meaning first ("ulan" = "rain").
 */
class IntentModel<T>(examples: List<Pair<String, T>>) {

    /** The best topic for a question, how close it was (0..1), and the runner-up's score. */
    data class Match<T>(val label: T, val score: Double, val runnerUp: Double)

    private class Example<T>(val label: T, val vector: Map<String, Double>)

    private val idf: Map<String, Double>
    private val unknownIdf: Double
    private val trained: List<Example<T>>
    /** One combined vector per topic (all its examples added up): catches questions that mix words from several examples. */
    private val centroids: Map<T, Map<String, Double>>

    init {
        require(examples.isNotEmpty()) { "needs examples" }
        val docs = examples.map { (q, label) -> label to features(q) }
        val n = docs.size.toDouble()
        val df = HashMap<String, Int>()
        docs.forEach { (_, f) -> f.keys.forEach { df[it] = (df[it] ?: 0) + 1 } }
        idf = df.mapValues { (_, d) -> ln((n + 1) / (d + 1)) + 1 }
        unknownIdf = ln(n + 1) + 1
        trained = docs.map { (label, f) -> Example(label, weigh(f)) }
        centroids = trained.groupBy { it.label }.mapValues { (_, exs) ->
            val sum = HashMap<String, Double>()
            exs.forEach { ex -> ex.vector.forEach { (k, v) -> sum[k] = (sum[k] ?: 0.0) + v } }
            val norm = sqrt(sum.values.sumOf { it * it })
            sum.mapValues { it.value / norm }
        }
    }

    /**
     * The closest topic, or null if the question has no usable words.
     * A topic's score is the better of: its closest single example, or all its examples combined.
     */
    fun match(question: String): Match<T>? {
        val q = weigh(features(question))
        if (q.isEmpty()) return null
        val best = HashMap<T, Double>()
        for (ex in trained) {
            val s = cosine(q, ex.vector)
            if (s > (best[ex.label] ?: -1.0)) best[ex.label] = s
        }
        for ((label, c) in centroids) {
            val s = cosine(q, c)
            if (s > (best[label] ?: -1.0)) best[label] = s
        }
        val ranked = best.entries.sortedByDescending { it.value }
        val top = ranked.first()
        return Match(top.key, top.value, ranked.getOrNull(1)?.value ?: 0.0)
    }

    private fun weigh(tf: Map<String, Int>): Map<String, Double> {
        val v = tf.mapValues { (term, count) -> (1 + ln(count.toDouble())) * (idf[term] ?: unknownIdf) }
        val norm = sqrt(v.values.sumOf { it * it })
        return if (norm == 0.0) emptyMap() else v.mapValues { it.value / norm }
    }

    private fun cosine(a: Map<String, Double>, b: Map<String, Double>): Double {
        val (small, large) = if (a.size <= b.size) a to b else b to a
        var dot = 0.0
        for ((k, v) in small) large[k]?.let { dot += v * it }
        return dot
    }

    companion object {
        /** Words (and two-word phrases) of a question, after cleaning, with how often each appears. */
        fun features(text: String): Map<String, Int> {
            val words = tokens(text)
            val out = HashMap<String, Int>()
            words.forEach { out[it] = (out[it] ?: 0) + 1 }
            words.zipWithNext().forEach { (a, b) -> "$a $b".let { out[it] = (out[it] ?: 0) + 1 } }
            return out
        }

        fun tokens(text: String): List<String> =
            text.lowercase(Locale.ROOT)
                .replace("'", "")
                .replace(Regex("[^a-z0-9ñ]+"), " ")
                .trim()
                .split(" ")
                .asSequence()
                .filter { it.isNotEmpty() }
                .map { SYNONYMS[it] ?: it }
                .filter { it !in STOP_WORDS }
                .map { stem(it) }
                .toList()

        /** Light English stemming: "sensors" -> "sensor", "raining" -> "rain", "measured" -> "measur". */
        private fun stem(w: String): String = when {
            w.length <= 4 -> w
            w.endsWith("ies") -> w.dropLast(3) + "y"
            w.endsWith("ing") && w.length > 5 -> w.dropLast(3)
            w.endsWith("ed") && w.length > 5 -> w.dropLast(2)
            w.endsWith("es") && w.length > 5 && (w.endsWith("sses") || w.endsWith("ches") || w.endsWith("shes")) -> w.dropLast(2)
            w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") -> w.dropLast(1)
            else -> w
        }

        /** Filipino and alternative words mapped to one meaning. */
        private val SYNONYMS = mapOf(
            "tubig" to "water", "baha" to "flood", "bumabaha" to "flood", "babaha" to "flood", "flooding" to "flood", "flooded" to "flood",
            "ulan" to "rain", "umuulan" to "rain", "raining" to "rain", "rainfall" to "rain", "bagyo" to "storm", "typhoon" to "storm",
            "ligtas" to "safe", "delikado" to "danger", "dangerous" to "danger", "mapanganib" to "danger",
            "daan" to "route", "ruta" to "route", "routes" to "route", "directions" to "route", "path" to "route",
            "kalsada" to "street", "kalye" to "street", "streets" to "street", "road" to "street", "roads" to "street",
            "kotse" to "car", "sasakyan" to "car", "vehicle" to "car", "driving" to "drive", "naglalakad" to "walk", "lakad" to "walk", "walking" to "walk",
            "notif" to "notification", "notifs" to "notification", "notifications" to "notification", "notified" to "notification",
            "notify" to "notification", "alert" to "notification", "alerts" to "notification", "abiso" to "notification",
            "receive" to "receiv", "receiving" to "receiv", "received" to "receiv", "getting" to "receiv",
            "detect" to "measure", "detects" to "measure", "detected" to "measure", "sense" to "measure", "senses" to "measure",
            "sinusukat" to "measure", "nasusukat" to "measure", "precise" to "accuracy", "trust" to "accuracy",
            "lookup" to "search", "locate" to "search", "hahanapin" to "search",
            "pick" to "choose", "select" to "choose", "piliin" to "choose",
            "remember" to "forgot", "nakalimutan" to "forgot", "forget" to "forgot",
            "sell" to "share", "selling" to "share", "shared" to "share", "sharing" to "share", "give" to "share",
            "bot" to "bot", "robot" to "bot", "chatbot" to "bot", "ai" to "bot", "assistant" to "bot",
            "zone" to "area", "zones" to "area", "areas" to "area", "lugar" to "place",
            "built" to "made", "build" to "made", "developed" to "made", "gumawa" to "made", "ginawa" to "made",
            "theme" to "mode", "learn" to "train", "learned" to "train", "trained" to "train", "training" to "train",
            "open" to "tap", "click" to "tap", "press" to "tap", "pindutin" to "tap",
            "colours" to "color", "colors" to "color", "kulay" to "color",
            "sukat" to "measure", "measures" to "measure", "measured" to "measure", "measuring" to "measure",
            "hula" to "forecast", "prediction" to "forecast", "predict" to "forecast", "predicts" to "forecast", "forecasts" to "forecast",
            "mapa" to "map", "hanapin" to "search", "maghanap" to "search", "find" to "search",
            "numero" to "number", "cellphone" to "phone", "cp" to "phone", "mobile" to "phone",
            "pangalan" to "name", "burahin" to "delete", "remove" to "delete", "erase" to "delete",
            "datos" to "data", "impormasyon" to "data", "info" to "data", "information" to "data",
            "sensors" to "sensor", "device" to "sensor", "devices" to "sensor",
            "app" to "bahgo", "application" to "bahgo",
            "taas" to "level", "lalim" to "deep", "depth" to "deep",
            "accurate" to "accuracy", "tama" to "accuracy", "reliable" to "accuracy",
            "paghahanda" to "prepare", "handa" to "prepare", "preparation" to "prepare",
            "wika" to "language", "tagalog" to "filipino",
            "gumagana" to "work", "works" to "work", "working" to "work"
        )

        /** Words that say little on their own (English and Filipino). */
        private val STOP_WORDS = setOf(
            "a", "an", "the", "is", "are", "am", "be", "was", "were", "do", "does", "did", "i", "you", "me", "my", "your",
            "it", "its", "to", "of", "in", "on", "for", "at", "and", "or", "this", "that", "these", "those", "with", "can",
            "could", "would", "will", "should", "please", "pls", "how", "what", "whats", "why", "where", "when", "which",
            "who", "there", "here", "about", "tell", "know", "want", "need", "get", "use", "using", "used", "show", "hi", "so",
            "ba", "ang", "ng", "sa", "na", "po", "yung", "mga", "ko", "ako", "ito", "ano", "paano", "bakit", "si", "kung",
            "may", "ay", "lang", "naman", "din", "rin", "nga", "pa", "mo", "nyo", "kayo", "natin", "namin", "saan", "kailan"
        )
    }
}
