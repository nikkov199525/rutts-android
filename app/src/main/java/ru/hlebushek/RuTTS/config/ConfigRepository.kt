package ru.hlebushek.RuTTS.config

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

class ConfigRepository(context: Context) {
    // Настройки живут в device-protected хранилище, чтобы движок читал их в
    // Direct Boot до разблокировки. preferenceContext() до API 24 возвращает
    // обычный контекст приложения. См. AppStorage.
    private val preferences =
        PreferenceManager.getDefaultSharedPreferences(AppStorage.preferenceContext(context))

    // Словари кэшируются по «сырой» строке из SharedPreferences, а не через
    // OnSharedPreferenceChangeListener. Слушатель ненадёжен: он не срабатывает
    // между разными экземплярами репозитория так, как ожидается, и clear() при
    // восстановлении бэкапа тоже его не будит. Поэтому синтез перечитывает
    // строку на каждом запросе (дёшево, как preferences.all для config) и
    // переразбирает JSON только когда содержимое изменилось — так правки словарей
    // и восстановление настроек применяются на лету, без перезапуска синтеза.
    private val dictionaryCacheLock = Any()
    private var cachedCharactersLoaded = false
    private var cachedCharactersRaw: String? = null
    private var cachedCharacters: List<DictionaryEntry> = emptyList()
    private var cachedSingleCharactersLoaded = false
    private var cachedSingleCharactersRaw: String? = null
    private var cachedSingleCharacters: List<DictionaryEntry> = emptyList()

    init {
        ensureInitialized()
        migrateLegacyBooleanPreferences()
        migrateLegacySeekBarPreferences()
        migrateLegacyRuntimePreferences()
    }

    fun loadSynthesisSettings(): SynthesisSettings {
        val values = preferences.all
        val dictionaries = loadDictionaries()
        return SynthesisSettings(
            config = loadConfig(values),
            runtimeSettings = loadRuntimeSettings(values),
            equalizerSettings = loadEqualizerSettings(values),
            characters = dictionaries.characters,
            singleCharacters = dictionaries.singleCharacters
        )
    }

    private fun loadDictionaries(): Dictionaries {
        return synchronized(dictionaryCacheLock) {
            Dictionaries(
                characters = cachedDictionary(DictionaryKind.CHARACTERS),
                singleCharacters = cachedDictionary(DictionaryKind.SINGLE_CHARACTERS)
            )
        }
    }

    // Возвращает стабильный экземпляр списка, пока «сырая» строка не изменилась,
    // чтобы TextPreprocessor мог кэшировать правила по ссылке. Вызывается под
    // dictionaryCacheLock. Новый экземпляр — только при реальном изменении.
    private fun cachedDictionary(kind: DictionaryKind): List<DictionaryEntry> {
        val raw = preferences.getString(dictionaryStorageKey(kind), null)
        return when (kind) {
            DictionaryKind.CHARACTERS -> {
                if (!cachedCharactersLoaded || raw != cachedCharactersRaw) {
                    cachedCharacters = parseDictionary(kind, raw)
                    cachedCharactersRaw = raw
                    cachedCharactersLoaded = true
                }
                cachedCharacters
            }
            DictionaryKind.SINGLE_CHARACTERS -> {
                if (!cachedSingleCharactersLoaded || raw != cachedSingleCharactersRaw) {
                    cachedSingleCharacters = parseDictionary(kind, raw)
                    cachedSingleCharactersRaw = raw
                    cachedSingleCharactersLoaded = true
                }
                cachedSingleCharacters
            }
        }
    }

    fun ensureInitialized() {
        if (preferences.contains(PreferenceKeys.SAMPLES_PER_SEC)) {
            return
        }
        writeDefaults()
    }

    fun loadConfig(): RuttsConfig {
        return loadConfig(preferences.all)
    }

    private fun loadConfig(values: Map<String, *>): RuttsConfig {
        return RuttsConfig(
            useRulex = readBoolean(values, PreferenceKeys.USE_RULEX, true),
            readEmojis = readBoolean(values, PreferenceKeys.READ_EMOJIS, false),
            readPhoneNumbers = readBoolean(values, PreferenceKeys.READ_PHONE_NUMBERS, false),
            useAlternativeVoice = readBoolean(values, PreferenceKeys.USE_ALTERNATIVE_VOICE, false),
            useRateBoost = readBoolean(values, PreferenceKeys.USE_RATE_BOOST, false),
            useNewRateAlgorithm = readBoolean(values, PreferenceKeys.USE_NEW_RATE_ALGORITHM, false),
            useSoftResampling = readBoolean(values, PreferenceKeys.USE_SOFT_RESAMPLING, false),
            samplesPerSec = readInt(values, PreferenceKeys.SAMPLES_PER_SEC, 10000, 8000, 16000),
            interpolationMultiplier = normalizeInterpolationMultiplier(
                readInt(values, PreferenceKeys.INTERPOLATION_MULTIPLIER, 1, 1, 4)
            ),
            interpolationAlgorithm = if (readInt(values, PreferenceKeys.INTERPOLATION_ALGORITHM, 0, 0, 1) == 0) {
                InterpolationAlgorithm.LINEAR
            } else {
                InterpolationAlgorithm.ZERO_ORDER_HOLD
            },
            waveBufferSize = 128,
            silenceAtBegin = readInt(values, PreferenceKeys.SILENCE_AT_BEGIN, 0, 0, 100),
            silenceAtEnd = readInt(values, PreferenceKeys.SILENCE_AT_END, 0, 0, 100),
            generalGapFactor = readInt(values, PreferenceKeys.GENERAL_GAP_FACTOR, 100, 0, 5000),
            commaGapFactor = readInt(values, PreferenceKeys.COMMA_GAP_FACTOR, 100, 0, 750),
            dotGapFactor = readInt(values, PreferenceKeys.DOT_GAP_FACTOR, 100, 0, 500),
            semicolonGapFactor = readInt(values, PreferenceKeys.SEMICOLON_GAP_FACTOR, 100, 0, 600),
            colonGapFactor = readInt(values, PreferenceKeys.COLON_GAP_FACTOR, 100, 0, 600),
            questionGapFactor = readInt(values, PreferenceKeys.QUESTION_GAP_FACTOR, 100, 0, 375),
            exclamationGapFactor = readInt(values, PreferenceKeys.EXCLAMATION_GAP_FACTOR, 100, 0, 300),
            intonationalGapFactor = readInt(values, PreferenceKeys.INTONATIONAL_GAP_FACTOR, 100, 0, 1000),
            decSepPoint = readBoolean(values, PreferenceKeys.DEC_SEP_POINT, false),
            decSepComma = readBoolean(values, PreferenceKeys.DEC_SEP_COMMA, true),
            intonation = readInt(values, PreferenceKeys.INTONATION, 100, 0, 140)
        )
    }

    fun loadRuntimeSettings(): RuntimeSettings {
        return loadRuntimeSettings(preferences.all)
    }

    private fun loadRuntimeSettings(values: Map<String, *>): RuntimeSettings {
        val speechRate = readInt(values, PreferenceKeys.RUNTIME_SPEECH_RATE, 100, 20, 500)
        val pitchPercent = readInt(values, PreferenceKeys.RUNTIME_PITCH, 100, 50, 300)
        val volumePercent = readInt(values, PreferenceKeys.RUNTIME_VOLUME, 100, 0, 100)
        val spellOut = values[PreferenceKeys.RUNTIME_SPELL_OUT] as? Boolean ?: false
        return RuntimeSettings(
            speechRate = speechRate,
            pitchPercent = pitchPercent,
            volumePercent = volumePercent,
            spellOut = spellOut
        )
    }

    fun loadEqualizerSettings(): EqualizerSettings {
        return loadEqualizerSettings(preferences.all)
    }

    private fun loadEqualizerSettings(values: Map<String, *>): EqualizerSettings {
        return EqualizerSettings(
            bassDb = readInt(values, PreferenceKeys.EQ_BASS, 0, -12, 12),
            midDb = readInt(values, PreferenceKeys.EQ_MID, 0, -12, 12),
            trebleDb = readInt(values, PreferenceKeys.EQ_TREBLE, 0, -12, 12)
        )
    }

    fun saveRuntimeSettings(runtimeSettings: RuntimeSettings) {
        preferences.edit {
            putString(PreferenceKeys.RUNTIME_SPEECH_RATE, runtimeSettings.speechRate.coerceIn(20, 500).toString())
            putString(PreferenceKeys.RUNTIME_PITCH, runtimeSettings.pitchPercent.coerceIn(50, 300).toString())
            putInt(PreferenceKeys.RUNTIME_VOLUME, runtimeSettings.volumePercent.coerceIn(0, 100))
            putBoolean(PreferenceKeys.RUNTIME_SPELL_OUT, runtimeSettings.spellOut)
        }
    }

    fun resetEqualizerSettings() {
        preferences.edit {
            putInt(PreferenceKeys.EQ_BASS, 0)
            putInt(PreferenceKeys.EQ_MID, 0)
            putInt(PreferenceKeys.EQ_TREBLE, 0)
        }
    }

    fun loadDictionary(kind: DictionaryKind): MutableList<DictionaryEntry> {
        val raw = preferences.getString(dictionaryStorageKey(kind), null)
        return parseDictionary(kind, raw).toMutableList()
    }

    private fun parseDictionary(kind: DictionaryKind, raw: String?): List<DictionaryEntry> {
        if (raw.isNullOrBlank()) {
            return defaultDictionary(kind)
        }

        return runCatching {
            val entries = mutableListOf<DictionaryEntry>()
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val entryKey = item.optString("key")
                val entryValue = item.optString("value")
                if (entryKey.isNotBlank() && entryValue.isNotBlank()) {
                    entries += DictionaryEntry(entryKey, entryValue)
                }
            }
            if (entries.isEmpty()) {
                defaultDictionary(kind)
            } else {
                entries
            }
        }.getOrElse {
            defaultDictionary(kind)
        }
    }

    fun saveDictionary(kind: DictionaryKind, entries: List<DictionaryEntry>) {
        preferences.edit {
            putString(dictionaryStorageKey(kind), serializeEntries(entries))
        }
    }

    fun restoreDictionaryDefaults(kind: DictionaryKind) {
        saveDictionary(kind, defaultDictionary(kind))
    }

    fun applySpeakingMouseProfile() {
        preferences.edit {
            putString(PreferenceKeys.SAMPLES_PER_SEC, "11025")
            putString(PreferenceKeys.INTONATION, "71")
            putString(PreferenceKeys.RUNTIME_PITCH, "96")
        }
    }

    fun resetAll() {
        preferences.edit {
            clear()
        }
        writeDefaults()
    }

    fun createSettingsBackup(): String {
        val encodedPreferences = JSONObject()
        preferences.all.toSortedMap().forEach { (key, value) ->
            encodedPreferences.put(key, encodePreference(value))
        }
        return JSONObject()
            .put("format", BACKUP_FORMAT)
            .put("version", BACKUP_VERSION)
            .put("preferences", encodedPreferences)
            .toString(2)
    }

    fun restoreSettingsBackup(contents: String) {
        val root = JSONObject(contents)
        require(root.optString("format") == BACKUP_FORMAT) { "Unsupported settings backup" }
        require(root.optInt("version", -1) == BACKUP_VERSION) { "Unsupported settings backup version" }

        val encodedPreferences = root.getJSONObject("preferences")
        val restoredPreferences = mutableListOf<RestoredPreference>()
        val keys = encodedPreferences.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val encoded = encodedPreferences.getJSONObject(key)
            val type = encoded.getString("type")
            val value = decodePreference(type, encoded.get("value"))
            restoredPreferences += RestoredPreference(key, type, value)
        }

        val editor = preferences.edit().clear()
        restoredPreferences.forEach { preference ->
            when (preference.type) {
                TYPE_BOOLEAN -> editor.putBoolean(preference.key, preference.value as Boolean)
                TYPE_INT -> editor.putInt(preference.key, preference.value as Int)
                TYPE_LONG -> editor.putLong(preference.key, preference.value as Long)
                TYPE_FLOAT -> editor.putFloat(preference.key, preference.value as Float)
                TYPE_STRING -> editor.putString(preference.key, preference.value as String)
                TYPE_STRING_SET -> {
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(preference.key, preference.value as Set<String>)
                }
            }
        }
        check(editor.commit()) { "Failed to restore settings" }
    }

    private fun writeDefaults() {
        preferences.edit {
            putBoolean(PreferenceKeys.USE_RULEX, true)
            putBoolean(PreferenceKeys.READ_EMOJIS, false)
            putBoolean(PreferenceKeys.READ_PHONE_NUMBERS, false)
            putBoolean(PreferenceKeys.USE_ALTERNATIVE_VOICE, false)
            putBoolean(PreferenceKeys.USE_RATE_BOOST, false)
            putBoolean(PreferenceKeys.USE_NEW_RATE_ALGORITHM, false)
            putBoolean(PreferenceKeys.USE_SOFT_RESAMPLING, false)
            putString(PreferenceKeys.SAMPLES_PER_SEC, "10000")
            putString(PreferenceKeys.INTERPOLATION_MULTIPLIER, "1")
            putString(PreferenceKeys.INTERPOLATION_ALGORITHM, "0")
            putInt(PreferenceKeys.SILENCE_AT_BEGIN, 0)
            putInt(PreferenceKeys.SILENCE_AT_END, 0)
            putInt(PreferenceKeys.GENERAL_GAP_FACTOR, 100)
            putInt(PreferenceKeys.COMMA_GAP_FACTOR, 100)
            putInt(PreferenceKeys.DOT_GAP_FACTOR, 100)
            putInt(PreferenceKeys.SEMICOLON_GAP_FACTOR, 100)
            putInt(PreferenceKeys.COLON_GAP_FACTOR, 100)
            putInt(PreferenceKeys.QUESTION_GAP_FACTOR, 100)
            putInt(PreferenceKeys.EXCLAMATION_GAP_FACTOR, 100)
            putInt(PreferenceKeys.INTONATIONAL_GAP_FACTOR, 100)
            putBoolean(PreferenceKeys.DEC_SEP_POINT, false)
            putBoolean(PreferenceKeys.DEC_SEP_COMMA, true)
            putString(PreferenceKeys.INTONATION, "100")
            putString(PreferenceKeys.RUNTIME_SPEECH_RATE, "100")
            putString(PreferenceKeys.RUNTIME_PITCH, "100")
            putInt(PreferenceKeys.RUNTIME_VOLUME, 100)
            putBoolean(PreferenceKeys.RUNTIME_SPELL_OUT, false)
            putInt(PreferenceKeys.EQ_BASS, 0)
            putInt(PreferenceKeys.EQ_MID, 0)
            putInt(PreferenceKeys.EQ_TREBLE, 0)
            putString(PreferenceKeys.APP_THEME, "system")
            putString(dictionaryStorageKey(DictionaryKind.CHARACTERS), serializeEntries(DefaultDictionaries.characters))
            putString(
                dictionaryStorageKey(DictionaryKind.SINGLE_CHARACTERS),
                serializeEntries(DefaultDictionaries.singleCharacters)
            )
        }
    }

    private fun serializeEntries(entries: List<DictionaryEntry>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("key", entry.key)
                    .put("value", entry.value)
            )
        }
        return array.toString()
    }

    private fun encodePreference(value: Any?): JSONObject {
        val encoded = JSONObject()
        when (value) {
            is Boolean -> encoded.put("type", TYPE_BOOLEAN).put("value", value)
            is Int -> encoded.put("type", TYPE_INT).put("value", value)
            is Long -> encoded.put("type", TYPE_LONG).put("value", value)
            is Float -> encoded.put("type", TYPE_FLOAT).put("value", value.toDouble())
            is String -> encoded.put("type", TYPE_STRING).put("value", value)
            is Set<*> -> {
                val items = JSONArray()
                value.forEach { item ->
                    require(item is String) { "Unsupported preference value" }
                    items.put(item)
                }
                encoded.put("type", TYPE_STRING_SET).put("value", items)
            }
            else -> throw IllegalArgumentException("Unsupported preference value")
        }
        return encoded
    }

    private fun decodePreference(type: String, value: Any): Any {
        return when (type) {
            TYPE_BOOLEAN -> value as Boolean
            TYPE_INT -> (value as Number).toInt()
            TYPE_LONG -> (value as Number).toLong()
            TYPE_FLOAT -> (value as Number).toFloat()
            TYPE_STRING -> value as String
            TYPE_STRING_SET -> {
                val items = value as JSONArray
                buildSet {
                    for (index in 0 until items.length()) add(items.getString(index))
                }
            }
            else -> throw IllegalArgumentException("Unsupported preference type")
        }
    }

    private fun dictionaryStorageKey(kind: DictionaryKind): String {
        return when (kind) {
            DictionaryKind.CHARACTERS -> PreferenceKeys.DICTIONARY_CHARACTERS
            DictionaryKind.SINGLE_CHARACTERS -> PreferenceKeys.DICTIONARY_SINGLE_CHARACTERS
        }
    }

    private fun defaultDictionary(kind: DictionaryKind): List<DictionaryEntry> {
        return when (kind) {
            DictionaryKind.CHARACTERS -> DefaultDictionaries.characters
            DictionaryKind.SINGLE_CHARACTERS -> DefaultDictionaries.singleCharacters
        }
    }

    private fun readBoolean(values: Map<String, *>, key: String, defaultValue: Boolean): Boolean {
        return when (val stored = values[key]) {
            is Boolean -> stored
            is String -> stored.toBooleanStrictOrNull() ?: defaultValue
            else -> defaultValue
        }
    }

    private fun readInt(
        values: Map<String, *>,
        key: String,
        defaultValue: Int,
        minValue: Int,
        maxValue: Int
    ): Int {
        val stored = values[key]
        val parsed = when (stored) {
            is Int -> stored
            is Long -> stored.toInt()
            is String -> stored.toIntOrNull()
            else -> null
        }
        return parsed?.coerceIn(minValue, maxValue) ?: defaultValue
    }

    private fun normalizeInterpolationMultiplier(value: Int): Int {
        return when {
            value < 2 -> 1
            value > 2 -> 4
            else -> 2
        }
    }

    private fun migrateLegacyRuntimePreferences() {
        val legacySpeechRate = preferences.all[PreferenceKeys.RUNTIME_SPEECH_RATE]
        val legacyPitch = preferences.all[PreferenceKeys.RUNTIME_PITCH]
        if (legacySpeechRate !is Int && legacyPitch !is Int) {
            return
        }

        preferences.edit {
            if (legacySpeechRate is Int) {
                putString(PreferenceKeys.RUNTIME_SPEECH_RATE, legacySpeechRate.toString())
            }
            if (legacyPitch is Int) {
                putString(PreferenceKeys.RUNTIME_PITCH, legacyPitch.toString())
            }
        }
    }

    private fun migrateLegacyBooleanPreferences() {
        val keys = listOf(
            PreferenceKeys.USE_RULEX,
            PreferenceKeys.READ_EMOJIS,
            PreferenceKeys.READ_PHONE_NUMBERS,
            PreferenceKeys.USE_ALTERNATIVE_VOICE,
            PreferenceKeys.USE_RATE_BOOST,
            PreferenceKeys.USE_NEW_RATE_ALGORITHM,
            PreferenceKeys.USE_SOFT_RESAMPLING,
            PreferenceKeys.DEC_SEP_POINT,
            PreferenceKeys.DEC_SEP_COMMA
        )

        preferences.edit {
            migrateLegacyBooleanKey(PreferenceKeys.LEGACY_READ_EMOJIS, PreferenceKeys.READ_EMOJIS)
            migrateLegacyBooleanKey(PreferenceKeys.LEGACY_READ_PHONE_NUMBERS, PreferenceKeys.READ_PHONE_NUMBERS)
            keys.forEach { key ->
                val value = preferences.all[key] as? String ?: return@forEach
                val parsed = value.toBooleanStrictOrNull() ?: return@forEach
                putBoolean(key, parsed)
            }
        }
    }

    private fun android.content.SharedPreferences.Editor.migrateLegacyBooleanKey(
        legacyKey: String,
        targetKey: String
    ) {
        val stored = preferences.all[legacyKey] ?: return
        val parsed = when (stored) {
            is Boolean -> stored
            is String -> stored.toBooleanStrictOrNull()
            else -> null
        } ?: return
        putBoolean(targetKey, parsed)
        remove(legacyKey)
    }

    private fun migrateLegacySeekBarPreferences() {
        val keys = listOf(
            PreferenceKeys.RUNTIME_VOLUME,
            PreferenceKeys.GENERAL_GAP_FACTOR,
            PreferenceKeys.COMMA_GAP_FACTOR,
            PreferenceKeys.DOT_GAP_FACTOR,
            PreferenceKeys.SEMICOLON_GAP_FACTOR,
            PreferenceKeys.COLON_GAP_FACTOR,
            PreferenceKeys.QUESTION_GAP_FACTOR,
            PreferenceKeys.EXCLAMATION_GAP_FACTOR,
            PreferenceKeys.INTONATIONAL_GAP_FACTOR,
            PreferenceKeys.SILENCE_AT_BEGIN,
            PreferenceKeys.SILENCE_AT_END,
            PreferenceKeys.EQ_BASS,
            PreferenceKeys.EQ_MID,
            PreferenceKeys.EQ_TREBLE
        )

        preferences.edit {
            keys.forEach { key ->
                val value = preferences.all[key] as? String ?: return@forEach
                val parsed = value.toIntOrNull() ?: return@forEach
                putInt(key, parsed)
            }
        }
    }

    data class SynthesisSettings(
        val config: RuttsConfig,
        val runtimeSettings: RuntimeSettings,
        val equalizerSettings: EqualizerSettings,
        val characters: List<DictionaryEntry>,
        val singleCharacters: List<DictionaryEntry>
    )

    private data class Dictionaries(
        val characters: List<DictionaryEntry>,
        val singleCharacters: List<DictionaryEntry>
    )

    private data class RestoredPreference(
        val key: String,
        val type: String,
        val value: Any
    )

    private companion object {
        const val BACKUP_FORMAT = "rutts-settings"
        const val BACKUP_VERSION = 1
        const val TYPE_BOOLEAN = "boolean"
        const val TYPE_INT = "int"
        const val TYPE_LONG = "long"
        const val TYPE_FLOAT = "float"
        const val TYPE_STRING = "string"
        const val TYPE_STRING_SET = "string_set"
    }
}
