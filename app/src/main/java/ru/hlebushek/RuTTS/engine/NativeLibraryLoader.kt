package ru.hlebushek.RuTTS.engine

import android.util.Log

object NativeLibraryLoader {
    @Volatile
    private var loaded = false

    @Volatile
    private var lastError: Throwable? = null

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary(LIBRARY_NAME)
            loaded = true
            lastError = null
            true
        } catch (error: Throwable) {
            lastError = error
            Log.e(TAG, "Unable to load $LIBRARY_NAME", error)
            false
        }
    }

    fun requireLoaded() {
        if (!ensureLoaded()) {
            throw UnsatisfiedLinkError(lastError?.message ?: "Unable to load $LIBRARY_NAME")
        }
    }

    fun diagnostic(): String = lastError?.toString().orEmpty()

    private const val LIBRARY_NAME = "rutts_android"
    private const val TAG = "RuTTS.NativeLoader"
}
