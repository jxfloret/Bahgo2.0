package com.example.bahgo20

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val mainView = findViewById<View>(R.id.main)
        val logoLayout = findViewById<View>(R.id.logoLayout)
        val btnStack = findViewById<View>(R.id.btnStack)
        val ivSettings = findViewById<View>(R.id.ivSettings)

        ViewCompat.setOnApplyWindowInsetsListener(mainView) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            logoLayout.updatePadding(top = systemBars.top)
            btnStack.updateLayoutParams<ConstraintLayout.LayoutParams> {
                bottomMargin = (52 * resources.displayMetrics.density).toInt() + systemBars.bottom
            }
            insets
        }

        findViewById<View>(R.id.btnGetStarted).setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }

        findViewById<View>(R.id.btnLogin).setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }

        ivSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }
}
