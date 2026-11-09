package com.example.bahgo20.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.example.bahgo20.R
import kotlin.math.PI
import kotlin.math.sin

/**
 * Flood water rising from the bottom of the screen: three wavy layers that roll sideways
 * while the level climbs until the screen is full. Used between the intro and the welcome screen.
 */
class FloodRiseView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dp = resources.displayMetrics.density
    private val colors = intArrayOf(
        ContextCompat.getColor(context, R.color.flood_wave_back),
        ContextCompat.getColor(context, R.color.flood_wave_mid),
        ContextCompat.getColor(context, R.color.flood_wave_front)
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val foam = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * dp }
    private val path = Path()

    /** 0 = no water, 1 = the screen is full. */
    var level = 0f
        private set
    private var phase = 0f
    private var riseAnim: ValueAnimator? = null
    private var waveAnim: ValueAnimator? = null

    /** Raises the water over [durationMs]; [onFull] runs once the screen is covered. */
    fun rise(durationMs: Long, onProgress: (Float) -> Unit = {}, onFull: () -> Unit) {
        waveAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            interpolator = null
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
        riseAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                level = it.animatedValue as Float
                onProgress(level)
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = onFull()
            })
            start()
        }
    }

    fun stop() {
        riseAnim?.removeAllListeners()
        riseAnim?.cancel()
        waveAnim?.cancel()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val h = height.toFloat()
        val w = width.toFloat()
        // Each layer: a little behind the next, its own wave size and speed
        val layers = listOf(
            Triple(0.06f, 14f, 1.0f),    // back: higher, gentle
            Triple(0.03f, 18f, -1.4f),   // middle: rolls the other way
            Triple(0.0f, 22f, 1.8f)      // front: biggest waves
        )
        layers.forEachIndexed { i, (ahead, amp, speed) ->
            // Water surface, from below the screen up to a bit above the top when full
            val surface = h + 40 * dp - (level + ahead) * (h + 80 * dp)
            val a = amp * dp * (0.6f + 0.4f * (1 - level))
            val waveLen = w * (0.9f + 0.25f * i)
            val shift = phase * speed * waveLen
            path.reset()
            path.moveTo(0f, h)
            var x = 0f
            while (x <= w + 8 * dp) {
                val y = surface + a * sin(((x + shift) / waveLen * 2 * PI).toFloat())
                path.lineTo(x, y)
                x += 8 * dp
            }
            path.lineTo(w, h)
            path.close()
            val c = colors[i]
            paint.shader = LinearGradient(0f, surface - a, 0f, h,
                c, ColorUtils.blendARGB(c, 0xFF000000.toInt(), 0.25f), Shader.TileMode.CLAMP)
            canvas.drawPath(path, paint)
            if (i == 2) {
                // Light foam line on the front wave
                foam.color = ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), 90)
                path.reset()
                x = 0f
                while (x <= w + 8 * dp) {
                    val y = surface + a * sin(((x + shift) / waveLen * 2 * PI).toFloat())
                    if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                    x += 8 * dp
                }
                canvas.drawPath(path, foam)
            }
        }
    }
}
