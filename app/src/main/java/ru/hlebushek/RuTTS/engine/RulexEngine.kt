package ru.hlebushek.RuTTS.engine

import android.content.Context
import java.io.File
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.Locale

class RulexEngine(context: Context) {
    private val appContext = context.applicationContext

    fun warmUp() {
        ensureLoaded()
    }

    fun applyToRussianWords(text: String): String {
        if (text.isBlank()) {
            return text
        }

        ensureLoaded()
        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val current = text[index]
            if (isRulexWordChar(current)) {
                val start = index
                while (index < text.length && isRulexWordChar(text[index])) {
                    index++
                }
                val word = text.substring(start, index)
                val transformed = if (word.contains('+') || word.contains('\u0301')) {
                    word.replace('\u0301', '+')
                } else {
                    searchWord(word.lowercase(Locale.ROOT))
                }
                builder.append(transformed)
            } else {
                builder.append(current)
                index++
            }
        }
        return builder.toString()
    }

    fun isLoaded(): Boolean {
        return NativeRulex.nativeIsLoaded()
    }

    private fun ensureLoaded() {
        if (NativeRulex.nativeIsLoaded()) {
            return
        }
        synchronized(loadLock) {
            if (NativeRulex.nativeIsLoaded()) {
                return
            }
            val seed = appContext.assets.readBytes("rulex/rulex.db")
            val fingerprint = MessageDigest.getInstance("SHA-256")
                .digest(seed)
                .joinToString("") { byte -> "%02x".format(byte) }
            val cacheDirectory = File(appContext.noBackupFilesDir, "rulex")
            check(cacheDirectory.exists() || cacheDirectory.mkdirs()) {
                "Failed to create Rulex cache directory"
            }
            val database = File(cacheDirectory, "rulex.db")
            val version = File(cacheDirectory, "rulex.version")
            val cacheIsCurrent = version.takeIf { it.isFile }?.readText() == fingerprint
            if (!cacheIsCurrent) {
                database.delete()
                File(database.path + "-lock").delete()
                version.delete()
            }

            val loaded = NativeRulex.nativeEnsureLoaded(database.absolutePath, seed)
            if (loaded && !cacheIsCurrent) {
                version.writeText(fingerprint)
            }
            check(loaded) { "Failed to initialize native Rulex runtime" }
        }
    }

    private fun searchWord(word: String): String {
        synchronized(wordCacheLock) {
            wordCache[word]?.let { return it }
        }
        val transformed = NativeRulex.nativeSearchWord(word.toByteArray(koi8Charset)).toString(koi8Charset)
        synchronized(wordCacheLock) {
            wordCache[word] = transformed
        }
        return transformed
    }

    private fun isRulexWordChar(ch: Char): Boolean {
        return isCyrillicLetter(ch) || ch == '\u0301' || ch == '+'
    }

    private fun isCyrillicLetter(ch: Char): Boolean {
        return ch in '\u0410'..'\u044F' || ch == '\u0401' || ch == '\u0451'
    }

    companion object {
        private val loadLock = Any()
        private val wordCacheLock = Any()
        private val koi8Charset: Charset = Charset.forName("KOI8-R")
        private val wordCache = object : LinkedHashMap<String, String>(512, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
                return size > maxCachedWords
            }
        }
        private const val maxCachedWords = 4096
    }
}

private fun android.content.res.AssetManager.readBytes(path: String): ByteArray {
    return open(path).use { it.readBytes() }
}
