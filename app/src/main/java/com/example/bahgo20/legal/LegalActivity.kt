package com.example.bahgo20.legal

import com.example.bahgo20.R
import android.os.Bundle
import android.text.method.LinkMovementMethod
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.bahgo20.databinding.ActivityLegalBinding

/**
 * Shows one legal document (Privacy Policy, Terms, Cookie Policy or consent form) from
 * assets/legal. Plain text rendering, no web view: nothing is loaded from the internet.
 */
class LegalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLegalBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityLegalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.legalRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        val doc = Legal.Doc.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_DOC) } ?: Legal.Doc.PRIVACY
        binding.tvLegalTitle.text = doc.title
        val html = try {
            assets.open(doc.asset).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "<p>" + getString(R.string.legal_open_failed) + "</p>"
        }
        binding.tvLegalBody.text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)
        binding.tvLegalBody.movementMethod = LinkMovementMethod.getInstance()
        binding.ivLegalBack.setOnClickListener { finish() }
    }

    companion object {
        const val EXTRA_DOC = "LEGAL_DOC"
    }
}
