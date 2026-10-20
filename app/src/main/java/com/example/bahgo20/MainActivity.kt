package com.example.bahgo20

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
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
        
        // Session routing is handled by SplashActivity
        
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val mainView = findViewById<View>(R.id.main)
        val logoLayout = findViewById<View>(R.id.logoLayout)
        val rainfallView = findViewById<View>(R.id.rainfallView)
        val btnGetStarted = findViewById<Button>(R.id.btnGetStarted)
        val btnLogin = findViewById<Button>(R.id.btnLogin)

        rainfallView.elevation = 0f
        btnGetStarted.bringToFront()
        btnLogin.bringToFront()

        ViewCompat.setOnApplyWindowInsetsListener(mainView) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            logoLayout.updatePadding(top = systemBars.top)
            btnLogin.updateLayoutParams<ConstraintLayout.LayoutParams> {
                bottomMargin = (48 * resources.displayMetrics.density).toInt() + systemBars.bottom
            }
            insets
        }

        btnGetStarted.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }

        btnLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }
    }
}
