package ru.hlebushek.RuTTS.ui.settings

import android.os.Bundle
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ru.hlebushek.RuTTS.R
import ru.hlebushek.RuTTS.config.ConfigRepository
import ru.hlebushek.RuTTS.config.DictionaryKind
import ru.hlebushek.RuTTS.config.PreferenceKeys
import ru.hlebushek.RuTTS.config.SettingSpecs
import ru.hlebushek.RuTTS.ui.theme.AppTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RuttsSettingsFragment : PreferenceFragmentCompat() {
    private lateinit var repository: ConfigRepository
    private val createBackupDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument(SETTINGS_MIME_TYPE)
    ) { uri ->
        if (uri != null) saveSettingsBackup(uri)
    }
    private val openBackupDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) restoreSettingsBackup(uri)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        repository = ConfigRepository(requireContext())
        setPreferencesFromResource(R.xml.rutts_preferences, rootKey)
        configurePreferences()
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        val isNumericPreference = preference is EditTextPreference &&
            SettingSpecs.numericPreferences.any { it.key == preference.key }
        if (isNumericPreference) {
            if (parentFragmentManager.findFragmentByTag(NUMERIC_DIALOG_TAG) != null) {
                return
            }
            NumericPreferenceDialogFragment.newInstance(preference.key).apply {
                setTargetFragment(this@RuttsSettingsFragment, 0)
            }.show(parentFragmentManager, NUMERIC_DIALOG_TAG)
            return
        }
        super.onDisplayPreferenceDialog(preference)
    }

    private fun configurePreferences() {
        SettingSpecs.numericPreferences.forEach { spec ->
            val preference = findPreference<EditTextPreference>(spec.key) ?: return@forEach
            preference.setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_NUMBER
                editText.keyListener = DigitsKeyListener.getInstance("0123456789")
                editText.setSelectAllOnFocus(true)
            }
        }

        findPreference<Preference>("edit_characters")?.setOnPreferenceClickListener {
            openDictionary(DictionaryKind.CHARACTERS)
            true
        }
        findPreference<Preference>("edit_single_characters")?.setOnPreferenceClickListener {
            openDictionary(DictionaryKind.SINGLE_CHARACTERS)
            true
        }
        findPreference<Preference>("open_equalizer_settings")?.setOnPreferenceClickListener {
            startActivity(EqualizerSettingsActivity.createIntent(requireContext()))
            true
        }
        findPreference<Preference>("apply_speaking_mouse_profile")?.setOnPreferenceClickListener {
            repository.applySpeakingMouseProfile()
            reloadPreferences()
            Toast.makeText(requireContext(), R.string.settings_speaking_mouse_applied, Toast.LENGTH_SHORT).show()
            true
        }
        findPreference<ListPreference>(PreferenceKeys.APP_THEME)?.setOnPreferenceChangeListener { _, newValue ->
            AppTheme.apply(newValue as? String)
            true
        }
        findPreference<Preference>("restore_defaults")?.setOnPreferenceClickListener {
            confirmResetAll()
            true
        }
        findPreference<Preference>("backup_settings")?.setOnPreferenceClickListener {
            createBackupDocument.launch(backupFileName())
            true
        }
        findPreference<Preference>("restore_settings")?.setOnPreferenceClickListener {
            openBackupDocument.launch(arrayOf("*/*"))
            true
        }
    }

    private fun openDictionary(kind: DictionaryKind) {
        startActivity(DictionaryEditorActivity.createIntent(requireContext(), kind))
    }

    private fun confirmResetAll() {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.settings_restore_defaults_message)
            .setNegativeButton(R.string.dictionary_cancel, null)
            .setPositiveButton(R.string.settings_restore_defaults) { _, _ ->
                repository.resetAll()
                AppTheme.applySaved(requireContext())
                reloadPreferences()
            }
            .show()
    }

    private fun saveSettingsBackup(uri: android.net.Uri) {
        val saved = runCatching {
            val output = requireContext().contentResolver.openOutputStream(uri)
                ?: error("Unable to open backup destination")
            output.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(repository.createSettingsBackup())
            }
        }.isSuccess
        Toast.makeText(
            requireContext(),
            if (saved) R.string.settings_backup_done else R.string.settings_backup_failed,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun restoreSettingsBackup(uri: android.net.Uri) {
        val restored = runCatching {
            val input = requireContext().contentResolver.openInputStream(uri)
                ?: error("Unable to open settings backup")
            val contents = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
            repository.restoreSettingsBackup(contents)
        }.isSuccess
        if (restored) {
            AppTheme.applySaved(requireContext())
            reloadPreferences()
        }
        Toast.makeText(
            requireContext(),
            if (restored) R.string.settings_restore_done else R.string.settings_restore_failed,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun backupFileName(): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.ROOT).format(Date())
        return "ruttsbackup_$timestamp.settings"
    }

    private fun reloadPreferences() {
        preferenceScreen.removeAll()
        setPreferencesFromResource(R.xml.rutts_preferences, null)
        configurePreferences()
    }

    private companion object {
        const val NUMERIC_DIALOG_TAG = "numeric_preference_dialog"
        const val SETTINGS_MIME_TYPE = "application/octet-stream"
    }
}
