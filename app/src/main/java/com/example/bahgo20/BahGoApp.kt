package com.example.bahgo20

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class BahGoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        val sessionManager = SessionManager.getInstance(this)
        MainScope().launch {
            val mode = sessionManager.getThemeMode()
            when (mode) {
                1 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                2 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
        }
    }
}
