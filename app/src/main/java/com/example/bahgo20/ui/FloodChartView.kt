package com.example.bahgo20.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class FloodChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3B82F6")
        strokeWidth = 8f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        setShadowLayer(10f, 0f, 10f, Color.parseColor("#403B82F6"))
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        strokeWidth = 1f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        textSize = 30f
        textAlign = Paint.Align.RIGHT
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        textSize = 30f
        textAlign = Paint.Align.CENTER
    }

    private val thresholdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val points = listOf(
        PointF(0.0f, 0.7f),
        PointF(0.1f, 0.8f),
        PointF(0.2f, 0.75f),
        PointF(0.3f, 0.85f),
        PointF(0.4f, 0.9f),
        PointF(0.5f, 0.8f),
        PointF(0.6f, 0.7f),
        PointF(0.7f, 0.65f),
        PointF(0.8f, 0.5f),
        PointF(0.9f, 0.55f),
        PointF(1.0f, 0.6f)
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val paddingRight = 100f
        val paddingBottom = 60f
        val chartWidth = w - paddingRight
        val chartHeight = h - paddingBottom

        // Draw Y Axis Labels and Grid
        val yLabels = listOf("65cm", "50cm", "30cm", "20cm", "10cm", "0")
        val yPositions = listOf(0.1f, 0.3f, 0.5f, 0.65f, 0.8f, 1.0f)
        
        for (i in yLabels.indices) {
            val y = yPositions[i] * chartHeight
            canvas.drawText(yLabels[i], w - 10f, y + 10f, textPaint)
            canvas.drawLine(0f, y, chartWidth, y, gridPaint)
        }

        // Draw Threshold Line (at 30cm roughly)
        val thresholdY = 0.5f * chartHeight
        canvas.drawLine(0f, thresholdY, chartWidth, thresholdY, thresholdPaint)

        // Draw Path
        val path = Path()
        points.forEachIndexed { index, point ->
            val x = point.x * chartWidth
            val y = point.y * chartHeight
            if (index == 0) path.moveTo(x, y)
            else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)

        // Draw X Axis Labels
        canvas.drawText("12am", 0.15f * chartWidth, h - 10f, labelPaint)
        canvas.drawText("6pm", 0.45f * chartWidth, h - 10f, labelPaint)
        canvas.drawText("6am", 0.75f * chartWidth, h - 10f, labelPaint)

        // Vertical indicator line (optional, based on image)
        val indicatorX = 0.55f * chartWidth
        val indicatorPaint = Paint(gridPaint).apply { color = Color.parseColor("#3B82F6") }
        canvas.drawLine(indicatorX, 0f, indicatorX, chartHeight, indicatorPaint)
    }
}
