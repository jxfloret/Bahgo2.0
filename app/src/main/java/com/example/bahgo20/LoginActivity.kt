package com.example.bahgo20

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.bahgo20.databinding.ActivityLoginBinding
import com.example.bahgo20.auth.AuthApi
import com.example.bahgo20.utils.PermissionHelper
import com.example.bahgo20.utils.Phone
import com.example.bahgo20.utils.bottomAboveKeyboard
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val sessionManager by lazy { SessionManager.getInstance(this) }

    private var isLoading = false

    companion object {
        private const val TAG = "BahGoLogin"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.loginRoot) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, 0, sys.right, insets.bottomAboveKeyboard())
            insets
        }

        binding.btnLogin.setOnClickListener { performLogin() }

        binding.tvSignUp.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
        }

        binding.ivSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun performLogin() {
        if (isLoading) return
        binding.tvLoginError.visibility = View.GONE

        val phone = binding.etPhoneNumber.text?.toString()?.trim().orEmpty()
        val password = binding.etPassword.text?.toString().orEmpty()

        if (phone.isEmpty() || password.isEmpty()) {
            showError(getString(R.string.login_enter_credentials))
            return
        }

        setLoading(true)

        lifecycleScope.launch {
            // The password is checked on the server, which then signs us in to Firebase
            AuthApi.login(phone, password)
                .onSuccess { e164 -> onLoginSuccess(e164.ifEmpty { Phone.toE164(phone) }) }
                .onFailure { e ->
                    Log.w(TAG, "Login failed: ${e.message}")
                    setLoading(false)
                    showError(e.message ?: getString(R.string.login_failed))
                }
        }
    }

    private fun onLoginSuccess(phoneNumber: String) {
        setLoading(false)
        sessionManager.saveSession(phoneNumber)
        // Ask for location (then notifications) if they aren't allowed yet, same as after sign-up
        startActivity(PermissionHelper.afterSignInIntent(this@LoginActivity))
        finish()
    }

    private fun setLoading(loading: Boolean) {
        isLoading = loading
        binding.loginProgress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !loading
    }

    private fun showError(msg: String) {
        binding.tvLoginError.text = msg
        binding.tvLoginError.visibility = View.VISIBLE
    }
}
