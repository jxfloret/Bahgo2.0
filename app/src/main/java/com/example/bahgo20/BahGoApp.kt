package com.example.bahgo20

import android.app.Application

class BahGoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Recorded flood days shipped inside the app (forecast training and the Conditions chart)
        RecordedDataset.init(this)

        val sessionManager = SessionManager.getInstance(this)
        sessionManager.applyTheme()

        // Usage statistics stay off unless the user agreed to them (see legal/Legal.kt)
        com.example.bahgo20.legal.Legal.applyAnalytics(this)
    }
}
