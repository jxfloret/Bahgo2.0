package com.example.bahgo20.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.bahgo20.R
import com.example.bahgo20.databinding.SheetChooseStreetBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView

/**
 * "Choose your street" pop-up for Home's Change button, in the app's pop-up style:
 * one glass card per street with its live status, and a check on the current one.
 */
object StreetPickerSheet {

    /** One street: [status] e.g. "12 cm · safe" or "Offline", [color] its risk color. */
    class Street(val id: Int, val name: String, val status: String, val color: Int)

    fun show(context: Context, streets: List<Street>, selectedId: Int, onPick: (Int) -> Unit) {
        val b = SheetChooseStreetBinding.inflate(LayoutInflater.from(context))
        val dialog = BottomSheetDialog(context)
        streets.forEach { s ->
            b.llStreetRows.addView(row(context, s, s.id == selectedId) {
                dialog.dismiss()
                if (s.id != selectedId) onPick(s.id)
            })
        }
        dialog.setContentView(b.root)
        // Let the gradient's rounded corners show (the default sheet is a plain rectangle)
        (b.root.parent as? ViewGroup)?.setBackgroundColor(Color.TRANSPARENT)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        b.ivStreetSheetClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun row(context: Context, s: Street, selected: Boolean, onClick: () -> Unit): View {
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()
        val accent = ContextCompat.getColor(context, R.color.button_primary)

        val card = MaterialCardView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(10) }
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.card_glass))
            radius = dp(20).toFloat()
            cardElevation = dp(2).toFloat()
            strokeWidth = dp(if (selected) 2 else 1)
            strokeColor = if (selected) accent else ContextCompat.getColor(context, R.color.card_stroke)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(16), dp(16))
        }
        // Risk-colored dot, like the Nearby cards
        line.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12))
            setBackgroundResource(R.drawable.bg_button_solid)
            backgroundTintList = ColorStateList.valueOf(s.color)
        })
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) }
        }
        texts.addView(TextView(context).apply {
            text = s.name
            setTextColor(ContextCompat.getColor(context, R.color.text_on_card))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(null, Typeface.BOLD)
        })
        texts.addView(TextView(context).apply {
            text = s.status
            setTextColor(s.color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, Typeface.BOLD)
        })
        line.addView(texts)
        line.addView(ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
            setImageResource(R.drawable.ic_check_circle)
            imageTintList = ColorStateList.valueOf(accent)
            visibility = if (selected) View.VISIBLE else View.INVISIBLE
            contentDescription = null
        })
        card.addView(line)
        return card
    }
}
