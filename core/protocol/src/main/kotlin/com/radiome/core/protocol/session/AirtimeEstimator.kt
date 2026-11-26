package com.radiome.core.protocol.session

import com.radiome.core.dsp.fec.ReedSolomon
import com.radiome.core.dsp.framing.FrameCodec
import com.radiome.core.dsp.modem.MfskModemEngine
import com.radiome.core.dsp.modem.PhyHeader

/** Exact on-air time, computed from the same burst geometry the modulator will use. */
class AirtimeEstimator(private val engine: MfskModemEngine = MfskModemEngine()) {
    fun framesMs(frameCount: Int, options: TransferOptions): Long {
        if (frameCount <= 0) return 0
        val k = FrameCodec.frameLength(options.chunkPayloadSize)
        val nsym = ReedSolomon.parityFor(k, options.effectiveParityRatio)
        var remaining = frameCount
        var total = 0L
        val perBurst = options.contentFramesPerBurst
        val extra = if (options.station != null) 1 else 0
        while (remaining > 0) {
            val n = minOf(remaining, perBurst)
            total += engine.airtimeMs(PhyHeader(options.profile, options.effectiveInterleaveDepth, k, nsym, n + extra))
            remaining -= n
        }
        return total
    }

    /** Air time for sending [wireBytes] of payload, HEADER frame included. */
    fun payloadMs(wireBytes: Int, options: TransferOptions): Long {
        val chunks = maxOf(1, (wireBytes + options.chunkPayloadSize - 1) / options.chunkPayloadSize)
        return framesMs(chunks + 1, options)
    }
}
