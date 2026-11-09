package com.example.bahgo20.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * The app's language: English or Filipino (Settings > Preferences > Language).
 *
 * Uses Android's per-app language, so every screen switches right away. The choice is also
 * kept in our own prefs so notifications from the background monitor (no screen open) use it.
 */
object AppLanguage {

    const val ENGLISH = "en"
    const val FILIPINO = "fil"

    private const val PREFS = "bahgo_language"
    private const val KEY = "language"

    /** "en" or "fil": the picked language, or the phone's language if none was picked. */
    fun current(context: Context): String {
        val picked = AppCompatDelegate.getApplicationLocales()[0]?.language
            ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: context.resources.configuration.locales[0].language
        return if (isFilipinoCode(picked)) FILIPINO else ENGLISH
    }

    fun isFilipino(context: Context) = current(context) == FILIPINO

    /** Switches the whole app; open screens are recreated in the new language. */
    fun set(context: Context, language: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language).apply()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language))
    }

    /**
     * A context whose text is in the app's language, for code that runs without a screen
     * (notifications). Android 13+ already does this; older phones need the language applied.
     */
    fun localized(context: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return context
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return context
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale.forLanguageTag(saved))
        return context.createConfigurationContext(config)
    }

    private fun isFilipinoCode(code: String) = code == "fil" || code == "tl"
}
