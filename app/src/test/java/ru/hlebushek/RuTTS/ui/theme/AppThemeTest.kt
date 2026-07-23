package ru.hlebushek.RuTTS.ui.theme

import androidx.appcompat.app.AppCompatDelegate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class AppThemeTest {
    @After
    fun resetNightMode() {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    @Test
    fun nightModeForMapsStoredPreferenceValues() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppTheme.nightModeFor(AppTheme.SYSTEM))
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, AppTheme.nightModeFor(AppTheme.LIGHT))
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppTheme.nightModeFor(AppTheme.DARK))
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppTheme.nightModeFor("unexpected"))
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppTheme.nightModeFor(null))
    }

    @Test
    fun applyUpdatesAppCompatDelegateNightMode() {
        AppTheme.apply(AppTheme.DARK)
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.getDefaultNightMode())

        AppTheme.apply(AppTheme.LIGHT)
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.getDefaultNightMode())

        AppTheme.apply(AppTheme.SYSTEM)
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppCompatDelegate.getDefaultNightMode())
    }
}
