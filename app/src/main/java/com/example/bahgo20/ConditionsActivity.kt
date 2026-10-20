package com.example.bahgo20

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.bahgo20.databinding.ActivityConditionsBinding
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.launch

class ConditionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConditionsBinding
    private val db = FirebaseDatabase
        .getInstance("https://bahgo-ee71b-default-rtdb.asia-southeast1.firebasedatabase.app/")
        .reference

    private var sensorId: Int = 1
    private var sensorListener: ValueEventListener? = null
    private var rootAlertStatusListener: ValueEventListener? = null
    private var globalAlertStatus: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityConditionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        sensorId = intent.getIntExtra("SENSOR_ID", 1)
        val streetName = intent.getStringExtra("STREET_NAME") ?: "Sealion Street"

        binding.tvStreetName.text = streetName
        binding.ivBack.setOnClickListener { finish() }

        setupSensorListener()
        loadUserMode()
    }

    private fun setupSensorListener() {
        val sensorNode = "Sensordata$sensorId"

        rootAlertStatusListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                globalAlertStatus = snapshot.value?.toString()
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        db.child("Last_Alert_Status").addValueEventListener(rootAlertStatusListener!!)

        sensorListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists() || snapshot.value == null) {
                    updateUi(0, "OFFLINE")
                    return
                }

                val statusFromChild = snapshot.child("Last_Alert_Status").value?.toString()
                    ?: snapshot.child("status").value?.toString()

                val levelStr = when {
                    snapshot.hasChild("waterLevel") -> snapshot.child("waterLevel").value?.toString()
                    snapshot.hasChild("level") -> snapshot.child("level").value?.toString()
                    snapshot.hasChild("distance") -> snapshot.child("distance").value?.toString()
                    snapshot.hasChild("value") -> snapshot.child("value").value?.toString()
                    !snapshot.hasChildren() -> snapshot.value?.toString()
                    else -> "0"
                }

                val rawValueStr = levelStr?.trim() ?: ""
                val levelInt = rawValueStr.toDoubleOrNull()?.toInt() ?: 0

                val rawStatus = statusFromChild
                    ?: (if (rawValueStr.toDoubleOrNull() == null && rawValueStr.isNotEmpty()) rawValueStr else null)
                    ?: globalAlertStatus

                val conditionStatus = resolveConditionStatus(rawStatus, rawValueStr, levelInt)
                updateUi(levelInt, conditionStatus)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("ConditionsActivity", "Error reading $sensorNode", error.toException())
                updateUi(0, "OFFLINE")
            }
        }
        db.child(sensorNode).addValueEventListener(sensorListener!!)
    }

    private fun resolveConditionStatus(rawStatus: String?, rawValueStr: String, levelInt: Int): String {
        val statusClean = rawStatus?.trim()?.uppercase() ?: ""

        if (rawValueStr.isEmpty() || rawValueStr.equals("null", true) || rawValueStr.equals("offline", true)) {
            if (statusClean.isEmpty() || statusClean == "NULL" || statusClean.contains("OFFLINE")) {
                return "OFFLINE"
            }
        }

        return when {
            statusClean.contains("OFFLINE") || statusClean == "NULL" || statusClean == "DISCONNECTED" -> "OFFLINE"
            statusClean.contains("CRITICAL") || statusClean.contains("DANGER") || statusClean.contains("RED") -> "CRITICAL"
            statusClean.contains("HIGH") || statusClean.contains("WARNING") || statusClean.contains("MODERATE") || statusClean.contains("ORANGE") -> "HIGH"
            statusClean.contains("SAFE") || statusClean.contains("NORMAL") || statusClean.contains("LOW") || statusClean.contains("GREEN") -> "SAFE"
            else -> {
                when {
                    levelInt > 50 -> "CRITICAL"
                    levelInt > 20 -> "HIGH"
                    else -> "SAFE"
                }
            }
        }
    }

    private fun loadUserMode() {
        lifecycleScope.launch {
            val sessionManager = SessionManager.getInstance(this@ConditionsActivity)
            val phone = sessionManager.getPhoneNumber() ?: return@launch
            val sanitizedPhone = phone.replace("+", "")

            db.child("users").child(sanitizedPhone).child("hasVehicle").get().addOnSuccessListener { snapshot ->
                val hasVehicle = snapshot.getValue(Boolean::class.java) ?: false
                if (hasVehicle) {
                    binding.tvModeTitle.text = getString(R.string.car_mode)
                    binding.ivModeIcon.setImageResource(R.drawable.ic_car)
                } else {
                    binding.tvModeTitle.text = getString(R.string.person_mode)
                    binding.ivModeIcon.setImageResource(R.drawable.ic_person)
                }
            }
        }
    }

    private fun updateUi(waterLevel: Int, conditionStatus: String) {
        val isOffline = (conditionStatus == "OFFLINE")

        if (isOffline) {
            binding.cvLocationCard.setCardBackgroundColor("#9E9E9E".toColorInt())
            binding.tvOfflineBadge.visibility = View.VISIBLE
            binding.tvWaterLevel.text = "OFF"
            binding.tvThreshold.text = "Device Offline"
            binding.tvRiskLevel.text = "offline"
            binding.tvRiskDetail.text = "Unable to receive telemetry from sensor."
            binding.cvWaterLevelCard.setCardBackgroundColor("#B0BEC5".toColorInt())
            binding.cvRiskLevelCard.setCardBackgroundColor("#B0BEC5".toColorInt())
            binding.tvRateOfRise.text = "Rate of Rise: --"
            binding.tvPrecipitation.text = "Precipitation: --"
            binding.tvModeDescription.text = "This sensor device is currently offline. Real-time water level and flood severity metrics are unavailable."
            return
        }

        binding.cvLocationCard.setCardBackgroundColor("#1D4ED8".toColorInt())
        binding.tvOfflineBadge.visibility = View.GONE
        binding.cvWaterLevelCard.setCardBackgroundColor("#6495ED".toColorInt())

        val riskCardColor = when (conditionStatus) {
            "CRITICAL" -> "#D32F2F"
            "HIGH" -> "#FF9100"
            else -> "#4CAF50"
        }
        binding.cvRiskLevelCard.setCardBackgroundColor(riskCardColor.toColorInt())

        binding.tvWaterLevel.text = getString(R.string.water_level_cm_format, waterLevel)

        val threshold = 35
        val percent = (waterLevel.toFloat() / threshold * 100).toInt()
        binding.tvThreshold.text = "$percent% of threshold"

        val statusText: String
        val riskDetail: String

        when (conditionStatus) {
            "CRITICAL" -> {
                statusText = getString(R.string.status_dangerous)
                riskDetail = getString(R.string.chest_desc)
            }
            "HIGH" -> {
                statusText = getString(R.string.status_warning)
                riskDetail = getString(R.string.risk_warning_desc)
            }
            else -> {
                statusText = getString(R.string.status_safe)
                riskDetail = getString(R.string.ankle_desc)
            }
        }

        binding.tvRiskLevel.text = statusText
        binding.tvRiskDetail.text = riskDetail

        val rise = waterLevel / 10
        val precip = waterLevel / 5.0
        binding.tvRateOfRise.text = getString(R.string.rate_of_rise_format, rise)
        binding.tvPrecipitation.text = getString(R.string.precipitation_format, precip)

        val modeDesc = if (binding.tvModeTitle.text == getString(R.string.car_mode)) {
            "The Water level is ${waterLevel}cm. " + when (conditionStatus) {
                "CRITICAL" -> getString(R.string.critical_desc)
                "HIGH" -> getString(R.string.risky_desc)
                else -> getString(R.string.safe_desc)
            }
        } else {
            "The Water level is ${waterLevel}cm, which is $percent% of the safe limit for walking. " + when (conditionStatus) {
                "CRITICAL" -> getString(R.string.waist_desc)
                "HIGH" -> getString(R.string.knee_desc)
                else -> getString(R.string.ankle_desc)
            }
        }
        binding.tvModeDescription.text = modeDesc
    }

    override fun onDestroy() {
        super.onDestroy()
        rootAlertStatusListener?.let {
            db.child("Last_Alert_Status").removeEventListener(it)
        }
        sensorListener?.let {
            db.child("Sensordata$sensorId").removeEventListener(it)
        }
    }
}