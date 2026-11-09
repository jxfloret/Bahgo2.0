package com.example.bahgo20.legal

import com.example.bahgo20.R
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.example.bahgo20.SessionManager
import com.example.bahgo20.databinding.ActivityConsentBinding
import com.example.bahgo20.utils.PermissionHelper
import kotlinx.coroutines.launch

/**
 * Data privacy consent for accounts that haven't agreed to the current documents yet
 * (e.g. existing users logging in, or after the documents change). New users agree on the
 * sign-up form instead. Shown before the permission screens and Home.
 */
class ConsentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConsentBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityConsentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.consentRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        val html = try {
            assets.open(Legal.Doc.CONSENT.asset).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
        binding.tvConsentBody.text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)
        Legal.linkify(binding.cbAgree, getString(R.string.consent_agree_text))
        Legal.linkify(binding.tvCookieNote, getString(R.string.consent_cookie_note))

        binding.btnConsentContinue.isEnabled = false
        binding.cbAgree.setOnCheckedChangeListener { _, checked -> binding.btnConsentContinue.isEnabled = checked }

        binding.btnConsentContinue.setOnClickListener {
            Legal.saveConsent(this, analytics = binding.cbAnalytics.isChecked)
            lifecycleScope.launch {
                SessionManager.getInstance(this@ConsentActivity).getPhoneNumber()
                    ?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }
                    ?.let { Legal.upload(this@ConsentActivity, it) }
                startActivity(PermissionHelper.afterSignInIntent(this@ConsentActivity))
                finish()
            }
        }
    }
}
