package ru.hlebushek.RuTTS.engine

import java.nio.charset.Charset

object RulexCodec {
    private val koi8Charset = Charset.forName("KOI8-R")

    @JvmStatic
    private external fun nativePackDataRaw(key: ByteArray, value: ByteArray): ByteArray?

    @JvmStatic
    private external fun nativeApplyDiffsRaw(base: ByteArray, diffs: ByteArray): ByteArray

    fun packData(key: String, value: String): ByteArray? {
        NativeLibraryLoader.requireLoaded()
        return nativePackDataRaw(
            key.toByteArray(koi8Charset),
            value.toByteArray(koi8Charset)
        )
    }

    fun applyDiffs(base: String, diffs: ByteArray): String {
        NativeLibraryLoader.requireLoaded()
        return nativeApplyDiffsRaw(base.toByteArray(koi8Charset), diffs).toString(koi8Charset)
    }
}
