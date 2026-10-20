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
import com.example.bahgo20.databinding.ActivityPermissionLocationBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class LocationPermissionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPermissionLocationBinding
    private var isRequestingAlways = false

    private val requestForegroundLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (granted) {
            if (isRequestingAlways && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                showBackgroundDisclosure()
            } else {
                proceedToNotifications()
            }
        } else {
            // If denied (even permanently), just show a brief message and move on.
            // This stops the app from forcing the user into a settings loop.
            Toast.makeText(this, "Location skipped. You can enable it in settings later if needed.", Toast.LENGTH_SHORT).show()
            proceedToNotifications()
        }
    }

    private val requestBackgroundLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Proceed regardless of background result
        proceedToNotifications()
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

        binding.btnAllowWhileUsing.setOnClickListener {
            isRequestingAlways = false
            requestForegroundLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }

        binding.btnAllowAlways.setOnClickListener {
            isRequestingAlways = true
            requestForegroundLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }

        binding.btnDeny.setOnClickListener {
            // Allow user to skip immediately without any pop-ups
            proceedToNotifications()
        }
    }

    override fun onResume() {
        super.onResume()
        // Removed auto-redirect to prevent jumping screens unexpectedly
    }

    private fun showBackgroundDisclosure() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.background_location_disclosure_title)
            .setMessage(R.string.background_location_disclosure_msg)
            .setPositiveButton(R.string.ok) { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    requestBackgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            }
            .setNegativeButton("Skip") { _, _ -> proceedToNotifications() }
            .setCancelable(false)
            .show()
    }

    private fun proceedToNotifications() {
        startActivity(Intent(this, NotificationPermissionActivity::class.java))
        finish()
    }
}
