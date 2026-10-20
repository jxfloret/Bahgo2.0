package com.example.bahgo20

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.bahgo20.databinding.ActivityPermissionNotificationBinding
import com.example.bahgo20.utils.OnboardingManager

class NotificationPermissionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPermissionNotificationBinding

    private val requestNotificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Notifications disabled. You can enable them later in settings.", Toast.LENGTH_SHORT).show()
        }
        proceedToHome()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityPermissionNotificationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = systemBars.bottom, top = systemBars.top)
            insets
        }

        binding.btnAllowNotifications.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                proceedToHome()
            }
        }

        binding.btnDeny.setOnClickListener {
            proceedToHome()
        }
    }

    private fun proceedToHome() {
        OnboardingManager(this).setOnboardingCompleted(true)
        val intent = Intent(this, HomeActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }
}
