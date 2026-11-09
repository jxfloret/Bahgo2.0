package com.example.bahgo20

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.bahgo20.auth.AuthApi
import com.example.bahgo20.databinding.ActivitySettingsBinding
import com.example.bahgo20.legal.Legal
import com.example.bahgo20.utils.AccountRepository
import com.example.bahgo20.utils.AppLanguage
import com.example.bahgo20.utils.OnboardingManager
import com.example.bahgo20.utils.Phone
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Settings, in the usual order:
 *   profile -> Account (edit name, mobile number) -> Privacy & Security (change password,
 *   change phone number) -> Preferences (appearance, Person/Car) -> Notifications (flood alerts)
 *   -> About (app tour, about) -> Log out.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var sessionManager: SessionManager
    private val db = FirebaseDatabase
        .getInstance("https://bahgo-ee71b-default-rtdb.asia-southeast1.firebasedatabase.app/")
        .reference

    // Person/Car mode is kept on the device so Home and Conditions update instantly
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    private var modeTouched = false

    /** The signed-in account's key in /users (digits, e.g. 639171234567), or null for a guest. */
    private var phoneKey: String? = null
    private var profile: AccountRepository.Profile? = null

    private companion object {
        const val TAG = "Settings"
        const val PREFS_NAME = "bahgo_monitor_prefs"
        const val KEY_HAS_VEHICLE = "has_vehicle"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager.getInstance(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom)
            insets
        }

        binding.ivBack.setOnClickListener { finish() }

        setupClickListeners()
        refreshSettingsData()
    }

    private fun setupClickListeners() {
        // Account
        binding.rowName.setOnClickListener { editName() }
        binding.rowPhone.setOnClickListener { changePhone() }

        // Privacy & Security
        binding.rowChangePassword.setOnClickListener { changePassword() }
        binding.rowChangePhone.setOnClickListener { changePhone() }

        // Privacy: optional usage statistics (Google Analytics for Firebase), off unless agreed
        binding.switchAnalytics.isChecked = Legal.analyticsAllowed(this)
        binding.switchAnalytics.setOnCheckedChangeListener { _, on ->
            if (!Legal.hasConsent(this)) Legal.saveConsent(this, on)
            Legal.setAnalytics(this, on, phoneKey)
        }

        // Preferences: appearance
        binding.btnLightMode.setOnClickListener {
            sessionManager.setThemeMode(1)
            updateAppearanceUi(1)
        }
        binding.btnDarkMode.setOnClickListener {
            sessionManager.setThemeMode(2)
            updateAppearanceUi(2)
        }

        // Preferences: language (the screen reopens in the new language)
        binding.btnEnglish.setOnClickListener { setLanguage(AppLanguage.ENGLISH) }
        binding.btnFilipino.setOnClickListener { setLanguage(AppLanguage.FILIPINO) }

        // Preferences: object to monitor
        binding.btnPersonMode.setOnClickListener { saveMonitorPreference(false) }
        binding.btnCarMode.setOnClickListener { saveMonitorPreference(true) }

        // Notifications: flood alerts
        binding.switchFloodAlerts.isChecked = FloodAlerts.isEnabled(this)
        binding.switchFloodAlerts.setOnCheckedChangeListener { _, checked ->
            FloodAlerts.setEnabled(this, checked)
            // Re-checks the notification permission when turning alerts on
            if (checked) FloodAlerts.setup(this)
        }

        // Legal documents
        binding.rowPrivacyPolicy.setOnClickListener { Legal.open(this, Legal.Doc.PRIVACY) }
        binding.rowTerms.setOnClickListener { Legal.open(this, Legal.Doc.TERMS) }
        binding.rowCookiePolicy.setOnClickListener { Legal.open(this, Legal.Doc.COOKIES) }

        // About
        binding.btnAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        // Replay the Home tour: back on Home it starts again
        binding.btnAppTour.setOnClickListener {
            OnboardingManager(this).setHomeTourDone(false)
            startActivity(
                Intent(this, HomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            finish()
        }

        // Log out (only here)
        binding.btnLogout.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_logout_title)
                .setMessage(R.string.settings_logout_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.settings_logout_confirm) { _, _ -> logOut() }
                .show()
        }
    }

    private fun logOut() {
        // Stop alerts and forget the saved Person/Car choice so the next account starts fresh
        FloodAlerts.unsubscribe()
        FloodAlerts.stopMonitor(this)   // no flood notifications after logging out
        prefs.edit().remove(KEY_HAS_VEHICLE).apply()
        sessionManager.logout()
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    // ───────────────────────── Loading ─────────────────────────

    private fun refreshSettingsData() {
        // Show the saved choices immediately, before any network call
        updateMonitorUi(prefs.getBoolean(KEY_HAS_VEHICLE, false))
        updateAppearanceUi(sessionManager.getThemeMode())
        updateLanguageUi()

        lifecycleScope.launch {
            val phone = try { sessionManager.getPhoneNumber() } catch (e: Exception) { null }
            phoneKey = phone?.filter { it.isDigit() }?.ifEmpty { null }
            showProfile()

            val key = phoneKey ?: return@launch
            try {
                profile = AccountRepository.loadProfile(key)
                showProfile()
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't load the account", e)
            }

            // Only seed Person/Car from Firebase if this phone has no saved choice yet
            if (prefs.contains(KEY_HAS_VEHICLE)) return@launch
            try {
                val snapshot = db.child("users").child(key).child("hasVehicle").get().await()
                if (!modeTouched && !prefs.contains(KEY_HAS_VEHICLE)) {
                    val remote = snapshot.getValue(Boolean::class.java) ?: false
                    prefs.edit().putBoolean(KEY_HAS_VEHICLE, remote).apply()
                    updateMonitorUi(remote)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Mode sync failed", e)
            }
        }
    }

    private fun showProfile() {
        val p = profile
        val name = p?.let { "${it.firstName} ${it.surname}".trim() }?.ifEmpty { null }
        val phoneText = phoneKey?.let { formatPhone(it) } ?: getString(R.string.settings_not_signed_in)
        binding.tvProfileName.text = name ?: getString(if (phoneKey == null) R.string.settings_guest else R.string.settings_your_account)
        binding.tvProfilePhone.text = phoneText
        binding.tvAvatar.text = initials(p)
        binding.tvNameValue.text = name ?: getString(R.string.settings_add_your_name)
        binding.tvPhoneValue.text = phoneText
    }

    private fun initials(p: AccountRepository.Profile?): String {
        val a = p?.firstName?.firstOrNull()?.uppercaseChar()
        val b = p?.surname?.firstOrNull()?.uppercaseChar()
        return listOfNotNull(a, b).joinToString("").ifEmpty { "?" }
    }

    /** 639171234567 -> +63 917 123 4567 */
    private fun formatPhone(key: String): String =
        if (key.length == 12 && key.startsWith("63")) "+63 ${key.substring(2, 5)} ${key.substring(5, 8)} ${key.substring(8)}"
        else "+$key"

    // ───────────────────────── Account: name ─────────────────────────

    private fun editName() {
        val key = phoneKey ?: return notSignedIn()
        val (first, firstBox) = field(getString(R.string.settings_first_name), InputType.TYPE_TEXT_FLAG_CAP_WORDS, profile?.firstName)
        val (last, lastBox) = field(getString(R.string.surname_label), InputType.TYPE_TEXT_FLAG_CAP_WORDS, profile?.surname)
        formDialog(getString(R.string.settings_edit_name), listOf(firstBox, lastBox), getString(R.string.settings_save)) { dialog ->
            val f = first.text.toString().trim()
            val s = last.text.toString().trim()
            firstBox.error = if (f.isEmpty()) getString(R.string.settings_enter_first_name) else null
            lastBox.error = if (s.isEmpty()) getString(R.string.settings_enter_surname) else null
            if (f.isEmpty() || s.isEmpty()) return@formDialog
            lifecycleScope.launch {
                try {
                    AccountRepository.updateName(key, f, s)
                    profile = AccountRepository.Profile(f, s)
                    showProfile()
                    toast(getString(R.string.settings_name_updated))
                    dialog.dismiss()
                } catch (e: Exception) {
                    Log.e(TAG, "Name update failed", e)
                    toast(getString(R.string.settings_save_failed))
                }
            }
        }
    }

    // ───────────────────────── Privacy: password ─────────────────────────

    private fun changePassword() {
        phoneKey ?: return notSignedIn()
        val (current, currentBox) = field(getString(R.string.settings_current_password), InputType.TYPE_TEXT_VARIATION_PASSWORD, password = true)
        val (new, newBox) = field(getString(R.string.settings_new_password), InputType.TYPE_TEXT_VARIATION_PASSWORD, password = true)
        val (confirm, confirmBox) = field(getString(R.string.settings_confirm_new_password), InputType.TYPE_TEXT_VARIATION_PASSWORD, password = true)
        newBox.helperText = getString(R.string.settings_password_rules)
        formDialog(getString(R.string.settings_change_password_title), listOf(currentBox, newBox, confirmBox), getString(R.string.settings_save)) { dialog ->
            val cur = current.text.toString()
            val pw = new.text.toString()
            listOf(currentBox, newBox, confirmBox).forEach { it.error = null }
            if (cur.isEmpty()) { currentBox.error = getString(R.string.settings_enter_current_password); return@formDialog }
            AccountRepository.passwordProblem(pw)?.let { newBox.error = getString(it); return@formDialog }
            if (pw == cur) { newBox.error = getString(R.string.settings_password_same); return@formDialog }
            if (confirm.text.toString() != pw) { confirmBox.error = getString(R.string.settings_password_mismatch); return@formDialog }
            lifecycleScope.launch {
                // Checked and saved on the server
                AuthApi.changePassword(cur, pw)
                    .onSuccess { toast(getString(R.string.settings_password_changed)); dialog.dismiss() }
                    .onFailure {
                        Log.w(TAG, "Password change failed: ${it.message}")
                        if (it.message?.contains("Current password") == true) currentBox.error = it.message
                        else toast(it.message ?: getString(R.string.settings_password_change_failed))
                    }
            }
        }
    }

    // ───────────────────────── Privacy: phone number ─────────────────────────

    /** Step 1: new number + current password. Step 2: the code sent to the new number. */
    private fun changePhone() {
        val oldKey = phoneKey ?: return notSignedIn()
        val (number, numberBox) = field(getString(R.string.settings_new_mobile_number), InputType.TYPE_CLASS_PHONE)
        val (pass, passBox) = field(getString(R.string.settings_current_password), InputType.TYPE_TEXT_VARIATION_PASSWORD, password = true)
        numberBox.helperText = getString(R.string.settings_phone_example)
        formDialog(getString(R.string.settings_change_phone_title), listOf(numberBox, passBox), getString(R.string.settings_send_code)) { dialog ->
            val input = number.text.toString().trim()
            numberBox.error = null
            passBox.error = null
            if (input.filter { it.isDigit() }.length < 10) { numberBox.error = getString(R.string.settings_enter_valid_number); return@formDialog }
            val newKey = Phone.keyOf(input)
            if (newKey == oldKey) { numberBox.error = getString(R.string.settings_same_number); return@formDialog }
            val password = pass.text.toString()
            if (password.isEmpty()) { passBox.error = getString(R.string.settings_enter_password); return@formDialog }
            lifecycleScope.launch {
                // The server also checks the new number isn't registered yet
                AuthApi.sendOtp(input, "changePhone")
                    .onSuccess {
                        dialog.dismiss()
                        confirmNewPhone(input, newKey, password)
                    }
                    .onFailure { numberBox.error = it.message ?: getString(R.string.settings_send_code_failed) }
            }
        }
    }

    private fun confirmNewPhone(input: String, newKey: String, password: String) {
        val (code, codeBox) = field(getString(R.string.settings_six_digit_code), InputType.TYPE_CLASS_NUMBER)
        codeBox.helperText = getString(R.string.settings_code_sent_to, formatPhone(newKey))
        formDialog(getString(R.string.settings_enter_the_code), listOf(codeBox), getString(R.string.settings_confirm)) { dialog ->
            codeBox.error = null
            val entered = code.text.toString().trim()
            if (entered.length != 6) { codeBox.error = getString(R.string.settings_enter_six_digits); return@formDialog }
            lifecycleScope.launch {
                // The server checks the password and code, moves the account and signs us in as the new number
                AuthApi.changePhone(password, input, entered)
                    .onSuccess { e164 ->
                        sessionManager.saveSession(e164.ifEmpty { Phone.toE164(input) }, prefs.getBoolean(KEY_HAS_VEHICLE, false))
                        phoneKey = newKey
                        showProfile()
                        toast(getString(R.string.settings_number_changed, formatPhone(newKey)))
                        dialog.dismiss()
                    }
                    .onFailure {
                        Log.w(TAG, "Phone change failed: ${it.message}")
                        codeBox.error = it.message ?: getString(R.string.settings_number_change_failed)
                    }
            }
        }
    }

    // ───────────────────────── Dialog helpers ─────────────────────────

    private fun field(
        hint: String, inputType: Int, value: String? = null, password: Boolean = false
    ): Pair<TextInputEditText, TextInputLayout> {
        val box = TextInputLayout(this).apply {
            this.hint = hint
            if (password) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
        }
        val edit = TextInputEditText(box.context).apply {
            this.inputType = if (password) InputType.TYPE_CLASS_TEXT or inputType
            else if (inputType and InputType.TYPE_MASK_CLASS == 0) InputType.TYPE_CLASS_TEXT or inputType else inputType
            setText(value.orEmpty())
            maxLines = 1
            typeface = android.graphics.Typeface.DEFAULT   // password fields otherwise use a typewriter font
        }
        box.addView(edit)
        return edit to box
    }

    /** A dialog with input fields; [onConfirm] decides when to close it (errors keep it open). */
    private fun formDialog(title: String, boxes: List<TextInputLayout>, confirm: String, onConfirm: (AlertDialog) -> Unit) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            boxes.forEach { addView(it) }
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            // Scrolls, so a field the keyboard would cover can be scrolled into view
            .setView(ScrollView(this).apply { addView(container) })
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(confirm, null)
            .create()
        // The dialog shrinks above the keyboard instead of being covered by it
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { onConfirm(dialog) }
        }
        dialog.show()
    }

    private fun notSignedIn() = toast(getString(R.string.settings_log_in_first))

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    // ───────────────────────── Preferences ─────────────────────────

    private fun saveMonitorPreference(hasVehicle: Boolean) {
        modeTouched = true
        // Save locally first so Home and Conditions pick it up as soon as you go back
        prefs.edit().putBoolean(KEY_HAS_VEHICLE, hasVehicle).apply()
        updateMonitorUi(hasVehicle)

        lifecycleScope.launch {
            val key = phoneKey ?: return@launch
            try {
                db.child("users").child(key).child("hasVehicle").setValue(hasVehicle).await()
                Toast.makeText(this@SettingsActivity, getString(R.string.settings_preference_saved), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Firebase save failed", e)
                Toast.makeText(this@SettingsActivity, getString(R.string.settings_sync_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setLanguage(language: String) {
        if (AppLanguage.current(this) == language) return
        AppLanguage.set(this, language)   // every open screen is recreated in the new language
        updateLanguageUi()
    }

    private fun updateLanguageUi() {
        val filipino = AppLanguage.isFilipino(this)
        applyToggleStyle(binding.btnEnglish, selected = !filipino)
        applyToggleStyle(binding.btnFilipino, selected = filipino)
    }

    private fun updateAppearanceUi(mode: Int) {
        applyToggleStyle(binding.btnLightMode, selected = (mode == 1))
        applyToggleStyle(binding.btnDarkMode, selected = (mode == 2))
    }

    private fun updateMonitorUi(hasVehicle: Boolean) {
        applyToggleStyle(binding.btnPersonMode, selected = !hasVehicle)
        applyToggleStyle(binding.btnCarMode, selected = hasVehicle)
        binding.tvModeDescription.text = if (hasVehicle)
            getString(R.string.settings_mode_desc_car)
        else
            getString(R.string.settings_mode_desc_person)
    }

    private fun applyToggleStyle(button: com.google.android.material.button.MaterialButton, selected: Boolean) {
        val bg = if (selected) ContextCompat.getColor(this, R.color.toggle_selected_bg) else Color.TRANSPARENT
        val fg = ContextCompat.getColor(this, if (selected) R.color.toggle_selected_text else R.color.toggle_unselected_text)

        button.backgroundTintList = ColorStateList.valueOf(bg)
        button.setTextColor(fg)
        button.iconTint = ColorStateList.valueOf(fg)

        button.strokeWidth = if (selected) 0 else dp(1)
        if (!selected) {
            button.strokeColor = ColorStateList.valueOf(fg)
        }
    }
}
