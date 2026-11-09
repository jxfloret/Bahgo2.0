package com.example.bahgo20.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.bahgo20.HomeActivity
import com.example.bahgo20.LocationPermissionActivity
import com.example.bahgo20.NotificationPermissionActivity
import com.example.bahgo20.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object PermissionHelper {

    /**
     * Where to go right after logging in or signing up: the consent form if the user hasn't
     * agreed to the current Privacy Policy and Terms yet, then the Location screen if location isn't
     * allowed yet, then the Notifications screen if notifications aren't, otherwise Home.
     * Screens for permissions that are already allowed are skipped.
     */
    fun afterSignInIntent(context: Context): Intent {
        val target = when {
            !com.example.bahgo20.legal.Legal.hasConsent(context) -> com.example.bahgo20.legal.ConsentActivity::class.java
            !hasLocationPermission(context) -> LocationPermissionActivity::class.java
            !hasNotificationPermission(context) -> NotificationPermissionActivity::class.java
            else -> HomeActivity::class.java
        }
        return Intent(context, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

    /**
     * Checks if foreground location permissions (FINE and COARSE) are granted.
     */
    fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Checks if background location permission is granted (API 29+).
     */
    fun hasBackgroundLocationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Checks if notification permission is granted (API 33+).
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    /**
     * Shows a generic dialog directing the user to App Settings.
     */
    fun showSettingsDialog(activity: Activity, title: String, message: String) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.go_to_settings) { _, _ ->
                openAppSettings(activity)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * App-styled pop-up for a permission the app needs but was refused (Don't allow).
     * It can't be dismissed: the user either opens the phone's Settings or tries again.
     */
    fun showNeededDialog(
        activity: Activity, iconRes: Int, title: String, message: String,
        onSettings: () -> Unit, onRetry: () -> Unit
    ): android.app.Dialog {
        val b = com.example.bahgo20.databinding.DialogPermissionNeededBinding.inflate(activity.layoutInflater)
        b.ivPermIcon.setImageResource(iconRes)
        b.tvPermTitle.text = title
        b.tvPermMessage.text = message
        val dialog = android.app.Dialog(activity).apply {
            setContentView(b.root)
            setCancelable(false)
            window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            window?.setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.88).toInt(),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        b.btnPermSettings.setOnClickListener { dialog.dismiss(); onSettings() }
        b.btnPermRetry.setOnClickListener { dialog.dismiss(); onRetry() }
        dialog.show()
        return dialog
    }

    /**
     * Opens the system settings page for the app.
     */
    fun openAppSettings(activity: Activity) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", activity.packageName, null)
        }
        activity.startActivity(intent)
    }

    /**
     * Opens notification settings (Android 8.0+).
     */
    fun openNotificationSettings(activity: Activity) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", activity.packageName, null)
            }
        }
        activity.startActivity(intent)
    }
}
