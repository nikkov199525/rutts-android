package ru.hlebushek.RuTTS.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.HashMap
import java.util.concurrent.atomic.AtomicBoolean

class TtsProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != actionSpeak) {
            return
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val text = intent.getStringExtra(extraText)?.takeIf { it.isNotBlank() } ?: defaultText
        val speechRate = intent.getFloatExtra(extraSpeechRate, 1.0f)
        val pitch = intent.getFloatExtra(extraPitch, 1.0f)
        val defaultEngine = Settings.Secure.getString(appContext.contentResolver, "tts_default_synth")
        val broadcastFinished = AtomicBoolean(false)

        fun finishBroadcast() {
            if (!broadcastFinished.compareAndSet(false, true)) {
                return
            }
            pendingResult.finish()
        }

        fun shutdown(tts: TextToSpeech? = activeTts) {
            try {
                tts?.stop()
                tts?.shutdown()
            } catch (e: Exception) {
                Log.w(logTag, "Failed to shutdown probe TTS cleanly", e)
            } finally {
                if (activeTts === tts) {
                    activeTts = null
                }
            }
        }

        activeTts?.shutdown()
        activeTts = null

        var tts: TextToSpeech? = null
        tts = TextToSpeech(appContext) { status ->
            val engine = tts?.defaultEngine
            Log.i(
                logTag,
                "init status=$status requestedText='$text' defaultEngineSetting=$defaultEngine connectedEngine=$engine"
            )
            if (status != TextToSpeech.SUCCESS) {
                shutdown(tts)
                finishBroadcast()
                return@TextToSpeech
            }

            val instance = tts ?: run {
                finishBroadcast()
                return@TextToSpeech
            }
            activeTts = instance

            instance.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) {
                        Log.i(logTag, "utterance start id=$utteranceId")
                    }

                    override fun onDone(utteranceId: String) {
                        Log.i(logTag, "utterance done id=$utteranceId")
                        shutdown(instance)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String) {
                        Log.e(logTag, "utterance error id=$utteranceId")
                        shutdown(instance)
                    }

                    override fun onError(utteranceId: String, errorCode: Int) {
                        Log.e(logTag, "utterance error id=$utteranceId code=$errorCode")
                        shutdown(instance)
                    }
                }
            )

            val languageResult = instance.setLanguage(Locale("ru", "RU"))
            instance.setSpeechRate(speechRate)
            instance.setPitch(pitch)

            val utteranceId = "probe-${SystemClock.uptimeMillis()}"
            val speakResult = speakCompat(instance, text, utteranceId)
            Log.i(
                logTag,
                "speak languageResult=$languageResult speechRate=$speechRate pitch=$pitch utteranceId=$utteranceId result=$speakResult"
            )
            finishBroadcast()
            if (speakResult != TextToSpeech.SUCCESS) {
                shutdown(instance)
            }
        }
    }

    companion object {
        private const val logTag = "RuTTS.DebugProbe"
        private const val actionSpeak = "ru.hlebushek.RuTTS.DEBUG_SPEAK"
        private const val extraText = "text"
        private const val extraSpeechRate = "speech_rate"
        private const val extraPitch = "pitch"
        private const val defaultText = "\u042f \u0440\u043e\u0434\u0438\u043b\u0441\u044f!"

        @Volatile
        private var activeTts: TextToSpeech? = null
    }

    private fun speakCompat(tts: TextToSpeech, text: String, utteranceId: String): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        } else {
            @Suppress("DEPRECATION")
            tts.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                HashMap<String, String>().apply {
                    put(TextToSpeech.Engine.KEY_PARAM_VOLUME, "1.0")
                    put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                }
            )
        }
    }
}
