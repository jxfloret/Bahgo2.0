package com.example.bahgo20.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

class RainfallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = Color.WHITE
        alpha = 30
        strokeWidth = 2.0f
        strokeCap = Paint.Cap.ROUND
    }

    private val count = 10
    private val xRatios = FloatArray(count)
    private val yOffsetRatios = FloatArray(count)
    private val speeds = FloatArray(count)
    private val lengths = FloatArray(count)

    private var animValue = 0f
    
    // Disable animation on emulators to prevent "System UI not responding"
    private val isEmulator = Build.FINGERPRINT.contains("generic") || 
                             Build.MODEL.contains("Emulator") || 
                             Build.MODEL.contains("Android SDK built for x86")

    private val animatorRunnable = object : Runnable {
        override fun run() {
            animValue += 0.003f
            if (animValue > 1f) animValue = 0f
            invalidate()
            postDelayed(this, 60) // Low frame rate (15-20 fps)
        }
    }

    init {
        for (i in 0 until count) {
            xRatios[i] = Random.nextFloat()
            yOffsetRatios[i] = Random.nextFloat()
            speeds[i] = Random.nextFloat() * 0.2f + 0.1f
            lengths[i] = Random.nextFloat() * 10f + 10f
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
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
        if (!isEmulator) {
            removeCallbacks(animatorRunnable)
            post(animatorRunnable)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(animatorRunnable)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && !isEmulator) {
            removeCallbacks(animatorRunnable)
            post(animatorRunnable)
        } else {
            removeCallbacks(animatorRunnable)
        }
    }
}
