package ru.hlebushek.RuTTS.ui.settings

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import ru.hlebushek.RuTTS.R
import ru.hlebushek.RuTTS.config.ConfigRepository

class EqualizerSettingsFragment : PreferenceFragmentCompat() {
    private lateinit var repository: ConfigRepository

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // См. AppStorage: то же device-protected хранилище, что и у сервиса.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            preferenceManager.setStorageDeviceProtected()
        }
        repository = ConfigRepository(requireContext())
        setPreferencesFromResource(R.xml.equalizer_preferences, rootKey)
        configurePreferences()
    }

    private fun configurePreferences() {
        findPreference<Preference>("reset_equalizer")?.setOnPreferenceClickListener {
            repository.resetEqualizerSettings()
            reloadPreferences()
            Toast.makeText(requireContext(), R.string.equalizer_reset_done, Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun reloadPreferences() {
        preferenceScreen.removeAll()
        setPreferencesFromResource(R.xml.equalizer_preferences, null)
        configurePreferences()
    }
}
