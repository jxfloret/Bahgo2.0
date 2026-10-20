package com.example.bahgo20

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.example.bahgo20.databinding.ActivitySetupBinding
import com.example.bahgo20.otp.OtpActivity
import com.example.bahgo20.otp.OtpProvider
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private val db = FirebaseDatabase.getInstance("https://bahgo-ee71b-default-rtdb.asia-southeast1.firebasedatabase.app/").reference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        enableEdgeToEdge()
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.setupMain) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.tvSetupTitle.updatePadding(top = systemBars.top)
            binding.setupCard.updatePadding(bottom = systemBars.bottom)
            insets
        }

        setupTextWatchers()
        updateSignUpButtonState()

        binding.btnContinue.setOnClickListener {
            if (validateInputs()) {
                checkUserAndPerformSignUp()
            }
        }
    }

    private fun isSignUpFormComplete(): Boolean {
        val firstName = binding.etFirstName.text?.toString()?.trim().orEmpty()
        val surname = binding.etSurname.text?.toString()?.trim().orEmpty()
        val phone = binding.etPhoneNumber.text?.toString()?.trim().orEmpty()
        val password = binding.etPassword.text?.toString().orEmpty()
        return firstName.isNotEmpty() && surname.isNotEmpty() && phone.isNotEmpty() && password.isNotEmpty()
    }

    private fun updateSignUpButtonState() {
        binding.btnContinue.isEnabled = isSignUpFormComplete()
    }

    private fun setupTextWatchers() {
        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateSignUpButtonState()
            }
            override fun afterTextChanged(s: Editable?) {}
        }

        binding.etFirstName.addTextChangedListener(textWatcher)
        binding.etSurname.addTextChangedListener(textWatcher)
        binding.etPhoneNumber.addTextChangedListener(textWatcher)
        binding.etPassword.addTextChangedListener(textWatcher)
    }

    private fun validateInputs(): Boolean {
        val password = binding.etPassword.text.toString()
        val phone = binding.etPhoneNumber.text.toString().trim()
        val firstName = binding.etFirstName.text.toString().trim()
        val surname = binding.etSurname.text.toString().trim()

        binding.tilPassword.error = null

        if (firstName.isEmpty() || surname.isEmpty()) {
            Toast.makeText(this, "Please enter your name", Toast.LENGTH_SHORT).show()
            return false
        }

        if (phone.length < 10) {
            Toast.makeText(this, "Please enter a valid phone number", Toast.LENGTH_SHORT).show()
            return false
        }

        if (password.length < 8) {
            binding.tilPassword.error = "Password must be at least 8 characters"
            return false
        }

        if (!password.any { it.isDigit() }) {
            binding.tilPassword.error = "Password must contain at least 1 number"
            return false
        }

        if (!password.any { !it.isLetterOrDigit() }) {
            binding.tilPassword.error = "Password must contain at least 1 special character"
            return false
        }

        return true
    }

    private fun checkUserAndPerformSignUp() {
        val phone = binding.etPhoneNumber.text.toString().trim()
        val e164Phone = OtpProvider.otp.toE164(phone)
        val sanitizedPhone = e164Phone.replace("+", "")
        
        binding.btnContinue.isEnabled = false
        
        lifecycleScope.launch {
            try {
                val snapshot = db.child("users").child(sanitizedPhone).get().await()
                if (snapshot.exists()) {
                    showAlreadyRegisteredDialog()
                    updateSignUpButtonState()
                } else {
                    performSignUp(phone)
                }
            } catch (e: Exception) {
                Toast.makeText(this@SetupActivity, "Error checking user: ${e.message}", Toast.LENGTH_SHORT).show()
                updateSignUpButtonState()
            }
        }
    }

    private fun showAlreadyRegisteredDialog() {
        AlertDialog.Builder(this)
            .setTitle("Already Registered")
            .setMessage("This number is already registered. Please log in.")
            .setPositiveButton("Log In") { _, _ ->
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performSignUp(phone: String) {
        val firstName = binding.etFirstName.text.toString().trim()
        val surname = binding.etSurname.text.toString().trim()
        val password = binding.etPassword.text.toString()

        lifecycleScope.launch {
            val salt = generateSalt()
            val passwordHash = hashPassword(password.toCharArray(), salt)
            val saltString = Base64.encodeToString(salt, Base64.NO_WRAP)

            OtpProvider.otp.sendOtp(phone)
                .onSuccess {
                    OtpActivity.start(
                        this@SetupActivity, 
                        phone, 
                        firstName, 
                        surname, 
                        binding.cbVehicle.isChecked, 
                        passwordHash, 
                        saltString
                    )
                }
                .onFailure {
                    Toast.makeText(this@SetupActivity, "Error: ${it.message}", Toast.LENGTH_LONG).show()
                }
            updateSignUpButtonState()
        }
    }

    private fun generateSalt(): ByteArray {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        return salt
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
