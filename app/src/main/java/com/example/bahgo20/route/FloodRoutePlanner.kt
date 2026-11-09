package com.example.bahgo20.route

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.bahgo20.FloodAlerts
import com.example.bahgo20.R
import com.example.bahgo20.databinding.ActivityHomeBinding
import com.example.bahgo20.databinding.SheetRoutesBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import com.example.bahgo20.utils.PlaceSearch
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineSortKey
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * "Iwas-Baha Route" on Home: From / To fields above the map. Finds a few routes (walking or driving),
 * judges each with [RouteSafety] (live sensors + NOAH 5-year flood map), draws them on the map
 * (green = easiest & safest, orange = passable with caution, red = avoid) and lists them below it.
 */
class FloodRoutePlanner(
    private val activity: AppCompatActivity,
    private val binding: ActivityHomeBinding,
    private val map: () -> MapLibreMap?,
    /** The 4 street sensors with their latest readings (called on the main thread). */
    private val sensors: () -> List<SensorStatus>,
    /** Person / Car Mode saved in Settings. */
    private val savedHasVehicle: () -> Boolean,
    private val requestLocationPermission: () -> Unit
) {
    /** What a From / To field points at. */
    private sealed class Endpoint {
        data object Empty : Endpoint()
        data object Current : Endpoint()
        data class Place(val name: String, val point: LatLon) : Endpoint()
        /** Text typed but not picked from the list: looked up when Find is tapped. */
        data class Typed(val text: String) : Endpoint()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private var from: Endpoint = Endpoint.Current
    private var to: Endpoint = Endpoint.Empty
    private var activeField: EditText? = null
    private var settingText = false
    private var mode = TravelMode.PERSON
    private var modeChosenHere = false
    private var syncingMode = false
    private var waitingForPermission = false
    private var searchJob: Job? = null
    private var suggestJob: Job? = null

    /** Routes on screen, in display order (best, caution options, avoid), and the highlighted one. */
    private var shown: List<Assessment> = emptyList()
    private var bestIndex = -1
    private var selected = 0
    private val cards = mutableListOf<MaterialCardView>()

    private val ctx get() = activity

    // ───────────────────────── Setup ─────────────────────────

    fun setup() {
        setField(binding.etRouteFrom, Endpoint.Current)
        mode = if (savedHasVehicle()) TravelMode.CAR else TravelMode.PERSON
        syncModeToggle()

        binding.toggleRouteMode.addOnButtonCheckedListener { _, id, checked ->
            if (!checked || syncingMode) return@addOnButtonCheckedListener
            val newMode = if (id == R.id.btnRouteModeCar) TravelMode.CAR else TravelMode.PERSON
            if (newMode == mode) return@addOnButtonCheckedListener
            mode = newMode
            modeChosenHere = true
            if (shown.isNotEmpty()) find()   // redo the search for the other mode
        }

        watch(binding.etRouteFrom, binding.ivRouteFromClear) { from = it }
        watch(binding.etRouteTo, binding.ivRouteToClear) { to = it }
        binding.ivRouteFromClear.setOnClickListener { setField(binding.etRouteFrom, Endpoint.Empty); binding.etRouteFrom.requestFocus() }
        binding.ivRouteToClear.setOnClickListener { setField(binding.etRouteTo, Endpoint.Empty); binding.etRouteTo.requestFocus() }
        binding.etRouteTo.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { find(); true } else false
        }
        binding.ivRouteSwap.setOnClickListener {
            val f = from; val t = to
            setField(binding.etRouteFrom, t)
            setField(binding.etRouteTo, f)
        }
        binding.btnFindRoute.setOnClickListener { find() }
        binding.btnClearRoute.setOnClickListener { clear() }
        binding.btnShowRoutes.setOnClickListener { showMapThenSheet() }
        buildQuickPicks()
    }

    /** Called from Home's onResume: follow the Person / Car Mode from Settings unless changed here. */
    fun onResume() {
        if (modeChosenHere) return
        val saved = if (savedHasVehicle()) TravelMode.CAR else TravelMode.PERSON
        if (saved != mode) { mode = saved; syncModeToggle() }
    }

    /** The map style (re)loaded: put the route lines back. */
    fun onStyleLoaded(style: Style) {
        ensureLayers(style)
        drawRoutes()
    }

    /** Answer to the location permission prompt started by Find. */
    fun onLocationPermissionResult(granted: Boolean) {
        if (!waitingForPermission) return
        waitingForPermission = false
        if (granted) find() else showError(R.string.route_err_location_denied)
    }

    private fun syncModeToggle() {
        syncingMode = true
        binding.toggleRouteMode.check(if (mode == TravelMode.CAR) R.id.btnRouteModeCar else R.id.btnRouteModePerson)
        syncingMode = false
    }

    private fun watch(field: EditText, clearIcon: ImageView, onChange: (Endpoint) -> Unit) {
        field.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                activeField = field
                // "Current location" is a label, not text to edit: select it so typing replaces it
                if (field.text.toString() == ctx.getString(R.string.route_current_location)) field.selectAll()
            } else if (activeField == v) {
                binding.llRouteSuggestions.postDelayed({ if (activeField?.hasFocus() != true) hideSuggestions() }, 150)
            }
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.trim().orEmpty()
                clearIcon.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                if (settingText) return
                // An old error ("Choose where you're going.") goes away once you start typing
                if (binding.llRouteStatus.visibility == View.VISIBLE && binding.pbRoute.visibility != View.VISIBLE) hideStatus()
                onChange(if (text.isEmpty()) Endpoint.Empty else Endpoint.Typed(text))
                suggest(field, text)
            }
        })
    }

    private fun setField(field: EditText, endpoint: Endpoint) {
        settingText = true
        field.setText(label(endpoint))
        settingText = false
        if (field === binding.etRouteFrom) from = endpoint else to = endpoint
        hideSuggestions()
    }

    private fun label(e: Endpoint): String = when (e) {
        Endpoint.Empty -> ""
        Endpoint.Current -> ctx.getString(R.string.route_current_location)
        is Endpoint.Place -> e.name
        is Endpoint.Typed -> e.text
    }

    /** Chips under the fields: current location (start) and the 4 sensor streets. */
    private fun buildQuickPicks() {
        val group = binding.cgRouteQuick
        group.removeAllViews()
        group.addView(chip(ctx.getString(R.string.route_current_location), R.drawable.ic_location_pin) {
            setField(binding.etRouteFrom, Endpoint.Current)
        })
        sensorPlaces().forEach { place ->
            group.addView(chip(place.name, null) {
                val target = if (activeField === binding.etRouteFrom && binding.etRouteFrom.hasFocus()) binding.etRouteFrom else binding.etRouteTo
                setField(target, place)
            })
        }
    }

    private fun chip(text: String, icon: Int?, onClick: () -> Unit) = Chip(ctx).apply {
        this.text = text
        isCheckable = false
        textSize = 12f
        icon?.let { chipIcon = ContextCompat.getDrawable(ctx, it); chipIconTint = ColorStateList.valueOf(color(R.color.icon_accent)) }
        setOnClickListener { onClick() }
    }

    private fun sensorPlaces(): List<Endpoint.Place> = sensors().map {
        Endpoint.Place(FloodAlerts.STREETS[it.id] ?: it.street, it.position)
    }

    // ───────────────────────── Suggestions ─────────────────────────

    private fun suggest(field: EditText, query: String) {
        suggestJob?.cancel()
        if (query.length < 2) { hideSuggestions(); return }
        val sensorMatches = sensorPlaces().filter { it.name.contains(query, ignoreCase = true) }
        showSuggestions(field, sensorMatches.map { it to ctx.getString(R.string.route_sensor_area, sensorIdOf(it)) })
        suggestJob = activity.lifecycleScope.launch {
            delay(450)  // wait until typing pauses (Nominatim allows about 1 search a second)
            val places = withContext(Dispatchers.IO) { PlaceSearch.search(http, query, 6) } ?: return@launch
            if (!field.hasFocus()) return@launch
            val items = sensorMatches.map { it to ctx.getString(R.string.route_sensor_area, sensorIdOf(it)) } +
                    places.map { Endpoint.Place(it.name, LatLon(it.lat, it.lon)) to it.area }
            showSuggestions(field, items.distinctBy { it.first.name.lowercase() }.take(7))
        }
    }

    private fun sensorIdOf(p: Endpoint.Place): Int = sensors().firstOrNull { it.position == p.point }?.id ?: 0

    private fun showSuggestions(field: EditText, items: List<Pair<Endpoint.Place, String>>) {
        val list = binding.llRouteSuggestions
        list.removeAllViews()
        items.forEach { (place, area) ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
                isClickable = true
                isFocusable = true
                background = ContextCompat.getDrawable(ctx, outValue(android.R.attr.selectableItemBackground))
            }
            row.addView(ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
                setImageResource(R.drawable.ic_location_pin)
                imageTintList = ColorStateList.valueOf(color(R.color.icon_accent))
            })
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) }
            }
            texts.addView(text(place.name, 14f, R.color.text_on_card, bold = true))
            texts.addView(text(area, 12f, R.color.text_on_card_secondary))
            row.addView(texts)
            row.setOnClickListener {
                setField(field, place)
                if (field === binding.etRouteFrom) binding.etRouteTo.requestFocus() else hideKeyboard()
            }
            list.addView(row)
        }
        list.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun hideSuggestions() {
        suggestJob?.cancel()
        binding.llRouteSuggestions.removeAllViews()
        binding.llRouteSuggestions.visibility = View.GONE
    }

    // ───────────────────────── Finding routes ─────────────────────────

    private fun find() {
        hideKeyboard()
        hideSuggestions()
        binding.etRouteFrom.clearFocus(); binding.etRouteTo.clearFocus()
        if (to == Endpoint.Empty) { showError(R.string.route_err_need_to); return }
        if (from == Endpoint.Empty) { showError(R.string.route_err_need_from); return }

        searchJob?.cancel()
        searchJob = activity.lifecycleScope.launch {
            try {
                val start = resolve(from, binding.etRouteFrom) ?: return@launch
                val end = resolve(to, binding.etRouteTo) ?: return@launch
                if (!inTaguig(start) || !inTaguig(end)) { showError(R.string.route_err_outside); return@launch }
                if (RouteGeometry.distance(start, end) < 30) { showError(R.string.route_err_same); return@launch }

                showBusy(R.string.route_status_routing)
                val travel = mode
                val routes = try {
                    withContext(Dispatchers.IO) { RouteService(http).routes(start, end, travel) }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Routing failed", e)
                    showError(R.string.route_err_network); return@launch
                }
                if (routes.isEmpty()) { showError(R.string.route_err_no_routes); return@launch }

                showBusy(R.string.route_status_checking)
                val live = sensors()
                val hazardMap = FloodHazardMap.get(ctx.applicationContext, http)
                val assessments = withContext(Dispatchers.IO) {
                    routes.map { r ->
                        val hazards = try {
                            hazardMap.hazards(RouteSafety.samples(r))
                        } catch (e: Exception) {
                            Log.w(TAG, "NOAH flood map unavailable", e)
                            null
                        }
                        RouteSafety.assess(r, hazards, live, travel)
                    }
                }
                showPlan(RouteSafety.plan(assessments), travel, start, end)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Route planning failed", e)
                showError(R.string.route_err_network)
            }
        }
    }

    /** The point for a field, or null (after showing why) if it can't be found. */
    private suspend fun resolve(e: Endpoint, field: EditText): LatLon? = when (e) {
        Endpoint.Empty -> null
        is Endpoint.Place -> e.point
        Endpoint.Current -> currentLocation()
        is Endpoint.Typed -> {
            showBusy(R.string.route_status_routing)
            val sensor = sensorPlaces().firstOrNull { it.name.contains(e.text, ignoreCase = true) }
            val place = sensor ?: withContext(Dispatchers.IO) { PlaceSearch.search(http, e.text, 1) }?.firstOrNull()
                ?.let { Endpoint.Place(it.name, LatLon(it.lat, it.lon)) }
            if (place == null) {
                showError(ctx.getString(R.string.route_err_no_match, e.text))
                null
            } else {
                setField(field, place)
                place.point
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(): LatLon? {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            waitingForPermission = true
            hideStatus()
            requestLocationPermission()
            return null
        }
        showBusy(R.string.route_status_locating)
        val client = LocationServices.getFusedLocationProviderClient(ctx)
        val loc = try {
            withTimeoutOrNull(12_000) {
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).await()
            } ?: client.lastLocation.await()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Location failed", e)
            null
        } ?: map()?.locationComponent?.lastKnownLocation
        if (loc == null) { showError(R.string.route_err_no_location); return null }
        return LatLon(loc.latitude, loc.longitude)
    }

    private fun inTaguig(p: LatLon) = p.lat in 14.455..14.575 && p.lon in 121.005..121.115

    private fun clear() {
        searchJob?.cancel()
        shown = emptyList(); bestIndex = -1; selected = 0
        cards.clear()
        sheet?.dismiss()
        binding.btnShowRoutes.visibility = View.GONE
        binding.btnClearRoute.visibility = View.GONE
        hideStatus()
        setField(binding.etRouteTo, Endpoint.Empty)
        setField(binding.etRouteFrom, Endpoint.Current)
        drawRoutes()
    }

    // ───────────────────────── Status line ─────────────────────────

    private fun showBusy(@StringRes text: Int) {
        binding.llRouteStatus.visibility = View.VISIBLE
        binding.pbRoute.visibility = View.VISIBLE
        binding.tvRouteStatus.setTextColor(color(R.color.text_on_card_secondary))
        binding.tvRouteStatus.text = ctx.getString(text)
        binding.btnFindRoute.isEnabled = false
    }

    private fun showError(@StringRes text: Int) = showError(ctx.getString(text))

    private fun showError(text: String) {
        binding.llRouteStatus.visibility = View.VISIBLE
        binding.pbRoute.visibility = View.GONE
        binding.tvRouteStatus.setTextColor(color(R.color.status_dangerous))
        binding.tvRouteStatus.text = text
        binding.btnFindRoute.isEnabled = true
    }

    private fun hideStatus() {
        binding.llRouteStatus.visibility = View.GONE
        binding.btnFindRoute.isEnabled = true
    }

    // ───────────────────────── Results ─────────────────────────

    private fun showPlan(plan: RoutePlan, travel: TravelMode, start: LatLon, end: LatLon) {
        hideStatus()
        binding.btnClearRoute.visibility = View.VISIBLE
        shown = listOfNotNull(plan.best) + plan.others + plan.avoid.take(3)
        bestIndex = if (plan.best != null) 0 else -1
        selected = if (plan.best != null) 0 else -1
        endpoints = start to end
        shownMode = travel
        noSafeRoute = plan.best == null
        checkedAt = DateFormat.getTimeFormat(ctx).format(Date())
        binding.btnShowRoutes.text = ctx.getString(R.string.route_show_routes_count, shown.size)
        binding.btnShowRoutes.visibility = View.VISIBLE

        drawRoutes()
        fitCamera()
        showMapThenSheet()
    }

    /** Jumps the page so the map is at the top, then opens the pop-up right below it. */
    private fun showMapThenSheet() {
        hideKeyboard()
        binding.scrollView.scrollTo(0, (binding.cvMap.top - dp(8)).coerceAtLeast(0))
        // Wait one frame so the scroll is applied before measuring where the map ends
        binding.scrollView.post { if (!activity.isFinishing && shown.isNotEmpty()) openSheet() }
    }

    private var shownMode = TravelMode.PERSON
    private var noSafeRoute = false
    private var checkedAt = ""
    private var sheet: BottomSheetDialog? = null

    /** The routes pop-up: half-height so the Home map above still shows the routes. */
    private fun openSheet() {
        sheet?.dismiss()
        val b = SheetRoutesBinding.inflate(LayoutInflater.from(ctx))
        val dialog = BottomSheetDialog(ctx)
        b.tvRoutesSheetTitle.text = ctx.getString(if (shownMode == TravelMode.CAR) R.string.route_results_car else R.string.route_results_person)
        b.ivRoutesSheetMode.setImageResource(if (shownMode == TravelMode.CAR) R.drawable.ic_car else R.drawable.ic_person)
        b.tvRoutesSheetSubtitle.text = ctx.getString(R.string.route_checked_at, checkedAt)
        b.ivRoutesSheetClose.setOnClickListener { dialog.dismiss() }
        fillRoutes(b.llRouteRows)

        dialog.setContentView(b.root)
        (b.root.parent as? ViewGroup)?.setBackgroundColor(Color.TRANSPARENT)
        dialog.window?.setDimAmount(0f)   // keep the map bright so the highlighted route is easy to see
        dialog.behavior.skipCollapsed = false
        dialog.behavior.peekHeight = peekHeight()
        dialog.behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        dialog.setOnDismissListener { if (sheet === dialog) sheet = null }
        sheet = dialog
        dialog.show()
    }

    /** Room below the map card, so the pop-up starts right under the map. */
    private fun peekHeight(): Int {
        val loc = IntArray(2)
        binding.scrollView.getLocationOnScreen(loc)
        val mapBottom = loc[1] + dp(8) + binding.cvMap.height
        val screen = ctx.resources.displayMetrics.heightPixels
        return (screen - mapBottom).coerceIn(dp(280), (screen * 0.75).toInt())
    }

    private fun fillRoutes(list: LinearLayout) {
        list.removeAllViews()
        cards.clear()
        val travel = shownMode
        if (noSafeRoute) list.addView(noSafeRouteCard())

        fun sectionOf(i: Int): Int = when {
            i == bestIndex -> R.string.route_best_title
            shown[i].verdict == Verdict.AVOID -> R.string.route_avoid_title
            shown[i].verdict == Verdict.SAFE -> R.string.route_others_clear_title
            else -> R.string.route_others_title
        }
        shown.forEachIndexed { i, a ->
            val section = sectionOf(i)
            if (i == 0 || section != sectionOf(i - 1)) list.addView(sectionTitle(section, routeColorRes(i)))
            list.addView(routeCard(i, a, travel))
        }
        highlightCards()
    }

    private var endpoints: Pair<LatLon, LatLon>? = null

    @ColorRes
    private fun routeColorRes(i: Int): Int = when {
        i == bestIndex -> R.color.status_safe
        shown[i].verdict == Verdict.AVOID -> R.color.status_dangerous
        else -> R.color.status_warning
    }

    private fun sectionTitle(@StringRes title: Int, @ColorRes dot: Int): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(8))
        }
        row.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(8) }
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_button_solid)
            backgroundTintList = ColorStateList.valueOf(color(dot))
        })
        row.addView(text(ctx.getString(title), 14f, R.color.text_on_gradient, bold = true))
        return row
    }

    private fun routeCard(index: Int, a: Assessment, travel: TravelMode): MaterialCardView {
        val accent = color(routeColorRes(index))
        val card = baseCard()
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        // Distance · time, and the verdict badge
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) }
            setImageResource(if (travel == TravelMode.CAR) R.drawable.ic_car else R.drawable.ic_person)
            imageTintList = ColorStateList.valueOf(accent)
        })
        top.addView(text(ctx.getString(R.string.route_distance_time, formatDistance(a.route.distanceM), formatDuration(a.route.durationS)),
            16f, R.color.text_on_card, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val badge = when (a.verdict) {
            Verdict.SAFE -> R.string.route_badge_clear
            Verdict.CAUTION -> R.string.route_badge_caution
            Verdict.AVOID -> R.string.route_badge_avoid
        }
        val badgeColor = color(when (a.verdict) {
            Verdict.SAFE -> R.color.status_safe
            Verdict.CAUTION -> R.color.status_warning
            Verdict.AVOID -> R.color.status_dangerous
        })
        top.addView(TextView(ctx).apply {
            text = ctx.getString(badge)
            setTextColor(color(R.color.white))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), dp(3), dp(10), dp(3))
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_button_solid)
            backgroundTintList = ColorStateList.valueOf(badgeColor)
        })
        body.addView(top)

        // Why: sensors passed, NOAH stretches, notes
        // Main reasons first; offline sensors are folded into one note at the end
        val offline = a.reasons.filterIsInstance<Reason.SensorOffline>().map { it.street }
        val main = a.reasons.filter { it !is Reason.SensorOffline }.map { reasonText(it) }.ifEmpty {
            listOf(ctx.getString(if (index == bestIndex) R.string.route_reason_clear else R.string.route_reason_longer))
        }
        val lines = main + when (offline.size) {
            0 -> emptyList()
            1 -> listOf(ctx.getString(R.string.route_reason_sensor_offline, offline[0]))
            else -> listOf(ctx.getString(R.string.route_reason_sensors_offline, offline.joinToString(", ")))
        }
        lines.forEach { line ->
            body.addView(text("• $line", 13f, R.color.text_on_card).apply { setPadding(0, dp(6), 0, 0) })
        }
        body.addView(text(noahSummary(a), 12f, R.color.text_on_card_secondary).apply { setPadding(0, dp(6), 0, 0) })

        // What to do, for walking or driving
        body.addView(text(advice(a.verdict, travel), 13f, R.color.text_on_card).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setTypeface(null, Typeface.ITALIC)
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_input_field)
            backgroundTintList = ColorStateList.valueOf((accent and 0x00FFFFFF) or 0x22000000)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) }
        })

        card.addView(body)
        card.setOnClickListener {
            selected = index
            highlightCards()
            drawRoutes()
            fitCamera()
            // If the pop-up was dragged up over the map, drop it back down so the route shows
            sheet?.behavior?.let { if (it.state == BottomSheetBehavior.STATE_EXPANDED) it.state = BottomSheetBehavior.STATE_COLLAPSED }
        }
        cards += card
        return card
    }

    private fun noSafeRouteCard(): View {
        val card = baseCard().apply {
            strokeColor = color(R.color.status_dangerous)
            strokeWidth = dp(2)
        }
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val title = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        title.addView(ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) }
            setImageResource(R.drawable.ic_warning)
            imageTintList = ColorStateList.valueOf(color(R.color.status_dangerous))
        })
        title.addView(text(ctx.getString(R.string.route_none_safe_title), 16f, R.color.status_dangerous, bold = true))
        body.addView(title)
        body.addView(text(ctx.getString(R.string.route_none_safe_text), 13f, R.color.text_on_card).apply { setPadding(0, dp(8), 0, dp(8)) })
        body.addView(MaterialButton(ctx).apply {
            text = ctx.getString(R.string.route_call_911)
            setIconResource(R.drawable.ic_phone)
            backgroundTintList = ColorStateList.valueOf(color(R.color.status_dangerous))
            setOnClickListener { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:911"))) }
        })
        card.addView(body)
        return card
    }

    private fun baseCard() = MaterialCardView(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(12) }
        setCardBackgroundColor(color(R.color.card_glass))
        radius = dp(20).toFloat()
        cardElevation = dp(2).toFloat()
        strokeWidth = dp(1)
        strokeColor = color(R.color.card_stroke)
        isClickable = true
        isFocusable = true
    }

    private fun highlightCards() {
        cards.forEachIndexed { i, card ->
            val on = i == selected
            card.strokeWidth = dp(if (on) 3 else 1)
            card.strokeColor = if (on) color(routeColorRes(i)) else color(R.color.card_stroke)
        }
    }

    private fun reasonText(r: Reason): String = when (r) {
        is Reason.SensorCritical -> ctx.getString(R.string.route_reason_sensor_critical, r.street, cm(r.levelCm))
        is Reason.SensorTooDeepForCar -> ctx.getString(R.string.route_reason_sensor_too_deep_car, r.street, cm(r.levelCm))
        is Reason.SensorWarning -> ctx.getString(R.string.route_reason_sensor_warning, r.street, cm(r.levelCm))
        is Reason.SensorOffline -> ctx.getString(R.string.route_reason_sensor_offline, r.street)
        is Reason.NoahHigh -> ctx.getString(R.string.route_reason_noah_high, formatDistance(r.meters))
        is Reason.NoahMedium -> ctx.getString(R.string.route_reason_noah_medium, formatDistance(r.meters))
        Reason.FloodDataMissing -> ctx.getString(R.string.route_reason_flood_missing)
    }

    private fun noahSummary(a: Assessment): String {
        if (a.reasons.contains(Reason.FloodDataMissing)) return ctx.getString(R.string.route_reason_flood_missing)
        val parts = listOf(
            Hazard.HIGH to R.string.route_noah_high,
            Hazard.MEDIUM to R.string.route_noah_medium,
            Hazard.LOW to R.string.route_noah_low
        ).mapNotNull { (h, res) ->
            val m = a.meters(h)
            if (m < 10) null else ctx.getString(res, formatDistance(m))
        }
        return if (parts.isEmpty()) ctx.getString(R.string.route_noah_none)
        else ctx.getString(R.string.route_noah_summary, parts.joinToString(" · "))
    }

    private fun advice(v: Verdict, travel: TravelMode): String = ctx.getString(
        when (travel) {
            TravelMode.PERSON -> when (v) {
                Verdict.SAFE -> R.string.route_advice_person_safe
                Verdict.CAUTION -> R.string.route_advice_person_caution
                Verdict.AVOID -> R.string.route_advice_person_avoid
            }
            TravelMode.CAR -> when (v) {
                Verdict.SAFE -> R.string.route_advice_car_safe
                Verdict.CAUTION -> R.string.route_advice_car_caution
                Verdict.AVOID -> R.string.route_advice_car_avoid
            }
        }
    )

    private fun formatDistance(m: Double): String =
        if (m < 1000) ctx.getString(R.string.route_meters, ((m / 10).roundToInt() * 10))
        else ctx.getString(R.string.route_km, String.format(Locale.US, "%.1f", m / 1000))

    private fun formatDuration(s: Double): String {
        val min = (s / 60).roundToInt().coerceAtLeast(1)
        return if (min < 60) ctx.getString(R.string.route_minutes, min)
        else ctx.getString(R.string.route_hours_minutes, min / 60, min % 60)
    }

    private fun cm(level: Double?): String = when {
        level == null -> "--"
        level % 1.0 == 0.0 -> "${level.toInt()} cm"
        else -> String.format(Locale.US, "%.1f cm", level)
    }

    // ───────────────────────── Map ─────────────────────────

    private fun ensureLayers(style: Style) {
        try {
            if (style.getSource(ROUTE_SOURCE) == null) style.addSource(GeoJsonSource(ROUTE_SOURCE, EMPTY))
            if (style.getSource(ENDS_SOURCE) == null) style.addSource(GeoJsonSource(ENDS_SOURCE, EMPTY))
            val selected = Expression.raw("""["get", "selected"]""")
            if (style.getLayer(CASING_LAYER) == null) style.addLayer(LineLayer(CASING_LAYER, ROUTE_SOURCE).withProperties(
                lineColor("#FFFFFF"),
                lineWidth(Expression.switchCase(selected, Expression.literal(11f), Expression.literal(8f))),
                lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
                lineSortKey(Expression.get("sort"))
            ))
            if (style.getLayer(LINE_LAYER) == null) style.addLayer(LineLayer(LINE_LAYER, ROUTE_SOURCE).withProperties(
                lineColor(Expression.get("color")),
                lineWidth(Expression.switchCase(selected, Expression.literal(7f), Expression.literal(4.5f))),
                lineOpacity(Expression.switchCase(selected, Expression.literal(1f), Expression.literal(0.8f))),
                lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
                lineSortKey(Expression.get("sort"))
            ))
            if (style.getLayer(ENDS_LAYER) == null) style.addLayer(CircleLayer(ENDS_LAYER, ENDS_SOURCE).withProperties(
                circleColor(Expression.get("color")),
                circleRadius(7f),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(2.5f)
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Route layers failed", e)
        }
    }

    /** Lines for the routes on screen. Avoid routes only appear when tapped. */
    private fun drawRoutes() {
        val style = map()?.style ?: return
        if (!style.isFullyLoaded) return
        ensureLayers(style)
        val features = shown.mapIndexedNotNull { i, a ->
            val isSelected = i == selected
            if (a.verdict == Verdict.AVOID && !isSelected) return@mapIndexedNotNull null
            val sort = when { isSelected -> 3; i == bestIndex -> 2; else -> 1 }
            val coords = a.route.points.joinToString(",") { String.format(Locale.US, "[%.6f,%.6f]", it.lon, it.lat) }
            """{"type":"Feature","properties":{"color":"${hex(routeColorRes(i))}","selected":$isSelected,"sort":$sort},""" +
                    """"geometry":{"type":"LineString","coordinates":[$coords]}}"""
        }
        style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(collection(features))

        val ends = endpoints?.takeIf { shown.isNotEmpty() }?.let { (a, b) ->
            listOf(a to hex(R.color.icon_accent), b to hex(R.color.status_dangerous)).map { (p, c) ->
                String.format(Locale.US, """{"type":"Feature","properties":{"color":"%s"},"geometry":{"type":"Point","coordinates":[%.6f,%.6f]}}""", c, p.lon, p.lat)
            }
        }.orEmpty()
        style.getSourceAs<GeoJsonSource>(ENDS_SOURCE)?.setGeoJson(collection(ends))
    }

    /** Zooms the map to the highlighted route (or all routes when none is). */
    private fun fitCamera() {
        val m = map() ?: return
        val points = shown.getOrNull(selected)?.route?.points ?: shown.flatMap { it.route.points }
        if (points.size < 2) return
        val bounds = LatLngBounds.Builder().apply { points.forEach { include(LatLng(it.lat, it.lon)) } }.build()
        try {
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, dp(48)), 900)
        } catch (e: Exception) {
            Log.w(TAG, "Fit camera failed", e)
        }
    }

    private fun collection(features: List<String>) = """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""

    private fun hex(@ColorRes res: Int) = String.format("#%06X", color(res) and 0xFFFFFF)

    // ───────────────────────── Helpers ─────────────────────────

    private fun text(s: String, sizeSp: Float, @ColorRes colorRes: Int, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        setTextColor(color(colorRes))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun hideKeyboard() {
        val imm = ctx.getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    private fun outValue(attr: Int): Int {
        val tv = TypedValue()
        ctx.theme.resolveAttribute(attr, tv, true)
        return tv.resourceId
    }

    private fun color(@ColorRes res: Int) = ContextCompat.getColor(ctx, res)

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    private companion object {
        const val TAG = "FloodRoutePlanner"
        const val ROUTE_SOURCE = "wayout-routes"
        const val ENDS_SOURCE = "wayout-ends"
        const val CASING_LAYER = "wayout-route-casing"
        const val LINE_LAYER = "wayout-route-line"
        const val ENDS_LAYER = "wayout-ends-layer"
        const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
    }
}
