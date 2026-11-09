package com.example.bahgo20

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.example.bahgo20.databinding.ActivityFloodRiseBinding

/**
 * Short "rising flood" animation: water rises and fills the screen while the logo floats up
 * with it, then the next screen fades in. Plays when the app opens and after the intro's
 * Get Started. Tap to skip.
 */
class FloodRiseActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_NEXT = "next_screen"

        /** Plays the animation, then opens [next] (the welcome screen if null). */
        fun open(from: Context, next: Intent? = null) {
            from.startActivity(Intent(from, FloodRiseActivity::class.java).apply {
                if (next != null) putExtra(EXTRA_NEXT, next)
            })
        }
    }

    private lateinit var binding: ActivityFloodRiseBinding
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityFloodRiseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.root.setOnClickListener { goToWelcome() }

        // The logo and name appear, then ride up a little on the water
        binding.llFloodLogo.alpha = 0f
        binding.llFloodLogo.translationY = 60 * resources.displayMetrics.density
        binding.llFloodLogo.animate().alpha(1f).translationY(0f).setDuration(700).setStartDelay(250).start()

        binding.floodRise.post {
            binding.floodRise.rise(
                durationMs = 2200,
                onProgress = { level ->
                    binding.llFloodLogo.translationY = -level * 40 * resources.displayMetrics.density
                },
                onFull = { binding.root.postDelayed({ goToWelcome() }, 350) }
            )
        }
    }

    private fun goToWelcome() {
        if (done || isFinishing) return
        done = true
        binding.floodRise.stop()
        @Suppress("DEPRECATION")
        val next = intent.getParcelableExtra<Intent>(EXTRA_NEXT) ?: Intent(this, MainActivity::class.java)
        startActivity(next)
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}
