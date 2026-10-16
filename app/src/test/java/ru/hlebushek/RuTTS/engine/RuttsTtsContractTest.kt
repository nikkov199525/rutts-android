package ru.hlebushek.RuTTS.engine

import android.speech.tts.TextToSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hlebushek.RuTTS.config.RuttsConfig

class RuttsTtsContractTest {
    @Test
    fun waveBufferMatchesAndroidChunk() {
        assertEquals(8192, NativeRutts.streamingWaveBufferSize(8192))
        assertEquals(128, NativeRutts.streamingWaveBufferSize(64))
    }

    @Test
    fun covoxPreservesTheSelectedSampleRate() {
        assertFalse(RuttsConfig().useCovox)
        assertEquals(10000, RuttsConfig().outputSampleRate)
        assertEquals(
            11025,
            RuttsConfig(useCovox = true, samplesPerSec = 11025).outputSampleRate
        )
    }

    @Test
    fun keepsDirectStreamingForEveryAudioMode() {
        assertTrue(RuttsTtsContract.shouldUseDirectStreaming(RuttsConfig(), equalizerIsFlat = true))
        assertTrue(RuttsTtsContract.shouldUseDirectStreaming(RuttsConfig(), equalizerIsFlat = false))
        assertTrue(
            RuttsTtsContract.shouldUseDirectStreaming(
                RuttsConfig(useRateBoost = true),
                equalizerIsFlat = true
            )
        )
    }

    @Test
    fun acceptsNonRussianHostLocaleAsFallback() {
        assertEquals(
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            RuttsTtsContract.languageAvailability("rus", "RUS", "")
        )
        assertEquals(
            TextToSpeech.LANG_AVAILABLE,
            RuttsTtsContract.languageAvailability("eng", "USA", "")
        )
        assertEquals(
            TextToSpeech.LANG_AVAILABLE,
            RuttsTtsContract.languageAvailability("ukr", "UKR", "screenReader")
        )
    }
}
