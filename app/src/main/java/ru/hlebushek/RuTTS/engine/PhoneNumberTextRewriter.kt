package ru.hlebushek.RuTTS.engine

import android.content.Context
import io.michaelrocks.libphonenumber.android.PhoneNumberUtil

class PhoneNumberTextRewriter(context: Context?) {
    private val appContext: Context? = context?.applicationContext ?: context

    @Volatile
    private var phoneNumberUtil: PhoneNumberUtil? = null

    @Volatile
    private var unavailable = false

    fun rewrite(text: String, enabled: Boolean): String {
        if (text.isEmpty() || !enabled || unavailable || !mayContainPhoneNumber(text)) {
            return text
        }

        val local = phoneNumberUtil ?: synchronized(this) {
            phoneNumberUtil ?: runCatching { PhoneNumberUtil.createInstance(appContext) }
                .onFailure { unavailable = true }
                .getOrNull()
                ?.also { phoneNumberUtil = it }
        } ?: return text

        return rewrite(text, local)
    }

    private fun rewrite(text: String, util: PhoneNumberUtil): String {
        var out: StringBuilder? = null
        var last = 0
        val matches = util.findNumbers(
            text,
            DEFAULT_REGION,
            PhoneNumberUtil.Leniency.VALID,
            Long.MAX_VALUE
        )
        for (match in matches) {
            if (match.start() < last) {
                continue
            }

            val spoken = speakRawPhoneNumber(match.rawString())
            if (spoken.isEmpty()) {
                continue
            }

            val target = out ?: StringBuilder(text.length + 32).also { out = it }
            target.append(text, last, match.start())
            appendReadable(target, spoken)
            last = match.end()
        }

        val target = out ?: return text
        target.append(text, last, text.length)
        return target.toString()
    }

    private fun mayContainPhoneNumber(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val cp = text.codePointAt(index)
            if (cp == '+'.code || Character.digit(cp, 10) >= 0) {
                return true
            }
            index += Character.charCount(cp)
        }
        return false
    }

    private companion object {
        private const val DEFAULT_REGION = "RU"
        private val digitWords = arrayOf(
            "ноль",
            "один",
            "два",
            "три",
            "четыре",
            "пять",
            "шесть",
            "семь",
            "восемь",
            "девять"
        )

        fun speakRawPhoneNumber(raw: String?): String {
            val out = StringBuilder()
            var sawDigit = false
            var index = 0
            while (raw != null && index < raw.length) {
                val cp = raw.codePointAt(index)
                if (cp == '+'.code && !sawDigit && out.isEmpty()) {
                    appendWord(out, "плюс")
                } else {
                    val digit = Character.digit(cp, 10)
                    if (digit >= 0) {
                        appendWord(out, digitWords[digit])
                        sawDigit = true
                    }
                }
                index += Character.charCount(cp)
            }
            return out.toString()
        }

        fun appendReadable(out: StringBuilder, value: String) {
            if (out.isNotEmpty() && !Character.isWhitespace(out[out.length - 1])) {
                out.append(' ')
            }
            out.append(value).append(' ')
        }

        private fun appendWord(out: StringBuilder, word: String) {
            if (out.isNotEmpty()) {
                out.append(' ')
            }
            out.append(word)
        }
    }
}
