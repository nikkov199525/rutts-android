package ru.hlebushek.RuTTS.engine

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import kotlin.math.min
import kotlin.math.roundToInt
import ru.hlebushek.RuTTS.BuildConfig
import ru.hlebushek.RuTTS.config.ConfigRepository
import ru.hlebushek.RuTTS.config.RuntimeSettings
import java.util.concurrent.atomic.AtomicLong

class RuttsTextToSpeechService : TextToSpeechService() {
    private lateinit var repository: ConfigRepository
    private lateinit var rulexEngine: RulexEngine
    private lateinit var textPreprocessor: TextPreprocessor

    private val requestGeneration = AtomicLong(0)

    override fun onCreate() {
        super.onCreate()
        repository = ConfigRepository(this)
        rulexEngine = RulexEngine(this)
        textPreprocessor = TextPreprocessor(
            rulexEngine = rulexEngine,
            emojiTextRewriter = EmojiTextRewriter(this),
            phoneNumberTextRewriter = PhoneNumberTextRewriter(this)
        )
    }

    override fun onGetLanguage(): Array<String> {
        return arrayOf(
            RuttsTtsContract.iso3Language,
            RuttsTtsContract.iso3Country,
            ""
        )
    }

    override fun onIsLanguageAvailable(lang: String, country: String?, variant: String?): Int {
        return RuttsTtsContract.languageAvailability(lang, country, variant)
    }

    override fun onLoadLanguage(lang: String, country: String?, variant: String?): Int {
        return onIsLanguageAvailable(lang, country, variant)
    }

    override fun onGetVoices(): MutableList<Voice> {
        return mutableListOf(
            Voice(
                RuttsTtsContract.voiceName,
                RuttsTtsContract.locale,
                Voice.QUALITY_NORMAL,
                Voice.LATENCY_LOW,
                false,
                emptySet()
            )
        )
    }

    override fun onGetDefaultVoiceNameFor(lang: String, country: String, variant: String): String? {
        return if (onIsLanguageAvailable(lang, country, variant) != TextToSpeech.LANG_NOT_SUPPORTED) {
            RuttsTtsContract.voiceName
        } else {
            null
        }
    }

    override fun onLoadVoice(voiceName: String): Int {
        return if (voiceName == RuttsTtsContract.voiceName) {
            TextToSpeech.SUCCESS
        } else {
            TextToSpeech.ERROR
        }
    }

    override fun onIsValidVoiceName(voiceName: String): Int {
        return if (voiceName == RuttsTtsContract.voiceName) {
            TextToSpeech.SUCCESS
        } else {
            TextToSpeech.ERROR
        }
    }

    override fun onStop() {
        requestGeneration.incrementAndGet()
        NativeRutts.requestStop()
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val requestId = requestGeneration.incrementAndGet()
        NativeRutts.requestStop()
        try {
            synthesizeText(request, callback, requestId)
        } catch (_: InterruptedException) {
            Log.e(logTag, "InterruptedException")
            signalError(callback, requestId, TextToSpeech.ERROR_OUTPUT)
        } catch (e: Exception) {
            Log.e(logTag, "Exception during synthesis", e)
            signalError(callback, requestId, TextToSpeech.ERROR_SYNTHESIS)
        } finally {
            // done() обязателен на любом исходе и ровно один раз: именно он
            // отправляет клиенту onDone/onError. Сам error() ничего не
            // отправляет, а фреймворк дозывает done() только если успел
            // отработать start() (AOSP PlaybackSynthesisCallback.done(),
            // SynthesisSpeechItem.playImpl). Без этого вызова TalkBack ждёт
            // завершения реплики бесконечно и вся очередь речи встаёт.
            callback.done()
        }
    }

    private fun synthesizeText(
        request: SynthesisRequest,
        callback: SynthesisCallback,
        requestId: Long
    ) {
        if (!NativeLibraryLoader.ensureLoaded()) {
            Log.e(logTag, "native library unavailable: ${NativeLibraryLoader.diagnostic()}")
            signalError(callback, requestId, TextToSpeech.ERROR_SYNTHESIS)
            return
        }
        if (!isRequestSupported(request)) {
            Log.e(logTag, "request not supported")
            signalError(callback, requestId, TextToSpeech.ERROR_SYNTHESIS)
            return
        }

        val text = request.charSequenceText?.toString().orEmpty()
        if (text.isBlank()) {
            startSilentUtterance(callback, requestId)
            return
        }

        val synthesisSettings = repository.loadSynthesisSettings()
        val config = synthesisSettings.config
        val runtimeDefaults = synthesisSettings.runtimeSettings
        val equalizerSettings = synthesisSettings.equalizerSettings
        val characters = synthesisSettings.characters
        val singleCharacters = synthesisSettings.singleCharacters
        val runtimeSettings = RuntimeSettings(
            speechRate = scalePercent(runtimeDefaults.speechRate, request.speechRate, 20, 500),
            pitchPercent = scalePercent(runtimeDefaults.pitchPercent, request.pitch, 50, 300),
            volumePercent = extractVolumePercent(request, runtimeDefaults.volumePercent),
            spellOut = false
        )
        val voicePitchPercent = runtimeSettings.pitchPercent

        val directStreaming = RuttsTtsContract.shouldUseDirectStreaming(
            config,
            equalizerSettings.isFlat
        )
        logLatency(
            SystemClock.elapsedRealtimeNanos(),
            "streaming decision direct=$directStreaming flat=${equalizerSettings.isFlat} " +
                "rateBoost=${config.useRateBoost} newRate=${config.useNewRateAlgorithm} " +
                "rulex=${config.useRulex} softResampling=${config.useSoftResampling} " +
                "interpolation=${config.interpolationMultiplier} covox=${config.useCovox}"
        )
        if (directStreaming) {
            val directStartedAt = SystemClock.elapsedRealtimeNanos()
            logLatency(directStartedAt, "direct request entered")
            val maxChunkSize = androidChunkSize(callback)
            val outputSampleRate = if (config.useCovox) {
                config.outputSampleRate
            } else {
                platformOutputSampleRate(config.outputSampleRate)
            }
            val streamingCallback = if (equalizerSettings.isFlat) {
                callback
            } else {
                EqualizingSynthesisCallback(callback, outputSampleRate, equalizerSettings)
            }
            val segments = sourceSegments(text).iterator()
            val startResult = streamingCallback.start(outputSampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
            if (startResult != TextToSpeech.SUCCESS) return
            var index = 0
            var segment = segments.next()
            while (true) {
                val isLastSegment = !segments.hasNext()
                if (!isActive(requestId)) return
                logLatency(directStartedAt, "text processing started segment=$index chars=${segment.length}")
                val preparedSegment = textPreprocessor.prepare(
                    text = segment,
                    spellOut = runtimeSettings.spellOut,
                    config = config,
                    characters = characters,
                    singleCharacters = singleCharacters
                )
                logLatency(directStartedAt, "text processing finished")
                if (!isActive(requestId)) return
                if (preparedSegment.isNotBlank()) {
                    val segmentConfig = config.copy(
                        silenceAtBegin = if (index == 0) config.silenceAtBegin else 0,
                        silenceAtEnd = if (isLastSegment) config.silenceAtEnd else 0
                    )
                    val completed = NativeRutts.synthesizeStreaming(
                        preparedSegment,
                        segmentConfig,
                        runtimeSettings,
                        voicePitchPercent,
                        streamingCallback,
                        outputSampleRate,
                        maxChunkSize
                    )
                    if (!completed) {
                        logLatency(directStartedAt, "native streaming stopped segment=$index")
                        return
                    }
                }
                if (isLastSegment) break
                segment = segments.next()
                index++
            }
            // Ни один сегмент не дал произносимого текста (строка из скобок,
            // эмодзи при выключенном чтении эмодзи и т.п.) — это не сбой:
            // start() уже отработал, реплика завершится пустой в done().
            return
        }

        val preparedText = textPreprocessor.prepare(
            text = text,
            spellOut = runtimeSettings.spellOut,
            config = config,
            characters = characters,
            singleCharacters = singleCharacters
        )
        if (!isActive(requestId)) {
            Log.w(logTag, "stopped before synthesis")
            return
        }
        if (preparedText.isBlank()) {
            startSilentUtterance(callback, requestId)
            return
        }

        val pcm = NativeRutts.synthesize(preparedText, config, runtimeSettings, voicePitchPercent)
        if (!isActive(requestId)) {
            Log.w(logTag, "stopped after synthesis")
            return
        }
        if (pcm.isEmpty()) {
            Log.w(logTag, "native synth returned empty pcm")
            startSilentUtterance(callback, requestId)
            return
        }
        AudioEqualizer.applyInPlace(pcm, config.outputSampleRate, equalizerSettings)

        val startResult = callback.start(config.outputSampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
        if (startResult != TextToSpeech.SUCCESS) {
            return
        }

        writeAudio(callback, pcm, pcm.size, androidChunkSize(callback), requestId)
    }

    // Пустая реплика: строка без произносимого текста (пустая строка между
    // абзацами, один разделитель) прилетает от TalkBack отдельным запросом.
    // Отвечать error() нельзя — TalkBack считает подряд идущие ошибки отказом
    // движка и уходит на другой. Отвечаем успехом: start() без единого
    // audioAvailable() создаёт AudioTrack, но не запускает его — ни задержки,
    // ни щелчка. Реплику закрывает общий done() в onSynthesizeText.
    private fun startSilentUtterance(callback: SynthesisCallback, requestId: Long) {
        if (!isActive(requestId)) {
            return
        }
        callback.start(
            platformOutputSampleRate(SILENT_UTTERANCE_SAMPLE_RATE),
            AudioFormat.ENCODING_PCM_16BIT,
            1
        )
    }

    private fun isRequestSupported(request: SynthesisRequest): Boolean {
        val voiceName = request.voiceName?.takeIf { it.isNotBlank() }
        if (voiceName != null && voiceName != RuttsTtsContract.voiceName) {
            return false
        }

        val language = request.language?.takeIf { it.isNotBlank() } ?: return true
        val country = request.country?.takeIf { it.isNotBlank() }
        val variant = request.variant?.takeIf { it.isNotBlank() }
        return onIsLanguageAvailable(language, country, variant) != TextToSpeech.LANG_NOT_SUPPORTED
    }

    private fun extractVolumePercent(request: SynthesisRequest, defaultVolumePercent: Int): Int {
        if (!request.params.containsKey(TextToSpeech.Engine.KEY_PARAM_VOLUME)) {
            return defaultVolumePercent.coerceIn(0, 100)
        }

        val volume = request.params.getFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        return scalePercent(defaultVolumePercent, (volume.coerceIn(0.0f, 1.0f) * 100.0f).roundToInt(), 0, 100)
    }

    private fun writeAudio(
        callback: SynthesisCallback,
        pcm: ByteArray,
        size: Int,
        maxChunkSize: Int,
        requestId: Long
    ): Boolean {
        val available = size.coerceIn(0, pcm.size)
        var offset = 0
        while (offset < available) {
            if (!isActive(requestId)) {
                return false
            }
            val chunkSize = min(maxChunkSize, available - offset)
            val result = callback.audioAvailable(pcm, offset, chunkSize)
            if (result != TextToSpeech.SUCCESS) {
                Log.e(logTag, "audioAvailable failed result=$result offset=$offset chunkSize=$chunkSize")
                return false
            }
            offset += chunkSize
        }
        return true
    }

    private fun isActive(requestId: Long): Boolean {
        return requestGeneration.get() == requestId
    }

    private fun androidChunkSize(callback: SynthesisCallback): Int {
        return callback.maxBufferSize.coerceAtLeast(2) and 1.inv()
    }

    private fun sourceSegments(text: String): Sequence<String> = sequence {
        var start = 0
        while (start < text.length) {
            val isFirstSegment = start == 0
            val maxChars = if (isFirstSegment) {
                FIRST_SOURCE_SEGMENT_CHARS
            } else {
                MAX_SOURCE_SEGMENT_CHARS
            }
            val minChars = if (isFirstSegment) {
                MIN_FIRST_SOURCE_SEGMENT_CHARS
            } else {
                MIN_SOURCE_SEGMENT_CHARS
            }
            var end = preferredSegmentEnd(text, start, minChars, maxChars)
            if (end <= start) end = min(start + maxChars, text.length)
            if (end < text.length && end > start && Character.isLowSurrogate(text[end])) end--
            yield(text.substring(start, end))
            start = end
        }
    }

    private fun preferredSegmentEnd(text: String, start: Int, minChars: Int, maxChars: Int): Int {
        val hardLimit = min(start + maxChars, text.length)
        if (hardLimit >= text.length) return text.length
        val softStart = min(start + minChars, hardLimit)
        for (index in (hardLimit - 1) downTo softStart) {
            if (text[index] == '.' || text[index] == '!' || text[index] == '?' ||
                text[index] == ';' || text[index] == '\n'
            ) return includeTrailingSpace(text, index + 1)
        }
        for (index in (hardLimit - 1) downTo softStart) {
            if (text[index].isWhitespace()) return includeTrailingSpace(text, index + 1)
        }
        return hardLimit
    }

    private fun includeTrailingSpace(text: String, index: Int): Int {
        var end = index
        while (end < text.length && text[end].isWhitespace()) end++
        return end
    }

    private fun platformOutputSampleRate(fallbackSampleRate: Int): Int {
        return AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
            .takeIf { it in MIN_OUTPUT_SAMPLE_RATE..MAX_OUTPUT_SAMPLE_RATE }
            ?: fallbackSampleRate
    }

    private fun signalError(callback: SynthesisCallback, requestId: Long, errorCode: Int) {
        if (isActive(requestId)) {
            callback.error(errorCode)
        }
    }

    private fun logLatency(startedAtNanos: Long, stage: String) {
        if (!BuildConfig.DEBUG) {
            return
        }
        val now = SystemClock.elapsedRealtimeNanos()
        Log.i(
            "RuTTS.Latency",
            "$stage elapsedMs=${(now - startedAtNanos) / 1_000_000.0} elapsedRealtimeMs=${now / 1_000_000L}"
        )
    }

    private fun scalePercent(baseValue: Int, systemPercent: Int, minValue: Int, maxValue: Int): Int {
        return ((baseValue.coerceAtLeast(0) * systemPercent.coerceAtLeast(0)) / 100.0f)
            .roundToInt()
            .coerceIn(minValue, maxValue)
    }

    companion object {
        private const val logTag = "RuTTS.TtsService"
        private const val SILENT_UTTERANCE_SAMPLE_RATE = 16000
        private const val MIN_OUTPUT_SAMPLE_RATE = 8000
        private const val MAX_OUTPUT_SAMPLE_RATE = 192000
        private const val MIN_FIRST_SOURCE_SEGMENT_CHARS = 80
        private const val FIRST_SOURCE_SEGMENT_CHARS = 160
        private const val MIN_SOURCE_SEGMENT_CHARS = 240
        private const val MAX_SOURCE_SEGMENT_CHARS = 700
    }
}
