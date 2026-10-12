package com.example.bahgo20

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SessionManager private constructor(context: Context) {
    private var prefs: SharedPreferences? = null
    private val appContext = context.applicationContext

    private suspend fun getPrefs(): SharedPreferences = withContext(Dispatchers.IO) {
        prefs ?: synchronized(this@SessionManager) {
            if (prefs != null) return@synchronized prefs!!
            
            try {
                val masterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                EncryptedSharedPreferences.create(
                    appContext,
                    "bahgo_session",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                ).also { prefs = it }
            } catch (e: Exception) {
                Log.e("SessionManager", "Error initializing EncryptedSharedPreferences", e)
                // Fallback to standard SharedPreferences if encrypted fails (common on some emulators)
                appContext.getSharedPreferences("bahgo_session_fallback", Context.MODE_PRIVATE).also { prefs = it }
            }
        }
    }

    companion object {
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_PHONE_NUMBER = "phone_number"

        @Volatile
        private var INSTANCE: SessionManager? = null

        fun getInstance(context: Context): SessionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SessionManager(context).also { INSTANCE = it }
            }
        }
    }

    suspend fun saveSession(phoneNumber: String) {
        getPrefs().edit()
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .putString(KEY_PHONE_NUMBER, phoneNumber)
            .apply()
    }

    suspend fun isLoggedIn(): Boolean = withContext(Dispatchers.IO) {
        try {
            getPrefs().getBoolean(KEY_IS_LOGGED_IN, false)
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getPhoneNumber(): String? = withContext(Dispatchers.IO) {
        getPrefs().getString(KEY_PHONE_NUMBER, null)
    }

    suspend fun logout() {
        withContext(Dispatchers.IO) {
            getPrefs().edit().clear().apply()
        }
    }
}
