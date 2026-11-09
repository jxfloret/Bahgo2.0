package com.example.bahgo20

import android.annotation.SuppressLint
import android.content.Intent
import com.example.bahgo20.utils.OnboardingManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            val session = SessionManager.getInstance(this@SplashActivity)
            val isLoggedIn = withContext(Dispatchers.IO) { session.isLoggedIn() }

            // Remember who is signed in (needed to change the password or number in Settings)
            if (isLoggedIn) com.example.bahgo20.auth.AuthApi.restore(session.getPhoneNumber())

            // The database only answers signed-in apps: sign in to Firebase (anonymously, free)
            // before Home starts listening to the sensors. Kept between app starts.
            com.example.bahgo20.auth.AuthApi.ensureFirebaseSignIn()

            if (isLoggedIn) {
                // Signed-in users who haven't agreed to the current Privacy Policy / Terms see the consent form first
                val next = if (com.example.bahgo20.legal.Legal.hasConsent(this@SplashActivity)) HomeActivity::class.java
                           else com.example.bahgo20.legal.ConsentActivity::class.java
                val intent = Intent(this@SplashActivity, next)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                // Every app open: the rising flood animation first, then the next screen
                FloodRiseActivity.open(this@SplashActivity, intent)
            } else {
                // First time: the 2-slide Get Started intro, then the welcome (log in / sign up) screen
                val first = !OnboardingManager(this@SplashActivity).isIntroSeen()
                FloodRiseActivity.open(this@SplashActivity,
                    Intent(this@SplashActivity, if (first) IntroActivity::class.java else MainActivity::class.java))
            }
            finish()
        }
    }
}
