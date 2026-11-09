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
import com.example.bahgo20.R
import com.example.bahgo20.SessionManager
import com.example.bahgo20.utils.PermissionHelper
import com.example.bahgo20.auth.AuthApi
import com.example.bahgo20.legal.Legal
import com.example.bahgo20.utils.Phone
import kotlinx.coroutines.launch

class OtpActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PHONE = "phone"
        private const val EXTRA_FIRST_NAME = "first_name"
        private const val EXTRA_SURNAME = "surname"
        private const val EXTRA_HAS_VEHICLE = "has_vehicle"
        private const val TAG = "BahGoSignUp"

        /**
         * The new password, kept only in memory (never in the Intent or on disk) until the
         * code is confirmed and it's sent to the server with it.
         */
        private var pendingPassword: String? = null

        fun start(
            context: Context,
            phone: String,
            firstName: String,
            surname: String,
            hasVehicle: Boolean,
            password: String
        ) {
            pendingPassword = password
            val intent = Intent(context, OtpActivity::class.java).apply {
                putExtra(EXTRA_PHONE, phone)
                putExtra(EXTRA_FIRST_NAME, firstName)
                putExtra(EXTRA_SURNAME, surname)
                putExtra(EXTRA_HAS_VEHICLE, hasVehicle)
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

        // Back button
        findViewById<android.view.View>(R.id.ivBack).setOnClickListener { finish() }

        tvPhone.text = Phone.toE164(phone)

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
            showError(getString(R.string.otp_enter_all_digits))
            return
        }

        val password = pendingPassword
        if (password == null) {
            // The app was closed in between, so the password is gone: start the sign-up again
            Toast.makeText(this, R.string.otp_fill_form_again, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setLoading(true)
        lifecycleScope.launch {
            // The server checks the code, creates the account (with the consent given on the
            // sign-up form) and signs us in
            AuthApi.signUp(
                phone = phone,
                code = code,
                firstName = intent.getStringExtra(EXTRA_FIRST_NAME).orEmpty(),
                surname = intent.getStringExtra(EXTRA_SURNAME).orEmpty(),
                password = password,
                hasVehicle = intent.getBooleanExtra(EXTRA_HAS_VEHICLE, false),
                consentVersion = if (Legal.hasConsent(this@OtpActivity)) Legal.VERSION else null,
                analytics = Legal.analyticsAllowed(this@OtpActivity)
            ).onSuccess { e164 ->
                pendingPassword = null
                sessionManager.saveSession(e164.ifEmpty { Phone.toE164(phone) }, intent.getBooleanExtra(EXTRA_HAS_VEHICLE, false))
                // Location, then notifications (screens for permissions already allowed are skipped)
                startActivity(PermissionHelper.afterSignInIntent(this@OtpActivity))
                finish()
            }.onFailure {
                Log.w(TAG, "Sign-up failed: ${it.message}")
                setLoading(false)
                showError(it.message ?: getString(R.string.otp_verification_failed))
                clearBoxes()
            }
        }
    }

    private fun resend() {
        setLoading(true)
        lifecycleScope.launch {
            AuthApi.sendOtp(phone, "signup")
                .onSuccess {
                    clearBoxes()
                    hideError()
                    startResendTimer()
                }
                .onFailure { showError(it.message ?: getString(R.string.otp_send_failed)) }
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
