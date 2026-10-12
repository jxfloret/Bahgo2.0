package com.example.bahgo20

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.example.bahgo20.databinding.ActivityLoginBinding
import com.example.bahgo20.otp.OtpProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val db = FirebaseFirestore.getInstance()
    private lateinit var sessionManager: SessionManager
    
    private var failedAttempts = 0
    private val maxAttempts = 5
    private val lockoutDuration = 30000L // 30 seconds

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager.getInstance(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.loginMain) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.tvLoginTitle.updatePadding(top = systemBars.top)
            binding.loginCard.updatePadding(bottom = systemBars.bottom)
            insets
        }

        binding.btnLogin.setOnClickListener {
            performLogin()
        }

        binding.tvSignUp.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
        }
    }

    private fun performLogin() {
        val phone = binding.etPhoneNumber.text.toString().trim()
        val password = binding.etPassword.text.toString()

        if (phone.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show()
            return
        }

        val e164Phone = OtpProvider.otp.toE164(phone)
        
        binding.btnLogin.isEnabled = false
        
        lifecycleScope.launch {
            try {
                val userDoc = db.collection("users").document(e164Phone).get().await()
                
                if (!userDoc.exists()) {
                    handleFailedLogin()
                    return@launch
                }

                val storedHash = userDoc.getString("passwordHash") ?: ""
                val storedSalt = userDoc.getString("salt") ?: ""
                
                val saltBytes = Base64.decode(storedSalt, Base64.NO_WRAP)
                val computedHash = hashPassword(password.toCharArray(), saltBytes)
                
                if (MessageDigest.isEqual(Base64.decode(storedHash, Base64.NO_WRAP), Base64.decode(computedHash, Base64.NO_WRAP))) {
                    onLoginSuccess(e164Phone)
                } else {
                    handleFailedLogin()
                }
            } catch (e: Exception) {
                Log.e("LoginLog", "Error during login", e)
                Toast.makeText(this@LoginActivity, "An error occurred. Please try again.", Toast.LENGTH_SHORT).show()
                binding.btnLogin.isEnabled = true
            }
        }
    }

    private fun handleFailedLogin() {
        failedAttempts++
        Toast.makeText(this, "Incorrect phone number or password", Toast.LENGTH_SHORT).show()
        
        if (failedAttempts >= maxAttempts) {
            binding.btnLogin.isEnabled = false
            Toast.makeText(this, "Too many failed attempts. Try again in 30 seconds.", Toast.LENGTH_LONG).show()
            Handler(Looper.getMainLooper()).postDelayed({
                failedAttempts = 0
                binding.btnLogin.isEnabled = true
            }, lockoutDuration)
        } else {
            binding.btnLogin.isEnabled = true
        }
    }

    private fun onLoginSuccess(phoneNumber: String) {
        lifecycleScope.launch {
            sessionManager.saveSession(phoneNumber)
            
            // Save login history
            val loginData = hashMapOf(
                "phoneNumber" to phoneNumber,
                "loginTime" to FieldValue.serverTimestamp(),
                "status" to "SUCCESS"
            )
            db.collection("logins").add(loginData)

            val intent = Intent(this@LoginActivity, HomeActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }

    private suspend fun hashPassword(password: CharArray, salt: ByteArray): String = withContext(Dispatchers.Default) {
        val iterations = 100000
        val keyLength = 256
        val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password, salt, iterations, keyLength)
        val key = skf.generateSecret(spec)
        Base64.encodeToString(key.encoded, Base64.NO_WRAP)
    }
}
