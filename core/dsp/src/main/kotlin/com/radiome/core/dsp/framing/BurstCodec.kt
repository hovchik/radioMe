package com.radiome.core.dsp.framing

import com.radiome.core.dsp.fec.BlockInterleaver
import com.radiome.core.dsp.fec.ReedSolomon
import com.radiome.core.dsp.modem.ModemProfile
import com.radiome.core.dsp.modem.PhyBurst
import com.radiome.core.dsp.modem.PhyHeader
import com.radiome.core.dsp.modem.RawBurst

enum class CodewordStatus { OK, FEC_REPAIRED, LOST }

class CodewordResult(
    val index: Int,
    val status: CodewordStatus,
    /** Recovered codeword data (k bytes), null when [status] is LOST. */
    val data: ByteArray?,
    val correctedBytes: Int,
    val erasedBytes: Int,
)

/** Frames <-> PHY burst: RS(n = k + nsym) per frame, then byte interleaving across frames. */
object BurstCodec {
    fun encode(
        frames: List<ByteArray>,
        profile: ModemProfile,
        parityRatio: Double = profile.defaultParityRatio,
        interleaveDepth: Int = profile.defaultInterleaveDepth,
    ): PhyBurst {
        require(frames.isNotEmpty() && frames.size <= 255) { "1..255 frames per burst" }
        val k = frames[0].size
        require(frames.all { it.size == k }) { "all frames in a burst must have the same length" }
        val nsym = ReedSolomon.parityFor(k, parityRatio)
        val rs = ReedSolomon(nsym)
        val depth = interleaveDepth.coerceIn(1, 16)
        val body = BlockInterleaver.interleave(frames.map { rs.encode(it) }, depth)
        return PhyBurst(PhyHeader(profile, depth, k, nsym, frames.size), body)
    }

    fun encodeFrames(
        frames: List<Frame>,
        profile: ModemProfile,
        payloadCapacity: Int = FrameCodec.DEFAULT_PAYLOAD,
        parityRatio: Double = profile.defaultParityRatio,
        interleaveDepth: Int = profile.defaultInterleaveDepth,
    ): PhyBurst = encode(frames.map { FrameCodec.encode(it, payloadCapacity) }, profile, parityRatio, interleaveDepth)

    fun decode(raw: RawBurst): List<CodewordResult> {
        val h = raw.header
        val n = h.codewordLength
        val rs = ReedSolomon(h.paritySymbols)
        val cws = BlockInterleaver.deinterleave(raw.body, n, h.codewordCount, h.interleaveDepth)
        val flags = BlockInterleaver.deinterleaveFlags(raw.erasures, n, h.codewordCount, h.interleaveDepth)
        return cws.mapIndexed { i, cw ->
            val erasures = flags[i].indices.filter { flags[i][it] }.toIntArray()
            var res = if (erasures.size <= h.paritySymbols) rs.decode(cw, erasures) else rs.decode(cw)
            if (!res.success && erasures.isNotEmpty() && erasures.size <= h.paritySymbols) res = rs.decode(cw)
            when {
                !res.success -> CodewordResult(i, CodewordStatus.LOST, null, 0, erasures.size)
                res.correctedBytes == 0 -> CodewordResult(i, CodewordStatus.OK, res.data(h.paritySymbols), 0, erasures.size)
                else -> CodewordResult(i, CodewordStatus.FEC_REPAIRED, res.data(h.paritySymbols), res.correctedBytes, erasures.size)
            }
        }
    }

    class FrameResult(val slot: Int, val status: CodewordStatus, val frame: Frame?, val correctedBytes: Int)

    /** Decode and parse link frames; a codeword that RS "repaired" into something with a bad CRC counts as LOST. */
    fun decodeFrames(raw: RawBurst): List<FrameResult> = decode(raw).map { cw ->
        val parsed = cw.data?.let { FrameCodec.decode(it) }
        if (parsed is FrameCodec.Result.Valid) FrameResult(cw.index, cw.status, parsed.frame, cw.correctedBytes)
        else FrameResult(cw.index, CodewordStatus.LOST, null, 0)
    }
}
