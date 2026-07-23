package ru.hlebushek.RuTTS.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import ru.hlebushek.RuTTS.config.EqualizerSettings

object AudioEqualizer {
    fun applyInPlace(pcm: ByteArray, sampleRate: Int, settings: EqualizerSettings) {
        Processor(sampleRate, settings).applyInPlace(pcm, 0, pcm.size)
    }

    internal fun streamingProcessor(sampleRate: Int, settings: EqualizerSettings): Processor {
        return Processor(sampleRate, settings)
    }

    internal class Processor(sampleRate: Int, settings: EqualizerSettings) {
        private val bass = settings.bassDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        private val mid = settings.midDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        private val treble = settings.trebleDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        private val filters = buildFilters(sampleRate.coerceAtLeast(MIN_SAMPLE_RATE).toDouble(), bass, mid, treble)
        private val headroomGain = dbToLinear(-maxOf(0, bass, mid, treble) * HEADROOM_DB_PER_BOOST_DB)

        fun applyInPlace(pcm: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset + length <= pcm.size)
            require(offset % BYTES_PER_SAMPLE == 0)
            if (filters.isEmpty() || length < BYTES_PER_SAMPLE) {
                return
            }
            val end = offset + length
            var index = offset
            while (index + 1 < end) {
                var sample = readSample(pcm, index).toDouble() * headroomGain
                var filterIndex = 0
                while (filterIndex < filters.size) {
                    sample = filters[filterIndex].process(sample)
                    filterIndex++
                }
                writeSample(pcm, index, sample.roundToInt().coerceIn(SAMPLE_MIN, SAMPLE_MAX))
                index += BYTES_PER_SAMPLE
            }
        }
    }

    private fun buildFilters(sampleRate: Double, bassDb: Int, midDb: Int, trebleDb: Int): List<Biquad> {
        val filters = ArrayList<Biquad>(3)
        if (bassDb != 0) {
            filters += Biquad.lowShelf(sampleRate, BASS_FREQUENCY_HZ, bassDb.toDouble())
        }
        if (midDb != 0) {
            filters += Biquad.peaking(
                sampleRate,
                MID_FREQUENCY_HZ.coerceAtMost(sampleRate * MID_FREQUENCY_LIMIT),
                MID_Q,
                midDb.toDouble()
            )
        }
        if (trebleDb != 0) {
            filters += Biquad.highShelf(
                sampleRate,
                TREBLE_FREQUENCY_HZ.coerceAtMost(sampleRate * TREBLE_FREQUENCY_LIMIT),
                trebleDb.toDouble()
            )
        }
        return filters
    }

    private fun readSample(pcm: ByteArray, index: Int): Short {
        val low = pcm[index].toInt() and 0xff
        val high = pcm[index + 1].toInt() shl 8
        return (low or high).toShort()
    }

    private fun writeSample(pcm: ByteArray, index: Int, sample: Int) {
        pcm[index] = (sample and 0xff).toByte()
        pcm[index + 1] = ((sample ushr 8) and 0xff).toByte()
    }

    private fun dbToLinear(db: Double): Double {
        return 10.0.pow(db / 20.0)
    }

    private class Biquad(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double
    ) {
        private var z1 = 0.0
        private var z2 = 0.0

        fun process(input: Double): Double {
            val output = b0 * input + z1
            z1 = b1 * input - a1 * output + z2
            z2 = b2 * input - a2 * output
            return output
        }

        companion object {
            fun peaking(sampleRate: Double, frequency: Double, q: Double, gainDb: Double): Biquad {
                val a = 10.0.pow(gainDb / 40.0)
                val w0 = 2.0 * PI * frequency / sampleRate
                val alpha = sin(w0) / (2.0 * q)
                val cosW0 = cos(w0)
                return normalize(
                    b0 = 1.0 + alpha * a,
                    b1 = -2.0 * cosW0,
                    b2 = 1.0 - alpha * a,
                    a0 = 1.0 + alpha / a,
                    a1 = -2.0 * cosW0,
                    a2 = 1.0 - alpha / a
                )
            }

            fun lowShelf(sampleRate: Double, frequency: Double, gainDb: Double): Biquad {
                val a = 10.0.pow(gainDb / 40.0)
                val w0 = 2.0 * PI * frequency / sampleRate
                val cosW0 = cos(w0)
                val alpha = sin(w0) / 2.0 * sqrt(2.0)
                val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
                return normalize(
                    b0 = a * ((a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha),
                    b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0),
                    b2 = a * ((a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha),
                    a0 = (a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha,
                    a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW0),
                    a2 = (a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha
                )
            }

            fun highShelf(sampleRate: Double, frequency: Double, gainDb: Double): Biquad {
                val a = 10.0.pow(gainDb / 40.0)
                val w0 = 2.0 * PI * frequency / sampleRate
                val cosW0 = cos(w0)
                val alpha = sin(w0) / 2.0 * sqrt(2.0)
                val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
                return normalize(
                    b0 = a * ((a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha),
                    b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0),
                    b2 = a * ((a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha),
                    a0 = (a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha,
                    a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW0),
                    a2 = (a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha
                )
            }

            private fun normalize(
                b0: Double,
                b1: Double,
                b2: Double,
                a0: Double,
                a1: Double,
                a2: Double
            ): Biquad {
                return Biquad(
                    b0 = b0 / a0,
                    b1 = b1 / a0,
                    b2 = b2 / a0,
                    a1 = a1 / a0,
                    a2 = a2 / a0
                )
            }
        }
    }

    private const val BYTES_PER_SAMPLE = 2
    private const val MIN_SAMPLE_RATE = 8000
    private const val MIN_GAIN_DB = -12
    private const val MAX_GAIN_DB = 12
    private const val SAMPLE_MIN = Short.MIN_VALUE.toInt()
    private const val SAMPLE_MAX = Short.MAX_VALUE.toInt()
    private const val BASS_FREQUENCY_HZ = 220.0
    private const val MID_FREQUENCY_HZ = 1000.0
    private const val MID_FREQUENCY_LIMIT = 0.24
    private const val MID_Q = 0.9
    private const val TREBLE_FREQUENCY_HZ = 3200.0
    private const val TREBLE_FREQUENCY_LIMIT = 0.35
    private const val HEADROOM_DB_PER_BOOST_DB = 0.35
}
