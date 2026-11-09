package com.example.bahgo20

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.bahgo20.utils.bottomAboveKeyboard
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.example.bahgo20.auth.AuthApi
import com.example.bahgo20.databinding.ActivitySetupBinding
import com.example.bahgo20.otp.OtpActivity
import kotlinx.coroutines.launch

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        enableEdgeToEdge()
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.setupMain) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.tvSetupTitle.updatePadding(top = systemBars.top)
            // Keyboard open: shrink the screen so the scroll view can bring the field above it
            v.updatePadding(bottom = insets.bottomAboveKeyboard())
            insets
        }

        setupTextWatchers()
        updateSignUpButtonState()

        // Data privacy consent: required to sign up; usage statistics are optional
        com.example.bahgo20.legal.Legal.linkify(binding.cbConsent,
            getString(R.string.setup_consent_text))
        binding.cbConsent.setOnCheckedChangeListener { _, _ -> updateSignUpButtonState() }

        binding.ivBack.setOnClickListener { finish() }

        binding.tvAlreadyHaveAccount.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        binding.btnContinue.setOnClickListener {
            if (validateInputs()) {
                com.example.bahgo20.legal.Legal.saveConsent(this, analytics = binding.cbAnalyticsSignup.isChecked)
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
        binding.btnContinue.isEnabled = isSignUpFormComplete() && binding.cbConsent.isChecked
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
            Toast.makeText(this, R.string.setup_enter_name, Toast.LENGTH_SHORT).show()
            return false
        }

        if (phone.length < 10) {
            Toast.makeText(this, R.string.setup_enter_valid_phone, Toast.LENGTH_SHORT).show()
            return false
        }

        if (password.length < 8) {
            binding.tilPassword.error = getString(R.string.setup_password_too_short)
            return false
        }

        if (!password.any { it.isDigit() }) {
            binding.tilPassword.error = getString(R.string.setup_password_needs_number)
            return false
        }

        if (!password.any { !it.isLetterOrDigit() }) {
            binding.tilPassword.error = getString(R.string.setup_password_needs_special)
            return false
        }

        return true
    }

    /** Asks the server to text a code (it also checks the number isn't registered yet). */
    private fun checkUserAndPerformSignUp() {
        val phone = binding.etPhoneNumber.text.toString().trim()
        val firstName = binding.etFirstName.text.toString().trim()
        val surname = binding.etSurname.text.toString().trim()
        val password = binding.etPassword.text.toString()

        binding.btnContinue.isEnabled = false

        lifecycleScope.launch {
            AuthApi.sendOtp(phone, "signup")
                .onSuccess {
                    // The password only travels in memory to the code screen, which sends it with the code
                    OtpActivity.start(this@SetupActivity, phone, firstName, surname, binding.cbVehicle.isChecked, password)
                }
                .onFailure { e ->
                    if ((e as? AuthApi.AuthException)?.alreadyRegistered == true) showAlreadyRegisteredDialog()
                    else Toast.makeText(this@SetupActivity, e.message ?: getString(R.string.otp_send_failed_short), Toast.LENGTH_LONG).show()
                }
            updateSignUpButtonState()
        }
    }

    private fun showAlreadyRegisteredDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.setup_already_registered_title)
            .setMessage(R.string.setup_already_registered_message)
            .setPositiveButton(R.string.log_in) { _, _ ->
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
