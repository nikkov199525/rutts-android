package ru.hlebushek.RuTTS.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.max

class AudioPlayer {
    private var audioTrack: AudioTrack? = null

    fun playAndWait(sampleRate: Int, pcm: ByteArray) {
        stop()
        if (pcm.isEmpty()) {
            return
        }

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = max(minBufferSize, pcm.size)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track.release() }
            throw IllegalStateException("AudioTrack initialization failed")
        }

        audioTrack = track
        val written = track.write(pcm, 0, pcm.size)
        if (written <= 0) {
            audioTrack = null
            runCatching { track.release() }
            throw IllegalStateException("AudioTrack write failed: $written")
        }

        val totalFrames = written / 2
        try {
            track.play()
            while (audioTrack === track) {
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    break
                }
                if (track.playbackHeadPosition >= totalFrames) {
                    break
                }
                Thread.sleep(25)
            }
        } finally {
            if (audioTrack === track) {
                stop()
            }
        }
    }

    fun stop() {
        val track = audioTrack ?: return
        runCatching {
            track.pause()
            track.flush()
            track.stop()
        }
        runCatching {
            track.release()
        }
        audioTrack = null
    }
}

