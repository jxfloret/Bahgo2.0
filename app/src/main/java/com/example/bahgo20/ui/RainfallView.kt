package com.example.bahgo20.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.example.bahgo20.R
import kotlin.random.Random

/**
 * Background rain effect that looks like water: drops with a round head and a fading tail,
 * falling at a slight angle, and small ripples where they land.
 *
 * It only rains when a live sensor reports rain (see LiveRain); dry or offline = nothing is drawn
 * and the animation stops. Heavier rain = more, bigger and faster drops.
 * By default it follows the heaviest rain at any sensor; set [sensorId] to follow one street.
 */
class RainfallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), LiveRain.Listener {

    /** null = heaviest rain at any sensor; 1..4 = that street's sensor (Conditions). */
    var sensorId: Int? = null
        set(value) {
            field = value
            onRainChanged()
        }

    private class Drop(var x: Float, var y: Float, var speed: Float, var length: Float, var radius: Float, var landY: Float)
    private class Ripple(val x: Float, val y: Float, var age: Float)

    private val dp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, resources.displayMetrics)
    private val drops = mutableListOf<Drop>()
    private val ripples = mutableListOf<Ripple>()
    private var level = 0                 // 0 none, 1 light, 2 moderate, 3 heavy
    private var lastFrameMs = 0L
    private var running = false

    private val tailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val oval = RectF()

    /**
     * How strong the rain is drawn: 1 = full. Dark mode uses a lower value (res/values-night)
     * because light drops on a dark screen make the text behind them hard to read.
     */
    private val strength = resources.getFraction(R.fraction.rain_opacity, 1, 1)
    private fun a(v: Int) = (v * strength).toInt().coerceIn(0, 255)

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    // ───────────────────────── Rain level ─────────────────────────

    override fun onRainChanged() {
        val newLevel = sensorId?.let { LiveRain.level(it) } ?: LiveRain.overallLevel()
        if (newLevel == level) return
        level = newLevel
        if (level == 0) {
            stop()
            drops.clear()
            ripples.clear()
            invalidate()
        } else {
            start()
        }
    }

    private fun targetCount() = when (level) {
        1 -> 28
        2 -> 70
        3 -> 140
        else -> 0
    }

    private fun speedFactor() = when (level) {
        1 -> 0.8f
        2 -> 1.0f
        else -> 1.3f
    }

    // Wind: drops drift a little sideways as they fall (more in heavy rain)
    private fun slant() = if (level >= 3) 0.22f else 0.12f

    private fun newDrop(startAnywhere: Boolean): Drop {
        val h = height.toFloat().coerceAtLeast(1f)
        val sizeBoost = 1f + 0.15f * (level - 1)
        val length = (10f + Random.nextFloat() * 16f) * dp * sizeBoost
        return Drop(
            x = Random.nextFloat() * (width + h * slant()) - h * slant(),
            y = if (startAnywhere) Random.nextFloat() * h else -length - Random.nextFloat() * h * 0.3f,
            speed = (550f + Random.nextFloat() * 450f) * dp * speedFactor(),   // px per second
            length = length,
            radius = (1.4f + Random.nextFloat() * 1.3f) * dp * sizeBoost,
            landY = h * (0.45f + Random.nextFloat() * 0.55f)                     // where it splashes
        )
    }

    // ───────────────────────── Animation ─────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (level == 0 || width == 0 || height == 0) return

        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameMs == 0L) 0f else ((now - lastFrameMs) / 1000f).coerceAtMost(0.05f)
        lastFrameMs = now

        // Keep about the right number of drops (fills in smoothly when rain starts)
        val want = targetCount()
        while (drops.size < want) drops += newDrop(startAnywhere = drops.isEmpty())
        val color = ContextCompat.getColor(context, R.color.rain_drop)
        val rippleColor = ContextCompat.getColor(context, R.color.rain_ripple)

        val iterator = drops.iterator()
        while (iterator.hasNext()) {
            val d = iterator.next()
            val dy = d.speed * dt
            d.y += dy
            d.x += dy * slant()
            if (d.y >= d.landY) {
                ripples += Ripple(d.x, d.landY, 0f)
                if (drops.size > want) {
                    iterator.remove()
                } else {
                    val n = newDrop(startAnywhere = false)
                    d.x = n.x; d.y = n.y; d.speed = n.speed; d.length = n.length; d.radius = n.radius; d.landY = n.landY
                }
                continue
            }
            drawDrop(canvas, d, color)
        }

        // Ripples: expanding, fading rings where drops landed
        val rIt = ripples.iterator()
        while (rIt.hasNext()) {
            val r = rIt.next()
            r.age += dt
            val life = 0.45f
            if (r.age >= life) {
                rIt.remove()
                continue
            }
            val t = r.age / life
            val w = (4f + 18f * t) * dp
            oval.set(r.x - w, r.y - w * 0.32f, r.x + w, r.y + w * 0.32f)
            ripplePaint.strokeWidth = 1.4f * dp
            ripplePaint.color = ColorUtils.setAlphaComponent(rippleColor, a((150 * (1 - t)).toInt()))
            canvas.drawOval(oval, ripplePaint)
        }

        if (running) postInvalidateOnAnimation()
    }

    /** A water drop: fading tail, round head, small shine. */
    private fun drawDrop(canvas: Canvas, d: Drop, color: Int) {
        val tailDx = d.length * slant()
        tailPaint.strokeWidth = d.radius * 1.1f
        // Tail in 3 parts, fainter toward the top, so it reads as moving water rather than a line
        for (k in 0 until 3) {
            val f0 = k / 3f
            val f1 = (k + 1) / 3f
            tailPaint.color = ColorUtils.setAlphaComponent(color, a((25 + 45 * f1).toInt()))
            canvas.drawLine(
                d.x - tailDx * (1 - f0), d.y - d.length * (1 - f0),
                d.x - tailDx * (1 - f1), d.y - d.length * (1 - f1),
                tailPaint
            )
        }
        headPaint.color = ColorUtils.setAlphaComponent(color, a(170))
        canvas.drawCircle(d.x, d.y, d.radius, headPaint)
        shinePaint.alpha = a(150)
        canvas.drawCircle(d.x - d.radius * 0.35f, d.y - d.radius * 0.35f, d.radius * 0.35f, shinePaint)
    }

    private fun start() {
        if (running || !isAttachedToWindow || windowVisibility != VISIBLE) return
        running = true
        lastFrameMs = 0L
        postInvalidateOnAnimation()
    }

    private fun stop() {
        running = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        LiveRain.addListener(this)
        level = -1           // force a fresh read
        onRainChanged()
    }

    override fun onDetachedFromWindow() {
        LiveRain.removeListener(this)
        stop()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && level > 0) start() else stop()
    }
}
