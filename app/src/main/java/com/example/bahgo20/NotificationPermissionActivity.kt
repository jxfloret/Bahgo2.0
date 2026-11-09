package com.example.bahgo20

import android.Manifest
import android.app.Dialog
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

/**
 * Step 2 of 2: notifications (flood alerts). Android's own prompt opens right away; Allow moves
 * on to Home by itself. Don't allow shows a pop-up that blocks the step until notifications are
 * turned on, with a shortcut to the phone's Settings.
 */
class NotificationPermissionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPermissionNotificationBinding
    private var blockedDialog: Dialog? = null
    private var openedSettings = false
    private var done = false

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        if (PermissionHelper.hasNotificationPermission(this)) proceedToHome() else showBlocked()
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

        binding.btnAllowNotifications.setOnClickListener { ask() }
        binding.btnDeny.setOnClickListener { showBlocked() }

        if (savedInstanceState == null) binding.root.post { ask() }
    }

    override fun onResume() {
        super.onResume()
        if (openedSettings) {
            openedSettings = false
            if (PermissionHelper.hasNotificationPermission(this)) proceedToHome() else showBlocked()
        }
    }

    private fun ask() {
        blockedDialog?.dismiss()
        when {
            PermissionHelper.hasNotificationPermission(this) -> proceedToHome()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> showBlocked()     // Android 12 and older: notifications were turned off in Settings
        }
    }

    private fun showBlocked() {
        if (done || isFinishing) return
        blockedDialog?.dismiss()
        blockedDialog = PermissionHelper.showNeededDialog(
            this, R.drawable.ic_bell,
            getString(R.string.perm_notif_needed_title), getString(R.string.perm_notif_needed_msg),
            onSettings = { openedSettings = true; PermissionHelper.openNotificationSettings(this) },
            onRetry = { ask() }
        )
    }

    private fun proceedToHome() {
        if (done) return
        done = true
        blockedDialog?.dismiss()
        OnboardingManager(this).setOnboardingCompleted(true)
        val intent = Intent(this, HomeActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }
}
