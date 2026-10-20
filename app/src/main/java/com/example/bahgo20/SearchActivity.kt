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
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import kotlin.math.pow
import kotlin.math.sqrt

class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private val httpClient = OkHttpClient()
    private var searchJob: Job? = null

    data class TaguigStreetItem(
        val streetName: String,
        val barangayOrArea: String,
        val lat: Double,
        val lon: Double,
        val sensorId: Int,
        val isMonitoredSensor: Boolean = false
    )

    private val localTaguigStreets = listOf(
        TaguigStreetItem("Sealion Street", "Palar Village, Pinagsama, Taguig (Sensor 1)", 14.532438, 121.055188, 1, true),
        TaguigStreetItem("Samaritan Street", "Palar Village, Pinagsama, Taguig (Sensor 2)", 14.532438, 121.055438, 2, true),
        TaguigStreetItem("Swingfire Street", "Palar Village, Pinagsama, Taguig (Sensor 3)", 14.532438, 121.055688, 3, true),
        TaguigStreetItem("Striker Street", "Palar Village, Pinagsama, Taguig (Sensor 4)", 14.532688, 121.055313, 4, true),
        TaguigStreetItem("Churchill Street", "Palar Village, Pinagsama, Taguig", 14.5329, 121.0562, 3),
        TaguigStreetItem("Chieftain Street", "Palar Village, Pinagsama, Taguig", 14.5318, 121.0560, 3),
        TaguigStreetItem("Scorpion Street", "Palar Village, Pinagsama, Taguig", 14.5331, 121.0549, 4),
        TaguigStreetItem("Saladin Street", "Palar Village, Pinagsama, Taguig", 14.5334, 121.0555, 4),
        TaguigStreetItem("Sabre Street", "Palar Village, Pinagsama, Taguig", 14.5320, 121.0547, 1),
        TaguigStreetItem("Saracen Street", "Palar Village, Pinagsama, Taguig", 14.5328, 121.0545, 1),
        TaguigStreetItem("Carlos P. Garcia Avenue (C-5)", "Pinagsama / Ususan, Taguig", 14.5325, 121.0572, 3),
        TaguigStreetItem("Upper McKinley Road", "McKinley Hill, Fort Bonifacio, Taguig", 14.5340, 121.0515, 1),
        TaguigStreetItem("Lawton Avenue", "Fort Bonifacio, Taguig", 14.5310, 121.0425, 1),
        TaguigStreetItem("Bayani Road", "Western Bicutan, Taguig", 14.5215, 121.0450, 1),
        TaguigStreetItem("Cayetano Boulevard (Levi Mariano Ave)", "Ususan, Taguig", 14.5345, 121.0660, 3),
        TaguigStreetItem("General Luna Street", "Tuktukan / Ususan, Taguig", 14.5285, 121.0705, 3),
        TaguigStreetItem("M.L. Quezon Street", "Bagumbayan / Hagonoy, Taguig", 14.5020, 121.0620, 3),
        TaguigStreetItem("General Santos Avenue", "Lower Bicutan, Taguig", 14.4890, 121.0560, 3),
        TaguigStreetItem("Cuasay Street", "Central Signal Village, Taguig", 14.5130, 121.0580, 3),
        TaguigStreetItem("Ballecer Street", "Central Signal Village, Taguig", 14.5120, 121.0565, 3),
        TaguigStreetItem("Sampaguita Street", "Pembo, Taguig", 14.5440, 121.0590, 4),
        TaguigStreetItem("J.P. Rizal Extension", "Comembo / East Rembo, Taguig", 14.5520, 121.0620, 4),
        TaguigStreetItem("Kalayaan Avenue", "Cembo / West Rembo, Taguig", 14.5590, 121.0540, 4),
        TaguigStreetItem("5th Avenue", "Bonifacio Global City, Taguig", 14.5505, 121.0465, 4),
        TaguigStreetItem("32nd Street", "Bonifacio Global City, Taguig", 14.5555, 121.0505, 4),
        TaguigStreetItem("McKinley Parkway", "Bonifacio Global City, Taguig", 14.5475, 121.0510, 4)
    )

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

        renderResults(localTaguigStreets)

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim() ?: ""
                binding.ivClear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                performStreetSearch(query)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = binding.etSearch.text?.toString()?.trim() ?: ""
                performStreetSearch(query, immediate = true)
                true
            } else false
        }
    }

    private fun performStreetSearch(query: String, immediate: Boolean = false) {
        searchJob?.cancel()

        if (query.isEmpty()) {
            binding.progressBar.visibility = View.GONE
            binding.tvResultsLabel.text = "Taguig City Streets"
            renderResults(localTaguigStreets)
            return
        }

        val filteredLocal = localTaguigStreets.filter {
            it.streetName.contains(query, ignoreCase = true) ||
                    it.barangayOrArea.contains(query, ignoreCase = true)
        }
        binding.tvResultsLabel.text = "Search Results in Taguig"
        renderResults(filteredLocal)

        searchJob = lifecycleScope.launch {
            if (!immediate) delay(350)
            binding.progressBar.visibility = View.VISIBLE
            val remoteResults = fetchTaguigStreetsFromOsm(query)
            binding.progressBar.visibility = View.GONE

            val combined = (filteredLocal + remoteResults)
                .distinctBy { it.streetName.lowercase() + "|" + it.barangayOrArea.lowercase() }
            renderResults(combined)
        }
    }

    private suspend fun fetchTaguigStreetsFromOsm(query: String): List<TaguigStreetItem> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<TaguigStreetItem>()
            try {
                val encodedQuery = URLEncoder.encode("$query, Taguig, Metro Manila, Philippines", "UTF-8")
                val url = "https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&addressdetails=1&limit=12&viewbox=121.015,14.565,121.105,14.465&bounded=1"

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "BahGo20FloodApp/1.0 (Android)")
                    .get().build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val bodyStr = response.body?.string() ?: return@withContext emptyList()
                    val jsonArray = JSONArray(bodyStr)

                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val lat = obj.optDouble("lat", 14.5325)
                        val lon = obj.optDouble("lon", 121.0554)
                        val road = obj.optJSONObject("address")?.optString("road", "")
                            ?.takeIf { it.isNotEmpty() }
                            ?: obj.optString("name", "").takeIf { it.isNotEmpty() }
                            ?: obj.optString("display_name", "").substringBefore(",").trim()

                        val suburb = obj.optJSONObject("address")?.run {
                            optString("suburb", "").takeIf { it.isNotEmpty() }
                                ?: optString("neighbourhood", "").takeIf { it.isNotEmpty() }
                                ?: optString("quarter", "")
                        }?.takeIf { it.isNotEmpty() } ?: "Taguig City"

                        if (road.isNotEmpty()) {
                            results.add(TaguigStreetItem(road, "$suburb, Taguig", lat, lon, findNearestSensorId(lat, lon, road),
                                road.contains("Sealion", true) || road.contains("Samaritan", true) ||
                                road.contains("Swingfire", true) || road.contains("Striker", true)))
                        }
                    }
                }
            } catch (_: Exception) {}
            results
        }

    private fun findNearestSensorId(lat: Double, lon: Double, streetName: String): Int {
        if (streetName.contains("Sealion", true)) return 1
        if (streetName.contains("Samaritan", true)) return 2
        if (streetName.contains("Swingfire", true)) return 3
        if (streetName.contains("Striker", true)) return 4
        val sensors = mapOf(1 to (14.532438 to 121.055188), 2 to (14.532438 to 121.055438), 3 to (14.532438 to 121.055688), 4 to (14.532688 to 121.055313))
        return sensors.minByOrNull { (_, coords) -> sqrt((lat - coords.first).pow(2) + (lon - coords.second).pow(2)) }?.key ?: 1
    }

    private fun renderResults(items: List<TaguigStreetItem>) {
        binding.llSearchResults.removeAllViews()
        if (items.isEmpty()) {
            binding.tvEmptyState.visibility = View.VISIBLE
            return
        }
        binding.tvEmptyState.visibility = View.GONE
        items.forEach { binding.llSearchResults.addView(createStreetCardView(it)) }
    }

    private fun createStreetCardView(item: TaguigStreetItem): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dpToPx(12) }
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.card_glass))
            radius = dpToPx(20).toFloat()
            cardElevation = dpToPx(2).toFloat()
            strokeWidth = dpToPx(1)
            strokeColor = ContextCompat.getColor(context, R.color.card_stroke)
            isClickable = true
            isFocusable = true
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = dpToPx(16)
            setPadding(pad, pad, pad, pad)
        }

        val pinIcon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(20), dpToPx(20))
            setImageResource(R.drawable.ic_location_pin)
            val tintColor = if (item.isMonitoredSensor) "#D32F2F".toColorInt() else ContextCompat.getColor(context, R.color.icon_accent)
            imageTintList = ColorStateList.valueOf(tintColor)
        }

        val textContainer = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dpToPx(16); marginEnd = dpToPx(8) }
            orientation = LinearLayout.VERTICAL
        }

        textContainer.addView(TextView(this).apply {
            text = item.streetName; setTextColor(ContextCompat.getColor(context, R.color.text_on_card))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTypeface(null, Typeface.BOLD)
        })
        textContainer.addView(TextView(this).apply {
            text = item.barangayOrArea; setTextColor(ContextCompat.getColor(context, R.color.text_on_card_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })

        row.addView(pinIcon)
        row.addView(textContainer)
        row.addView(ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(16), dpToPx(16))
            setImageResource(R.drawable.ic_chevron_right); imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.icon_muted))
        })
        card.addView(row)
        card.setOnClickListener { showStreetActionDialog(item) }
        return card
    }

    private fun showStreetActionDialog(item: TaguigStreetItem) {
        val bottomSheet = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dpToPx(24)
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(ContextCompat.getColor(context, R.color.sheet_background))
        }

        container.addView(TextView(this).apply {
            text = item.streetName; setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); setTypeface(null, Typeface.BOLD)
        })
        container.addView(TextView(this).apply {
            text = item.barangayOrArea; setTextColor(ContextCompat.getColor(context, R.color.text_on_card_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpToPx(4); bottomMargin = dpToPx(20) }
        })

        container.addView(MaterialButton(this).apply {
            text = "Center on Flood Map"; isAllCaps = false; setTextColor(ContextCompat.getColor(context, R.color.white))
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.button_primary))
            cornerRadius = dpToPx(16)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(52)).apply { bottomMargin = dpToPx(10) }
            setOnClickListener { bottomSheet.dismiss(); val resultIntent = Intent().apply { putExtra("CENTER_LAT", item.lat); putExtra("CENTER_LON", item.lon); putExtra("STREET_NAME", item.streetName) }; setResult(Activity.RESULT_OK, resultIntent); finish() }
        })

        container.addView(MaterialButton(this).apply {
            text = "View Street Conditions"; isAllCaps = false; setTextColor(ContextCompat.getColor(context, R.color.button_primary))
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.card_muted))
            cornerRadius = dpToPx(16)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(52))
            setOnClickListener { bottomSheet.dismiss(); startActivity(Intent(this@SearchActivity, ConditionsActivity::class.java).apply { putExtra("SENSOR_ID", item.sensorId); putExtra("STREET_NAME", item.streetName) }) }
        })

        bottomSheet.setContentView(container); bottomSheet.show()
    }

    private fun dpToPx(dp: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()
}