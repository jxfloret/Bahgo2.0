package com.example.bahgo20

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.bahgo20.databinding.ActivityIntroBinding
import com.example.bahgo20.utils.OnboardingManager

/**
 * Get Started: 2 slides shown once, before the log in / sign up screen.
 *   1) what BahGo does (drawn example of the street sensors and their levels)
 *   2) how to use it (4 illustrated steps)
 * Swipe or tap Next; Skip or Get Started goes on to the welcome screen.
 */
class IntroActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIntroBinding
    private val slides = listOf(R.layout.item_intro_what, R.layout.item_intro_how)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityIntroBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.introRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        binding.introPager.adapter = SlideAdapter()
        binding.introPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updateControls(position)
        })
        updateControls(0)

        binding.btnIntroNext.setOnClickListener {
            val page = binding.introPager.currentItem
            if (page < slides.lastIndex) binding.introPager.currentItem = page + 1 else finishIntro()
        }
        binding.tvSkip.setOnClickListener { finishIntro() }
    }

    private fun updateControls(position: Int) {
        val last = position == slides.lastIndex
        binding.btnIntroNext.text = getString(if (last) R.string.get_started else R.string.intro_next)
        binding.tvSkip.visibility = if (last) View.INVISIBLE else View.VISIBLE
        listOf(binding.dot1, binding.dot2).forEachIndexed { i, dot ->
            dot.layoutParams = dot.layoutParams.apply { width = dp(if (i == position) 24 else 8) }
            dot.alpha = if (i == position) 1f else 0.5f
        }
    }

    private fun finishIntro() {
        OnboardingManager(this).setIntroSeen(true)
        // Rising flood animation first; it opens the welcome screen when done
        FloodRiseActivity.open(this)
        finish()
    }

    // ───────────────────────── Slides ─────────────────────────

    private inner class SlideAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = slides.size
        override fun getItemViewType(position: Int) = slides[position]

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(viewType, parent, false)
            when (viewType) {
                R.layout.item_intro_what -> fillSensorExample(view)
                R.layout.item_intro_how -> fillHowSteps(view)
            }
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
    }

    /** Slide 1 drawing: three example streets at Safe, Warning and Critical. */
    private fun fillSensorExample(slide: View) {
        fun row(id: Int, name: String, levelCm: Int, color: String, label: String) {
            val r = slide.findViewById<View>(id)
            val c = ColorStateList.valueOf(color.toColorInt())
            r.findViewById<View>(R.id.vRowDot).backgroundTintList = c
            r.findViewById<TextView>(R.id.tvRowName).text = name
            r.findViewById<View>(R.id.vRowFill).apply {
                backgroundTintList = c
                (layoutParams as LinearLayout.LayoutParams).weight =
                    (levelCm / SensorTracker.SENSOR_HEIGHT_CM).toFloat().coerceIn(0.05f, 1f)
            }
            r.findViewById<TextView>(R.id.tvRowLevel).apply {
                text = "$levelCm cm\n$label"
                setTextColor(c)
            }
        }
        row(R.id.rowSafe, "Sealion St", 12, "#4CAF50", getString(R.string.risk_safe))
        row(R.id.rowWarning, "Samaritan St", 42, "#FF9100", getString(R.string.risk_warning))
        row(R.id.rowCritical, "Swingfire St", 66, "#D32F2F", getString(R.string.risk_critical))
    }

    /** Slide 2: four steps, each with a colored icon. */
    private fun fillHowSteps(slide: View) {
        val list = slide.findViewById<LinearLayout>(R.id.llHowSteps)
        val steps = listOf(
            Triple(R.drawable.ic_location_pin, "#1E88E5", getString(R.string.intro_step_map_title) to
                    getString(R.string.intro_step_map_text)),
            Triple(R.drawable.ic_trending_up, "#FF9100", getString(R.string.intro_step_street_title) to
                    getString(R.string.intro_step_street_text)),
            Triple(R.drawable.ic_search, "#4CAF50", getString(R.string.intro_step_search_title) to
                    getString(R.string.intro_step_search_text)),
            Triple(R.drawable.ic_bell, "#D32F2F", getString(R.string.intro_step_alerts_title) to
                    getString(R.string.intro_step_alerts_text))
        )
        steps.forEach { (icon, color, texts) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, dp(12))
            }
            row.addView(ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                setBackgroundResource(R.drawable.bg_intro_icon_circle)
                backgroundTintList = ColorStateList.valueOf(color.toColorInt())
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.white))
                setPadding(dp(11), dp(11), dp(11), dp(11))
            })
            val text = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(14) }
            }
            text.addView(TextView(this).apply {
                this.text = texts.first
                setTextColor(ContextCompat.getColor(context, R.color.text_on_card))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(null, Typeface.BOLD)
            })
            text.addView(TextView(this).apply {
                this.text = texts.second
                setTextColor(ContextCompat.getColor(context, R.color.text_on_card_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
            row.addView(text)
            list.addView(row)
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
