package ru.hlebushek.RuTTS.engine

import android.os.Build
import android.speech.tts.SynthesisCallback
import androidx.annotation.RequiresApi
import ru.hlebushek.RuTTS.config.EqualizerSettings

class EqualizingSynthesisCallback(
    private val delegate: SynthesisCallback,
    sampleRate: Int,
    settings: EqualizerSettings
) : SynthesisCallback {
    private val processor = AudioEqualizer.streamingProcessor(sampleRate, settings)

    override fun getMaxBufferSize(): Int = delegate.maxBufferSize

    override fun start(sampleRateInHz: Int, audioFormat: Int, channelCount: Int): Int {
        return delegate.start(sampleRateInHz, audioFormat, channelCount)
    }

    override fun audioAvailable(buffer: ByteArray, offset: Int, length: Int): Int {
        processor.applyInPlace(buffer, offset, length)
        return delegate.audioAvailable(buffer, offset, length)
    }

    override fun done(): Int = delegate.done()

    override fun error() = delegate.error()

    // Методы SynthesisCallback, добавленные во фреймворк позже минимального
    // API — фреймворк вызывает их только на своих версиях. Делегируем.
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    override fun error(errorCode: Int) = delegate.error(errorCode)

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    override fun hasStarted(): Boolean = delegate.hasStarted()

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    override fun hasFinished(): Boolean = delegate.hasFinished()

    @RequiresApi(Build.VERSION_CODES.O)
    override fun rangeStart(markerInFrames: Int, start: Int, end: Int) {
        delegate.rangeStart(markerInFrames, start, end)
    }
}
