package com.radiome.core.protocol.session

import com.radiome.core.dsp.framing.FrameCodec
import com.radiome.core.dsp.modem.ModemProfile
import com.radiome.core.protocol.payload.CompressionMode

data class TransferOptions(
    val profile: ModemProfile = ModemProfile.STANDARD,
    /** Payload bytes per frame, 32..64 (P). */
    val chunkPayloadSize: Int = FrameCodec.DEFAULT_PAYLOAD,
    /** RS parity / data, 0.20..0.50. Null uses the profile's default. */
    val parityRatio: Double? = null,
    /** Frames per interleave block. Null uses the profile's default. */
    val interleaveDepth: Int? = null,
    val compression: CompressionMode = CompressionMode.AUTO,
    /** Upper bound on frames in one burst (each burst repeats lead-in, sync and PHY header). */
    val maxFramesPerBurst: Int = 64,
) {
    init {
        require(chunkPayloadSize in MIN_CHUNK..MAX_CHUNK) { "chunk payload must be $MIN_CHUNK..$MAX_CHUNK" }
        parityRatio?.let { require(it in 0.20..0.50) { "parity ratio must be 0.20..0.50" } }
        interleaveDepth?.let { require(it in 1..16) }
        require(maxFramesPerBurst in 1..255)
    }

    val effectiveParityRatio: Double get() = parityRatio ?: profile.defaultParityRatio
    val effectiveInterleaveDepth: Int get() = interleaveDepth ?: profile.defaultInterleaveDepth

    fun withProfile(p: ModemProfile) = copy(profile = p, parityRatio = parityRatio, interleaveDepth = interleaveDepth)

    companion object {
        const val MIN_CHUNK = 32
        const val MAX_CHUNK = 64
    }
}
