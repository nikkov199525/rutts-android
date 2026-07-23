package ru.hlebushek.RuTTS.engine

object NativeRulex {
    fun nativeEnsureLoaded(databasePath: String, rulexSeed: ByteArray): Boolean {
        NativeLibraryLoader.requireLoaded()
        return nativeEnsureLoadedRaw(databasePath, rulexSeed)
    }

    @JvmStatic
    private external fun nativeEnsureLoadedRaw(databasePath: String, rulexSeed: ByteArray): Boolean

    fun nativeSearchWord(word: ByteArray): ByteArray {
        NativeLibraryLoader.requireLoaded()
        return nativeSearchWordRaw(word)
    }

    @JvmStatic
    private external fun nativeSearchWordRaw(word: ByteArray): ByteArray

    fun nativeIsLoaded(): Boolean {
        if (!NativeLibraryLoader.ensureLoaded()) return false
        return nativeIsLoadedRaw()
    }

    @JvmStatic
    private external fun nativeIsLoadedRaw(): Boolean
}
