package com.radiome.feature.transceiver.settings

import com.radiome.core.audio.AudioEngineConfig
import com.radiome.core.audio.RoutePreference
import com.radiome.core.dsp.modem.ModemConfig
import com.radiome.core.dsp.modem.ModemProfile
import com.radiome.core.protocol.arq.ArqConfig
import com.radiome.core.protocol.image.ImagePreset
import com.radiome.core.protocol.image.MaxEdge
import com.radiome.core.protocol.link.Callsign
import com.radiome.core.protocol.link.StationInfo
import com.radiome.core.protocol.session.TransferOptions

/** Everything the user can change, as stored. [sanitized] clamps values read from disk into their legal ranges. */
data class RadioSettings(
    /** VOX / squelch opening pilot before each burst. */
    val leadInMs: Int = ModemConfig.DEFAULT_LEAD_IN_MS,
    val defaultProfile: ModemProfile = ModemProfile.STANDARD,
    /** Reed-Solomon parity as % of data bytes, 20..50. Null uses the profile's default. */
    val parityPercent: Int? = null,
    /** Codewords per interleave block, 1..16. Null uses the profile's default. */
    val interleaveDepth: Int? = null,
    /** Payload bytes per chunk (P), 32..64. */
    val chunkPayloadSize: Int = 48,
    val autoNack: Boolean = true,
    val sendAck: Boolean = true,
    val stepDownToRobust: Boolean = true,
    /** Quiet time before the receiver answers (radio turnaround). */
    val turnaroundMs: Int = 2000,
    val routePreference: RoutePreference = RoutePreference.AUTO,
    /** Digital gain on transmitted audio, 10..100 %. */
    val outputGainPercent: Int = 100,
    val imageMaxEdge: MaxEdge = MaxEdge.PX_240,
    val imageQuality: Int = 25,
    val imageGrayscale: Boolean = false,
    /** Send a small preview level first so the receiver sees the picture early. */
    val imageProgressive: Boolean = true,
    /** This station's callsign, sent in clear at the start of every burst. Blank: no identification. */
    val callsign: String = "",
    /** One-tap presets on the compose card. */
    val quickMessages: List<String> = DEFAULT_QUICK_MESSAGES,
    /** Seal texts, images and files with the group key too (positions are always sealed). */
    val encryptMessages: Boolean = false,
    /** Send our position every this many minutes while the radio is on; 0 = only when asked. */
    val beaconIntervalMin: Int = 0,
    /** Profile for position beacons: they are tiny, so the robust one costs little. */
    val beaconProfile: ModemProfile = ModemProfile.ROBUST,
) {
    fun sanitized(): RadioSettings = copy(
        leadInMs = leadInMs.coerceIn(ModemConfig.MIN_LEAD_IN_MS, ModemConfig.MAX_LEAD_IN_MS),
        parityPercent = parityPercent?.coerceIn(MIN_PARITY, MAX_PARITY),
        interleaveDepth = interleaveDepth?.coerceIn(1, MAX_INTERLEAVE),
        chunkPayloadSize = chunkPayloadSize.coerceIn(TransferOptions.MIN_CHUNK, TransferOptions.MAX_CHUNK),
        turnaroundMs = turnaroundMs.coerceIn(MIN_TURNAROUND, MAX_TURNAROUND),
        outputGainPercent = outputGainPercent.coerceIn(MIN_GAIN, 100),
        imageQuality = imageQuality.coerceIn(ImagePreset.MIN_QUALITY, ImagePreset.MAX_QUALITY),
        callsign = Callsign.normalize(callsign),
        beaconIntervalMin = beaconIntervalMin.takeIf { it in BEACON_INTERVALS } ?: 0,
        quickMessages = quickMessages.map { it.trim().take(MAX_QUICK_LENGTH) }.filter { it.isNotEmpty() }.distinct().take(MAX_QUICK_MESSAGES),
    )

    fun modemConfig(): ModemConfig = ModemConfig(leadInMs = sanitized().leadInMs)

    fun arqConfig(): ArqConfig = sanitized().let {
        ArqConfig(
            autoNack = it.autoNack,
            turnaroundMs = it.turnaroundMs,
            stepDownToRobust = it.stepDownToRobust,
            sendAck = it.sendAck,
        )
    }

    fun audioConfig(): AudioEngineConfig = AudioEngineConfig(outputGain = outputGain)

    val outputGain: Float get() = sanitized().outputGainPercent / 100f

    /** [to] is the addressee's callsign; null or blank sends to every station. */
    fun transferOptions(profile: ModemProfile = defaultProfile, to: String? = null): TransferOptions = sanitized().let {
        val dest = to?.let(Callsign::normalize)?.ifEmpty { null }
        TransferOptions(
            profile = profile,
            chunkPayloadSize = it.chunkPayloadSize,
            parityRatio = it.parityPercent?.let { p -> p / 100.0 },
            interleaveDepth = it.interleaveDepth,
            station = if (it.callsign.isEmpty() && dest == null) null else StationInfo(it.callsign, dest),
            encrypt = it.encryptMessages,
        )
    }

    fun imagePreset(): ImagePreset = sanitized().let {
        ImagePreset(it.imageMaxEdge, it.imageQuality, it.imageGrayscale, progressive = it.imageProgressive)
    }

    /** True when switching from [old] to this needs the link restarted (lead-in, ARQ timing, audio engine). */
    fun needsLinkRestart(old: RadioSettings): Boolean =
        modemConfig() != old.modemConfig() || arqConfig() != old.arqConfig()

    companion object {
        const val MIN_PARITY = 20
        const val MAX_PARITY = 50
        const val MAX_INTERLEAVE = 16
        const val MIN_TURNAROUND = 500
        const val MAX_TURNAROUND = 5000
        const val MIN_GAIN = 10
        val BEACON_INTERVALS = listOf(0, 5, 15, 30, 60)
        const val MAX_QUICK_MESSAGES = 12
        const val MAX_QUICK_LENGTH = 60
        val DEFAULT_QUICK_MESSAGES = listOf("OK", "Copy that", "Need help", "At position", "Returning to base", "Stand by")
    }
}
