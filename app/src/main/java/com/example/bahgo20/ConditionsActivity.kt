package com.example.bahgo20

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.bahgo20.chart.ChartHistory
import com.example.bahgo20.chart.ChartPoint
import com.example.bahgo20.chart.ChartSource
import com.example.bahgo20.chart.ChartWindow
import com.example.bahgo20.chart.LiveHistoryStore
import com.example.bahgo20.chart.RecordedChartLoader
import com.example.bahgo20.databinding.ActivityConditionsBinding
import com.example.bahgo20.ml.LevelFeatures
import com.example.bahgo20.ml.FloodFeatures
import com.example.bahgo20.ui.RainForecastSection
import com.example.bahgo20.ui.StatusInfoSheet
import com.example.bahgo20.ui.StatusText
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class ConditionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConditionsBinding
    private val db = FirebaseDatabase
        .getInstance("https://bahgo-ee71b-default-rtdb.asia-southeast1.firebasedatabase.app/")
        .reference

    // Decides online/offline from Last_Updated and works out the rate of rise (see SensorTracker.kt)
    private val tracker = SensorTracker()
    private var sensorId: Int = 1
    private var sensorListener: ValueEventListener? = null
    private var lastUpdatedListener: ValueEventListener? = null

    // Pop-up sheet over Home: drag down or tap X to close
    private lateinit var sheetBehavior: BottomSheetBehavior<View>

    // Rain Forecast card, from this street's live rain sensor (see ui/RainForecastSection.kt)
    private lateinit var rainSection: RainForecastSection

    // Flood Severity Chart: recorded (simulated) + live water levels (see chart/ChartHistory.kt)
    private var chartWindow = ChartWindow.H24
    private var recordedPoints: List<ChartPoint>? = null   // null = still loading

    // Re-evaluates "offline" every few seconds even when Firebase sends nothing new
    private val ticker = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            render()
            ticker.postDelayed(this, REFRESH_MS)
        }
    }

    private companion object {
        const val TAG = "ConditionsActivity"

        const val REFRESH_MS = 15_000L

        // Status colors
        const val COLOR_SAFE = "#4CAF50"
        const val COLOR_MEDIUM = "#FF9100"
        const val COLOR_CRITICAL = "#D32F2F"
        const val COLOR_OFFLINE = "#9E9E9E"
        const val COLOR_WATER_CARD = "#1E88E5"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityConditionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Sheet starts a little below the status bar, so Home shows (dimmed) above it
        ViewCompat.setOnApplyWindowInsetsListener(binding.sheetRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val dp = resources.displayMetrics.density
            (binding.sheet.layoutParams as ViewGroup.MarginLayoutParams).topMargin = bars.top + (12 * dp).toInt()
            binding.sheet.requestLayout()
            binding.scrollConditions.setPadding(bars.left, 0, bars.right, bars.bottom + (24 * dp).toInt())
            insets
        }
        setupSheet()

        sensorId = intent.getIntExtra("SENSOR_ID", 1)
        binding.rainfallView.sensorId = sensorId   // rain effect follows this street's rain sensor
        binding.tvStreetName.text = intent.getStringExtra("STREET_NAME") ?: getString(R.string.default_street)
        binding.ivClose.setOnClickListener { closeSheet() }

        setupListeners()
        loadForecastModels()

        rainSection = RainForecastSection(binding.tvRainSummary, binding.vRainDivider, binding.llRainStrip)
        setupChart()
        setupStatusInfo()
        render()
    }

    // ───────────────────────── Pop-up sheet ─────────────────────────

    /** Slides the sheet up when it opens; closing it (drag down, X, back) finishes the screen. */
    private fun setupSheet() {
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        sheetBehavior = BottomSheetBehavior.from(binding.sheet)
        sheetBehavior.isHideable = true
        sheetBehavior.skipCollapsed = true
        sheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        sheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(sheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_HIDDEN && !isFinishing) {
                    finish()
                    @Suppress("DEPRECATION")
                    overridePendingTransition(0, 0)
                }
            }

            override fun onSlide(sheet: View, slideOffset: Float) {
                // Fade the dim layer as the sheet is dragged away (offset -1 = hidden, 0 = open)
                binding.sheetRoot.background?.alpha = (255 * (1 + slideOffset).coerceIn(0f, 1f)).toInt()
            }
        })
        binding.sheetRoot.setOnClickListener { closeSheet() }   // tap the dimmed area above the sheet
        binding.sheet.post { sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = closeSheet()
        })
    }

    private fun closeSheet() {
        sheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
    }

    /** Trains the forecast models on the recorded data in the app (already done if Home opened first). */
    private fun loadForecastModels() {
        lifecycleScope.launch {
            RecordedTraining.load()
            render()
        }
    }

    private fun setupListeners() {
        sensorListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                tracker.setSensor(sensorId, snapshot)
                render()
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Sensordata$sensorId cancelled: ${error.message}")
                tracker.clearSensor(sensorId)
                render()
            }
        }
        db.child("Sensordata$sensorId").addValueEventListener(sensorListener!!)

        // Root-level Last_Updated (used when the device has no Last_Updated inside its own node)
        lastUpdatedListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                tracker.setRootUpdated(snapshot)
                render()
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Last_Updated cancelled: ${error.message}")
                tracker.setRootUpdated(null)
                render()
            }
        }
        db.child("Last_Updated").addValueEventListener(lastUpdatedListener!!)
    }

    /** Tap a Current Level Status box -> a plain-language explanation slides up. */
    private fun setupStatusInfo() {
        binding.cvWaterLevelCard.setOnClickListener {
            StatusInfoSheet.show(this, StatusInfoSheet.Box.WATER_LEVEL, binding.tvWaterLevel.text.toString())
        }
        binding.cvRiskLevelCard.setOnClickListener {
            StatusInfoSheet.show(this, StatusInfoSheet.Box.RISK_LEVEL, binding.tvRiskLevel.text.toString())
        }
        binding.cvRiseCard.setOnClickListener {
            StatusInfoSheet.show(this, StatusInfoSheet.Box.RISE_RATE,
                "${binding.tvRateOfRise.text} · ${binding.tvRiseDetail.text}")
        }
        binding.cvRainCard.setOnClickListener {
            StatusInfoSheet.show(this, StatusInfoSheet.Box.RAIN, binding.tvPrecipitation.text.toString())
        }
    }

    // ───────────────────────── Rendering ─────────────────────────

    private fun render() {
        val r = tracker.reading(sensorId)
        val online = r.risk != Risk.OFFLINE
        val color = riskColor(r.risk)

        // Online / offline badge
        binding.tvOnlineBadge.text = getString(if (online) R.string.risk_online else R.string.risk_offline)
        binding.tvOnlineBadge.backgroundTintList = ColorStateList.valueOf(color)

        // Last updated (from Last_Updated, or the last time the app saw it change)
        if (r.updatedMs != null) {
            val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
            binding.tvLastUpdated.text = getString(R.string.updated_at, fmt.format(Date(r.updatedMs)))
            binding.tvLastUpdated.visibility = View.VISIBLE
        } else {
            binding.tvLastUpdated.visibility = View.GONE
        }

        // Water level card (shows the last known reading, grayed out when offline)
        binding.cvWaterLevelCard.setCardBackgroundColor(
            (if (online) COLOR_WATER_CARD else COLOR_OFFLINE).toColorInt()
        )
        binding.tvWaterLevel.text = formatCm(r.level)
        binding.tvThreshold.text = when {
            r.level == null -> getString(R.string.conditions_no_data)
            !online -> getString(R.string.last_reading)
            else -> getString(R.string.threshold_percent_format, (r.level / SensorTracker.THRESHOLD_CM * 100).roundToInt())
        }

        // Risk card
        binding.cvRiskLevelCard.setCardBackgroundColor(color)
        binding.tvRiskLevel.text = riskLabel(r.risk)
        binding.tvRiskDetail.text = riskDetail(r.risk)

        // Rise Rate and Rain boxes: same style as Water Level (blue, gray when offline)
        val boxColor = (if (online) COLOR_WATER_CARD else COLOR_OFFLINE).toColorInt()
        binding.cvRiseCard.setCardBackgroundColor(boxColor)
        binding.cvRainCard.setCardBackgroundColor(boxColor)
        val (riseValue, riseDetail) = StatusText.rise(this, if (online) tracker.trend(sensorId) else null)
        binding.tvRateOfRise.text = riseValue
        binding.tvRiseDetail.text = riseDetail
        val snap = if (online) tracker.snapshot(sensorId) else null
        binding.tvPrecipitation.text = StatusText.rain(this, snap)
        binding.tvRainDetail.text = getString(if (online) R.string.from_rain_sensor else R.string.home_no_live_data)

        // Forecast: expected level in 30 minutes, and rain in 30 minutes
        renderForecast(online, r.level)
        renderRainForecast(online)
        renderChart(online)
    }

    /**
     * Flood Forecast card, in plain words for users. Model details and test scores go to Logcat
     * only (tag "RecordedTraining"), not to the screen.
     *   - Water level in 30 minutes: LINEAR REGRESSION on the water level readings, then checked
     *     against the Safe / Warning / Critical thresholds (objective 2)
     *   - Flood risk score for this street: LASSO REGRESSION on water level + rain (objective 3)
     * Each uses its full inputs when there are enough recent readings, otherwise the quick ones.
     */
    private fun renderForecast(online: Boolean, levelNow: Double?) {
        renderLevelForecast(online, levelNow)
        renderRiskScore(online)
    }

    private fun renderLevelForecast(online: Boolean, levelNow: Double?) {
        val full = RecordedTraining.levelForecaster()
        val early = RecordedTraining.levelEarlyForecaster()
        val f = if (full == null || !online) null else tracker.forecast(sensorId, full, early)
        if (f == null) {
            showNotReady(binding.tvForecastLevel, binding.tvForecastDetail, online, full == null, early != null, LevelFeatures.HISTORY_STEPS)
            return
        }

        // Linear Regression: water level in 30 minutes, checked against the thresholds
        val predicted = f.linear
        val risk = SensorTracker.riskForLevel(predicted)
        binding.tvForecastLevel.text = "${formatCm(predicted)} · ${riskLabel(risk)}"
        binding.tvForecastLevel.setTextColor(riskColor(risk))

        // Percentage of the danger level, and which way the water is going
        val percent = (predicted / SensorTracker.THRESHOLD_CM * 100).roundToInt()
        val lines = mutableListOf(getString(R.string.forecast_percent_danger, percent))
        if (levelNow != null) {
            val change = predicted - levelNow
            lines += when {
                change >= 1.0 -> getString(R.string.forecast_may_rise, formatNumber(change.roundToInt().toDouble()))
                change <= -1.0 -> getString(R.string.forecast_may_fall, formatNumber((-change).roundToInt().toDouble()))
                else -> getString(R.string.forecast_same)
            }
            val riskNow = SensorTracker.riskForLevel(levelNow)
            if (risk.ordinal > riskNow.ordinal) lines += getString(R.string.forecast_could_reach, riskLabel(risk))
        }
        binding.tvForecastDetail.text = lines.joinToString("\n")
    }

    private fun renderRiskScore(online: Boolean) {
        val full = RecordedTraining.riskForecaster()
        val early = RecordedTraining.riskEarlyForecaster()
        val f = if (full == null || !online) null else tracker.forecast(sensorId, full, early)
        if (f == null) {
            showNotReady(binding.tvRiskScore, binding.tvRiskScoreDetail, online, full == null, early != null, FloodFeatures.HISTORY_STEPS)
            return
        }

        // Lasso Regression: flood risk score 0..100 from water level and rain, shown as a word only
        val score = f.lasso
        val scoreRisk = SensorTracker.riskForScore(score)
        binding.tvRiskScore.text = StatusText.scoreLabel(this, score)
        binding.tvRiskScore.setTextColor(riskColor(scoreRisk))
        binding.tvRiskScoreDetail.text = getString(
            when (scoreRisk) {
                Risk.CRITICAL -> R.string.flood_risk_detail_critical
                Risk.MEDIUM -> R.string.flood_risk_detail_warning
                else -> R.string.flood_risk_detail_low
            }
        )
    }

    /** "--" while offline, "Not available" if training failed, otherwise "Getting ready…". */
    private fun showNotReady(
        value: TextView, detail: TextView, online: Boolean, noModel: Boolean, hasQuick: Boolean, fullSteps: Int
    ) {
        value.setTextColor(ContextCompat.getColor(this, R.color.text_on_card))
        when {
            !online -> {
                value.text = "--"
                detail.text = getString(R.string.forecast_offline)
            }
            noModel && RecordedTraining.loadError != null -> {
                value.text = getString(R.string.forecast_not_available)
                detail.text = getString(R.string.forecast_not_available_detail)
            }
            else -> {
                val needMin = (if (hasQuick) 1 else fullSteps) * FloodFeatures.STEP_MIN
                val left = maxOf(1, needMin - tracker.historyMinutes(sensorId))
                value.text = getString(R.string.conditions_getting_ready)
                detail.text = getString(R.string.forecast_ready_in, left)
            }
        }
    }

    // ───────────────────────── Flood Severity Chart ─────────────────────────

    private fun setupChart() {
        binding.chipGroupWindow.setOnCheckedStateChangeListener { _, ids ->
            if (ids.isEmpty()) return@setOnCheckedStateChangeListener   // in-between state while switching
            chartWindow = when (ids.first()) {
                R.id.chip6h -> ChartWindow.H6
                R.id.chip3d -> ChartWindow.D3
                R.id.chipAll -> ChartWindow.ALL
                else -> ChartWindow.H24
            }
            loadRecordedForChart()
        }
        // Start on 24h once there's at least an hour of live history; before that the
        // short windows would be nearly empty, so start on the recorded sample data.
        val live = LiveHistoryStore.points(sensorId)
        val liveSpanMs = if (live.size < 2) 0L else live.last().timeMs - live.first().timeMs
        if (liveSpanMs < 60 * 60_000L) {
            binding.chipGroupWindow.check(R.id.chipAll)   // the listener loads the data
        } else {
            loadRecordedForChart()
        }
    }

    /** Reads the recorded records this window needs (once; cached afterwards), then redraws. */
    private fun loadRecordedForChart() {
        val window = chartWindow
        val cached = RecordedChartLoader.cached(sensorId, window.recordedLimit)
        if (cached != null) {
            recordedPoints = cached
            renderChart(tracker.reading(sensorId).risk != Risk.OFFLINE)
            return
        }
        recordedPoints = null
        renderChart(tracker.reading(sensorId).risk != Risk.OFFLINE)
        RecordedChartLoader.load(sensorId, window.recordedLimit) { points ->
            if (isDestroyed || window != chartWindow) return@load
            recordedPoints = points
            renderChart(tracker.reading(sensorId).risk != Risk.OFFLINE)
        }
    }

    /**
     * Merges recorded + live points for the selected window and draws them, plus a dotted
     * 30-minute forecast (the same Linear water level forecast as the Flood Forecast card)
     * when it is ready, the sensor is online and the newest point is live.
     */
    private fun renderChart(online: Boolean) {
        val live = LiveHistoryStore.points(sensorId)
        val recorded = recordedPoints
        if (recorded == null && live.isEmpty()) {
            binding.floodChart.showLoading()
            return
        }
        val now = System.currentTimeMillis()
        val from = chartWindow.durationMs?.let { now - it }
        val points = ChartHistory.build(recorded.orEmpty(), live, from, now)

        var forecastPoint: ChartPoint? = null
        val lastPoint = points.lastOrNull()
        val model = RecordedTraining.levelForecaster()
        if (online && model != null && lastPoint?.source == ChartSource.LIVE) {
            tracker.forecast(sensorId, model, RecordedTraining.levelEarlyForecaster())?.let { f ->
                forecastPoint = ChartPoint(lastPoint.timeMs + f.horizonMin * 60_000L, f.linear, ChartSource.LIVE)
            }
        }
        binding.floodChart.setData(points, forecastPoint, from, now)

        // Say where the sample data is when this window doesn't include any of it
        val lastRecorded = recorded?.lastOrNull()
        binding.floodChart.setHint(
            if (lastRecorded != null && points.none { it.source == ChartSource.RECORDED }) {
                val ends = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(lastRecorded.timeMs))
                getString(R.string.chart_recorded_ends, ends)
            } else null
        )
    }

    /** Rain Forecast card: rain now and in 30 minutes, from this street's rain sensor. */
    private fun renderRainForecast(online: Boolean) {
        if (!::rainSection.isInitialized) return
        if (online) rainSection.show(tracker, sensorId) else rainSection.showOffline()
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

    private fun formatCm(level: Double?): String =
        if (level == null) "--" else "${formatNumber(level)} cm"

    private fun formatNumber(n: Double): String =
        if (n % 1.0 == 0.0) n.toInt().toString() else String.format(Locale.US, "%.1f", n)

    // ───────────────────────── Lifecycle ─────────────────────────

    override fun onResume() {
        super.onResume()
        ticker.removeCallbacks(tickRunnable)
        ticker.post(tickRunnable)
    }

    override fun onPause() {
        ticker.removeCallbacks(tickRunnable)
        tracker.saveHistory()   // keep the latest readings for next time the app opens
        super.onPause()
    }

    override fun onDestroy() {
        ticker.removeCallbacks(tickRunnable)
        sensorListener?.let { db.child("Sensordata$sensorId").removeEventListener(it) }
        lastUpdatedListener?.let { db.child("Last_Updated").removeEventListener(it) }
        super.onDestroy()
    }
}