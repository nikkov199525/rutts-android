package ru.hlebushek.RuTTS.ui.settings

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceDialogFragmentCompat
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import ru.hlebushek.RuTTS.R
import ru.hlebushek.RuTTS.config.SettingSpecs

class NumericPreferenceDialogFragment : PreferenceDialogFragmentCompat() {
    private lateinit var valueLayout: TextInputLayout
    private lateinit var valueInput: TextInputEditText

    override fun onCreateDialogView(context: Context): View {
        return LayoutInflater.from(context).inflate(R.layout.dialog_numeric_preference, null)
    }

    override fun onBindDialogView(view: View) {
        super.onBindDialogView(view)
        val editTextPreference = preference as EditTextPreference
        valueLayout = view.findViewById(R.id.valueLayout)
        valueInput = view.findViewById(R.id.valueInput)
        valueLayout.hint = editTextPreference.title
        valueInput.inputType = InputType.TYPE_CLASS_NUMBER
        valueInput.keyListener = DigitsKeyListener.getInstance("0123456789")
        valueInput.setText(editTextPreference.text.orEmpty())
        valueInput.setSelection(valueInput.text?.length ?: 0)
        valueInput.doAfterTextChanged {
            valueLayout.error = null
        }
    }

    override fun needInputMethod(): Boolean = true

    override fun onStart() {
        super.onStart()
        val alertDialog = dialog as? AlertDialog ?: return
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            persistValueOrShowError()
        }
    }

    override fun onDialogClosed(positiveResult: Boolean) = Unit

    private fun persistValueOrShowError() {
        val editTextPreference = preference as EditTextPreference
        val spec = SettingSpecs.numericPreferences.first { it.key == editTextPreference.key }
        val parsed = valueInput.text?.toString()?.trim()?.toIntOrNull()
        if (parsed == null || parsed !in spec.minValue..spec.maxValue) {
            valueLayout.error = getString(R.string.settings_invalid_number, spec.minValue, spec.maxValue)
            return
        }

        val newValue = parsed.toString()
        if (editTextPreference.callChangeListener(newValue)) {
            editTextPreference.text = newValue
            dismiss()
        }
    }

    companion object {
        fun newInstance(key: String): NumericPreferenceDialogFragment {
            return NumericPreferenceDialogFragment().apply {
                arguments = Bundle(1).apply {
                    putString("key", key)
                }
            }
        }
    }
}
