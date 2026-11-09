package com.example.bahgo20

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.bahgo20.databinding.ActivityHomeBinding
import com.example.bahgo20.ui.DailyForecastSection
import com.example.bahgo20.chat.ChatActivity
import com.example.bahgo20.route.LatLon
import com.example.bahgo20.route.SensorStatus
import com.example.bahgo20.route.FloodRoutePlanner
import com.example.bahgo20.ui.GuidedTour
import com.example.bahgo20.utils.HomeStreet
import com.example.bahgo20.utils.OnboardingManager
import com.example.bahgo20.ui.StreetPickerSheet
import com.example.bahgo20.ui.StatusInfoSheet
import com.example.bahgo20.ui.StatusText
import com.example.bahgo20.weather.WeatherRepository
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.VectorSource
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var sessionManager: SessionManager
    private val db = FirebaseDatabase
        .getInstance("https://bahgo-ee71b-default-rtdb.asia-southeast1.firebasedatabase.app/")
        .reference

    // Decides online/offline from Last_Updated (see SensorTracker.kt)
    private val tracker = SensorTracker()
    private val sensorListeners = mutableListOf<Pair<String, ValueEventListener>>()
    private var lastUpdatedListener: ValueEventListener? = null
    private var mapRef: MapLibreMap? = null
    private var hasVehicle = false

    // 7-day forecast card (weather data for the area, see weather/WeatherRepository.kt)
    private lateinit var dailySection: DailyForecastSection

    // Re-evaluates "offline" every few seconds even when Firebase sends nothing new
    private val ticker = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            renderAll()
            ticker.postDelayed(this, REFRESH_MS)
        }
    }

    private val sensorCoordinates = mapOf(
        1 to LatLng(14.532438, 121.055188),
        2 to LatLng(14.532438, 121.055438),
        3 to LatLng(14.532438, 121.055688),
        4 to LatLng(14.532688, 121.055313)
    )

    // First-time guided tour of Home (replay it from Settings > App tour)
    private var tour: GuidedTour? = null

    // Place picked in Search: shown as a pin on the MapLibre map (null = no pin)
    private var searchPlace: LatLng? = null

    private val searchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) return@registerForActivityResult
        val lat = data.getDoubleExtra(SearchActivity.EXTRA_LAT, Double.NaN)
        val lon = data.getDoubleExtra(SearchActivity.EXTRA_LON, Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return@registerForActivityResult
        showPlaceOnMap(LatLng(lat, lon), data.getStringExtra(SearchActivity.EXTRA_NAME).orEmpty())
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (granted) {
            mapRef?.style?.let { enableUserLocation(it) }
        }
        routePlanner.onLocationPermissionResult(granted)
    }

    // "Iwas-Baha Route" planner above the map (route/FloodRoutePlanner.kt)
    private lateinit var routePlanner: FloodRoutePlanner

    private companion object {
        const val TAG = "HomeActivity"

        // Shared with SettingsActivity / ConditionsActivity
        const val PREFS_NAME = "bahgo_monitor_prefs"
        const val KEY_HAS_VEHICLE = "has_vehicle"

        const val REFRESH_MS = 15_000L

        // Status colors
        const val COLOR_SAFE = "#4CAF50"
        const val COLOR_MEDIUM = "#FF9100"
        const val COLOR_CRITICAL = "#D32F2F"
        const val COLOR_OFFLINE = "#9E9E9E"
        const val COLOR_WATER_CARD = "#1E88E5"

        val SENSOR_STREETS = FloodAlerts.STREETS

        const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        const val HF_PMTILES_URL = "https://huggingface.co/datasets/bettergovph/project-noah-hazard-maps/resolve/main/PMTiles/layers/flood_5yr.pmtiles"
        const val LOCAL_ASSET_NAME = "taguig_flood_5yr.pmtiles"
        const val FLOOD_SOURCE_ID = "noah-flood-5yr-source"
        const val FLOOD_LAYER_ID = "flood-5yr-layer"
        const val FLOOD_SOURCE_LAYER = "flood_5yr"
        const val MASK_SOURCE_ID = "taguig-mask-source"
        const val MASK_LAYER_ID = "taguig-mask-fill"
        const val BORDER_LAYER_ID = "taguig-border-line"
        val TAGUIG_BOUNDS: LatLngBounds = LatLngBounds.from(14.560, 121.105, 14.465, 121.015)
        val PALAR_CENTER = LatLng(14.53250, 121.05544)
        const val TAGUIG_MASK_GEOJSON = """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[[[120.0,13.5],[122.0,13.5],[122.0,15.5],[120.0,15.5],[120.0,13.5]],[[121.018,14.512],[121.028,14.538],[121.045,14.562],[121.064,14.560],[121.076,14.548],[121.098,14.542],[121.104,14.525],[121.088,14.495],[121.070,14.468],[121.042,14.468],[121.032,14.485],[121.018,14.512]]]}}]}"""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // MUST run before the layout (which contains the MapView) is inflated
        MapLibre.getInstance(this)

        enableEdgeToEdge()
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager.getInstance(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Leave room for the keyboard while typing in Iwas-Baha Route, so place suggestions stay visible
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            v.setPadding(systemBars.left, 0, systemBars.right, keyboard)
            insets
        }
        // Chat button sits above the navigation bar
        val fabMargin = binding.fabChat.let { (it.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin }
        ViewCompat.setOnApplyWindowInsetsListener(binding.fabChat) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            (v.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = fabMargin + bottom
            v.requestLayout()
            insets
        }

        setupStaticUi()
        routePlanner = FloodRoutePlanner(
            activity = this,
            binding = binding,
            map = { mapRef },
            sensors = ::routeSensors,
            savedHasVehicle = { getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_HAS_VEHICLE, false) },
            requestLocationPermission = { locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)) }
        ).also { it.setup() }
        dailySection = DailyForecastSection(binding.tvDailyStatus, binding.llDaily)
        WeatherRepository.saved(this)?.let { dailySection.show(it) } ?: dailySection.showLoading()
        initMapView(savedInstanceState)

        // Push notifications: channel, permission prompt (Android 13+), topic subscription
        FloodAlerts.setup(this)

        lifecycleScope.launch {
            delay(100)
            setupSensorListeners()
            // Train the forecast models on the recorded data shipped in the app (used by Conditions)
            launch { RecordedTraining.load() }
            loadUserData()
        }
    }

    // ───────────────────────── UI setup ─────────────────────────

    private fun setupStaticUi() {
        binding.ivSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.ivSearch.setOnClickListener {
            searchLauncher.launch(Intent(this, SearchActivity::class.java))
        }
        binding.llSearchPin.setOnClickListener { hidePlacePin() }
        binding.fabChat.setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java))
        }

        // Tap a Current Level Status box -> a plain-language explanation slides up
        binding.cvGridLevel.setOnClickListener {
            StatusInfoSheet.show(this, StatusInfoSheet.Box.WATER_LEVEL, binding.tvGridLevelValue.text.toString())
        }
        binding.cvGridRisk.setOnClickListener {
            StatusInfoSheet.show(this, StatusInfoSheet.Box.RISK_LEVEL, binding.tvGridRiskValue.text.toString())
        }
        binding.cvGridRise.setOnClickListener {
            StatusInfoSheet.show(this, StatusInfoSheet.Box.RISE_RATE,
                "${binding.tvGridRiseValue.text} · ${binding.tvGridRiseSub.text}")
        }
        binding.cvGridRain.setOnClickListener {
            StatusInfoSheet.show(this, StatusInfoSheet.Box.RAIN, binding.tvGridRainValue.text.toString())
        }
        // Log out lives in Settings only

        binding.dotSensor1.setOnClickListener { openConditions(1) }
        binding.dotSensor2.setOnClickListener { openConditions(2) }
        binding.dotSensor3.setOnClickListener { openConditions(3) }
        binding.dotSensor4.setOnClickListener { openConditions(4) }

        // Your street and the other three: follow the street picked with Change
        binding.cvMainLocation.setOnClickListener { openConditions(homeId) }
        binding.cvNearby2.setOnClickListener { openConditions(nearbyIds()[0]) }
        binding.cvNearby3.setOnClickListener { openConditions(nearbyIds()[1]) }
        binding.cvNearby4.setOnClickListener { openConditions(nearbyIds()[2]) }
        binding.cvChangeLocation.setOnClickListener { pickHomeStreet() }
    }

    /** The street shown as "your current location" (1..4), picked with Change. */
    private var homeId = 1

    /** The other three streets, in order, for the Nearby cards. */
    private fun nearbyIds() = (1..4).filter { it != homeId }

    /** "Choose your street" sheet: each street with its live status; the pick is saved. */
    private fun pickHomeStreet() {
        val streets = (1..4).map { id ->
            val r = tracker.reading(id)
            val status = if (r.risk == Risk.OFFLINE) getString(R.string.risk_offline)
                else "${formatCm(r.level)} · ${riskLabel(r.risk)}"
            StreetPickerSheet.Street(id, SENSOR_STREETS.getValue(id), status, riskColor(r.risk))
        }
        StreetPickerSheet.show(this, streets, homeId) { id ->
            homeId = id
            HomeStreet.set(this, id)
            renderAll()
        }
    }

    /** The 4 sensors with their latest readings, for Iwas-Baha Route scoring. */
    private fun routeSensors(): List<SensorStatus> = (1..4).map { id ->
        val r = tracker.reading(id)
        val p = sensorCoordinates.getValue(id)
        SensorStatus(id, SENSOR_STREETS.getValue(id), LatLon(p.latitude, p.longitude), r.risk, r.level)
    }

    private fun openConditions(id: Int) {
        startActivity(Intent(this, ConditionsActivity::class.java).apply {
            putExtra("SENSOR_ID", id)
            putExtra("STREET_NAME", SENSOR_STREETS[id])
        })
    }

    // ───────────────────────── Map ─────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun initMapView(savedInstanceState: Bundle?) {
        try {
            // Let the map take touches instead of the page scroll while you drag it
            binding.mapView.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.parent.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.parent.requestDisallowInterceptTouchEvent(false)
                }
                false
            }

            binding.mapView.onCreate(savedInstanceState)
            binding.mapView.getMapAsync { map ->
                mapRef = map
                setupFloodMap(map)
                binding.mapView.post { updateMarkerPositions() }
            }
            binding.btnMyLocation.setOnClickListener {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    mapRef?.style?.let { enableUserLocation(it) }
                    centerOnUserLocation()
                } else locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Map init failed", e)
        }
    }

    private fun setupFloodMap(map: MapLibreMap) {
        map.setLatLngBoundsForCameraTarget(TAGUIG_BOUNDS)
        map.cameraPosition = CameraPosition.Builder().target(PALAR_CENTER).zoom(17.8).build()
        // A place picked in Search before the map was ready: start there instead
        searchPlace?.let { map.cameraPosition = CameraPosition.Builder().target(it).zoom(17.0).build() }
        map.addOnCameraMoveListener { updateMarkerPositions() }
        map.addOnCameraIdleListener { updateMarkerPositions() }

        map.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
            addTaguigMaskAndBorder(style)
            routePlanner.onStyleLoaded(style)
            updateMarkerPositions()
            lifecycleScope.launch {
                val pmtilesUri = resolvePmTilesUri()
                attachFloodLayerToStyle(style, pmtilesUri)
            }
        }
    }

    private fun updateMarkerPositions() {
        val map = mapRef ?: return
        try {
            val projection = map.projection
            sensorCoordinates.forEach { (id, latLng) ->
                val dot = dotFor(id)
                val offset = if (dot.width > 0) dot.width / 2f else 18f
                val p = projection.toScreenLocation(latLng)
                dot.translationX = p.x - offset
                dot.translationY = p.y - offset
                dot.visibility = View.VISIBLE
            }

            // Search pin: its tip sits on the place
            val pin = binding.llSearchPin
            val place = searchPlace
            if (place != null && pin.width > 0) {
                val p = projection.toScreenLocation(place)
                pin.translationX = p.x - pin.width / 2f
                pin.translationY = p.y - pin.height
                pin.visibility = View.VISIBLE
            } else if (place == null) {
                pin.visibility = View.INVISIBLE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Marker positioning failed", e)
        }
    }

    /** Scrolls up to the map, flies there and drops a labelled pin. */
    private fun showPlaceOnMap(place: LatLng, name: String) {
        searchPlace = place
        binding.tvSearchPinLabel.text = name.ifBlank { getString(R.string.home_selected_place) }
        binding.scrollView.smoothScrollTo(0, 0)
        val map = mapRef
        if (map == null) {
            // Map still loading: the pin appears once it's ready (see setupFloodMap)
            return
        }
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(place, 17.0), 1200)
        binding.llSearchPin.post { updateMarkerPositions() }
    }

    private fun hidePlacePin() {
        searchPlace = null
        binding.llSearchPin.visibility = View.INVISIBLE
    }

    private fun dotFor(id: Int): View = when (id) {
        1 -> binding.dotSensor1
        2 -> binding.dotSensor2
        3 -> binding.dotSensor3
        else -> binding.dotSensor4
    }

    private suspend fun resolvePmTilesUri(): String = withContext(Dispatchers.IO) {
        val outFile = File(cacheDir, LOCAL_ASSET_NAME)
        if (!outFile.exists() || outFile.length() == 0L) {
            try {
                assets.open(LOCAL_ASSET_NAME).use { input -> outFile.outputStream().use { output -> input.copyTo(output) } }
            } catch (e: Exception) { return@withContext "pmtiles://$HF_PMTILES_URL" }
        }
        "pmtiles://file://${outFile.absolutePath}"
    }

    private fun attachFloodLayerToStyle(style: Style, pmtilesUri: String) {
        try {
            if (style.getSource(FLOOD_SOURCE_ID) == null) style.addSource(VectorSource(FLOOD_SOURCE_ID, pmtilesUri))
            val colorExp = Expression.raw("""["case", ["==", ["to-number", ["get", "Var"]], 1], "#93C5FD", ["==", ["to-number", ["get", "Var"]], 2], "#3B82F6", [">=", ["to-number", ["get", "Var"]], 3], "#1D4ED8", "rgba(0, 0, 0, 0)"]""")
            if (style.getLayer(FLOOD_LAYER_ID) == null) {
                val layer = FillLayer(FLOOD_LAYER_ID, FLOOD_SOURCE_ID).apply {
                    sourceLayer = FLOOD_SOURCE_LAYER
                    setProperties(fillColor(colorExp), fillOpacity(0.65f))
                }
                if (style.getLayer(MASK_LAYER_ID) != null) {
                    style.addLayerBelow(layer, MASK_LAYER_ID)
                } else {
                    style.addLayer(layer)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Flood layer failed", e)
        }
    }

    private fun addTaguigMaskAndBorder(style: Style) {
        try {
            if (style.getSource(MASK_SOURCE_ID) == null) style.addSource(GeoJsonSource(MASK_SOURCE_ID, TAGUIG_MASK_GEOJSON))
            if (style.getLayer(MASK_LAYER_ID) == null) {
                style.addLayer(FillLayer(MASK_LAYER_ID, MASK_SOURCE_ID).apply {
                    setProperties(fillColor("#F0F4F8".toColorInt()), fillOpacity(0.88f))
                })
            }
            if (style.getLayer(BORDER_LAYER_ID) == null) {
                style.addLayer(LineLayer(BORDER_LAYER_ID, MASK_SOURCE_ID).apply {
                    setProperties(lineColor("#1D4ED8".toColorInt()), lineWidth(2.0f))
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Mask/border failed", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableUserLocation(style: Style) {
        try {
            val options = LocationComponentOptions.builder(this).pulseEnabled(true).build()
            val activation = LocationComponentActivationOptions.builder(this, style).locationComponentOptions(options).build()
            mapRef?.locationComponent?.activateLocationComponent(activation)
            mapRef?.locationComponent?.isLocationComponentEnabled = true
        } catch (e: Exception) {
            Log.e(TAG, "Location component failed", e)
        }
    }

    private fun centerOnUserLocation() {
        val loc = mapRef?.locationComponent?.lastKnownLocation ?: return
        val pos = LatLng(loc.latitude, loc.longitude)
        if (TAGUIG_BOUNDS.contains(pos)) mapRef?.animateCamera(CameraUpdateFactory.newLatLngZoom(pos, 17.5))
    }

    // ───────────────────────── Firebase ─────────────────────────

    private fun setupSensorListeners() {
        (1..4).forEach { id ->
            val name = "Sensordata$id"
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    tracker.setSensor(id, snapshot)
                    renderAll()
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "$name cancelled: ${error.message}")
                    tracker.clearSensor(id)
                    renderAll()
                }
            }
            db.child(name).addValueEventListener(listener)
            sensorListeners.add(name to listener)
        }

        // Root-level Last_Updated (used when a device has no Last_Updated inside its own node)
        val updatedListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                tracker.setRootUpdated(snapshot)
                renderAll()
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Last_Updated cancelled: ${error.message}")
                tracker.setRootUpdated(null)
                renderAll()
            }
        }
        db.child("Last_Updated").addValueEventListener(updatedListener)
        lastUpdatedListener = updatedListener
    }

    /**
     * The choice saved on this phone (set in Settings) always wins.
     * Firebase is only used to seed it the first time, when nothing is saved yet.
     */
    private fun loadUserData() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.contains(KEY_HAS_VEHICLE)) return

        lifecycleScope.launch {
            val phone = sessionManager.getPhoneNumber()?.replace("+", "") ?: return@launch
            try {
                val snapshot = db.child("users").child(phone).child("hasVehicle").get().await()
                val remote = snapshot.getValue(Boolean::class.java) ?: false
                // Only apply it if the user didn't pick a mode while this was loading
                if (!prefs.contains(KEY_HAS_VEHICLE)) {
                    prefs.edit().putBoolean(KEY_HAS_VEHICLE, remote).apply()
                    hasVehicle = remote
                    renderAll()
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadUserData failed", e)
            }
        }
    }

    // ───────────────────────── Rendering ─────────────────────────

    private fun renderAll() {
        val readings = (1..4).associateWith { tracker.reading(it) }

        // Map dots
        readings.forEach { (id, r) ->
            dotFor(id).backgroundTintList = ColorStateList.valueOf(riskColor(r.risk))
        }

        // Current location (sensor 1)
        val cur = readings.getValue(homeId)
        val online = cur.risk != Risk.OFFLINE
        val color = riskColor(cur.risk)

        binding.tvMainStreet.text = SENSOR_STREETS.getValue(homeId)
        binding.tvMainOnline.text = getString(if (online) R.string.home_online_dot else R.string.home_offline)
        binding.tvMainOnline.setTextColor(color)
        binding.vMainIndicator.backgroundTintList = ColorStateList.valueOf(color)
        binding.tvMainLevelValue.text = if (online) formatCm(cur.level) else getString(R.string.risk_offline)
        binding.tvMainLevelValue.setTextColor(color)

        // Current level status cards
        binding.cvGridLevel.setCardBackgroundColor(
            (if (online) COLOR_WATER_CARD else COLOR_OFFLINE).toColorInt()
        )
        binding.tvGridLevelValue.text = formatCm(cur.level)
        binding.tvGridLevelSub.text = when {
            cur.level == null -> getString(R.string.conditions_no_data)
            !online -> getString(R.string.last_reading)
            else -> getString(R.string.threshold_percent_format, (cur.level / SensorTracker.THRESHOLD_CM * 100).roundToInt())
        }

        binding.cvGridRisk.setCardBackgroundColor(color)
        binding.tvGridRiskValue.text = riskLabel(cur.risk)

        // Rise Rate and Rain (same as on Conditions; blue, gray when offline)
        val boxColor = (if (online) COLOR_WATER_CARD else COLOR_OFFLINE).toColorInt()
        binding.cvGridRise.setCardBackgroundColor(boxColor)
        binding.cvGridRain.setCardBackgroundColor(boxColor)
        val (riseValue, riseDetail) = StatusText.rise(this, if (online) tracker.trend(homeId) else null)
        binding.tvGridRiseValue.text = riseValue
        binding.tvGridRiseSub.text = riseDetail
        binding.tvGridRainValue.text = StatusText.rain(this, if (online) tracker.snapshot(homeId) else null)
        binding.tvGridRainSub.text = getString(if (online) R.string.from_rain_sensor else R.string.home_no_live_data)
        binding.tvGridRiskSub.text = riskDetail(cur.risk) +
                (riskScore(homeId, cur)?.let { "\n" + getString(R.string.flood_risk_value, StatusText.scoreLabel(this, it)) } ?: "")

        // Person / Car mode
        val modeIcon = if (hasVehicle) R.drawable.ic_car else R.drawable.ic_person
        binding.tvModeTitle.text = getString(if (hasVehicle) R.string.car_mode else R.string.person_mode)
        binding.ivModeIcon.setImageResource(modeIcon)
        binding.ivMainModeIcon.setImageResource(modeIcon)
        binding.tvModeDesc.text = modeDescription(cur.risk)

        // Nearby devices
        val (n1, n2, n3) = nearbyIds()
        binding.tvNearby2Street.text = SENSOR_STREETS.getValue(n1)
        binding.tvNearby3Street.text = SENSOR_STREETS.getValue(n2)
        binding.tvNearby4Street.text = SENSOR_STREETS.getValue(n3)
        renderNearby(binding.tvNearby2Level, binding.vNearby2, readings.getValue(n1), riskScore(n1, readings.getValue(n1)))
        renderNearby(binding.tvNearby3Level, binding.vNearby3, readings.getValue(n2), riskScore(n2, readings.getValue(n2)))
        renderNearby(binding.tvNearby4Level, binding.vNearby4, readings.getValue(n3), riskScore(n3, readings.getValue(n3)))
    }

    /**
     * Flood risk score 0..100 for one street (Lasso Regression on water level + rain, see
     * RecordedTraining), or null while offline / still collecting readings.
     */
    private fun riskScore(id: Int, r: SensorReading): Double? {
        if (r.risk == Risk.OFFLINE) return null
        val full = RecordedTraining.riskForecaster() ?: return null
        return tracker.forecast(id, full, RecordedTraining.riskEarlyForecaster())?.lasso
    }

    private fun renderNearby(levelView: TextView, dot: View, r: SensorReading, score: Double?) {
        val color = riskColor(r.risk)
        dot.backgroundTintList = ColorStateList.valueOf(color)
        levelView.setTextColor(color)
        levelView.text = if (r.risk == Risk.OFFLINE) getString(R.string.risk_offline)
        else "${formatCm(r.level)} · ${riskLabel(r.risk)}" +
                (score?.let { " · " + getString(R.string.flood_risk_value, StatusText.scoreLabel(this, it)) } ?: "")
    }

    private fun riskColor(risk: Risk): Int = when (risk) {
        Risk.SAFE -> COLOR_SAFE
        Risk.MEDIUM -> COLOR_MEDIUM
        Risk.CRITICAL -> COLOR_CRITICAL
        Risk.OFFLINE -> COLOR_OFFLINE
    }.toColorInt()

    private fun riskLabel(risk: Risk): String = getString(
        when (risk) {
            Risk.SAFE -> R.string.status_label_safe
            Risk.MEDIUM -> R.string.status_label_warning
            Risk.CRITICAL -> R.string.status_label_dangerous
            Risk.OFFLINE -> R.string.status_offline
        }
    )

    private fun riskDetail(risk: Risk): String = getString(
        when (risk) {
            Risk.SAFE -> R.string.risk_detail_safe
            Risk.MEDIUM -> R.string.risk_detail_warning
            Risk.CRITICAL -> R.string.risk_detail_critical
            Risk.OFFLINE -> R.string.home_no_live_data_from
        }
    )

    private fun modeDescription(risk: Risk): String {
        if (risk == Risk.OFFLINE) return getString(R.string.mode_offline_guidance)
        val res = if (hasVehicle) {
            when (risk) {
                Risk.CRITICAL -> R.string.critical_desc
                Risk.MEDIUM -> R.string.risky_desc
                else -> R.string.safe_desc
            }
        } else {
            when (risk) {
                Risk.CRITICAL -> R.string.waist_desc
                Risk.MEDIUM -> R.string.knee_desc
                else -> R.string.ankle_desc
            }
        }
        return getString(res)
    }

    private fun formatCm(level: Double?): String = when {
        level == null -> "--"
        level % 1.0 == 0.0 -> "${level.toInt()} cm"
        else -> String.format(Locale.US, "%.1f cm", level)
    }

    // ───────────────────────── Lifecycle ─────────────────────────

    override fun onStart() { super.onStart(); binding.mapView.onStart() }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        // Pick up Person/Car mode changed in Settings
        hasVehicle = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_HAS_VEHICLE, false)
        homeId = HomeStreet.get(this)
        routePlanner.onResume()
        Log.d(TAG, "onResume: mode = ${if (hasVehicle) "CAR" else "PERSON"}")
        ticker.removeCallbacks(tickRunnable)
        ticker.post(tickRunnable)
        loadDailyForecast()
        maybeStartTour()
    }

    /** Shows the guided tour the first time Home opens (after the page has laid out). */
    private fun maybeStartTour() {
        if (OnboardingManager(this).isHomeTourDone() || tour?.isShowing == true) return
        binding.root.postDelayed({
            if (isFinishing || isDestroyed || tour?.isShowing == true) return@postDelayed
            tour = GuidedTour(this, binding.scrollView, tourSteps()) {
                OnboardingManager(this).setHomeTourDone(true)
                binding.scrollView.smoothScrollTo(0, 0)
            }.also { it.start() }
        }, 900)
    }

    private fun tourSteps() = listOf(
        GuidedTour.Step(listOf(binding.cvRoutePlanner), getString(R.string.tour_route_title), getString(R.string.tour_route_text)),
        GuidedTour.Step(listOf(binding.cvMap), getString(R.string.tour_map_title), getString(R.string.tour_map_text)),
        GuidedTour.Step(listOf(binding.ivSearch), getString(R.string.tour_search_title), getString(R.string.tour_search_text)),
        GuidedTour.Step(listOf(binding.cvMainLocation), getString(R.string.tour_street_title), getString(R.string.tour_street_text)),
        GuidedTour.Step(listOf(binding.cvGridLevel, binding.cvGridRain), getString(R.string.tour_status_title), getString(R.string.tour_status_text)),
        GuidedTour.Step(listOf(binding.cvDailyForecast), getString(R.string.tour_daily_title), getString(R.string.tour_daily_text)),
        GuidedTour.Step(listOf(binding.cvNearby2, binding.cvNearby4), getString(R.string.tour_nearby_title), getString(R.string.tour_nearby_text)),
        GuidedTour.Step(listOf(binding.fabChat), getString(R.string.tour_chat_title), getString(R.string.tour_chat_text)),
        GuidedTour.Step(listOf(binding.ivSettings), getString(R.string.settings_title), getString(R.string.tour_settings_text))
    )

    /** Downloads the 7-day forecast if the saved one is over 30 minutes old, then shows it. */
    private fun loadDailyForecast() {
        lifecycleScope.launch {
            val forecast = WeatherRepository.load(this@HomeActivity)
            if (forecast != null) dailySection.show(forecast) else dailySection.showUnavailable()
        }
    }

    override fun onPause() {
        ticker.removeCallbacks(tickRunnable)
        tracker.saveHistory()   // keep the latest readings for next time the app opens
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onStop() { super.onStop(); binding.mapView.onStop() }
    override fun onLowMemory() { super.onLowMemory(); binding.mapView.onLowMemory() }

    override fun onDestroy() {
        ticker.removeCallbacks(tickRunnable)
        sensorListeners.forEach { db.child(it.first).removeEventListener(it.second) }
        lastUpdatedListener?.let { db.child("Last_Updated").removeEventListener(it) }
        binding.mapView.onDestroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.mapView.onSaveInstanceState(outState)
    }
}