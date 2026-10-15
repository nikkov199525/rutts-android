package ru.hlebushek.RuTTS.engine

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.annotation.RequiresApi

// Движок открывает поток у фреймворка (callback.start()) на родной частоте
// выхода: без ресемплинга в AudioFlinger и с правом на FastMixer. Если
// экранный диктор просит низкую задержку (AudioAttributes.FLAG_LOW_LATENCY),
// AudioPolicy запрашивает fast-трек, и AudioFlinger выдаёт его только при
// частоте трека, равной аппаратной, — т. е. как раз нам.
//
// Но fast-трек не обрывается при остановке реплики. Фреймворк TTS гасит её
// через AudioTrack.stop() + release() без flush() (BlockingAudioTrack), а
// AudioFlinger держит остановленный и даже освобождённый fast-трек активным,
// пока не доиграет весь его буфер (STOPPING_1 -> STOPPING_2 -> presentation
// complete). Обычный трек микшера release() обрывает сразу. На Bluetooth
// буфер трека рассчитывается от задержки BT-выхода и тянет на сотни
// миллисекунд: при быстрых свайпах хвосты прерванных реплик звучат поверх
// новой, речь двоится и троится. На динамике буфер короткий и хвост не слышен.
//
// Поэтому при выводе на Bluetooth поток открывается на 1 Гц ниже родной
// частоты: трек теряет право на FastMixer и обрывается мгновенно. Весь свой
// тракт движок считает как прежде, а ресемплинг 47999 -> 48000 в AudioFlinger
// на слух прозрачен. Выигрыш FastMixer по задержке на фоне задержки самого
// Bluetooth неощутим; на динамике и проводных наушниках ничего не меняется.
internal object BluetoothRouting {
    fun outputSampleRate(
        audioManager: AudioManager?,
        requestParams: Bundle?,
        nativeSampleRate: Int
    ): Int {
        return if (isSpeechRoutedToBluetooth(audioManager, requestParams)) {
            nativeSampleRate - 1
        } else {
            nativeSampleRate
        }
    }

    private fun isSpeechRoutedToBluetooth(audioManager: AudioManager?, requestParams: Bundle?): Boolean {
        if (audioManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return runCatching {
            // До API 33 маршрут для конкретных атрибутов не узнать — считаем, что
            // речь идёт туда же, куда подключён Bluetooth-выход.
            val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                audioManager.getAudioDevicesForAttributes(speechAttributes(requestParams))
            } else {
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).asList()
            }
            devices.any { it.type in bluetoothDeviceTypes }
        }.getOrDefault(false)
    }

    // Те же атрибуты, с которыми фреймворк создаст AudioTrack для реплики
    // (TextToSpeechService.AudioOutputParams.createFromParamsBundle).
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun speechAttributes(requestParams: Bundle?): AudioAttributes {
        requestParams?.getParcelable(KEY_PARAM_AUDIO_ATTRIBUTES, AudioAttributes::class.java)
            ?.let { return it }
        val streamType = requestParams?.getInt(
            TextToSpeech.Engine.KEY_PARAM_STREAM,
            TextToSpeech.Engine.DEFAULT_STREAM
        ) ?: TextToSpeech.Engine.DEFAULT_STREAM
        return AudioAttributes.Builder()
            .setLegacyStreamType(streamType)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    // Скрытая константа TextToSpeech.Engine.KEY_PARAM_AUDIO_ATTRIBUTES.
    private const val KEY_PARAM_AUDIO_ATTRIBUTES = "audioAttributes"

    // Числовые типы устройств стабильны; на версиях, где новых типов ещё нет,
    // сравнение с ними просто никогда не срабатывает.
    @SuppressLint("InlinedApi")
    private val bluetoothDeviceTypes = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST
    )
}
