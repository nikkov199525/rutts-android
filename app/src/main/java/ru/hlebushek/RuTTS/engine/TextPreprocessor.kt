package ru.hlebushek.RuTTS.engine

import ru.hlebushek.RuTTS.config.DictionaryEntry
import ru.hlebushek.RuTTS.config.RuttsConfig
import java.nio.charset.Charset

class TextPreprocessor(
    private val rulexEngine: RulexEngine,
    private val emojiTextRewriter: EmojiTextRewriter? = null,
    private val phoneNumberTextRewriter: PhoneNumberTextRewriter? = null
) {
    private val koi8Charset = Charset.forName("KOI8-R")
    private val dictionaryCacheLock = Any()
    private var cachedCharacters: List<DictionaryEntry>? = null
    private var cachedSingleCharacters: List<DictionaryEntry>? = null
    private var cachedCharacterRules = DictionaryRules.EMPTY
    private var cachedSingleCharacterRules = DictionaryRules.EMPTY

    fun prepare(
        text: String,
        spellOut: Boolean,
        config: RuttsConfig,
        characters: List<DictionaryEntry>,
        singleCharacters: List<DictionaryEntry>
    ): String {
        var working = sanitizeText(
            text,
            silencePunctuation = false,
            discardIgnorableFormatChars = false
        )
        if (working.isBlank()) {
            return ""
        }

        val (characterRules, singleRules) = dictionaryRules(characters, singleCharacters)
        val exactSingleReplacement = singleRules.exactReplacement(working)

        working = when {
            exactSingleReplacement != null -> exactSingleReplacement
            spellOut || working.codePointCount(0, working.length) == 1 -> speakSpellOut(working, singleRules)
            else -> {
                var transformed = splitCamelCase(working)
                transformed = replaceSingleLatinManual(transformed, singleRules.singleCharacterMap)
                transformed = replaceAbbreviationsManual(transformed, singleRules.singleCharacterMap)
                transformed = splitLetterAfterNumber(transformed)
                applyDictionary(transformed, characterRules)
            }
        }

        working = emojiTextRewriter?.rewrite(working, config.readEmojis) ?: working
        working = phoneNumberTextRewriter?.rewrite(working, config.readPhoneNumbers) ?: working
        working = sanitizeText(working)
        if (working.isBlank()) {
            return ""
        }

        working = replaceAccentInRussianWords(working)
        if (config.useRulex) {
            working = rulexEngine.applyToRussianWords(working)
        }

        working = sanitizeText(working)
        if (working.isBlank()) {
            return ""
        }

        return normalizeForKoi8r(working)
    }

    private fun sanitizeText(
        input: String,
        silencePunctuation: Boolean = true,
        discardIgnorableFormatChars: Boolean = true
    ): String {
        val result = StringBuilder(input.length)
        var inTag = false
        var pendingSpace = false
        for (ch in input) {
            when (ch) {
                '<' -> inTag = true
                '>' -> inTag = false
                else -> if (!inTag && !isDiscardedControl(ch, discardIgnorableFormatChars)) {
                    val normalized = if (silencePunctuation && isSilentPunctuation(ch)) ' ' else ch
                    if (isCollapsibleSpace(normalized)) {
                        pendingSpace = result.isNotEmpty()
                    } else {
                        if (pendingSpace) {
                            result.append(' ')
                            pendingSpace = false
                        }
                        result.append(normalized)
                    }
                }
            }
        }
        return result.toString()
    }

    private fun isDiscardedControl(ch: Char, discardIgnorableFormatChars: Boolean): Boolean {
        return (ch.code < 0x20 && !isCollapsibleSpace(ch)) ||
            (discardIgnorableFormatChars && isIgnorableFormatChar(ch))
    }

    private fun isCollapsibleSpace(ch: Char): Boolean {
        return ch == ' ' || ch == '\r' || ch == '\n' || ch == '\t'
    }

    private fun isIgnorableFormatChar(ch: Char): Boolean {
        val code = ch.code
        return code == 0xFEFF ||
            code == 0x00AD ||
            code in 0x200B..0x200F ||
            code in 0x202A..0x202E ||
            code in 0x2066..0x2069 ||
            code in 0xFE00..0xFE0F
    }

    private fun isSilentPunctuation(ch: Char): Boolean {
        return when (ch) {
            '(', ')',
            '"', '\'', '`',
            '\u00AB', '\u00BB', '\u2018', '\u2019', '\u201A', '\u201B',
            '\u201C', '\u201D', '\u201E', '\u201F', '\u2039', '\u203A' -> true
            else -> false
        }
    }

    private fun collapseSpaces(input: String): String {
        return input.replace(Regex("\\s+"), " ").trim()
    }

    private fun speakSpellOut(text: String, rules: DictionaryRules): String {
        val parts = ArrayList<String>()
        var index = 0
        while (index < text.length) {
            val match = rules.matchAt(text, index)
            if (match != null) {
                parts += match.value
                index += match.key.length
            } else {
                val codePoint = text.codePointAt(index)
                parts += String(Character.toChars(codePoint))
                index += Character.charCount(codePoint)
            }
        }
        return parts.joinToString(" ")
    }

    private fun applyDictionary(text: String, rules: DictionaryRules): String {
        if (rules.entries.isEmpty()) {
            return text
        }
        return buildString {
            var index = 0
            while (index < text.length) {
                val match = rules.matchAt(text, index)
                if (match != null) {
                    append(match.value)
                    index += match.key.length
                } else {
                    val codePoint = text.codePointAt(index)
                    appendCodePoint(codePoint)
                    index += Character.charCount(codePoint)
                }
            }
        }
    }

    private fun replaceSingleLatinManual(text: String, singleMap: Map<Char, String>): String {
        val result = StringBuilder(text.length)
        text.forEachIndexed { index, ch ->
            if (isLatinLetter(ch)) {
                val previous = text.getOrNull(index - 1)
                val next = text.getOrNull(index + 1)
                if (!isRuOrLatLetter(previous) && !isRuOrLatLetter(next)) {
                    result.append(singleMap[ch.lowercaseChar()] ?: ch.toString())
                    return@forEachIndexed
                }
            }
            result.append(ch)
        }
        return result.toString()
    }

    private fun replaceAbbreviationsManual(text: String, singleMap: Map<Char, String>): String {
        val result = StringBuilder(text.length * 2)
        var index = 0
        while (index < text.length) {
            if (isRuOrLatLetter(text[index])) {
                val start = index
                while (index < text.length && isRuOrLatLetter(text[index])) {
                    index++
                }
                val length = index - start
                if (shouldSpellRun(text.substring(start, index))) {
                    for (position in 0 until length) {
                        val key = text[start + position].lowercaseChar()
                        result.append(singleMap[key] ?: text[start + position].toString())
                        if (position + 1 < length) {
                            result.append(' ')
                        }
                    }
                } else {
                    result.append(text, start, index)
                }
                continue
            }
            result.append(text[index])
            index++
        }
        return result.toString()
    }

    private fun splitLetterAfterNumber(text: String): String {
        val result = StringBuilder(text.length + 8)
        text.forEachIndexed { index, ch ->
            result.append(ch)
            val next = text.getOrNull(index + 1)
            if (ch.isDigit() && next != null && next.isLetter()) {
                result.append(' ')
            }
        }
        return result.toString()
    }

    private fun replaceAccentInRussianWords(text: String): String {
        if (!text.contains('\u0301')) {
            return text
        }
        val result = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            if (isRussianWordChar(text[index])) {
                val start = index
                while (index < text.length && isRussianWordChar(text[index])) {
                    index++
                }
                val word = text.substring(start, index)
                val accentIndex = word.indexOf('\u0301')
                if (accentIndex >= 0) {
                    result.append(word.substring(0, accentIndex))
                    result.append('+')
                    result.append(word.substring(accentIndex + 1).replace('\u0301', ' '))
                } else {
                    result.append(word)
                }
                continue
            }
            result.append(text[index])
            index++
        }
        return collapseSpaces(result.toString())
    }

    private fun normalizeForKoi8r(text: String): String {
        val encoder = koi8Charset.newEncoder()
        val result = StringBuilder(text.length)
        text.forEach { ch ->
            val normalized = when (ch.code) {
                0x2014, 0x2013, 0x2212, 0x2012, 0x2011 -> '-'
                else -> ch
            }
            if (isCommonKoi8rCharacter(normalized) || encoder.canEncode(normalized)) {
                result.append(normalized)
            } else {
                result.append(' ')
            }
        }
        return collapseSpaces(result.toString())
    }

    private fun dictionaryRules(
        characters: List<DictionaryEntry>,
        singleCharacters: List<DictionaryEntry>
    ): Pair<DictionaryRules, DictionaryRules> {
        synchronized(dictionaryCacheLock) {
            if (characters !== cachedCharacters) {
                cachedCharacters = characters
                cachedCharacterRules = buildDictionaryRules(characters)
            }
            if (singleCharacters !== cachedSingleCharacters) {
                cachedSingleCharacters = singleCharacters
                cachedSingleCharacterRules = buildDictionaryRules(singleCharacters)
            }
            return cachedCharacterRules to cachedSingleCharacterRules
        }
    }

    private fun buildDictionaryRules(entries: List<DictionaryEntry>): DictionaryRules {
        val singleCharacterMap = HashMap<Char, String>(entries.size)
        entries.forEach { entry ->
            entry.key.lowercase().singleOrNull()?.let { singleCharacterMap[it] = entry.value }
        }
        return DictionaryRules(
            entries = entries.filter { it.key.isNotEmpty() }.sortedByDescending { it.key.length },
            singleCharacterMap = singleCharacterMap
        )
    }

    private data class DictionaryRules(
        val entries: List<DictionaryEntry>,
        val singleCharacterMap: Map<Char, String>
    ) {
        fun exactReplacement(text: String): String? {
            return entries.firstOrNull { text.equals(it.key, ignoreCase = true) }?.value
        }

        fun matchAt(text: String, index: Int): DictionaryEntry? {
            return entries.firstOrNull { entry ->
                index + entry.key.length <= text.length &&
                    text.regionMatches(index, entry.key, 0, entry.key.length, ignoreCase = true)
            }
        }

        companion object {
            val EMPTY = DictionaryRules(emptyList(), emptyMap())
        }
    }

    private fun isCommonKoi8rCharacter(ch: Char): Boolean {
        return ch.code <= 0x7f || isCyrillicLetter(ch)
    }

    private fun isRuOrLatLetter(ch: Char?): Boolean {
        return ch != null && (isLatinLetter(ch) || isCyrillicLetter(ch))
    }

    private fun isRussianWordChar(ch: Char): Boolean {
        return isCyrillicLetter(ch) || ch == '\u0301'
    }

    internal companion object {
        fun isLatinLetter(ch: Char): Boolean {
            val lower = ch.lowercaseChar()
            return lower in 'a'..'z'
        }

        private fun isAbbrevChar(ch: Char): Boolean {
            return when (ch.lowercaseChar()) {
                'b', 'c', 'd', 'f', 'g', 'h', 'j', 'k', 'l', 'm', 'n', 'p', 'q', 'r', 's', 't', 'v', 'w', 'x', 'z',
                'б', 'в', 'г', 'д', 'ж', 'з', 'к', 'л', 'м', 'н', 'п', 'р', 'с', 'т', 'ф', 'х', 'ц', 'ч', 'ш', 'щ' -> true
                else -> false
            }
        }

        // По буквам читаются только сплошные согласные: ТТС, ФСБ, HTML, PNG.
        // Слова с гласными (POCO, OZON, ВНИМАНИЕ, НАТО, GIF) не трогаем.
        internal fun shouldSpellRun(run: String): Boolean {
            return run.length >= 2 && run.all { isAbbrevChar(it) }
        }

        // Разрезает camelCase, чтобы аббревиатура внутри слова стала отдельным
        // фрагментом: chatGPT -> "chat GPT", RuTTS -> "Ru TTS", TTSEngine -> "TTS Engine".
        internal fun splitCamelCase(text: String): String {
            val result = StringBuilder(text.length + 8)
            text.forEachIndexed { index, ch ->
                if (index > 0 && isRuOrLatUpper(ch)) {
                    val previous = text[index - 1]
                    val next = text.getOrNull(index + 1)
                    // строчная -> Заглавная (chatGPT), либо ЗАГЛАВНАЯ -> Заглавная+строчная (TTSEngine)
                    if (isRuOrLatLower(previous) ||
                        (isRuOrLatUpper(previous) && next != null && isRuOrLatLower(next))
                    ) {
                        result.append(' ')
                    }
                }
                result.append(ch)
            }
            return result.toString()
        }

        private fun isRuOrLatUpper(ch: Char): Boolean {
            return ch.isUpperCase() && (isLatinLetter(ch) || isCyrillicLetter(ch))
        }

        private fun isRuOrLatLower(ch: Char): Boolean {
            return ch.isLowerCase() && (isLatinLetter(ch) || isCyrillicLetter(ch))
        }

        fun isCyrillicLetter(ch: Char): Boolean {
            return ch in 'А'..'я' || ch == 'Ё' || ch == 'ё'
        }
    }
}
