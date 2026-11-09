package com.example.bahgo20

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.bahgo20.databinding.ActivityPermissionLocationBinding
import com.example.bahgo20.utils.OnboardingManager
import com.example.bahgo20.utils.PermissionHelper

/**
 * Step 1 of 2: location. Android's own prompt opens right away; allowing it (While using /
 * Only this time) moves on by itself. Don't allow shows a pop-up that blocks the step until
 * location is turned on, with a shortcut to the phone's Settings.
 */
class LocationPermissionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPermissionLocationBinding
    private var blockedDialog: Dialog? = null
    private var openedSettings = false
    private var done = false

    private val requestLocation = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (PermissionHelper.hasLocationPermission(this)) proceedToNotifications() else showBlocked()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityPermissionLocationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = systemBars.bottom, top = systemBars.top)
            insets
        }

        // The screen's buttons only re-open the prompt (it opens by itself the first time)
        binding.btnAllowWhileUsing.setOnClickListener { ask() }
        binding.btnAllowAlways.setOnClickListener { ask() }
        binding.btnDeny.setOnClickListener { showBlocked() }

        if (savedInstanceState == null) binding.root.post { ask() }
    }

    override fun onResume() {
        super.onResume()
        // Back from the phone's Settings: continue if location is on now, otherwise ask again
        if (openedSettings) {
            openedSettings = false
            if (PermissionHelper.hasLocationPermission(this)) proceedToNotifications() else showBlocked()
        }
    }

    private fun ask() {
        blockedDialog?.dismiss()
        if (PermissionHelper.hasLocationPermission(this)) proceedToNotifications()
        else requestLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun showBlocked() {
        if (done || isFinishing) return
        blockedDialog?.dismiss()
        blockedDialog = PermissionHelper.showNeededDialog(
            this, R.drawable.ic_location_pin,
            getString(R.string.perm_location_needed_title), getString(R.string.perm_location_needed_msg),
            onSettings = { openedSettings = true; PermissionHelper.openAppSettings(this) },
            onRetry = { ask() }
        )
    }

    /** Next: the Notifications screen, or straight to Home if notifications are already allowed. */
    private fun proceedToNotifications() {
        if (done) return
        done = true
        blockedDialog?.dismiss()
        if (PermissionHelper.hasNotificationPermission(this)) {
            OnboardingManager(this).setOnboardingCompleted(true)
            startActivity(
                Intent(this, HomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        } else {
            startActivity(Intent(this, NotificationPermissionActivity::class.java))
        }
        finish()
    }
}
