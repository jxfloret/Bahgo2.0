package com.example.bahgo20

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.bahgo20.databinding.ActivitySearchBinding
import com.example.bahgo20.utils.PlaceSearch
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Search: the 4 flood sensors, plus any place in Taguig (streets, barangays, malls, schools...)
 * from OpenStreetMap's place search (Nominatim).
 * Tapping a result goes back to Home, which moves the map there and drops a pin.
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private var searchJob: Job? = null

    /** A search result. [sensorId] is set for the 4 monitored streets. */
    data class Place(
        val name: String,
        val area: String,
        val lat: Double,
        val lon: Double,
        val sensorId: Int? = null
    )

    // The 4 flood sensors (same positions as the dots on the Home map)
    private val sensors by lazy {
        listOf(
            Place("Sealion Street", getString(R.string.search_sensor_area, 1), 14.532438, 121.055188, 1),
            Place("Samaritan Street", getString(R.string.search_sensor_area, 2), 14.532438, 121.055438, 2),
            Place("Swingfire Street", getString(R.string.search_sensor_area, 3), 14.532438, 121.055688, 3),
            Place("Striker Street", getString(R.string.search_sensor_area, 4), 14.532688, 121.055313, 4)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.ivBack.setOnClickListener { finish() }
        binding.ivClear.setOnClickListener { binding.etSearch.text?.clear() }

        showSensorsOnly()

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim() ?: ""
                binding.ivClear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                search(query)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search(binding.etSearch.text?.toString()?.trim().orEmpty(), immediate = true)
                true
            } else false
        }
    }

    private fun showSensorsOnly() {
        binding.progressBar.visibility = View.GONE
        binding.tvResultsLabel.text = getString(R.string.search_flood_sensors)
        render(sensors, null)
    }

    /** Matching sensors right away, then places in Taguig from OpenStreetMap. */
    private fun search(query: String, immediate: Boolean = false) {
        searchJob?.cancel()
        if (query.isEmpty()) {
            showSensorsOnly()
            return
        }

        val matchingSensors = sensors.filter {
            it.name.contains(query, ignoreCase = true) || it.area.contains(query, ignoreCase = true)
        }
        binding.tvResultsLabel.text = getString(R.string.search_results_in_taguig)
        render(matchingSensors, null)

        searchJob = lifecycleScope.launch {
            if (!immediate) delay(450)          // wait until typing pauses (the service allows ~1 search/second)
            if (query.length < 2) return@launch
            binding.progressBar.visibility = View.VISIBLE
            val places = searchTaguig(query)
            binding.progressBar.visibility = View.GONE
            if (places == null) {
                render(matchingSensors, getString(R.string.search_failed))
                return@launch
            }
            val combined = (matchingSensors + places)
                .distinctBy { it.name.lowercase() + "|" + "%.3f,%.3f".format(it.lat, it.lon) }
            render(combined, if (combined.isEmpty()) getString(R.string.search_no_match, query) else null)
        }
    }

    /** Any place in Taguig matching [query], or null if the search failed (e.g. no internet). */
    private suspend fun searchTaguig(query: String): List<Place>? = withContext(Dispatchers.IO) {
        PlaceSearch.search(httpClient, query)?.map { Place(it.name, it.area, it.lat, it.lon, sensorFor(it.name)) }
    }

    private fun sensorFor(name: String): Int? = sensors.firstOrNull { name.contains(it.name.substringBefore(" "), true) }?.sensorId

    // ───────────────────────── List ─────────────────────────

    private fun render(items: List<Place>, emptyMessage: String?) {
        binding.llSearchResults.removeAllViews()
        items.forEach { binding.llSearchResults.addView(card(it)) }
        binding.tvEmptyState.text = emptyMessage.orEmpty()
        binding.tvEmptyState.visibility = if (emptyMessage != null) View.VISIBLE else View.GONE
    }

    private fun card(place: Place): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(12) }
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.card_glass))
            radius = dp(20).toFloat()
            cardElevation = dp(2).toFloat()
            strokeWidth = dp(1)
            strokeColor = ContextCompat.getColor(context, R.color.card_stroke)
            isClickable = true
            isFocusable = true
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        row.addView(ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            setImageResource(R.drawable.ic_location_pin)
            imageTintList = ColorStateList.valueOf(
                if (place.sensorId != null) "#D32F2F".toColorInt() else ContextCompat.getColor(context, R.color.icon_accent)
            )
        })
        val text = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(16); marginEnd = dp(8) }
            orientation = LinearLayout.VERTICAL
        }
        text.addView(TextView(this).apply {
            this.text = place.name
            setTextColor(ContextCompat.getColor(context, R.color.text_on_card))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, Typeface.BOLD)
        })
        text.addView(TextView(this).apply {
            this.text = place.area
            setTextColor(ContextCompat.getColor(context, R.color.text_on_card_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        row.addView(text)
        row.addView(ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
            setImageResource(R.drawable.ic_chevron_right)
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.icon_muted))
        })
        card.addView(row)
        card.setOnClickListener { showOnMap(place) }
        return card
    }

    /** Back to Home, which moves the map to this place and drops a pin. */
    private fun showOnMap(place: Place) {
        setResult(Activity.RESULT_OK, Intent().apply {
            putExtra(EXTRA_LAT, place.lat)
            putExtra(EXTRA_LON, place.lon)
            putExtra(EXTRA_NAME, place.name)
            place.sensorId?.let { putExtra(EXTRA_SENSOR_ID, it) }
        })
        finish()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        const val EXTRA_LAT = "PLACE_LAT"
        const val EXTRA_LON = "PLACE_LON"
        const val EXTRA_NAME = "PLACE_NAME"
        const val EXTRA_SENSOR_ID = "PLACE_SENSOR_ID"
    }
}
