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
    /**
     * Cable was asked for (preference CABLE) but no cable input and output are attached, so Android's default
     * devices (usually the speaker and microphone) are in use.
     */
    val cableMissing: Boolean = false,
)

/** The parts of an AudioDeviceInfo the route choice depends on; plain, so the choice is unit tested. */
data class DeviceDesc(val id: Int, val type: Int, val name: String, val address: String = "")

data class RouteChoice(val mode: RouteMode, val input: DeviceDesc?, val output: DeviceDesc?, val cableMissing: Boolean = false)

/**
 * Picks the input and output for a [RoutePreference]. Pure.
 *
 * Cable devices: USB audio (Digirig, CM108 dongles, USB-C headset adapters) wins over the 3.5 mm jack, and the input
 * and output come from the same device when it has both, so a USB interface is never paired with the jack. AUTO
 * takes the cable only when it has both directions (headphones without a mic stay acoustic).
 */
object RoutePicker {
    // Android AudioDeviceInfo.TYPE_* values, inlined so this compiles and runs on the plain JVM.
    const val BUILTIN_EARPIECE = 1
    const val BUILTIN_SPEAKER = 2
    const val WIRED_HEADSET = 3
    const val WIRED_HEADPHONES = 4
    const val LINE_ANALOG = 5
    const val BUILTIN_MIC = 15
    const val USB_DEVICE = 11
    const val USB_ACCESSORY = 12
    const val USB_HEADSET = 22

    private val USB_TYPES = setOf(USB_DEVICE, USB_HEADSET, USB_ACCESSORY)
    private val JACK_TYPES = setOf(WIRED_HEADSET, WIRED_HEADPHONES, LINE_ANALOG)
    val CABLE_TYPES = USB_TYPES + JACK_TYPES

    fun isCable(type: Int) = type in CABLE_TYPES

    fun pick(pref: RoutePreference, inputs: List<DeviceDesc>, outputs: List<DeviceDesc>): RouteChoice {
        val (cableIn, cableOut) = cablePair(inputs, outputs)
        val haveCable = cableIn != null && cableOut != null
        return when {
            pref == RoutePreference.CABLE && haveCable -> RouteChoice(RouteMode.CABLE, cableIn, cableOut)
            // Forced cable with only one direction attached: use what there is, flag the rest.
            pref == RoutePreference.CABLE -> RouteChoice(RouteMode.CABLE, cableIn, cableOut, cableMissing = true)
            pref == RoutePreference.AUTO && haveCable -> RouteChoice(RouteMode.CABLE, cableIn, cableOut)
            else -> RouteChoice(
                RouteMode.ACOUSTIC,
                inputs.firstOrNull { it.type == BUILTIN_MIC },
                outputs.firstOrNull { it.type == BUILTIN_SPEAKER },
            )
        }
    }

    private fun rank(d: DeviceDesc) = if (d.type in USB_TYPES) 0 else 1

    private fun cablePair(inputs: List<DeviceDesc>, outputs: List<DeviceDesc>): Pair<DeviceDesc?, DeviceDesc?> {
        val ins = inputs.filter { isCable(it.type) }.sortedBy(::rank)
        val outs = outputs.filter { isCable(it.type) }.sortedBy(::rank)
        // Same physical device in both directions: same address (USB card); only when either address is unknown,
        // same family and name. Two different non-empty addresses are never the same device.
        fun addressesDiffer(i: DeviceDesc, o: DeviceDesc) = i.address.isNotEmpty() && o.address.isNotEmpty() && i.address != o.address
        for (i in ins) {
            val o = outs.firstOrNull { o ->
                (i.address.isNotEmpty() && i.address == o.address) ||
                    (!addressesDiffer(i, o) && rank(i) == rank(o) && i.name == o.name)
            }
            if (o != null) return i to o
        }
        for (i in ins) {
            val o = outs.firstOrNull { rank(it) == rank(i) && !addressesDiffer(i, it) }
            if (o != null) return i to o
        }
        return ins.firstOrNull() to outs.firstOrNull()
    }
}

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
        val choice = RoutePicker.pick(pref, inputs.map(::desc), outputs.map(::desc))
        val input = choice.input?.let { c -> inputs.firstOrNull { it.id == c.id } }
        val output = choice.output?.let { c -> outputs.firstOrNull { it.id == c.id } }
        return AudioRoute(choice.mode, label(input), label(output), input, output, choice.cableMissing)
    }

    private fun desc(d: AudioDeviceInfo) = DeviceDesc(d.id, d.type, d.productName?.toString().orEmpty(), d.address.orEmpty())

    private fun label(d: AudioDeviceInfo?): String = d?.productName?.toString()?.takeIf { it.isNotBlank() } ?: "System default"
}
