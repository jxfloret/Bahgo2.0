package com.example.bahgo20.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.bahgo20.R

/**
 * A day's low-to-high temperature as a colored bar on the week's range (like a weather app).
 * Optionally a dot for the temperature right now (shown on Today).
 */
class TempRangeBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var weekMin = 0.0
    private var weekMax = 1.0
    private var dayMin = 0.0
    private var dayMax = 1.0
    private var now: Double? = null

    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.card_stroke)
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.card_glass)
    }
    private val dotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = ContextCompat.getColor(context, R.color.temp_bar_warm)
    }
    private val rect = RectF()

    fun setRange(weekMin: Double, weekMax: Double, dayMin: Double, dayMax: Double, now: Double? = null) {
        this.weekMin = weekMin
        this.weekMax = if (weekMax > weekMin) weekMax else weekMin + 1
        this.dayMin = dayMin
        this.dayMax = dayMax
        this.now = now
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (12 * density).toInt()
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val barH = 4 * density
        val cy = height / 2f
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val r = barH / 2

        rect.set(left, cy - r, right, cy + r)
        canvas.drawRoundRect(rect, r, r, trackPaint)

        fun x(t: Double) = left + ((t - weekMin) / (weekMax - weekMin)).toFloat().coerceIn(0f, 1f) * (right - left)
        val x1 = x(dayMin)
        val x2 = maxOf(x(dayMax), x1 + barH)
        barPaint.shader = LinearGradient(
            x1, 0f, x2, 0f,
            ContextCompat.getColor(context, R.color.temp_bar_cool),
            ContextCompat.getColor(context, R.color.temp_bar_warm),
            Shader.TileMode.CLAMP
        )
        rect.set(x1, cy - r, x2, cy + r)
        canvas.drawRoundRect(rect, r, r, barPaint)

        now?.let {
            val cx = x(it)
            canvas.drawCircle(cx, cy, 3.5f * density, dotPaint)
            canvas.drawCircle(cx, cy, 3.5f * density, dotStroke)
        }
    }
}
