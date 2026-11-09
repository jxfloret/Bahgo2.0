package com.example.bahgo20.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.bahgo20.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * A short guided tour: dims the screen, cuts a spotlight around one part of it, and shows a
 * bubble that explains it, with "2 of 7", Skip and Next. Parts inside [scrollView] are scrolled
 * into view first. [onFinish] runs when the tour ends (Done or Skip).
 */
class GuidedTour(
    private val activity: Activity,
    private val scrollView: ScrollView?,
    private val steps: List<Step>,
    private val onFinish: () -> Unit
) {
    /** One stop of the tour: the views to highlight (their combined area) and what to say. */
    class Step(val targets: List<View>, val title: String, val text: String)

    private var overlay: Overlay? = null
    private var index = 0

    val isShowing: Boolean get() = overlay != null

    fun start() {
        if (steps.isEmpty() || overlay != null) return
        val root = activity.window.decorView as ViewGroup
        overlay = Overlay(activity).also {
            root.addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        show(0)
    }

    fun finish() {
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        overlay = null
        onFinish()
    }

    private fun show(i: Int) {
        index = i
        val step = steps[i]
        val o = overlay ?: return
        o.hideBubble()
        val target = step.targets.firstOrNull { it.isShown } ?: step.targets.first()
        val delay = scrollIntoView(target)
        o.postDelayed({
            if (overlay == null) return@postDelayed
            o.showStep(holeFor(step.targets), step, i, steps.size,
                onNext = { if (i + 1 < steps.size) show(i + 1) else finish() },
                onSkip = { finish() })
        }, delay)
    }

    /** Scrolls so the target sits in the middle of the page; returns how long to wait. */
    private fun scrollIntoView(target: View): Long {
        val sv = scrollView ?: return 50L
        val child = sv.getChildAt(0) ?: return 50L
        if (!isDescendant(target, child)) return 50L       // fixed header items don't scroll
        val r = android.graphics.Rect()
        target.getDrawingRect(r)
        (child as ViewGroup).offsetDescendantRectToMyCoords(target, r)
        val wanted = (r.centerY() - sv.height / 2).coerceAtLeast(0)
        if (kotlin.math.abs(sv.scrollY - wanted) < 4) return 50L
        sv.smoothScrollTo(0, wanted)
        return 450L
    }

    private fun isDescendant(v: View, parent: View): Boolean {
        var p = v.parent
        while (p != null) {
            if (p === parent) return true
            p = p.parent
        }
        return false
    }

    /** The combined on-screen area of the targets, in the overlay's coordinates, with some padding. */
    private fun holeFor(targets: List<View>): RectF {
        val o = overlay!!
        val origin = IntArray(2).also { o.getLocationInWindow(it) }
        val out = RectF()
        var first = true
        for (t in targets) {
            if (t.width == 0 && t.height == 0) continue
            val loc = IntArray(2).also { t.getLocationInWindow(it) }
            val r = RectF(
                (loc[0] - origin[0]).toFloat(), (loc[1] - origin[1]).toFloat(),
                (loc[0] - origin[0] + t.width).toFloat(), (loc[1] - origin[1] + t.height).toFloat()
            )
            if (first) out.set(r) else out.union(r)
            first = false
        }
        val pad = o.dp(8).toFloat()
        out.inset(-pad, -pad)
        return out
    }

    // ───────────────────────── The overlay view ─────────────────────────

    @SuppressLint("ViewConstructor")
    private class Overlay(context: Context) : FrameLayout(context) {
        private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#B3000000") }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            color = Color.WHITE
        }
        private var hole: RectF? = null
        private val path = Path()
        private val bubble: MaterialCardView
        private val tvTitle: TextView
        private val tvText: TextView
        private val tvCount: TextView
        private val btnSkip: MaterialButton
        private val btnNext: MaterialButton

        init {
            setWillNotDraw(false)
            elevation = dp(30).toFloat()
            bubble = MaterialCardView(context).apply {
                radius = dp(20).toFloat()
                cardElevation = dp(8).toFloat()
                setCardBackgroundColor(ContextCompat.getColor(context, R.color.card_glass))
                strokeWidth = 0
                visibility = INVISIBLE
            }
            val box = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(18), dp(20), dp(12))
            }
            tvCount = TextView(context).apply {
                setTextColor(ContextCompat.getColor(context, R.color.icon_accent))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            tvTitle = TextView(context).apply {
                setTextColor(ContextCompat.getColor(context, R.color.text_on_card))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(4), 0, 0)
            }
            tvText = TextView(context).apply {
                setTextColor(ContextCompat.getColor(context, R.color.text_on_card_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setLineSpacing(0f, 1.15f)
                setPadding(0, dp(6), 0, dp(6))
            }
            val buttons = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            btnSkip = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = context.getString(R.string.intro_skip)
                isAllCaps = false
                setTextColor(ContextCompat.getColor(context, R.color.text_on_card_secondary))
            }
            btnNext = MaterialButton(context).apply {
                isAllCaps = false
                cornerRadius = dp(20)
                setTextColor(Color.WHITE)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.button_primary)
                )
            }
            buttons.addView(btnSkip)
            buttons.addView(btnNext, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(8) })
            box.addView(tvCount)
            box.addView(tvTitle)
            box.addView(tvText)
            box.addView(buttons)
            bubble.addView(box)
            addView(bubble, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(20); rightMargin = dp(20)
            })
        }

        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

        fun hideBubble() {
            bubble.visibility = INVISIBLE
        }

        fun showStep(rect: RectF, step: Step, i: Int, count: Int, onNext: () -> Unit, onSkip: () -> Unit) {
            hole = rect
            tvCount.text = context.getString(R.string.tour_count, i + 1, count)
            tvTitle.text = step.title
            tvText.text = step.text
            btnNext.text = context.getString(if (i + 1 == count) R.string.tour_done else R.string.intro_next)
            btnSkip.visibility = if (i + 1 == count) GONE else VISIBLE
            btnNext.setOnClickListener { onNext() }
            btnSkip.setOnClickListener { onSkip() }
            invalidate()

            // Bubble below the spotlight if there's room, otherwise above it
            bubble.post {
                val gap = dp(16)
                val h = bubble.height
                val below = rect.bottom + gap
                val top = if (below + h < height - dp(24)) below else (rect.top - gap - h).coerceAtLeast(dp(32).toFloat())
                bubble.translationY = top
                bubble.visibility = VISIBLE
                bubble.alpha = 0f
                bubble.animate().alpha(1f).setDuration(180).start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val r = hole ?: run { canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim); return }
            val radius = dp(20).toFloat()
            path.reset()
            path.fillType = Path.FillType.EVEN_ODD
            path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            path.addRoundRect(r, radius, radius, Path.Direction.CW)
            canvas.drawPath(path, dim)
            canvas.drawRoundRect(r, radius, radius, ring)
        }

        // Block the screen underneath while the tour is showing
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean = true
    }
}
