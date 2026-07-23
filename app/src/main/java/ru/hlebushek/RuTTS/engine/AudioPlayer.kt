package ru.hlebushek.RuTTS.engine

import android.annotation.TargetApi
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
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
        val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            createApi23Track(sampleRate, bufferSize)
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STATIC
            )
        }

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

    @TargetApi(Build.VERSION_CODES.M)
    private fun createApi23Track(sampleRate: Int, bufferSize: Int): AudioTrack {
        return AudioTrack.Builder()
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
