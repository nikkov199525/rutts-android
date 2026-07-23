package ru.hlebushek.RuTTS.debug

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import androidx.preference.PreferenceManager
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max
import ru.hlebushek.RuTTS.config.PreferenceKeys

class TtsProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != actionSpeak) {
            return
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val text = intent.getStringExtra(extraTextBase64)
            ?.takeIf { it.isNotBlank() }
            ?.let(::decodeBase64Text)
            ?: intent.getStringExtra(extraText)?.takeIf { it.isNotBlank() }
            ?: defaultText
        val speechRate = intent.getFloatExtra(extraSpeechRate, 1.0f)
        val pitch = intent.getFloatExtra(extraPitch, 1.0f)
        val requestedEngine = intent.getStringExtra(extraEngine)?.takeIf { it.isNotBlank() }
        val repeatCount = intent.getIntExtra(extraRepeatCount, 1).coerceIn(1, maxRepeatCount)
        val repeatIntervalMs = intent.getLongExtra(extraRepeatIntervalMs, defaultRepeatIntervalMs)
            .coerceIn(minRepeatIntervalMs, maxRepeatIntervalMs)
        val markerDelayMs = intent.getLongExtra(extraMarkerDelayMs, 0L)
            .coerceIn(0L, maxMarkerDelayMs)
        val captureDurationMs = intent.getLongExtra(extraCaptureDurationMs, 0L)
            .coerceIn(0L, maxCaptureDurationMs)
        applyProbeSettings(appContext, intent)
        val defaultEngine = Settings.Secure.getString(appContext.contentResolver, "tts_default_synth")
        val requestStartedAt = SystemClock.elapsedRealtimeNanos()
        val broadcastFinished = AtomicBoolean(false)
        val utteranceIssuedAt = ConcurrentHashMap<String, Long>()
        val firstAudioReceived = ConcurrentHashMap.newKeySet<String>()
        val lastAudioReceivedAt = ConcurrentHashMap<String, Long>()
        val maximumAudioGap = ConcurrentHashMap<String, Long>()
        val audioBlockCount = ConcurrentHashMap<String, Int>()
        val audioByteCount = ConcurrentHashMap<String, Long>()
        val handler = Handler(Looper.getMainLooper())

        if (captureDurationMs > 0L) {
            startMicrophoneCapture(appContext, captureDurationMs)
        }

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
        tts = TextToSpeech(appContext, { status ->
            val engine = tts?.defaultEngine
            Log.i(
                logTag,
                "init status=$status afterMs=${elapsedMs(requestStartedAt)} requestedEngine=$requestedEngine " +
                    "defaultEngineSetting=$defaultEngine connectedEngine=$engine"
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
                        Log.i(logTag, "utterance start afterMs=${elapsedMs(requestStartedAt)} id=$utteranceId")
                    }

                    override fun onBeginSynthesis(
                        utteranceId: String,
                        sampleRateInHz: Int,
                        audioFormat: Int,
                        channelCount: Int
                    ) {
                        Log.i(
                            logTag,
                            "begin synthesis afterSpeakMs=${elapsedMs(utteranceIssuedAt[utteranceId])} " +
                                "sampleRate=$sampleRateInHz format=$audioFormat channels=$channelCount id=$utteranceId"
                        )
                    }

                    override fun onAudioAvailable(utteranceId: String, audio: ByteArray) {
                        val receivedAt = SystemClock.elapsedRealtimeNanos()
                        lastAudioReceivedAt.put(utteranceId, receivedAt)?.let { previous ->
                            maximumAudioGap.merge(utteranceId, receivedAt - previous, ::max)
                        }
                        audioBlockCount.merge(utteranceId, 1, Int::plus)
                        audioByteCount.merge(utteranceId, audio.size.toLong(), Long::plus)
                        if (firstAudioReceived.add(utteranceId)) {
                            Log.i(
                                logTag,
                                "first audio afterSpeakMs=${elapsedMs(utteranceIssuedAt[utteranceId])} " +
                                    "bytes=${audio.size} id=$utteranceId"
                            )
                        }
                    }

                    override fun onDone(utteranceId: String) {
                        Log.i(
                            logTag,
                            "utterance done afterMs=${elapsedMs(requestStartedAt)} " +
                                "afterSpeakMs=${elapsedMs(utteranceIssuedAt[utteranceId])} " +
                                "blocks=${audioBlockCount[utteranceId] ?: 0} bytes=${audioByteCount[utteranceId] ?: 0L} " +
                                "maxAudioGapMs=${nanosToMs(maximumAudioGap[utteranceId] ?: 0L)} id=$utteranceId"
                        )
                        if (repeatCount == 1) {
                            shutdown(instance)
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String) {
                        Log.e(logTag, "utterance error id=$utteranceId")
                        if (repeatCount == 1) {
                            shutdown(instance)
                        }
                    }

                    override fun onError(utteranceId: String, errorCode: Int) {
                        Log.e(logTag, "utterance error id=$utteranceId code=$errorCode")
                        if (repeatCount == 1) {
                            shutdown(instance)
                        }
                    }
                }
            )

            val languageResult = instance.setLanguage(Locale("ru", "RU"))
            instance.setSpeechRate(speechRate)
            instance.setPitch(pitch)

            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }
            lateinit var issueSpeech: (Int) -> Unit
            issueSpeech = { index ->
                if (activeTts === instance) {
                    val utteranceId = "probe-${SystemClock.uptimeMillis()}-$index"
                    utteranceIssuedAt[utteranceId] = SystemClock.elapsedRealtimeNanos()
                    val speakResult = instance.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
                    Log.i(
                        logTag,
                        "speak index=$index/$repeatCount textChars=${text.length} languageResult=$languageResult speechRate=$speechRate " +
                            "pitch=$pitch utteranceId=$utteranceId result=$speakResult"
                    )
                    if (speakResult != TextToSpeech.SUCCESS) {
                        shutdown(instance)
                    } else if (index + 1 < repeatCount) {
                        handler.postDelayed({ issueSpeech(index + 1) }, repeatIntervalMs)
                    } else if (repeatCount > 1) {
                        handler.postDelayed(
                            { shutdown(instance) },
                            maxOf(postBurstShutdownMs, text.length * postBurstShutdownPerCharMs)
                        )
                    }
                }
            }
            if (markerDelayMs > 0L) {
                val marker = ToneGenerator(AudioManager.STREAM_MUSIC, markerVolume)
                marker.startTone(ToneGenerator.TONE_DTMF_1, markerDurationMs)
                Log.i(logTag, "marker started delayMs=$markerDelayMs")
                handler.postDelayed({ marker.release() }, markerDurationMs.toLong())
                handler.postDelayed({ issueSpeech(0) }, markerDelayMs)
            } else {
                issueSpeech(0)
            }
            finishBroadcast()
        }, requestedEngine)
    }

    companion object {
        private const val logTag = "RuTTS.DebugProbe"
        private const val actionSpeak = "ru.hlebushek.RuTTS.DEBUG_SPEAK"
        private const val extraText = "text"
        private const val extraTextBase64 = "text_base64"
        private const val extraSpeechRate = "speech_rate"
        private const val extraPitch = "pitch"
        private const val extraEngine = "engine"
        private const val extraRepeatCount = "repeat_count"
        private const val extraRepeatIntervalMs = "repeat_interval_ms"
        private const val extraMarkerDelayMs = "marker_delay_ms"
        private const val extraCaptureDurationMs = "capture_duration_ms"
        private const val extraBaseRate = "base_rate"
        private const val extraBasePitch = "base_pitch"
        private const val extraSampleRate = "sample_rate"
        private const val extraInterpolationMultiplier = "interpolation_multiplier"
        private const val extraInterpolationAlgorithm = "interpolation_algorithm"
        private const val extraSilenceAtEnd = "silence_at_end"
        private const val extraEqBass = "eq_bass"
        private const val extraEqMid = "eq_mid"
        private const val extraEqTreble = "eq_treble"
        private const val extraUseRulex = "use_rulex"
        private const val extraUseNewRateAlgorithm = "use_new_rate_algorithm"
        private const val extraUseRateBoost = "use_rate_boost"
        private const val extraUseSoftResampling = "use_soft_resampling"
        private const val extraIntonation = "intonation"
        private const val defaultText = "\u042f \u0440\u043e\u0434\u0438\u043b\u0441\u044f!"
        private const val maxRepeatCount = 50
        private const val defaultRepeatIntervalMs = 30L
        private const val minRepeatIntervalMs = 1L
        private const val maxRepeatIntervalMs = 1_000L
        private const val postBurstShutdownMs = 2_000L
        private const val postBurstShutdownPerCharMs = 80L
        private const val markerDurationMs = 100
        private const val markerVolume = 100
        private const val maxMarkerDelayMs = 5_000L
        private const val maxCaptureDurationMs = 15_000L
        private const val captureSampleRate = 48_000
        private const val captureFileName = "tts_probe_capture.pcm"

        private fun decodeBase64Text(value: String): String {
            return Base64.decode(value, Base64.DEFAULT).toString(Charsets.UTF_8)
        }

        private fun elapsedMs(startedAtNanos: Long): Double {
            return (SystemClock.elapsedRealtimeNanos() - startedAtNanos) / 1_000_000.0
        }

        private fun elapsedMs(startedAtNanos: Long?): Double {
            return startedAtNanos?.let(::elapsedMs) ?: Double.NaN
        }

        private fun nanosToMs(nanos: Long): Double = nanos / 1_000_000.0

        private fun applyProbeSettings(context: Context, intent: Intent) {
            if (!intent.hasExtra(extraSampleRate)) {
                return
            }
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(PreferenceKeys.RUNTIME_SPEECH_RATE, intent.getIntExtra(extraBaseRate, 100).toString())
                .putString(PreferenceKeys.RUNTIME_PITCH, intent.getIntExtra(extraBasePitch, 100).toString())
                .putString(PreferenceKeys.SAMPLES_PER_SEC, intent.getIntExtra(extraSampleRate, 10_000).toString())
                .putString(
                    PreferenceKeys.INTERPOLATION_MULTIPLIER,
                    intent.getIntExtra(extraInterpolationMultiplier, 1).toString()
                )
                .putString(
                    PreferenceKeys.INTERPOLATION_ALGORITHM,
                    intent.getIntExtra(extraInterpolationAlgorithm, 0).toString()
                )
                .putInt(PreferenceKeys.SILENCE_AT_END, intent.getIntExtra(extraSilenceAtEnd, 0))
                .putInt(PreferenceKeys.EQ_BASS, intent.getIntExtra(extraEqBass, 0))
                .putInt(PreferenceKeys.EQ_MID, intent.getIntExtra(extraEqMid, 0))
                .putInt(PreferenceKeys.EQ_TREBLE, intent.getIntExtra(extraEqTreble, 0))
                .putBoolean(PreferenceKeys.USE_RULEX, intent.getBooleanExtra(extraUseRulex, true))
                .putBoolean(
                    PreferenceKeys.USE_NEW_RATE_ALGORITHM,
                    intent.getBooleanExtra(extraUseNewRateAlgorithm, false)
                )
                .putBoolean(PreferenceKeys.USE_RATE_BOOST, intent.getBooleanExtra(extraUseRateBoost, false))
                .putBoolean(
                    PreferenceKeys.USE_SOFT_RESAMPLING,
                    intent.getBooleanExtra(extraUseSoftResampling, false)
                )
                .putString(PreferenceKeys.INTONATION, intent.getIntExtra(extraIntonation, 100).toString())
                .commit()
        }

        private fun startMicrophoneCapture(context: Context, durationMs: Long) {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Log.e(logTag, "microphone permission is not granted")
                return
            }
            val minBufferSize = AudioRecord.getMinBufferSize(
                captureSampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufferSize <= 0) {
                Log.e(logTag, "microphone buffer is unavailable: $minBufferSize")
                return
            }
            val ready = CountDownLatch(1)
            val destination = File(context.filesDir, captureFileName)
            thread(name = "RuTTS-MicrophoneCapture", isDaemon = true) {
                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    captureSampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBufferSize, 4_096)
                )
                try {
                    recorder.startRecording()
                    ready.countDown()
                    Log.i(logTag, "microphone capture started elapsedRealtimeMs=${SystemClock.elapsedRealtime()}")
                    val deadline = SystemClock.elapsedRealtime() + durationMs
                    val buffer = ByteArray(maxOf(minBufferSize, 4_096))
                    FileOutputStream(destination, false).use { output ->
                        while (SystemClock.elapsedRealtime() < deadline) {
                            val count = recorder.read(buffer, 0, buffer.size)
                            if (count > 0) {
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    Log.i(logTag, "microphone capture saved bytes=${destination.length()}")
                } catch (e: Exception) {
                    Log.e(logTag, "microphone capture failed", e)
                } finally {
                    ready.countDown()
                    recorder.release()
                }
            }
            if (!ready.await(1, TimeUnit.SECONDS)) {
                Log.e(logTag, "microphone capture did not start")
            }
        }

        @Volatile
        private var activeTts: TextToSpeech? = null
    }
}
