package com.example.bahgo20.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.example.bahgo20.R
import com.example.bahgo20.SensorTracker
import com.example.bahgo20.chart.ChartPoint
import com.example.bahgo20.chart.ChartSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Flood Severity Chart: the water level of one sensor over time.
 *   - recorded part (simulated sample data) as a DASHED line, live part as a SOLID line
 *   - Medium (30 cm) and Critical (60 cm) lines with labels
 *   - last point coloured by its band, with its value
 *   - optional dotted 30-minute forecast at the end
 *   - drag a finger to see time, level and band
 * Data comes in through setData(); loading and merging live in chart/ChartHistory.
 */
class FloodChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dp = resources.displayMetrics.density
    private val sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)

    private var points: List<ChartPoint> = emptyList()
    private var forecast: ChartPoint? = null
    private var windowFrom: Long? = null
    private var windowTo: Long? = null
    private var message: String? = context.getString(R.string.settings_loading)
    private var hint: String? = null
    private var touchX: Float? = null

    private val lineColor = Color.parseColor("#3B82F6")
    private val colorSafe = ContextCompat.getColor(context, R.color.status_safe)
    private val colorMedium = ContextCompat.getColor(context, R.color.status_warning)
    private val colorCritical = ContextCompat.getColor(context, R.color.status_dangerous)
    private val textColor = ContextCompat.getColor(context, R.color.text_on_card_secondary)
    private val strongText = ContextCompat.getColor(context, R.color.text_on_card)

    private val livePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        strokeWidth = 3f * dp
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        setShadowLayer(4f * dp, 0f, 3f * dp, Color.parseColor("#403B82F6"))
    }
    private val recordedPaint = Paint(livePaint).apply {
        strokeWidth = 2.2f * dp
        pathEffect = DashPathEffect(floatArrayOf(7f * dp, 5f * dp), 0f)
        clearShadowLayer()
    }
    private val forecastPaint = Paint(livePaint).apply {
        strokeWidth = 2.5f * dp
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(0.1f, 6f * dp), 0f)   // dots
        clearShadowLayer()
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.card_stroke)
        strokeWidth = 1f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.5f * dp
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6f * dp, 4f * dp), 0f)
    }
    private val axisText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = 10f * sp
    }
    private val bandText = Paint(axisText).apply { isFakeBoldText = true }
    private val valueText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * sp
        isFakeBoldText = true
    }
    private val messageText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = 14f * sp
        textAlign = Paint.Align.CENTER
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E6263238") }
    private val tipText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * sp
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        strokeWidth = 1f * dp
    }

    // Plot area, worked out in onDraw
    private val plot = RectF()
    private var yMax = 90.0
    private var tMin = 0L
    private var tMax = 1L

    /** Shows "Loading…" instead of the chart. */
    /** A short note shown at the top of the chart (e.g. where the recorded sample data is). */
    fun setHint(text: String?) {
        if (hint != text) {
            hint = text
            invalidate()
        }
    }

    fun showLoading() {
        message = context.getString(R.string.settings_loading)
        invalidate()
    }

    /**
     * The points to draw (oldest first, already merged and thinned) and an optional forecast point.
     * Empty [points] shows "No data yet". [fromMs]/[toMs] set the time axis (window); null = fit the data.
     */
    fun setData(points: List<ChartPoint>, forecastPoint: ChartPoint?, fromMs: Long? = null, toMs: Long? = null) {
        this.points = points
        this.forecast = if (points.isEmpty()) null else forecastPoint
        this.windowFrom = fromMs
        this.windowTo = toMs
        message = if (points.isEmpty()) context.getString(R.string.chart_no_data_yet) else null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft + 4f * dp
        val right = width - paddingRight - 34f * dp           // room for the cm labels
        val top = paddingTop + 22f * dp                       // room for the legend
        val bottom = height - paddingBottom - 18f * dp        // room for the time labels
        plot.set(left, top, right, bottom)

        message?.let {
            canvas.drawText(it, width / 2f, height / 2f, messageText)
            return
        }

        // Axes ranges
        val maxLevel = (points.map { it.levelCm } + listOfNotNull(forecast?.levelCm)).maxOrNull() ?: 0.0
        yMax = max(90.0, ceil(maxLevel * 1.1 / 10) * 10)
        val first = points.first().timeMs
        val last = forecast?.timeMs ?: points.last().timeMs
        tMin = minOf(windowFrom ?: first, first)
        tMax = max(windowTo ?: last, last)
        if (tMax <= tMin) tMax = tMin + 60_000L

        drawGrid(canvas)
        drawBands(canvas)
        drawTimeLabels(canvas)
        drawLines(canvas)
        drawLastPoint(canvas)
        drawLegend(canvas)
        hint?.let { drawHint(canvas, it) }
        touchX?.let { drawTooltip(canvas, it) }
    }

    private fun x(t: Long) = plot.left + (t - tMin).toFloat() / (tMax - tMin) * plot.width()
    private fun y(level: Double) = plot.bottom - (level / yMax).toFloat() * plot.height()

    private fun drawGrid(canvas: Canvas) {
        var level = 0.0
        while (level <= yMax + 0.1) {
            val yy = y(level)
            canvas.drawLine(plot.left, yy, plot.right, yy, gridPaint)
            canvas.drawText("${level.toInt()}cm", plot.right + 4f * dp, yy + 4f * dp, axisText)
            level += 30.0
        }
    }

    private fun drawBands(canvas: Canvas) {
        for ((cm, color, label) in listOf(
            Triple(SensorTracker.MEDIUM_CM, colorMedium, context.getString(R.string.chart_medium)),
            Triple(SensorTracker.CRITICAL_CM, colorCritical, context.getString(R.string.risk_critical))
        )) {
            val yy = y(cm)
            bandPaint.color = color
            canvas.drawLine(plot.left, yy, plot.right, yy, bandPaint)
            bandText.color = color
            canvas.drawText(label, plot.left + 4f * dp, yy - 4f * dp, bandText)
        }
    }

    private fun drawTimeLabels(canvas: Canvas) {
        val span = tMax - tMin
        val fmt = SimpleDateFormat(if (span <= 26 * 3_600_000L) "h a" else "MMM d", Locale.getDefault())
        val ticks = 4
        for (k in 0..ticks) {
            val t = tMin + span * k / ticks
            val label = fmt.format(Date(t))
            val w = axisText.measureText(label)
            val xx = (x(t) - w / 2).coerceIn(plot.left, plot.right - w)
            canvas.drawText(label, xx, plot.bottom + 14f * dp, axisText)
        }
    }

    private fun drawLines(canvas: Canvas) {
        // One path per run of joined points from the same source (recorded = dashed, live = solid).
        // A break (gap > 20 min) starts a new run; a source change continues from the previous point.
        var path = Path()
        var source = ChartSource.LIVE
        var count = 0
        var runStart: ChartPoint? = null
        fun finish() {
            val paint = if (source == ChartSource.LIVE) livePaint else recordedPaint
            if (count >= 2) {
                canvas.drawPath(path, paint)
            } else if (count == 1) {
                runStart?.let {                                  // lone point: small dot
                    dotPaint.color = lineColor
                    canvas.drawCircle(x(it.timeMs), y(it.levelCm), 2f * dp, dotPaint)
                }
            }
            path = Path()
            count = 0
        }
        var prev: ChartPoint? = null
        for (p in points) {
            val join = prev != null && !p.breakBefore
            if (!join || p.source != source) {
                finish()
                source = p.source
                val from = if (join) prev!! else p
                path.moveTo(x(from.timeMs), y(from.levelCm))
                runStart = from
                count = 1
                if (join) {
                    path.lineTo(x(p.timeMs), y(p.levelCm))
                    count++
                }
            } else {
                path.lineTo(x(p.timeMs), y(p.levelCm))
                count++
            }
            prev = p
        }
        finish()

        // Dotted 30-minute forecast from the last point
        val f = forecast ?: return
        val lastPoint = points.last()
        canvas.drawLine(x(lastPoint.timeMs), y(lastPoint.levelCm), x(f.timeMs), y(f.levelCm), forecastPaint)
        dotPaint.color = bandColor(f.levelCm)
        canvas.drawCircle(x(f.timeMs), y(f.levelCm), 3f * dp, dotPaint)
    }

    private fun drawLastPoint(canvas: Canvas) {
        val p = points.last()
        val color = bandColor(p.levelCm)
        val cx = x(p.timeMs)
        val cy = y(p.levelCm)
        dotPaint.color = Color.WHITE
        canvas.drawCircle(cx, cy, 6f * dp, dotPaint)
        dotPaint.color = color
        canvas.drawCircle(cx, cy, 4.5f * dp, dotPaint)

        val label = "${fmt1(p.levelCm)} cm"
        valueText.color = color
        val w = valueText.measureText(label)
        val lx = (cx - w - 8f * dp).coerceAtLeast(plot.left)
        val ly = (cy - 8f * dp).coerceAtLeast(plot.top + 12f * dp)
        canvas.drawText(label, lx, ly, valueText)
    }

    private fun drawLegend(canvas: Canvas) {
        val yy = paddingTop + 10f * dp
        var xx = plot.left
        fun item(paint: Paint, label: String) {
            canvas.drawLine(xx, yy - 3f * dp, xx + 18f * dp, yy - 3f * dp, paint)
            xx += 22f * dp
            canvas.drawText(label, xx, yy, axisText)
            xx += axisText.measureText(label) + 12f * dp
        }
        if (points.any { it.source == ChartSource.LIVE }) item(livePaint, context.getString(R.string.chart_live))
        if (points.any { it.source == ChartSource.RECORDED }) item(recordedPaint, context.getString(R.string.chart_recorded))
        if (forecast != null) item(forecastPaint, context.getString(R.string.chart_forecast))
    }

    private fun drawHint(canvas: Canvas, text: String) {
        // Wrap to the plot width, centred near the top of the plot
        val words = text.split(' ')
        val lines = mutableListOf<String>()
        var line = ""
        for (w in words) {
            val next = if (line.isEmpty()) w else "$line $w"
            if (axisText.measureText(next) > plot.width() - 16f * dp && line.isNotEmpty()) {
                lines += line
                line = w
            } else {
                line = next
            }
        }
        if (line.isNotEmpty()) lines += line
        val lineH = axisText.textSize * 1.35f
        var yy = plot.top + plot.height() * 0.25f
        for (l in lines) {
            canvas.drawText(l, plot.centerX() - axisText.measureText(l) / 2, yy, axisText)
            yy += lineH
        }
    }

    private fun drawTooltip(canvas: Canvas, tx: Float) {
        val candidates = points + listOfNotNull(forecast)
        val p = candidates.minByOrNull { abs(x(it.timeMs) - tx) } ?: return
        val px = x(p.timeMs)
        canvas.drawLine(px, plot.top, px, plot.bottom, cursorPaint)
        dotPaint.color = bandColor(p.levelCm)
        canvas.drawCircle(px, y(p.levelCm), 4f * dp, dotPaint)

        val isForecast = p === forecast
        val kind = when {
            isForecast -> context.getString(R.string.chart_kind_forecast)
            p.source == ChartSource.LIVE -> context.getString(R.string.chart_kind_live)
            else -> context.getString(R.string.chart_kind_recorded)
        }
        val lines = listOf(
            SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(p.timeMs)),
            "${fmt1(p.levelCm)} cm · ${bandName(p.levelCm)} ($kind)"
        )
        val pad = 6f * dp
        val lineH = tipText.textSize * 1.3f
        val w = lines.maxOf { tipText.measureText(it) } + pad * 2
        val h = lineH * lines.size + pad * 2 - (lineH - tipText.textSize)
        val bx = (px - w / 2).coerceIn(plot.left, plot.right - w)
        val by = plot.top
        canvas.drawRoundRect(RectF(bx, by, bx + w, by + h), 6f * dp, 6f * dp, tipBg)
        lines.forEachIndexed { i, s -> canvas.drawText(s, bx + pad, by + pad + tipText.textSize + i * lineH, tipText) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (message != null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)   // keep the page from scrolling while dragging
                touchX = event.x
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                touchX = null
            }
        }
        invalidate()
        return true
    }

    private fun bandColor(level: Double) = when {
        level > SensorTracker.CRITICAL_CM -> colorCritical
        level > SensorTracker.MEDIUM_CM -> colorMedium
        else -> colorSafe
    }

    private fun bandName(level: Double) = context.getString(
        when {
            level > SensorTracker.CRITICAL_CM -> R.string.risk_critical
            level > SensorTracker.MEDIUM_CM -> R.string.chart_medium
            else -> R.string.risk_safe
        }
    )

    private fun fmt1(v: Double) = String.format(Locale.US, "%.1f", v)
}
