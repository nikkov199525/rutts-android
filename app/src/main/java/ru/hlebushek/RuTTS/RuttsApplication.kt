package ru.hlebushek.RuTTS

import android.app.Application
import android.util.Log
import ru.hlebushek.RuTTS.config.ConfigRepository
import ru.hlebushek.RuTTS.engine.NativeLibraryLoader
import ru.hlebushek.RuTTS.engine.RulexEngine
import ru.hlebushek.RuTTS.ui.theme.AppTheme

class RuttsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppTheme.applySaved(this)
        warmUpSpeechRuntime()
    }

    private fun warmUpSpeechRuntime() {
        try {
            if (!NativeLibraryLoader.ensureLoaded()) {
                return
            }
            if (ConfigRepository(this).loadConfig().useRulex) {
                RulexEngine(this).warmUp()
            }
        } catch (e: Exception) {
            Log.e(logTag, "Speech runtime warm-up failed", e)
        }
    }

    private companion object {
        const val logTag = "RuTTS.Application"
    }
}
