package com.example.bahgo20.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.example.bahgo20.R
import com.example.bahgo20.weather.HourForecast
import com.example.bahgo20.weather.WeatherIcon
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * One day's hourly chart for the 7-day forecast details, like a phone weather app:
 *   - TEMPERATURE: weather icons along the top, the temperature line (actual or "feels like")
 *     with H and L marks, °C on the right.
 *   - RAIN: chance of rain per hour, 0-100 % on the right.
 * Hours already past (today) are drawn dashed / hatched; a dot marks now.
 * Touch and drag to read the value at an hour.
 */
class DayChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode { TEMPERATURE, RAIN }

    private var mode = Mode.TEMPERATURE
    private var hours: List<HourForecast> = emptyList()
    private var values: List<Double> = emptyList()
    private var nowIndex: Int = -1          // hour index of "now", -1 = not today
    private var touchIndex: Int = -1

    private val dp = resources.displayMetrics.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val textColor = ContextCompat.getColor(context, R.color.text_on_card)
    private val mutedColor = ContextCompat.getColor(context, R.color.text_on_card_secondary)
    private val gridColor = ContextCompat.getColor(context, R.color.card_stroke)
    private val warm = ContextCompat.getColor(context, R.color.temp_bar_warm)
    private val cool = ContextCompat.getColor(context, R.color.temp_bar_cool)
    private val rainColor = ContextCompat.getColor(context, R.color.rain_chance)

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gridColor; strokeWidth = 1f * dp }
    private val dashedGrid = Paint(gridPaint).apply { pathEffect = DashPathEffect(floatArrayOf(3 * dp, 4 * dp), 0f) }
    private val axisText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = mutedColor; textSize = sp(12f); isFakeBoldText = true }
    private val markText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = mutedColor; textSize = sp(13f); isFakeBoldText = true; textAlign = Paint.Align.CENTER }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3.5f * dp; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val pastLinePaint = Paint(linePaint).apply { pathEffect = DashPathEffect(floatArrayOf(9 * dp, 7 * dp), 0f) }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pastShade = Paint().apply { style = Paint.Style.FILL }
    private val hatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f * dp }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * dp; color = 0xFF202124.toInt() }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = mutedColor; strokeWidth = 1.5f * dp }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.card_solid) }
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = textColor; textSize = sp(13f); isFakeBoldText = true; textAlign = Paint.Align.CENTER }

    private val plot = RectF()
    private var yMin = 0.0
    private var yMax = 100.0
    private var yStep = 20.0

    /** Temperature chart for [day] hours; [feelsLike] switches to the apparent temperature. */
    fun showTemperature(day: List<HourForecast>, nowHour: Int, feelsLike: Boolean) {
        mode = Mode.TEMPERATURE
        hours = day
        values = day.map { if (feelsLike && !it.feelsC.isNaN()) it.feelsC else it.tempC }
        nowIndex = nowHour
        val real = values.filter { !it.isNaN() }
        if (real.isNotEmpty()) {
            yStep = 3.0
            yMin = floor((real.min() - 4) / yStep) * yStep
            yMax = ceil((real.max() + 4) / yStep) * yStep
        }
        touchIndex = -1
        requestLayout(); invalidate()
    }

    /** Chance-of-rain chart for [day] hours. */
    fun showRain(day: List<HourForecast>, nowHour: Int) {
        mode = Mode.RAIN
        hours = day
        values = day.map { it.rainChance.toDouble() }
        nowIndex = nowHour
        yMin = 0.0; yMax = 100.0; yStep = 20.0
        touchIndex = -1
        requestLayout(); invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = ((if (mode == Mode.TEMPERATURE) 270 else 220) * dp).toInt()
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        if (values.size < 2) return
        val top = if (mode == Mode.TEMPERATURE) 44 * dp else 12 * dp
        plot.set(4 * dp, top, width - 46 * dp, height - 30 * dp)

        drawGrid(canvas)
        if (mode == Mode.TEMPERATURE) drawIcons(canvas)
        drawPast(canvas)
        drawSeries(canvas)
        drawAxisLabels(canvas)
        if (mode == Mode.TEMPERATURE) drawHighLow(canvas)
        drawNowDot(canvas)
        drawTouch(canvas)
    }

    private fun x(i: Int) = plot.left + plot.width() * i / (values.size - 1).coerceAtLeast(1)
    private fun y(v: Double) = (plot.bottom - (v - yMin) / (yMax - yMin) * plot.height()).toFloat()

    private fun drawGrid(canvas: Canvas) {
        var v = yMin
        while (v <= yMax + 0.01) {
            canvas.drawLine(plot.left, y(v), plot.right, y(v), gridPaint)
            v += yStep
        }
        // Dotted lines at 6 AM, 12 PM and 6 PM; solid edges
        for (h in listOf(6, 12, 18)) if (h < values.size) canvas.drawLine(x(h), plot.top, x(h), plot.bottom + 22 * dp, dashedGrid)
        canvas.drawLine(plot.right, if (mode == Mode.TEMPERATURE) 0f else plot.top, plot.right, height.toFloat(), gridPaint)
        if (mode == Mode.TEMPERATURE) canvas.drawLine(plot.left, plot.top - 4 * dp, plot.right, plot.top - 4 * dp, gridPaint)
    }

    /** A weather icon every 2 hours along the top. */
    private fun drawIcons(canvas: Canvas) {
        val size = (20 * dp).toInt()
        for (i in hours.indices step 2) {
            val hr = hours[i]
            val d = ContextCompat.getDrawable(context, iconRes(WeatherIcon.of(hr.weatherCode, hr.rainChance))) ?: continue
            val cx = (x(i) + x((i + 1).coerceAtMost(values.size - 1))) / 2
            val l = (cx - size / 2).toInt()
            d.setBounds(l, (10 * dp).toInt(), l + size, (10 * dp).toInt() + size)
            d.alpha = if (nowIndex >= 0 && i + 1 < nowIndex) 140 else 255
            d.draw(canvas)
        }
    }

    /** Hours already past: darker for temperature, hatched for rain. */
    private fun drawPast(canvas: Canvas) {
        if (nowIndex <= 0) return
        val right = x(nowIndex.coerceAtMost(values.size - 1))
        if (mode == Mode.RAIN) {
            canvas.save()
            canvas.clipRect(plot.left, plot.top, right, plot.bottom)
            hatchPaint.color = ColorUtils.setAlphaComponent(mutedColor, 60)
            var s = plot.left - plot.height()
            while (s < right) {
                canvas.drawLine(s, plot.bottom, s + plot.height(), plot.top, hatchPaint)
                s += 9 * dp
            }
            canvas.restore()
        } else {
            pastShade.color = ColorUtils.setAlphaComponent(mutedColor, 28)
            canvas.drawRect(plot.left, plot.top - 4 * dp, right, plot.bottom, pastShade)
        }
        canvas.drawLine(right, if (mode == Mode.TEMPERATURE) 0f else plot.top, right, height.toFloat(), gridPaint)
    }

    private fun drawSeries(canvas: Canvas) {
        val line = if (mode == Mode.TEMPERATURE) warm else rainColor
        linePaint.color = line
        pastLinePaint.color = ColorUtils.setAlphaComponent(line, 190)

        val start = if (mode == Mode.RAIN && nowIndex > 0) nowIndex else 0     // rain: only the hours ahead
        val path = smoothPath(start, values.size - 1)
        val fill = Path(path).apply {
            lineTo(x(values.size - 1), plot.bottom); lineTo(x(start), plot.bottom); close()
        }
        fillPaint.shader = if (mode == Mode.TEMPERATURE)
            LinearGradient(0f, plot.top, 0f, plot.bottom,
                ColorUtils.setAlphaComponent(line, 150), ColorUtils.setAlphaComponent(cool, 70), Shader.TileMode.CLAMP)
        else
            LinearGradient(0f, plot.top, 0f, plot.bottom,
                ColorUtils.setAlphaComponent(line, 120), ColorUtils.setAlphaComponent(line, 40), Shader.TileMode.CLAMP)
        canvas.drawPath(fill, fillPaint)

        if (mode == Mode.TEMPERATURE && nowIndex > 0) {
            canvas.drawPath(smoothPath(0, nowIndex.coerceAtMost(values.size - 1)), pastLinePaint)
            canvas.drawPath(smoothPath(nowIndex.coerceAtMost(values.size - 1), values.size - 1), linePaint)
        } else {
            canvas.drawPath(path, linePaint)
        }
    }

    /** A smooth line through hours [from]..[to] (missing values are skipped). */
    private fun smoothPath(from: Int, to: Int): Path {
        val p = Path()
        var started = false
        var px = 0f; var py = 0f
        for (i in from..to) {
            val v = values[i]
            if (v.isNaN()) continue
            val cx = x(i); val cy = y(v)
            if (!started) { p.moveTo(cx, cy); started = true }
            else { val mx = (px + cx) / 2; p.cubicTo(mx, py, mx, cy, cx, cy) }
            px = cx; py = cy
        }
        return p
    }

    private fun drawAxisLabels(canvas: Canvas) {
        axisText.textAlign = Paint.Align.LEFT
        var v = yMin
        while (v <= yMax + 0.01) {
            val label = if (mode == Mode.TEMPERATURE) "${v.roundToInt()}°" else "${v.roundToInt()}%"
            canvas.drawText(label, plot.right + 8 * dp, y(v) + 4 * dp, axisText)
            v += yStep
        }
        val fmt = DateTimeFormatter.ofPattern("h a", Locale.US)
        for (h in listOf(0, 6, 12, 18)) {
            if (h >= hours.size) continue
            canvas.drawText(hours[h].time.format(fmt).replace(" ", ""), x(h) + 6 * dp, height - 9 * dp, axisText)
        }
    }

    /** H above the warmest hour, L at the coolest. */
    private fun drawHighLow(canvas: Canvas) {
        val real = values.withIndex().filter { !it.value.isNaN() }
        val hi = real.maxByOrNull { it.value } ?: return
        val lo = real.minByOrNull { it.value } ?: return
        canvas.drawText("H", x(hi.index), y(hi.value) - 14 * dp, markText)
        canvas.drawText("L", x(lo.index).coerceAtMost(plot.right - 8 * dp), y(lo.value) - 14 * dp, markText)
        for (m in listOf(hi, lo)) {
            dotFill.color = warm
            canvas.drawCircle(x(m.index), y(m.value), 4.5f * dp, dotFill)
            canvas.drawCircle(x(m.index), y(m.value), 4.5f * dp, dotRing)
        }
        dotFill.color = 0xFFFFFFFF.toInt()
    }

    private fun drawNowDot(canvas: Canvas) {
        if (nowIndex < 0 || nowIndex >= values.size || values[nowIndex].isNaN()) return
        val cx = x(nowIndex); val cy = y(values[nowIndex])
        canvas.drawCircle(cx, cy, 6 * dp, dotFill)
        canvas.drawCircle(cx, cy, 6 * dp, dotRing)
    }

    private fun drawTouch(canvas: Canvas) {
        val i = touchIndex
        if (i < 0 || i >= values.size || values[i].isNaN()) return
        val cx = x(i)
        canvas.drawLine(cx, plot.top, cx, plot.bottom, cursorPaint)
        val time = hours[i].time.format(DateTimeFormatter.ofPattern("h a", Locale.US))
        val value = if (mode == Mode.TEMPERATURE) "${values[i].roundToInt()}°" else "${values[i].roundToInt()}%"
        val text = "$time · $value"
        val w = bubbleText.measureText(text) + 20 * dp
        val left = (cx - w / 2).coerceIn(plot.left, plot.right - w)
        val r = RectF(left, plot.top - 2 * dp, left + w, plot.top + 26 * dp)
        canvas.drawRoundRect(r, 12 * dp, 12 * dp, bubblePaint)
        canvas.drawText(text, r.centerX(), r.centerY() + 5 * dp, bubbleText)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (values.size < 2) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val f = ((event.x - plot.left) / plot.width()).coerceIn(0f, 1f)
                touchIndex = (f * (values.size - 1)).roundToInt()
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                touchIndex = -1
                invalidate()
            }
        }
        return true
    }

    private fun iconRes(icon: WeatherIcon) = when (icon) {
        WeatherIcon.SUN -> R.drawable.ic_weather_sun
        WeatherIcon.PARTLY_CLOUDY -> R.drawable.ic_weather_partly
        WeatherIcon.CLOUD -> R.drawable.ic_weather_cloud
        WeatherIcon.RAIN -> R.drawable.ic_weather_rain
        WeatherIcon.STORM -> R.drawable.ic_weather_storm
    }
}
