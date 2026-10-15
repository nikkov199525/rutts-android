package ru.hlebushek.RuTTS.engine

import android.annotation.SuppressLint
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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
// Поэтому, пока подключён Bluetooth-выход, поток открывается на 1 Гц ниже
// родной частоты: трек теряет право на FastMixer и обрывается мгновенно. Весь
// свой тракт движок считает как прежде, а ресемплинг 47999 -> 48000 в
// AudioFlinger на слух прозрачен. Выигрыш FastMixer по задержке на фоне
// задержки самого Bluetooth неощутим; на динамике и проводных наушниках
// ничего не меняется.
//
// Наличие Bluetooth-выхода не запрашивается на каждую реплику: флаг
// обновляется по событиям подключения и отключения аудиоустройств, реплика
// только читает его.
internal class BluetoothRouting(private val audioManager: AudioManager?) {
    @Volatile
    private var bluetoothOutputConnected = false

    // Тип AudioDeviceCallback появился в API 23 — держим как Any, чтобы на
    // старых версиях класс не загружался.
    private var deviceCallback: Any? = null

    fun start() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val callback = DeviceCallback()
        deviceCallback = callback
        runCatching {
            manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        }
        refresh()
    }

    fun stop() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val callback = deviceCallback as? AudioDeviceCallback ?: return
        deviceCallback = null
        runCatching { manager.unregisterAudioDeviceCallback(callback) }
    }

    fun outputSampleRate(nativeSampleRate: Int): Int {
        return if (bluetoothOutputConnected) nativeSampleRate - 1 else nativeSampleRate
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun refresh() {
        val manager = audioManager ?: return
        bluetoothOutputConnected = runCatching {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in bluetoothDeviceTypes }
        }.getOrDefault(false)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private inner class DeviceCallback : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    private companion object {
        // Числовые типы устройств стабильны; на версиях, где новых типов ещё
        // нет, сравнение с ними просто никогда не срабатывает.
        @SuppressLint("InlinedApi")
        val bluetoothDeviceTypes = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST
        )
    }
}
