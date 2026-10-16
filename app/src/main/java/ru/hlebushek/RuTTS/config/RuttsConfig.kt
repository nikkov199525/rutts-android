package ru.hlebushek.RuTTS.config

enum class DictionaryKind {
    CHARACTERS,
    SINGLE_CHARACTERS
}

data class DictionaryEntry(
    val key: String,
    val value: String
)

data class RuntimeSettings(
    val speechRate: Int = 100,
    val pitchPercent: Int = 100,
    val volumePercent: Int = 100,
    val spellOut: Boolean = false
)

data class EqualizerSettings(
    val bassDb: Int = 0,
    val midDb: Int = 0,
    val trebleDb: Int = 0
) {
    val isFlat: Boolean
        get() = bassDb == 0 && midDb == 0 && trebleDb == 0
}

data class RuttsConfig(
    val useRulex: Boolean = true,
    val readEmojis: Boolean = false,
    val readPhoneNumbers: Boolean = false,
    val useAlternativeVoice: Boolean = false,
    val useRateBoost: Boolean = false,
    val useNewRateAlgorithm: Boolean = false,
    val useCovox: Boolean = false,
    val samplesPerSec: Int = 10000,
    val waveBufferSize: Int = 128,
    val silenceAtBegin: Int = 0,
    val silenceAtEnd: Int = 0,
    val generalGapFactor: Int = 100,
    val commaGapFactor: Int = 100,
    val dotGapFactor: Int = 100,
    val semicolonGapFactor: Int = 100,
    val colonGapFactor: Int = 100,
    val questionGapFactor: Int = 100,
    val exclamationGapFactor: Int = 100,
    val intonationalGapFactor: Int = 100,
    val decSepPoint: Boolean = false,
    val decSepComma: Boolean = true,
    val intonation: Int = 100
) {
    val outputSampleRate: Int
        get() = samplesPerSec.coerceIn(8000, 192000)
}

data class NumericPreferenceSpec(
    val key: String,
    val minValue: Int,
    val maxValue: Int
)

object PreferenceKeys {
    const val USE_RULEX = "use_rulex"
    const val READ_EMOJIS = "read_emojis"
    const val READ_PHONE_NUMBERS = "read_phone_numbers"
    const val USE_ALTERNATIVE_VOICE = "use_alternative_voice"
    const val USE_RATE_BOOST = "use_rate_boost"
    const val USE_NEW_RATE_ALGORITHM = "use_new_rate_algorithm"
    const val USE_COVOX = "use_covox"
    const val SAMPLES_PER_SEC = "samples_per_sec"
    const val SILENCE_AT_BEGIN = "silence_at_begin"
    const val SILENCE_AT_END = "silence_at_end"
    const val GENERAL_GAP_FACTOR = "general_gap_factor"
    const val COMMA_GAP_FACTOR = "comma_gap_factor"
    const val DOT_GAP_FACTOR = "dot_gap_factor"
    const val SEMICOLON_GAP_FACTOR = "semicolon_gap_factor"
    const val COLON_GAP_FACTOR = "colon_gap_factor"
    const val QUESTION_GAP_FACTOR = "question_gap_factor"
    const val EXCLAMATION_GAP_FACTOR = "exclamation_gap_factor"
    const val INTONATIONAL_GAP_FACTOR = "intonational_gap_factor"
    const val DEC_SEP_POINT = "dec_sep_point"
    const val DEC_SEP_COMMA = "dec_sep_comma"
    const val INTONATION = "intonation"

    const val DICTIONARY_CHARACTERS = "dictionary_characters"
    const val DICTIONARY_SINGLE_CHARACTERS = "dictionary_single_characters"
    const val RUNTIME_SPEECH_RATE = "runtime_speech_rate"
    const val RUNTIME_PITCH = "runtime_pitch"
    const val RUNTIME_VOLUME = "runtime_volume"
    const val RUNTIME_SPELL_OUT = "runtime_spell_out"
    const val EQ_BASS = "eq_bass"
    const val EQ_MID = "eq_mid"
    const val EQ_TREBLE = "eq_treble"
    const val APP_THEME = "app_theme"

    const val LEGACY_READ_EMOJIS = "ReadEmojis"
    const val LEGACY_READ_PHONE_NUMBERS = "ReadPhoneNumbers"
}

object SettingSpecs {
    val numericPreferences = listOf(
        NumericPreferenceSpec(PreferenceKeys.SAMPLES_PER_SEC, 8000, 16000),
        NumericPreferenceSpec(PreferenceKeys.INTONATION, 0, 140),
        NumericPreferenceSpec(PreferenceKeys.RUNTIME_SPEECH_RATE, 20, 250),
        NumericPreferenceSpec(PreferenceKeys.RUNTIME_PITCH, 50, 300)
    )
}

object DefaultDictionaries {
    val characters = listOf(
        DictionaryEntry("j", "дж"),
        DictionaryEntry("q", "ку"),
        DictionaryEntry("w", "в"),
        DictionaryEntry("x", "кс"),
        DictionaryEntry("\"", " ")
    )

    val singleCharacters = listOf(
        DictionaryEntry("б", "бэ"),
        DictionaryEntry("в", "вэ"),
        DictionaryEntry("с", "эс"),
        DictionaryEntry("к", "ка"),
        DictionaryEntry("ь", "мягкий знак"),
        DictionaryEntry("ъ", "твёрдый знак"),
        DictionaryEntry("a", "эй"),
        DictionaryEntry("b", "би"),
        DictionaryEntry("c", "си"),
        DictionaryEntry("d", "ди"),
        DictionaryEntry("e", "и"),
        DictionaryEntry("f", "эф"),
        DictionaryEntry("g", "джи"),
        DictionaryEntry("h", "эйчь"),
        DictionaryEntry("i", "ай"),
        DictionaryEntry("j", "джей"),
        DictionaryEntry("k", "кей"),
        DictionaryEntry("l", "эл"),
        DictionaryEntry("m", "эм"),
        DictionaryEntry("n", "эн"),
        DictionaryEntry("o", "оу"),
        DictionaryEntry("p", "пи"),
        DictionaryEntry("q", "къю"),
        DictionaryEntry("r", "ар"),
        DictionaryEntry("s", "эс"),
        DictionaryEntry("t", "ти"),
        DictionaryEntry("u", "ю"),
        DictionaryEntry("v", "ви"),
        DictionaryEntry("w", "даблъю"),
        DictionaryEntry("x", "экс"),
        DictionaryEntry("y", "вай"),
        DictionaryEntry("z", "зэт")
    )
}
