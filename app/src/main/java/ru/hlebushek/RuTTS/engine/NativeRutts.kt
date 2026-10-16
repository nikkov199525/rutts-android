package ru.hlebushek.RuTTS.engine

import android.speech.tts.SynthesisCallback
import ru.hlebushek.RuTTS.config.RuntimeSettings
import ru.hlebushek.RuTTS.config.RuttsConfig
import java.nio.charset.Charset

object NativeRutts {
    private val koi8Charset = Charset.forName("KOI8-R")

    @JvmStatic
    private external fun nativeSynthesizeRaw(text: ByteArray, config: IntArray): ByteArray

    @JvmStatic
    private external fun nativeSynthesizeStreamingRaw(
        text: ByteArray,
        config: IntArray,
        callback: SynthesisCallback,
        outputSampleRate: Int,
        maxChunkSize: Int
    ): Boolean

    @JvmStatic
    private external fun nativeRequestStopRaw()

    fun synthesize(
        preparedText: String,
        config: RuttsConfig,
        runtimeSettings: RuntimeSettings,
        voicePitchPercent: Int = runtimeSettings.pitchPercent
    ): ByteArray {
        NativeLibraryLoader.requireLoaded()
        val textBytes = preparedText.toByteArray(koi8Charset)
        return nativeSynthesizeRaw(textBytes, buildConfig(config, runtimeSettings, voicePitchPercent))
    }

    fun synthesizeStreaming(
        preparedText: String,
        config: RuttsConfig,
        runtimeSettings: RuntimeSettings,
        voicePitchPercent: Int = runtimeSettings.pitchPercent,
        callback: SynthesisCallback,
        outputSampleRate: Int,
        maxChunkSize: Int
    ): Boolean {
        NativeLibraryLoader.requireLoaded()
        val textBytes = preparedText.toByteArray(koi8Charset)
        val streamingConfig = buildConfig(config, runtimeSettings, voicePitchPercent).also {
            it[streamingWaveBufferConfigIndex] = streamingWaveBufferSize(maxChunkSize)
        }
        return nativeSynthesizeStreamingRaw(
            textBytes,
            streamingConfig,
            callback,
            outputSampleRate,
            maxChunkSize
        )
    }

    fun requestStop() {
        if (NativeLibraryLoader.ensureLoaded()) {
            nativeRequestStopRaw()
        }
    }

    private fun buildConfig(
        config: RuttsConfig,
        runtimeSettings: RuntimeSettings,
        voicePitchPercent: Int
    ): IntArray {
        return intArrayOf(
            runtimeSettings.speechRate.coerceIn(20, 500),
            runtimeSettings.volumePercent.coerceIn(0, 100),
            config.samplesPerSec,
            config.waveBufferSize,
            config.silenceAtBegin,
            config.silenceAtEnd,
            if (config.useAlternativeVoice) 1 else 0,
            if (config.useRateBoost) 1 else 0,
            if (config.decSepPoint) 1 else 0,
            if (config.decSepComma) 1 else 0,
            config.generalGapFactor,
            config.commaGapFactor,
            config.dotGapFactor,
            config.semicolonGapFactor,
            config.colonGapFactor,
            config.questionGapFactor,
            config.exclamationGapFactor,
            config.intonationalGapFactor,
            voicePitchPercent.coerceIn(50, 300),
            config.intonation,
            if (config.useNewRateAlgorithm) 1 else 0,
            config.sampleRateFactor
        )
    }

    // Буфер ru_tts равен максимальному блоку фреймворка, как в SmartVoice
    // (audioBufferSize = callback.getMaxBufferSize()).
    internal fun streamingWaveBufferSize(maxChunkSize: Int): Int =
        maxChunkSize.coerceAtLeast(minStreamingWaveBufferSize)

    private const val streamingWaveBufferConfigIndex = 3
    private const val minStreamingWaveBufferSize = 128

}

