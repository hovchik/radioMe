package com.radiome.core.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RouteMode {
    /** Phone speaker and microphone held next to the radio. */
    ACOUSTIC,
    /** Wired TRRS or a USB-C audio interface (Digirig, APRS-K1 style cables). */
    CABLE,
}

enum class RoutePreference { AUTO, ACOUSTIC, CABLE }

data class AudioRoute(
    val mode: RouteMode,
    val inputName: String,
    val outputName: String,
    val input: AudioDeviceInfo? = null,
    val output: AudioDeviceInfo? = null,
)

/** Watches attached audio devices and picks input/output for the chosen [RoutePreference]. */
class AudioRouteMonitor(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val _route = MutableStateFlow(resolve(RoutePreference.AUTO))
    val route: StateFlow<AudioRoute> = _route.asStateFlow()

    var preference: RoutePreference = RoutePreference.AUTO
        set(value) {
            field = value
            _route.value = resolve(value)
        }

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            _route.value = resolve(preference)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            _route.value = resolve(preference)
        }
    }

    fun start() = audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))

    fun stop() = audioManager.unregisterAudioDeviceCallback(callback)

    private fun resolve(pref: RoutePreference): AudioRoute {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val cableIn = inputs.firstOrNull { it.type in CABLE_TYPES }
        val cableOut = outputs.firstOrNull { it.type in CABLE_TYPES }
        val useCable = when (pref) {
            RoutePreference.CABLE -> true
            RoutePreference.ACOUSTIC -> false
            RoutePreference.AUTO -> cableIn != null && cableOut != null
        }
        return if (useCable) {
            AudioRoute(RouteMode.CABLE, label(cableIn), label(cableOut), cableIn, cableOut)
        } else {
            val mic = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            val speaker = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            AudioRoute(RouteMode.ACOUSTIC, label(mic), label(speaker), mic, speaker)
        }
    }

    private fun label(d: AudioDeviceInfo?): String = d?.productName?.toString()?.takeIf { it.isNotBlank() } ?: "System default"

    private companion object {
        val CABLE_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
        )
    }
}
