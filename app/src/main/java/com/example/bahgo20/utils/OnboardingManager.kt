package com.example.bahgo20.utils

import android.content.Context
import android.content.SharedPreferences

class OnboardingManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("onboarding_prefs", Context.MODE_PRIVATE)

    fun isOnboardingCompleted(): Boolean {
        return prefs.getBoolean("completed", false)
    }

    fun setOnboardingCompleted(completed: Boolean) {
        prefs.edit().putBoolean("completed", completed).apply()
    }

    /** The 2-slide Get Started intro (shown once, before log in / sign up). */
    fun isIntroSeen(): Boolean = prefs.getBoolean("intro_seen", false)

    fun setIntroSeen(seen: Boolean) {
        prefs.edit().putBoolean("intro_seen", seen).apply()
    }

    /** The guided tour on Home (shown the first time Home opens). */
    fun isHomeTourDone(): Boolean = prefs.getBoolean("home_tour_done", false)

    fun setHomeTourDone(done: Boolean) {
        prefs.edit().putBoolean("home_tour_done", done).apply()
    }
}
