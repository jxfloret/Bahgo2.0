package com.example.bahgo20.legal

import android.content.Context
import android.content.Intent
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Log
import android.view.View
import android.widget.TextView
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue

/**
 * Privacy Policy, Terms, Cookie Policy and the consent form (HTML files in assets/legal),
 * plus the user's consent:
 *   - required: agree to the Privacy Policy + Terms (needed to use an account)
 *   - optional: usage statistics (Google Analytics for Firebase). Analytics is OFF by default
 *     (see the manifest) and only turned on when the user agrees.
 * Consent is kept on the phone and, when signed in, in /users/{phone}/consent.
 */
object Legal {
    /** Bump this when the documents change in an important way: everyone is asked again. */
    const val VERSION = "2026-10-29"

    enum class Doc(val title: String, val asset: String) {
        PRIVACY("Privacy Policy", "legal/privacy_policy.html"),
        TERMS("Terms and Conditions", "legal/terms.html"),
        COOKIES("Cookie Policy", "legal/cookie_policy.html"),
        CONSENT("Data Privacy Consent", "legal/consent.html")
    }

    private const val TAG = "Legal"
    private const val PREFS = "bahgo_legal"
    private const val KEY_VERSION = "consent_version"
    private const val KEY_ANALYTICS = "analytics_consent"
    private const val DB_URL = "https://bahgo-ee71b-default-rtdb.asia-southeast1.firebasedatabase.app/"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True if the user agreed to the current Privacy Policy and Terms on this phone. */
    fun hasConsent(c: Context): Boolean = prefs(c).getString(KEY_VERSION, null) == VERSION

    fun analyticsAllowed(c: Context): Boolean = hasConsent(c) && prefs(c).getBoolean(KEY_ANALYTICS, false)

    /** Saves the user's answers on the phone and switches analytics on or off to match. */
    fun saveConsent(c: Context, analytics: Boolean) {
        prefs(c).edit().putString(KEY_VERSION, VERSION).putBoolean(KEY_ANALYTICS, analytics).apply()
        applyAnalytics(c)
    }

    /** Changes only the optional usage-statistics choice (Settings switch). */
    fun setAnalytics(c: Context, allowed: Boolean, phoneKey: String?) {
        prefs(c).edit().putBoolean(KEY_ANALYTICS, allowed).apply()
        applyAnalytics(c)
        phoneKey?.let { upload(c, it) }
    }

    /** Analytics stays off unless the user agreed (call at app start and after any change). */
    fun applyAnalytics(c: Context) {
        try {
            FirebaseAnalytics.getInstance(c).setAnalyticsCollectionEnabled(analyticsAllowed(c))
        } catch (e: Exception) {
            Log.w(TAG, "Analytics setting not applied", e)
        }
    }

    /** Records the consent in the user's account: version, time, and the analytics choice. */
    fun upload(c: Context, phoneKey: String) {
        if (!hasConsent(c)) return
        try {
            FirebaseDatabase.getInstance(DB_URL).reference
                .child("users").child(phoneKey).child("consent")
                .setValue(
                    mapOf(
                        "version" to VERSION,
                        "analytics" to analyticsAllowed(c),
                        "acceptedAt" to ServerValue.TIMESTAMP
                    )
                )
                .addOnFailureListener { Log.w(TAG, "Consent not saved to the account", it) }
        } catch (e: Exception) {
            Log.w(TAG, "Consent upload failed", e)
        }
    }

    fun open(c: Context, doc: Doc) {
        c.startActivity(Intent(c, LegalActivity::class.java).putExtra(LegalActivity.EXTRA_DOC, doc.name))
    }

    /**
     * Turns the words "Privacy Policy", "Terms and Conditions" and "Cookie Policy" in [text]
     * into links that open the documents.
     */
    fun linkify(view: TextView, text: String) {
        val s = SpannableStringBuilder(text)
        for ((word, doc) in listOf(
            "Privacy Policy" to Doc.PRIVACY,
            "Terms and Conditions" to Doc.TERMS,
            "Cookie Policy" to Doc.COOKIES,
            "consent form" to Doc.CONSENT
        )) {
            val i = text.indexOf(word)
            if (i < 0) continue
            s.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) = open(widget.context, doc)
            }, i, i + word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        view.text = s
        view.movementMethod = LinkMovementMethod.getInstance()
    }
}
