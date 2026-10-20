package com.example.bahgo20

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.bahgo20.databinding.ActivityHomeBinding
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.VectorSource
import java.io.File

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var sessionManager: SessionManager
    private val db = FirebaseDatabase
        .getInstance("https://bahgo-ee71b-default-rtdb.asia-southeast1.firebasedatabase.app/")
        .reference

    private val sensorListeners = mutableListOf<Pair<String, ValueEventListener>>()
    private var rootAlertStatusListener: ValueEventListener? = null
    private var globalAlertStatus: String? = null

    private var mapRef: MapLibreMap? = null
    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // Exact GPS coordinates from Plus Codes in Palar Village, Taguig:
    // Sealion:   G3J4+X3 -> (14.532438, 121.055188)
    // Samaritan: G3J4+X5 -> (14.532438, 121.055438)
    // Swingfire: G3J4+X7 -> (14.532438, 121.055688)
    // Striker:   G3M4+34 -> (14.532688, 121.055313)
    private val sensorCoordinates = mapOf(
        1 to LatLng(14.532438, 121.055188), // Sealion Street
        2 to LatLng(14.532438, 121.055438), // Samaritan Street
        3 to LatLng(14.532438, 121.055688), // Swingfire Street
        4 to LatLng(14.532688, 121.055313)  // Striker Street
    )

    // Receives selected street coordinates from SearchActivity to center the map
    private val searchActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val lat = data.getDoubleExtra("CENTER_LAT", PALAR_CENTER.latitude)
            val lon = data.getDoubleExtra("CENTER_LON", PALAR_CENTER.longitude)
            val streetName = data.getStringExtra("STREET_NAME") ?: "Selected Street"

            val targetLatLng = LatLng(lat, lon)
            binding.scrollView.smoothScrollTo(0, 0)
            mapRef?.animateCamera(
                CameraUpdateFactory.newLatLngZoom(targetLatLng, 17.8),
                1000
            )
            Toast.makeText(this, "Centered on $streetName", Toast.LENGTH_SHORT).show()
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            mapRef?.style?.let { style ->
                enableUserLocation(style)
                centerOnUserLocation(showToastIfOutside = false)
            }
        } else {
            Toast.makeText(
                this,
                "Location permission is needed to show your position on the flood map.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private companion object {
        const val TAG = "FloodMapDebug"
        const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

        const val HF_PMTILES_URL =
            "https://huggingface.co/datasets/bettergovph/project-noah-hazard-maps/resolve/main/PMTiles/layers/flood_5yr.pmtiles"
        const val LOCAL_ASSET_NAME = "taguig_flood_5yr.pmtiles"

        const val FLOOD_SOURCE_ID = "noah-flood-5yr-source"
        const val FLOOD_LAYER_ID = "flood-5yr-layer"
        const val FLOOD_SOURCE_LAYER = "flood_5yr"

        const val MASK_SOURCE_ID = "taguig-mask-source"
        const val MASK_LAYER_ID = "taguig-mask-fill"
        const val BORDER_LAYER_ID = "taguig-border-line"

        val TAGUIG_BOUNDS: LatLngBounds = LatLngBounds.from(
            14.560, 121.105, 14.465, 121.015
        )
        val PALAR_CENTER = LatLng(14.53250, 121.05544)

        const val TAGUIG_MASK_GEOJSON = """
        {
          "type": "FeatureCollection",
          "features": [
            {
              "type": "Feature",
              "properties": {},
              "geometry": {
                "type": "Polygon",
                "coordinates": [
                  [
                    [120.0, 13.5], [122.0, 13.5], [122.0, 15.5], [120.0, 15.5], [120.0, 13.5]
                  ],
                  [
                    [121.018, 14.512], [121.028, 14.538], [121.045, 14.562],
                    [121.064, 14.560], [121.076, 14.548], [121.098, 14.542],
                    [121.104, 14.525], [121.088, 14.495], [121.070, 14.468],
                    [121.042, 14.468], [121.032, 14.485], [121.018, 14.512]
                  ]
                ]
              }
            }
          ]
        }
        """
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            MapLibre.getInstance(this)
        } catch (e: Exception) {
            Log.e(TAG, "MapLibre init error: ${e.message}", e)
        }

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager.getInstance(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            insets
        }

        initMapView(savedInstanceState)
        setupPinClickListeners()

        loadUserData()
        setupSensorListeners()

        binding.ivSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.ivSearch.setOnClickListener {
            searchActivityLauncher.launch(Intent(this, SearchActivity::class.java))
        }

        binding.btnLogout.setOnClickListener {
            lifecycleScope.launch {
                sessionManager.logout()
                val intent = Intent(this@HomeActivity, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
        }
    }

    private fun openConditions(sensorId: Int, streetName: String) {
        val intent = Intent(this, ConditionsActivity::class.java).apply {
            putExtra("SENSOR_ID", sensorId)
            putExtra("STREET_NAME", streetName)
        }
        startActivity(intent)
    }

    private fun setupPinClickListeners() {
        // Map dots open ConditionsActivity
        binding.dotSensor1.setOnClickListener { openConditions(1, "Sealion Street") }
        binding.dotSensor2.setOnClickListener { openConditions(2, "Samaritan Street") }
        binding.dotSensor3.setOnClickListener { openConditions(3, "Swingfire Street") }
        binding.dotSensor4.setOnClickListener { openConditions(4, "Striker Street") }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initMapView(savedInstanceState: Bundle?) {
        try {
            binding.mapView.onCreate(savedInstanceState)

            binding.mapView.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }

            binding.mapView.addOnDidFailLoadingMapListener { errorMessage ->
                Log.e(TAG, "Map load failed: $errorMessage")
                binding.tvMapFallback.visibility = View.VISIBLE
            }

            binding.btnMyLocation.setOnClickListener {
                if (hasLocationPermission()) {
                    mapRef?.style?.let { enableUserLocation(it) }
                    centerOnUserLocation(showToastIfOutside = true)
                } else {
                    requestLocationPermission()
                }
            }

            binding.mapView.getMapAsync { map ->
                mapRef = map
                setupFloodMap(map)
            }
        } catch (e: Exception) {
            Log.e(TAG, "MapView init failed: ${e.message}", e)
            binding.tvMapFallback.visibility = View.VISIBLE
        }
    }

    private fun setupFloodMap(map: MapLibreMap) {
        map.setLatLngBoundsForCameraTarget(TAGUIG_BOUNDS)
        map.setMinZoomPreference(11.8)
        map.setMaxZoomPreference(19.5)

        map.cameraPosition = CameraPosition.Builder()
            .target(PALAR_CENTER)
            .zoom(17.8)
            .build()

        map.addOnCameraMoveListener { updateMarkerPositions() }
        map.addOnCameraIdleListener { updateMarkerPositions() }

        binding.mapView.post {
            runCatching {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(PALAR_CENTER, 17.8))
                updateMarkerPositions()
            }
        }

        map.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
            binding.tvMapFallback.visibility = View.VISIBLE
            addTaguigMaskAndBorder(style)

            if (hasLocationPermission()) {
                enableUserLocation(style)
            }

            lifecycleScope.launch {
                val pmtilesUri = resolvePmTilesUri()
                attachFloodLayerToStyle(style, pmtilesUri)
                binding.tvMapFallback.visibility = View.GONE
                binding.mapView.post { updateMarkerPositions() }
            }
        }

        map.addOnMapClickListener { latLng ->
            checkHazardAt(map, latLng)
            true
        }
    }

    private fun updateMarkerPositions() {
        val map = mapRef ?: return
        try {
            val projection = map.projection
            val radiusOffset = if (binding.dotSensor1.width > 0) binding.dotSensor1.width / 2f else 18f

            val p1 = projection.toScreenLocation(sensorCoordinates[1]!!)
            binding.dotSensor1.translationX = p1.x - radiusOffset
            binding.dotSensor1.translationY = p1.y - radiusOffset

            val p2 = projection.toScreenLocation(sensorCoordinates[2]!!)
            binding.dotSensor2.translationX = p2.x - radiusOffset
            binding.dotSensor2.translationY = p2.y - radiusOffset

            val p3 = projection.toScreenLocation(sensorCoordinates[3]!!)
            binding.dotSensor3.translationX = p3.x - radiusOffset
            binding.dotSensor3.translationY = p3.y - radiusOffset

            val p4 = projection.toScreenLocation(sensorCoordinates[4]!!)
            binding.dotSensor4.translationX = p4.x - radiusOffset
            binding.dotSensor4.translationY = p4.y - radiusOffset
        } catch (_: Exception) {
            // Ignore during initial layout pass
        }
    }

    private suspend fun resolvePmTilesUri(): String = withContext(Dispatchers.IO) {
        try {
            val assetList = assets.list("") ?: emptyArray()
            if (assetList.contains(LOCAL_ASSET_NAME)) {
                val outFile = File(cacheDir, LOCAL_ASSET_NAME)
                if (!outFile.exists() || outFile.length() == 0L) {
                    assets.open(LOCAL_ASSET_NAME).use { input ->
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                return@withContext "pmtiles://file://${outFile.absolutePath}"
            }
        } catch (e: Exception) {
            Log.w(TAG, "No local asset found, falling back to remote Hugging Face URL: ${e.message}")
        }

        try {
            val request = Request.Builder()
                .url(HF_PMTILES_URL)
                .header("Range", "bytes=0-0")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                val finalUrl = response.request.url.toString()
                return@withContext "pmtiles://$finalUrl"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving pmtiles URI: ${e.message}")
            return@withContext "pmtiles://$HF_PMTILES_URL"
        }
    }

    private fun attachFloodLayerToStyle(style: Style, pmtilesUri: String) {
        try {
            if (!style.isFullyLoaded) return

            if (style.getSource(FLOOD_SOURCE_ID) == null) {
                val floodSource = VectorSource(FLOOD_SOURCE_ID, pmtilesUri)
                style.addSource(floodSource)
            }

            val colorExpression = Expression.raw(
                """
                [
                  "case",
                  ["==", ["to-number", ["get", "Var"]], 1], "#93C5FD",
                  ["==", ["to-number", ["get", "Var"]], 2], "#3B82F6",
                  [">=", ["to-number", ["get", "Var"]], 3], "#1D4ED8",
                  "rgba(0, 0, 0, 0)"
                ]
                """.trimIndent()
            )

            if (style.getLayer(FLOOD_LAYER_ID) == null) {
                val floodLayer = FillLayer(FLOOD_LAYER_ID, FLOOD_SOURCE_ID).apply {
                    sourceLayer = FLOOD_SOURCE_LAYER
                    setProperties(
                        fillColor(colorExpression),
                        fillOpacity(0.65f)
                    )
                }

                if (style.getLayer(MASK_LAYER_ID) != null) {
                    style.addLayerBelow(floodLayer, MASK_LAYER_ID)
                } else {
                    style.addLayer(floodLayer)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error attaching flood layer: ${e.message}", e)
            binding.tvMapFallback.visibility = View.VISIBLE
        }
    }

    private fun addTaguigMaskAndBorder(style: Style) {
        try {
            if (style.getSource(MASK_SOURCE_ID) == null) {
                val maskSource = GeoJsonSource(MASK_SOURCE_ID, TAGUIG_MASK_GEOJSON)
                style.addSource(maskSource)
            }

            if (style.getLayer(MASK_LAYER_ID) == null) {
                val maskFillLayer = FillLayer(MASK_LAYER_ID, MASK_SOURCE_ID)
                maskFillLayer.setProperties(
                    fillColor("#F0F4F8".toColorInt()),
                    fillOpacity(0.88f)
                )
                style.addLayer(maskFillLayer)
            }

            if (style.getLayer(BORDER_LAYER_ID) == null) {
                val borderLineLayer = LineLayer(BORDER_LAYER_ID, MASK_SOURCE_ID)
                borderLineLayer.setProperties(
                    lineColor("#1D4ED8".toColorInt()),
                    lineWidth(2.0f)
                )
                style.addLayer(borderLineLayer)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding Taguig mask: ${e.message}", e)
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun requestLocationPermission() {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    @SuppressLint("MissingPermission")
    private fun enableUserLocation(style: Style) {
        val map = mapRef ?: return
        if (!hasLocationPermission()) return

        try {
            val locationComponent = map.locationComponent
            if (!locationComponent.isLocationComponentActivated) {
                val customOptions = LocationComponentOptions.builder(this)
                    .pulseEnabled(true)
                    .pulseColor("#1D4ED8".toColorInt())
                    .accuracyAlpha(0.25f)
                    .build()

                val activationOptions = LocationComponentActivationOptions
                    .builder(this, style)
                    .locationComponentOptions(customOptions)
                    .useDefaultLocationEngine(true)
                    .build()

                locationComponent.activateLocationComponent(activationOptions)
            }

            locationComponent.isLocationComponentEnabled = true
            locationComponent.renderMode = RenderMode.COMPASS
            locationComponent.cameraMode = CameraMode.NONE
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable user location component: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun centerOnUserLocation(showToastIfOutside: Boolean) {
        val map = mapRef ?: return
        val locationComponent = map.locationComponent
        if (!locationComponent.isLocationComponentActivated) return

        val lastLoc = locationComponent.lastKnownLocation
        if (lastLoc != null) {
            val userLatLng = LatLng(lastLoc.latitude, lastLoc.longitude)

            if (TAGUIG_BOUNDS.contains(userLatLng)) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(userLatLng, 17.5))
            } else if (showToastIfOutside) {
                Toast.makeText(this, "Your current GPS location is outside Taguig City.", Toast.LENGTH_SHORT).show()
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(PALAR_CENTER, 17.8))
            }
        } else if (showToastIfOutside) {
            Toast.makeText(this, "Waiting for GPS signal...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkHazardAt(map: MapLibreMap, latLng: LatLng) {
        val screenPoint = map.projection.toScreenLocation(latLng)
        val features = map.queryRenderedFeatures(screenPoint, FLOOD_LAYER_ID)

        val level = runCatching {
            features.firstOrNull()?.getNumberProperty("Var")?.toInt()
                ?: features.firstOrNull()?.getStringProperty("Var")?.toDoubleOrNull()?.toInt()
        }.getOrNull()

        val text = when (level) {
            1 -> "Taguig Low Hazard (0 – 0.5 m flood depth)"
            2 -> "Taguig Medium Hazard (0.5 – 1.5 m flood depth)"
            3 -> "Taguig High Hazard (> 1.5 m flood depth)"
            else -> "No recorded flood hazard at this point"
        }
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------------------------------
    // User + sensors (Evaluating Last_Alert_Status & water level)
    // ---------------------------------------------------------------------------------------

    private fun loadUserData() {
        lifecycleScope.launch {
            val phone = sessionManager.getPhoneNumber() ?: return@launch
            val sanitizedPhone = phone.replace("+", "")

            try {
                val snapshot = db.child("users").child(sanitizedPhone).get().await()
                if (snapshot.exists()) {
                    val hasVehicle = snapshot.child("hasVehicle").getValue(Boolean::class.java) ?: false
                    updateModeUi(hasVehicle)
                }
            } catch (e: Exception) {
                Log.e("HomeActivity", "Error loading user data", e)
            }
        }
    }

    private fun updateModeUi(hasVehicle: Boolean) {
        val iconRes = if (hasVehicle) R.drawable.ic_car else R.drawable.ic_person
        val modeTitle = if (hasVehicle) R.string.car_mode else R.string.person_mode
        val modeDesc = if (hasVehicle) R.string.car_mode_desc else R.string.person_mode_desc

        binding.tvModeTitle.text = getString(modeTitle)
        binding.tvModeDesc.text = getString(modeDesc)

        val icon = ContextCompat.getDrawable(this, iconRes)
        binding.ivMainModeIcon.setImageDrawable(icon)
        binding.tvModeTitle.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
    }

    private fun setupSensorListeners() {
        rootAlertStatusListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                globalAlertStatus = snapshot.value?.toString()
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        db.child("Last_Alert_Status").addValueEventListener(rootAlertStatusListener!!)

        val sensors = listOf("Sensordata1", "Sensordata2", "Sensordata3", "Sensordata4")

        sensors.forEachIndexed { index, sensorName ->
            val sensorNum = index + 1
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists() || snapshot.value == null) {
                        updateSensorState(sensorNum, 0, "OFFLINE")
                        return
                    }

                    val statusFromChild = snapshot.child("Last_Alert_Status").value?.toString()
                        ?: snapshot.child("status").value?.toString()

                    val levelStr = when {
                        snapshot.hasChild("waterLevel") -> snapshot.child("waterLevel").value?.toString()
                        snapshot.hasChild("level") -> snapshot.child("level").value?.toString()
                        snapshot.hasChild("distance") -> snapshot.child("distance").value?.toString()
                        snapshot.hasChild("value") -> snapshot.child("value").value?.toString()
                        !snapshot.hasChildren() -> snapshot.value?.toString()
                        else -> "0"
                    }

                    val rawValueStr = levelStr?.trim() ?: ""
                    val levelInt = rawValueStr.toDoubleOrNull()?.toInt() ?: 0

                    val rawStatus = statusFromChild
                        ?: (if (rawValueStr.toDoubleOrNull() == null && rawValueStr.isNotEmpty()) rawValueStr else null)
                        ?: globalAlertStatus

                    val resolvedStatus = resolveConditionStatus(rawStatus, rawValueStr, levelInt)
                    updateSensorState(sensorNum, levelInt, resolvedStatus)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("HomeActivity", "Error reading $sensorName", error.toException())
                    updateSensorState(sensorNum, 0, "OFFLINE")
                }
            }
            db.child(sensorName).addValueEventListener(listener)
            sensorListeners.add(sensorName to listener)
        }
    }

    private fun resolveConditionStatus(rawStatus: String?, rawValueStr: String, levelInt: Int): String {
        val statusClean = rawStatus?.trim()?.uppercase() ?: ""

        if (rawValueStr.isEmpty() || rawValueStr.equals("null", true) || rawValueStr.equals("offline", true)) {
            if (statusClean.isEmpty() || statusClean == "NULL" || statusClean.contains("OFFLINE")) {
                return "OFFLINE"
            }
        }

        return when {
            statusClean.contains("OFFLINE") || statusClean == "NULL" || statusClean == "DISCONNECTED" -> "OFFLINE"
            statusClean.contains("CRITICAL") || statusClean.contains("DANGER") || statusClean.contains("RED") -> "CRITICAL"
            statusClean.contains("HIGH") || statusClean.contains("WARNING") || statusClean.contains("MODERATE") || statusClean.contains("ORANGE") -> "HIGH"
            statusClean.contains("SAFE") || statusClean.contains("NORMAL") || statusClean.contains("LOW") || statusClean.contains("GREEN") -> "SAFE"
            else -> {
                when {
                    levelInt > 50 -> "CRITICAL"
                    levelInt > 20 -> "HIGH"
                    else -> "SAFE"
                }
            }
        }
    }

    private fun updateSensorState(sensorNum: Int, levelInt: Int, conditionStatus: String) {
        val dotView = when (sensorNum) {
            1 -> binding.dotSensor1
            2 -> binding.dotSensor2
            3 -> binding.dotSensor3
            4 -> binding.dotSensor4
            else -> null
        }

        val dotColorHex = when (conditionStatus) {
            "OFFLINE" -> "#9E9E9E"  // Gray
            "SAFE" -> "#4CAF50"     // Green
            "HIGH" -> "#FF9100"     // Orange
            "CRITICAL" -> "#D32F2F" // Red
            else -> "#9E9E9E"
        }
        dotView?.backgroundTintList = ColorStateList.valueOf(dotColorHex.toColorInt())

        val isOffline = (conditionStatus == "OFFLINE")
        val statusLabel = when (conditionStatus) {
            "OFFLINE" -> "offline"
            "CRITICAL" -> getString(R.string.status_dangerous)
            "HIGH" -> getString(R.string.status_moderate)
            else -> getString(R.string.status_safe)
        }

        when (sensorNum) {
            1 -> {
                binding.tvMainLevelValue.text = if (isOffline) "Offline" else getString(R.string.water_level_value_format, levelInt, statusLabel)
                binding.tvMainLevelValue.setTextColor((if (isOffline) "#9E9E9E" else dotColorHex).toColorInt())
                binding.tvGridLevelValue.text = if (isOffline) "OFF" else getString(R.string.water_level_cm_format, levelInt)
                binding.vMainIndicator.backgroundTintList = ColorStateList.valueOf(dotColorHex.toColorInt())
            }
            2 -> {
                binding.tvPeachLevel.text = if (isOffline) "Offline" else getString(R.string.water_level_value_format, levelInt, statusLabel)
                binding.tvPeachLevel.setTextColor((if (isOffline) "#9E9E9E" else if (conditionStatus == "CRITICAL") "#D32F2F" else "#1E88E5").toColorInt())
            }
            3 -> {
                binding.tvLimeLevel.text = if (isOffline) "Offline" else getString(R.string.water_level_value_format, levelInt, statusLabel)
                binding.tvLimeLevel.setTextColor((if (isOffline) "#9E9E9E" else if (conditionStatus == "CRITICAL") "#D32F2F" else "#1E88E5").toColorInt())
            }
            4 -> {
                binding.tvStrikerLevel.text = if (isOffline) "Offline" else getString(R.string.water_level_value_format, levelInt, statusLabel)
                binding.tvStrikerLevel.setTextColor((if (isOffline) "#9E9E9E" else if (conditionStatus == "CRITICAL") "#D32F2F" else "#1E88E5").toColorInt())

                binding.tvGridRiskValue.text = when (conditionStatus) {
                    "OFFLINE" -> "OFF"
                    "CRITICAL", "HIGH" -> getString(R.string.status_warning)
                    else -> getString(R.string.status_normal)
                }
                binding.tvMetricRise.text = if (isOffline) "Rate of Rise: --" else getString(R.string.rate_of_rise_format, levelInt / 2)
                binding.tvMetricPrecip.text = if (isOffline) "Precipitation: --" else getString(R.string.precipitation_format, levelInt / 5.0)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------

    override fun onStart() {
        super.onStart()
        binding.mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onStop() {
        super.onStop()
        binding.mapView.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.mapView.onLowMemory()
    }

    override fun onDestroy() {
        rootAlertStatusListener?.let { db.child("Last_Alert_Status").removeEventListener(it) }
        sensorListeners.forEach { (name, listener) ->
            db.child(name).removeEventListener(listener)
        }
        sensorListeners.clear()

        super.onDestroy()
        binding.mapView.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.mapView.onSaveInstanceState(outState)
    }
}