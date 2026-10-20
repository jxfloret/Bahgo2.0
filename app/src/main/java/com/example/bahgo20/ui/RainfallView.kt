package com.example.bahgo20.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.example.bahgo20.R
import kotlin.random.Random

class RainfallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.rain_color)
        strokeWidth = 5.0f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val count = 100
    private val xRatios = FloatArray(count)
    private val yOffsetRatios = FloatArray(count)
    private val speeds = FloatArray(count)
    private val lengths = FloatArray(count)

    private var animValue = 0f

    private val animatorRunnable = object : Runnable {
        override fun run() {
            animValue += 0.01f
            if (animValue > 1f) animValue = 0f
            invalidate()
            postDelayed(this, 16)
        }
    }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        for (i in 0 until count) {
            xRatios[i] = Random.nextFloat()
            yOffsetRatios[i] = Random.nextFloat()
            speeds[i] = Random.nextFloat() * 0.5f + 0.5f
            lengths[i] = Random.nextFloat() * 30f + 20f
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Refresh color in case theme changed while view is active
        paint.color = ContextCompat.getColor(context, R.color.rain_color)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        for (i in 0 until count) {
            val y = ((yOffsetRatios[i] + animValue * speeds[i]) % 1f) * h
            val x = xRatios[i] * w
            canvas.drawLine(x, y, x, y + lengths[i], paint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimation()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) startAnimation() else stopAnimation()
    }

    private fun startAnimation() {
        removeCallbacks(animatorRunnable)
        post(animatorRunnable)
    }

    private fun stopAnimation() {
        removeCallbacks(animatorRunnable)
    }
}
