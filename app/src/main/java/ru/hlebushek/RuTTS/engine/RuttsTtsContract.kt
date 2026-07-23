package ru.hlebushek.RuTTS.engine

import android.speech.tts.TextToSpeech
import java.util.Locale
import ru.hlebushek.RuTTS.config.RuttsConfig

object RuttsTtsContract {
    val locale: Locale = Locale("ru", "RU")
    val voiceName: String = "${locale.language}-${locale.country}"
    val checkVoiceId: String = "${locale.getISO3Language()}-${locale.getISO3Country()}"

    val iso3Language: String = locale.getISO3Language()
    val iso3Country: String = locale.getISO3Country()

    @Suppress("UNUSED_PARAMETER")
    fun shouldUseDirectStreaming(config: RuttsConfig, equalizerIsFlat: Boolean): Boolean {
        return true
    }

    fun languageAvailability(lang: String?, country: String?, variant: String?): Int {
        if (lang.equals(iso3Language, ignoreCase = true) && variant.isNullOrEmpty()) {
            if (country.isNullOrEmpty()) return TextToSpeech.LANG_AVAILABLE
            if (country.equals(iso3Country, ignoreCase = true)) {
                return TextToSpeech.LANG_COUNTRY_AVAILABLE
            }
        }
        // Screen readers may probe the interface locale before choosing RuTTS.
        return TextToSpeech.LANG_AVAILABLE
    }
}
