package com.example.bahgo20

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.bahgo20.databinding.ActivityPermissionNotificationBinding
import com.example.bahgo20.utils.OnboardingManager
import com.example.bahgo20.utils.PermissionHelper

class NotificationPermissionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPermissionNotificationBinding

    private val requestNotificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            proceedToHome()
        } else {
            // Rationale/Settings logic
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (!shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                    showNotificationRequiredDialog()
                }
            }
        }
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
                // Notifications are enabled by default below API 33
                proceedToHome()
            }
        }

        binding.btnDeny.setOnClickListener {
            showNotificationRequiredDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check if user enabled it in settings and came back
        if (PermissionHelper.hasNotificationPermission(this)) {
            proceedToHome()
        }
    }

    private fun showNotificationRequiredDialog() {
        PermissionHelper.showSettingsDialog(
            this,
            getString(R.string.notifications_required_title),
            getString(R.string.notifications_required_msg)
        )
    }

    private fun proceedToHome() {
        OnboardingManager(this).setOnboardingCompleted(true)
        val intent = Intent(this, HomeActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }
}
