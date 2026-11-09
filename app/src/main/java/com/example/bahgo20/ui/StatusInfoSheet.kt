package com.example.bahgo20.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import com.example.bahgo20.R
import com.example.bahgo20.SensorTracker
import com.example.bahgo20.databinding.SheetStatusInfoBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.Locale

/**
 * Plain-language explanations for the four Current Level Status boxes (Home and Conditions).
 * Tap a box -> a sheet slides up: what the box means, what it shows now, and the levels.
 * The numbers come from SensorTracker, so they always match what the app uses.
 */
object StatusInfoSheet {

    enum class Box { WATER_LEVEL, RISK_LEVEL, RISE_RATE, RAIN }

    private const val GREEN = "#4CAF50"
    private const val BLUE = "#1E88E5"
    private const val ORANGE = "#FF9100"
    private const val RED = "#D32F2F"
    private const val GRAY = "#9E9E9E"

    private class Row(val color: String, val name: String, val meaning: String)

    /** [now] = what the box shows right now, e.g. "12 cm" (null or "--" = no data). */
    fun show(context: Context, box: Box, now: String?) {
        val b = SheetStatusInfoBinding.inflate(LayoutInflater.from(context))
        val medium = cm(SensorTracker.MEDIUM_CM)
        val critical = cm(SensorTracker.CRITICAL_CM)
        fun s(id: Int, vararg args: Any) = context.getString(id, *args)

        val title: String
        val what: String
        val rows: List<Row>
        val tip: String
        when (box) {
            Box.WATER_LEVEL -> {
                title = s(R.string.water_level_label)
                what = s(R.string.info_level_what)
                rows = listOf(
                    Row(GREEN, s(R.string.info_level_safe, medium), s(R.string.info_level_safe_text)),
                    Row(ORANGE, s(R.string.info_level_warning, medium, critical), s(R.string.info_level_warning_text)),
                    Row(RED, s(R.string.info_level_critical, critical), s(R.string.info_level_critical_text))
                )
                tip = s(R.string.info_level_tip, critical)
            }
            Box.RISK_LEVEL -> {
                title = s(R.string.risk_level_label)
                what = s(R.string.info_risk_what)
                rows = listOf(
                    Row(GREEN, s(R.string.risk_safe), s(R.string.info_risk_safe_text, medium)),
                    Row(ORANGE, s(R.string.risk_warning), s(R.string.info_risk_warning_text, medium, critical)),
                    Row(RED, s(R.string.risk_dangerous), s(R.string.info_risk_critical_text, critical)),
                    Row(GRAY, s(R.string.risk_offline), s(R.string.info_risk_offline_text))
                )
                tip = s(R.string.info_risk_tip)
            }
            Box.RISE_RATE -> {
                title = s(R.string.conditions_rise_rate)
                what = s(R.string.info_rise_what)
                rows = listOf(
                    Row(RED, s(R.string.info_rise_rising), s(R.string.info_rise_rising_text)),
                    Row(BLUE, s(R.string.rise_steady), s(R.string.info_rise_steady_text, cm(SensorTracker.STEADY_CM_PER_HR))),
                    Row(GREEN, s(R.string.info_rise_falling), s(R.string.rise_going_down) + "."),
                    Row(GRAY, s(R.string.rise_measuring), s(R.string.info_rise_measuring_text))
                )
                tip = s(R.string.info_rise_tip)
            }
            Box.RAIN -> {
                title = s(R.string.conditions_rain)
                what = s(R.string.info_rain_what)
                rows = listOf(
                    Row(GREEN, StatusText.rainStatus(context, 0), s(R.string.info_rain_none_text)),
                    Row(BLUE, StatusText.rainStatus(context, 1), s(R.string.info_rain_light_text)),
                    Row(ORANGE, StatusText.rainStatus(context, 2), s(R.string.info_rain_moderate_text)),
                    Row(RED, StatusText.rainStatus(context, 3), s(R.string.info_rain_heavy_text))
                )
                tip = s(R.string.info_rain_tip)
            }
        }

        b.tvInfoTitle.text = title
        b.tvInfoWhat.text = what
        b.tvInfoNow.text = if (now.isNullOrBlank() || now.trim() == "--") context.getString(R.string.info_now_no_data)
            else context.getString(R.string.info_now, now.trim())
        b.tvInfoTip.text = tip
        rows.forEach { b.llInfoRows.addView(rowView(context, it)) }

        BottomSheetDialog(context).apply {
            setContentView(b.root)
            show()
        }
    }

    private fun rowView(context: Context, row: Row): View {
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(6), 0, dp(10))
        }
        line.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply { topMargin = dp(4) }
            setBackgroundResource(R.drawable.bg_button_solid)
            backgroundTintList = ColorStateList.valueOf(row.color.toColorInt())
        })
        val text = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) }
        }
        text.addView(TextView(context).apply {
            this.text = row.name
            setTextColor(ContextCompat.getColor(context, R.color.text_on_card))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(null, Typeface.BOLD)
        })
        text.addView(TextView(context).apply {
            this.text = row.meaning
            setTextColor(ContextCompat.getColor(context, R.color.text_on_card_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(0f, 1.1f)
        })
        line.addView(text)
        return line
    }

    private fun cm(v: Double) =
        if (v % 1.0 == 0.0) "${v.toInt()} cm" else String.format(Locale.US, "%.1f cm", v)
}
