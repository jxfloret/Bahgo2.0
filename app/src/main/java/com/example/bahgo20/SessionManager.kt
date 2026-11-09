package com.example.bahgo20

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SessionManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val publicPrefs: SharedPreferences = 
        appContext.getSharedPreferences("bahgo_public_prefs_v5", Context.MODE_PRIVATE)
        
    @Volatile
    private var securePrefs: SharedPreferences? = null

    init {
        managerScope.launch {
            getSecurePrefs()
        }
    }

    private suspend fun getSecurePrefs(): SharedPreferences = withContext(Dispatchers.IO) {
        securePrefs ?: synchronized(this@SessionManager) {
            securePrefs?.let { return@synchronized it }
            try {
                val masterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    appContext,
                    "bahgo_secure_v5",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                ).also { securePrefs = it }
            } catch (e: Exception) {
                Log.e("SessionManager", "Secure init failed", e)
                appContext.getSharedPreferences("bahgo_fallback_v5", Context.MODE_PRIVATE).also { securePrefs = it }
            }
        }
    }

    companion object {
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_PHONE_NUMBER = "phone_number"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_HAS_VEHICLE = "has_vehicle"

        @Volatile
        private var INSTANCE: SessionManager? = null

        fun getInstance(context: Context): SessionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SessionManager(context).also { INSTANCE = it }
            }
        }
    }

    fun isLoggedIn(): Boolean = publicPrefs.getBoolean(KEY_IS_LOGGED_IN, false)

    fun saveSession(phoneNumber: String, hasVehicle: Boolean = false) {
        publicPrefs.edit()
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .putBoolean(KEY_HAS_VEHICLE, hasVehicle)
            .apply()
        
        managerScope.launch {
            try {
                getSecurePrefs().edit().putString(KEY_PHONE_NUMBER, phoneNumber).apply()
            } catch (e: Exception) {
                Log.e("SessionManager", "Failed to save phone", e)
            }
        }
    }

    fun setHasVehicle(hasVehicle: Boolean) {
        publicPrefs.edit().putBoolean(KEY_HAS_VEHICLE, hasVehicle).apply()
    }

    fun hasVehicle(): Boolean = publicPrefs.getBoolean(KEY_HAS_VEHICLE, false)

    suspend fun getPhoneNumber(): String? = withContext(Dispatchers.IO) {
        getSecurePrefs().getString(KEY_PHONE_NUMBER, null)
    }

    fun setThemeMode(mode: Int) {
        publicPrefs.edit().putInt(KEY_THEME_MODE, mode).apply()
        applyTheme(mode)
    }

    fun getThemeMode(): Int = publicPrefs.getInt(KEY_THEME_MODE, 0)

    fun applyTheme(mode: Int = getThemeMode()) {
        val nightMode = when (mode) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != nightMode) {
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }
    }

    fun logout() {
        FirebaseAuth.getInstance().signOut()
        com.example.bahgo20.auth.AuthApi.signOut()
        publicPrefs.edit().clear().apply()
        managerScope.launch {
            try {
                getSecurePrefs().edit().clear().apply()
            } catch (e: Exception) {
                Log.e("SessionManager", "Failed to clear", e)
            }
        }
    }
}