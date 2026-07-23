package ru.hlebushek.RuTTS.engine

import android.content.Context
import android.os.Build
import java.nio.charset.Charset
import java.util.Locale

object EmojiAssetLoader {
    private const val RU_ASSET = "mymouse/emojis_ru"
    private const val UK_ASSET = "mymouse/emojis_uk"
    private val utf8: Charset = Charset.forName("UTF-8")

    fun load(context: Context?): Map<String, String> {
        if (context == null) {
            return emptyMap()
        }

        val asset = if (usesUkrainian(context)) UK_ASSET else RU_ASSET
        return runCatching { readAsset(context, asset) }
            .recoverCatching { readAsset(context, RU_ASSET) }
            .getOrDefault(emptyMap())
    }

    private fun usesUkrainian(context: Context): Boolean {
        val configuration = context.resources.configuration
        val locale = if (Build.VERSION.SDK_INT >= 24) {
            configuration.locales[0]
        } else {
            Locale.getDefault()
        }
        return locale.language.equals("uk", ignoreCase = true)
    }

    private fun readAsset(context: Context, name: String): Map<String, String> {
        val out = HashMap<String, String>()
        context.assets.open(name).use { input ->
            input.bufferedReader(utf8).useLines { lines ->
                lines.forEach { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) {
                        return@forEach
                    }
                    val key = line.substring(0, separator)
                    val value = line.substring(separator + 1).trim()
                    if (key.isNotEmpty() && value.isNotEmpty()) {
                        out[key] = value
                    }
                }
            }
        }
        return out
    }
}
