package com.example.bahgo20

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.bahgo20.databinding.ActivitySettingsBinding
import com.google.android.gms.location.LocationServices
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var sessionManager: SessionManager
    private val db = FirebaseDatabase
        .getInstance("https://bahgo-ee71b-default-rtdb.asia-southeast1.firebasedatabase.app/")
        .reference

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            fetchUserCurrentLocationName()
        } else {
            binding.tvLocationTitle.text = "Taguig, Metro Manila"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager.getInstance(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.ivBack.setOnClickListener { finish() }

        loadCurrentMode()
        checkAndLoadLocation()
        setupAppearanceButtons()

        binding.tvChangeLoc.setOnClickListener {
            showChangeLocationDialog()
        }

        binding.btnPersonMode.setOnClickListener {
            updateUserMode(hasVehicle = false)
        }

        binding.btnCarMode.setOnClickListener {
            updateUserMode(hasVehicle = true)
        }

        binding.cardAbout.setOnClickListener {
            Toast.makeText(this, "BahGo v2.0 - Palar Village Flood Monitoring System", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupAppearanceButtons() {
        lifecycleScope.launch {
            val mode = sessionManager.getThemeMode()
            updateAppearanceUi(mode)
        }

        binding.btnLightMode.setOnClickListener {
            lifecycleScope.launch {
                sessionManager.setThemeMode(1)
                updateAppearanceUi(1)
            }
        }

        binding.btnDarkMode.setOnClickListener {
            lifecycleScope.launch {
                sessionManager.setThemeMode(2)
                updateAppearanceUi(2)
            }
        }
    }

    private fun updateAppearanceUi(mode: Int) {
        applyToggleStyle(binding.btnLightMode, selected = mode != 2)
        applyToggleStyle(binding.btnDarkMode, selected = mode == 2)
    }

    private fun checkAndLoadLocation() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (fine || coarse) {
            fetchUserCurrentLocationName()
        } else {
            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchUserCurrentLocationName() {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val addressName = getAddressFromCoordinates(location.latitude, location.longitude)
                    withContext(Dispatchers.Main) {
                        binding.tvLocationTitle.text = addressName ?: "Taguig, Metro Manila"
                    }
                }
            } else {
                binding.tvLocationTitle.text = "Taguig, Metro Manila"
            }
        }.addOnFailureListener {
            binding.tvLocationTitle.text = "Taguig, Metro Manila"
        }
    }

    private suspend fun getAddressFromCoordinates(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        try {
            val geocoder = Geocoder(this@SettingsActivity, Locale.getDefault())
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(lat, lon, 1)
            if (!addresses.isNullOrEmpty()) {
                val addr = addresses[0]
                val locality = addr.locality ?: addr.subAdminArea ?: "Taguig"
                val adminArea = addr.adminArea ?: "Metro Manila"
                return@withContext "$locality, $adminArea"
            }
        } catch (_: Exception) {}
        return@withContext null
    }

    private fun showChangeLocationDialog() {
        val locations = arrayOf(
            "Palar Village, Taguig",
            "Pinagsama, Taguig",
            "Fort Bonifacio (BGC), Taguig",
            "Ususan, Taguig",
            "Western Bicutan, Taguig",
            "Lower Bicutan, Taguig",
            "Pembo, Makati"
        )

        AlertDialog.Builder(this)
            .setTitle("Select Location")
            .setItems(locations) { _, which ->
                val selected = locations[which]
                binding.tvLocationTitle.text = selected
                Toast.makeText(this, "Location set to $selected", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadCurrentMode() {
        lifecycleScope.launch {
            val phone = sessionManager.getPhoneNumber() ?: return@launch
            val sanitizedPhone = phone.replace("+", "")

            try {
                val snapshot = db.child("users").child(sanitizedPhone).get().await()
                val hasVehicle = if (snapshot.exists()) {
                    snapshot.child("hasVehicle").getValue(Boolean::class.java) ?: false
                } else {
                    false
                }
                setToggleUi(hasVehicle)
            } catch (e: Exception) {
                setToggleUi(false)
            }
        }
    }

    // Update user mode in the database
    private fun updateUserMode(hasVehicle: Boolean) {
        lifecycleScope.launch {
            val phone = sessionManager.getPhoneNumber() ?: return@launch
            val sanitizedPhone = phone.replace("+", "")

            try {
                db.child("users").child(sanitizedPhone).child("hasVehicle").setValue(hasVehicle).await()
                setToggleUi(hasVehicle)
                Toast.makeText(this@SettingsActivity, if (hasVehicle) "Switched to Car Mode" else "Switched to Person Mode", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "Failed to update mode", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setToggleUi(hasVehicle: Boolean) {
        applyToggleStyle(binding.btnPersonMode, selected = !hasVehicle)
        applyToggleStyle(binding.btnCarMode, selected = hasVehicle)
    }

    private fun applyToggleStyle(button: com.google.android.material.button.MaterialButton, selected: Boolean) {
        val bg = if (selected) {
            ContextCompat.getColor(this, R.color.toggle_selected_bg)
        } else {
            Color.TRANSPARENT
        }
        val fg = ContextCompat.getColor(
            this,
            if (selected) R.color.toggle_selected_text else R.color.toggle_unselected_text
        )
        button.backgroundTintList = ColorStateList.valueOf(bg)
        button.setTextColor(fg)
        button.iconTint = ColorStateList.valueOf(fg)
    }
}
