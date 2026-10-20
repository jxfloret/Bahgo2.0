package com.example.bahgo20.otp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.bahgo20.HomeActivity
import com.example.bahgo20.LocationPermissionActivity
import com.example.bahgo20.R
import com.example.bahgo20.SessionManager
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class OtpActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PHONE = "phone"
        private const val EXTRA_FIRST_NAME = "first_name"
        private const val EXTRA_SURNAME = "surname"
        private const val EXTRA_HAS_VEHICLE = "has_vehicle"
        private const val EXTRA_PASSWORD_HASH = "password_hash"
        private const val EXTRA_SALT = "salt"
        private const val TAG = "LoginLog"
        private const val USER_TAG = "UserStore"
        private const val DB_URL = "https://bahgo-ee71b-default-rtdb.asia-southeast1.firebasedatabase.app/"

        fun start(
            context: Context,
            phone: String,
            firstName: String? = null,
            surname: String? = null,
            hasVehicle: Boolean = false,
            passwordHash: String? = null,
            salt: String? = null
        ) {
            val intent = Intent(context, OtpActivity::class.java).apply {
                putExtra(EXTRA_PHONE, phone)
                putExtra(EXTRA_FIRST_NAME, firstName)
                putExtra(EXTRA_SURNAME, surname)
                putExtra(EXTRA_HAS_VEHICLE, hasVehicle)
                putExtra(EXTRA_PASSWORD_HASH, passwordHash)
                putExtra(EXTRA_SALT, salt)
            }
            context.startActivity(intent)
        }
    }

    private lateinit var phone: String
    private lateinit var boxes: List<EditText>
    private lateinit var tvPhone: TextView
    private lateinit var tvError: TextView
    private lateinit var tvResend: TextView
    private lateinit var btnVerify: Button
    private lateinit var progress: ProgressBar
    private var timer: CountDownTimer? = null
    private lateinit var sessionManager: SessionManager
    private val db = FirebaseDatabase.getInstance(DB_URL).reference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_otp)

        sessionManager = SessionManager.getInstance(this)
        phone = intent.getStringExtra(EXTRA_PHONE) ?: run { finish(); return }

        boxes = listOf(
            findViewById(R.id.otp1), findViewById(R.id.otp2), findViewById(R.id.otp3),
            findViewById(R.id.otp4), findViewById(R.id.otp5), findViewById(R.id.otp6)
        )
        tvPhone = findViewById(R.id.tvPhone)
        tvError = findViewById(R.id.tvError)
        tvResend = findViewById(R.id.tvResend)
        btnVerify = findViewById(R.id.btnVerify)
        progress = findViewById(R.id.progress)

        tvPhone.text = OtpProvider.otp.toE164(phone)

        setupBoxes()
        btnVerify.setOnClickListener { verify() }
        tvResend.setOnClickListener { if (tvResend.isEnabled) resend() }

        startResendTimer()

        boxes[0].requestFocus()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    private fun setupBoxes() {
        boxes.forEachIndexed { i, box ->
            box.setSelectAllOnFocus(true)

            box.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    btnVerify.isEnabled = currentCode().length == boxes.size
                    
                    if (s.isNullOrEmpty()) return
                    hideError()
                    if (i < boxes.lastIndex) {
                        boxes[i + 1].requestFocus()
                    } else {
                        hideKeyboard()
                        if (currentCode().length == boxes.size) verify()
                    }
                }
            })

            box.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL &&
                    event.action == KeyEvent.ACTION_DOWN &&
                    box.text.isEmpty() && i > 0
                ) {
                    boxes[i - 1].apply { text.clear(); requestFocus() }
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun currentCode(): String = boxes.joinToString("") { it.text.toString() }

    private fun verify() {
        val code = currentCode()
        if (code.length < boxes.size) {
            showError("Enter all 6 digits.")
            return
        }

        setLoading(true)
        OtpProvider.otp.verifyOtp(phone, code)
            .onSuccess {
                lifecycleScope.launch {
                    val e164Phone = OtpProvider.otp.toE164(phone)
                    val isSignUp = intent.hasExtra(EXTRA_FIRST_NAME)
                    val sanitizedPhone = e164Phone.replace("+", "")
                    
                    if (isSignUp) {
                        val saved = saveUserToRTDB(sanitizedPhone)
                        if (!saved) {
                            setLoading(false)
                            return@launch
                        }
                    }

                    sessionManager.saveSession(e164Phone)
                    saveLoginToRTDB(e164Phone)
                    
                    startActivity(
                        Intent(this@OtpActivity, LocationPermissionActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                    finish()
                }
            }
            .onFailure {
                setLoading(false)
                showError(it.message ?: "Verification failed.")
                clearBoxes()
            }
    }

    private suspend fun saveUserToRTDB(sanitizedPhone: String): Boolean {
        val firstName = intent.getStringExtra(EXTRA_FIRST_NAME)
        val surname = intent.getStringExtra(EXTRA_SURNAME)
        val passwordHash = intent.getStringExtra(EXTRA_PASSWORD_HASH)
        val salt = intent.getStringExtra(EXTRA_SALT)
        val hasVehicle = intent.getBooleanExtra(EXTRA_HAS_VEHICLE, false)

        val userData = hashMapOf(
            "phoneNumber" to sanitizedPhone,
            "firstName" to firstName,
            "surname" to surname,
            "passwordHash" to passwordHash,
            "salt" to salt,
            "hasVehicle" to hasVehicle,
            "createdAt" to ServerValue.TIMESTAMP
        )

        return try {
            db.child("users").child(sanitizedPhone).setValue(userData).await()
            true
        } catch (e: Exception) {
            Log.e(USER_TAG, "Error saving user", e)
            Toast.makeText(this, "Failed to save account. Please try again.", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun saveLoginToRTDB(phoneNumber: String) {
        val loginData = hashMapOf(
            "phoneNumber" to phoneNumber,
            "loginTime" to ServerValue.TIMESTAMP,
            "status" to "SUCCESS"
        )

        db.child("logins").push().setValue(loginData)
            .addOnFailureListener { e ->
                Log.e(TAG, "RTDB error", e)
            }
    }

    private fun resend() {
        setLoading(true)
        lifecycleScope.launch {
            OtpProvider.otp.sendOtp(phone)
                .onSuccess {
                    clearBoxes()
                    hideError()
                    startResendTimer()
                }
                .onFailure { showError(it.message ?: "Could not send the code.") }
            setLoading(false)
        }
    }

    private fun startResendTimer() {
        timer?.cancel()
        tvResend.isEnabled = false
        timer = object : CountDownTimer(60_000, 1_000) {
            override fun onTick(msLeft: Long) {
                tvResend.text = getString(R.string.otp_resend_prompt, "${msLeft / 1000}s")
            }

            override fun onFinish() {
                tvResend.text = getString(R.string.otp_resend_prompt, getString(R.string.otp_resend_action))
                tvResend.isEnabled = true
            }
        }.start()
    }

    private fun clearBoxes() {
        boxes.forEach { it.text.clear() }
        boxes[0].requestFocus()
        btnVerify.isEnabled = false
    }

    private fun setLoading(loading: Boolean) {
        progress.visibility = if (loading) View.VISIBLE else View.GONE
        btnVerify.visibility = if (loading) View.INVISIBLE else View.VISIBLE
        btnVerify.isEnabled = !loading && currentCode().length == boxes.size
    }

    private fun showError(msg: String) {
        tvError.text = msg
        tvError.visibility = View.VISIBLE
    }

    private fun hideError() {
        tvError.visibility = View.INVISIBLE
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }
}
