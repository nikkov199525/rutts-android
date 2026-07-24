package ru.hlebushek.RuTTS.ui.theme

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import ru.hlebushek.RuTTS.config.AppStorage
import ru.hlebushek.RuTTS.config.PreferenceKeys

object AppTheme {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    fun applySaved(context: Context) {
        val value = PreferenceManager
            .getDefaultSharedPreferences(AppStorage.preferenceContext(context))
            .getString(PreferenceKeys.APP_THEME, SYSTEM)
        apply(value)
    }

    fun apply(value: String?) {
        AppCompatDelegate.setDefaultNightMode(nightModeFor(value))
    }

    fun nightModeFor(value: String?): Int {
        return when (value) {
            LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
    }
}
