package ru.hlebushek.RuTTS.ui.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import ru.hlebushek.RuTTS.R
import ru.hlebushek.RuTTS.engine.RuttsTtsContract

class EngineDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (intent.action) {
            TextToSpeech.Engine.ACTION_CHECK_TTS_DATA -> handleCheckVoiceData()
            TextToSpeech.Engine.ACTION_GET_SAMPLE_TEXT -> handleSampleText()
            TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA -> handleInstallData()
        }

        finish()
    }

    private fun handleCheckVoiceData() {
        val result = Intent().apply {
            putStringArrayListExtra(
                TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES,
                arrayListOf(RuttsTtsContract.checkVoiceId)
            )
            putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, arrayListOf())
        }
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, result)
    }

    private fun handleSampleText() {
        val result = Intent().apply {
            putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, getString(R.string.tts_sample_text))
        }
        setResult(TextToSpeech.LANG_COUNTRY_AVAILABLE, result)
    }

    private fun handleInstallData() {
        startActivity(
            Intent(this, EngineSettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS)
    }
}
