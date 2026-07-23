package ru.hlebushek.RuTTS.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import ru.hlebushek.RuTTS.config.EqualizerSettings

class AudioEqualizerTest {
    @Test
    fun streamingBlocksMatchWholeBufferProcessing() {
        val original = ByteArray(4096) { index -> ((index * 37 + 11) and 0xff).toByte() }
        val settings = EqualizerSettings(bassDb = 6, midDb = -4, trebleDb = 3)
        val wholeBuffer = original.clone()
        val streamed = original.clone()

        AudioEqualizer.applyInPlace(wholeBuffer, 48000, settings)
        val processor = AudioEqualizer.streamingProcessor(48000, settings)
        var offset = 0
        val blockSizes = intArrayOf(318, 514, 1024, 2240)
        blockSizes.forEach { length ->
            processor.applyInPlace(streamed, offset, length)
            offset += length
        }

        assertArrayEquals(wholeBuffer, streamed)
    }
}
