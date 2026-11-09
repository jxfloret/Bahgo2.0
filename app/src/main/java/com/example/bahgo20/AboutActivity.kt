package com.example.bahgo20

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.bahgo20.databinding.ActivityAboutBinding
import com.example.bahgo20.legal.Legal
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.RelativeCornerSize
import com.google.android.material.shape.ShapeAppearanceModel

/**
 * About BahGo: what the app is, how it works, the data sources and APIs it uses,
 * the legal documents, the developers, and how to contact them.
 */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    private class Step(val title: String, val text: String)
    private class Source(val name: String, val use: String, val url: String?)
    private class Member(val name: String, val role: String, val photo: Int)

    companion object {
        const val SCHOOL_ADDRESS =
            "STI Academic Center, University Parkway, Bonifacio Global City, Taguig City, Metro Manila 1634"
        const val CONTACT_EMAIL = "hannaduliesco@gmail.com"
    }

    // Built when used (getString needs the screen to exist), so they follow the app language
    private val howItWorks get() = listOf(
        Step(getString(R.string.about_how_sensors_title), getString(R.string.about_how_sensors_text)),
        Step(getString(R.string.about_how_levels_title), getString(R.string.about_how_levels_text,
            SensorTracker.MEDIUM_CM.toInt(), SensorTracker.CRITICAL_CM.toInt())),
        Step(getString(R.string.about_how_rise_title), getString(R.string.info_rise_what)),
        Step(getString(R.string.about_how_forecast_title), getString(R.string.about_how_forecast_text)),
        Step(getString(R.string.about_how_risk_title), getString(R.string.about_how_risk_text)),
        Step(getString(R.string.about_how_rain_title), getString(R.string.about_how_rain_text)),
        Step(getString(R.string.about_how_alerts_title), getString(R.string.about_how_alerts_text)),
        Step(getString(R.string.about_how_chart_title), getString(R.string.about_how_chart_text))
    )

    private val sources get() = listOf(
        Source("BahGo street sensors", getString(R.string.about_src_sensors), null),
        Source("Firebase Realtime Database (Google)", getString(R.string.about_src_rtdb), "https://firebase.google.com/products/realtime-database"),
        Source("Firebase Cloud Messaging (Google)", getString(R.string.about_src_fcm), "https://firebase.google.com/products/cloud-messaging"),
        Source("Google Analytics for Firebase", getString(R.string.about_src_analytics), "https://firebase.google.com/products/analytics"),
        Source("Linear and Lasso Regression", getString(R.string.about_src_models), null),
        Source("MapLibre Android SDK", getString(R.string.about_src_maplibre), "https://maplibre.org"),
        Source("OpenFreeMap", getString(R.string.about_src_openfreemap), "https://openfreemap.org"),
        Source("OpenStreetMap", getString(R.string.about_src_osm), "https://www.openstreetmap.org/copyright"),
        Source("Project NOAH flood hazard map", getString(R.string.about_src_noah), "https://huggingface.co/datasets/bettergovph/project-noah-hazard-maps"),
        Source("Open-Meteo", getString(R.string.about_src_openmeteo), "https://open-meteo.com"),
        Source("OpenStreetMap Nominatim", getString(R.string.about_src_nominatim), "https://nominatim.org"),
        Source("OSRM routing (FOSSGIS / Project OSRM)", getString(R.string.about_src_osrm), "https://routing.openstreetmap.de"),
        Source("SMS Gateway for Android", getString(R.string.about_src_sms), "https://sms-gate.app"),
        Source("Google Play services Location", getString(R.string.about_src_location), "https://developers.google.com/location-context/fused-location-provider")
    )

    private val team = listOf(
        Member("Duliesco, Johanna Vic", "Mobile Developer", R.drawable.dev_duliesco),
        Member("Sargado, Sophia", "Documentation", R.drawable.dev_sargado),
        Member("Levantino, Kaye", "Web Developer", R.drawable.dev_levantino),
        Member("Reyes, Christine Jessica", "Web Developer", R.drawable.dev_reyes)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom)
            insets
        }
        binding.ivBack.setOnClickListener { finish() }

        howItWorks.forEachIndexed { i, s -> binding.llHowItWorks.addView(stepRow(i + 1, s)) }
        sources.forEachIndexed { i, s ->
            if (i > 0) binding.llSources.addView(divider())
            binding.llSources.addView(sourceRow(s))
        }
        team.forEach { binding.llTeam.addView(memberRow(it)) }

        // Legal
        binding.rowAboutPrivacy.setOnClickListener { Legal.open(this, Legal.Doc.PRIVACY) }
        binding.rowAboutTerms.setOnClickListener { Legal.open(this, Legal.Doc.TERMS) }
        binding.rowAboutCookies.setOnClickListener { Legal.open(this, Legal.Doc.COOKIES) }
        binding.rowAboutConsent.setOnClickListener { Legal.open(this, Legal.Doc.CONSENT) }

        // Contact
        binding.tvAboutAddress.text = SCHOOL_ADDRESS
        binding.tvAboutEmail.text = CONTACT_EMAIL
        binding.rowAboutAddress.setOnClickListener {
            openOrToast(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode("STI College Global City, $SCHOOL_ADDRESS"))))
        }
        binding.rowAboutEmail.setOnClickListener {
            openOrToast(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$CONTACT_EMAIL")).apply {
                putExtra(Intent.EXTRA_SUBJECT, "BahGo app")
            })
        }
    }

    // ───────────────────────── Rows ─────────────────────────

    /** Numbered step: circle with the number, title, explanation. */
    private fun stepRow(n: Int, s: Step): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        row.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            gravity = Gravity.CENTER
            text = n.toString()
            setTextColor(ContextCompat.getColor(context, R.color.white))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, Typeface.BOLD)
            setBackgroundResource(R.drawable.bg_intro_icon_circle)
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.button_primary))
        })
        row.addView(textBlock(s.title, s.text))
        return row
    }

    /** Data source / API: name, what it's used for, and a link (tap to open). */
    private fun sourceRow(s: Source): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        row.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))
            setBackgroundResource(R.drawable.bg_button_solid)
            backgroundTintList = ColorStateList.valueOf("#1E88E5".toColorInt())
        })
        val text = textBlock(s.name, s.use)
        s.url?.let { url ->
            text.addView(TextView(this).apply {
                this.text = url.removePrefix("https://")
                setTextColor(ContextCompat.getColor(context, R.color.icon_accent))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            })
            row.setBackgroundResource(android.R.drawable.list_selector_background)
            row.setOnClickListener { openOrToast(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
        row.addView(text)
        return row
    }

    /** Developer: round photo, name, role. */
    private fun memberRow(m: Member): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        row.addView(ShapeableImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
            scaleType = ImageView.ScaleType.CENTER_CROP
            shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCornerSizes(RelativeCornerSize(0.5f)).build()
            setImageResource(m.photo)
            contentDescription = getString(R.string.about_photo_of, m.name)
        })
        row.addView(textBlock(m.name, m.role))
        return row
    }

    private fun textBlock(title: String, body: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(14) }
            addView(TextView(context).apply {
                text = title
                setTextColor(ContextCompat.getColor(context, R.color.text_on_card))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(null, Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = body
                setTextColor(ContextCompat.getColor(context, R.color.text_on_card_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setLineSpacing(0f, 1.12f)
            })
        }

    private fun divider() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            .apply { marginStart = dp(16); marginEnd = dp(16) }
        setBackgroundColor(ContextCompat.getColor(context, R.color.card_stroke))
    }

    private fun openOrToast(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
