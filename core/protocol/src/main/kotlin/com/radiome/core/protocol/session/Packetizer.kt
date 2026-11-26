package com.radiome.core.protocol.session

import com.radiome.core.dsp.framing.BurstCodec
import com.radiome.core.dsp.framing.Frame
import com.radiome.core.dsp.framing.FrameType
import com.radiome.core.dsp.modem.ModemProfile
import com.radiome.core.dsp.modem.PhyBurst
import com.radiome.core.protocol.payload.CompressionMode
import com.radiome.core.protocol.payload.Deflate
import com.radiome.core.protocol.payload.Payload

/** A transfer ready to go on air: one HEADER frame plus [dataFrames] DATA frames. */
class OutgoingTransfer(
    val sessionId: Int,
    val header: SessionHeader,
    val headerFrame: Frame,
    val dataFrames: List<Frame>,
    val options: TransferOptions,
) {
    val totalChunks: Int get() = dataFrames.size

    /** Frames for the given chunk indices; [Packetizer.HEADER_CHUNK] selects the HEADER frame. */
    fun framesFor(indices: Collection<Int>): List<Frame> {
        val set = indices.toSortedSet()
        val out = ArrayList<Frame>(set.size)
        if (Packetizer.HEADER_CHUNK in set) out += headerFrame
        for (i in set) if (i in dataFrames.indices) out += dataFrames[i]
        return out
    }

    fun allFrames(): List<Frame> = listOf(headerFrame) + dataFrames

    /** Split frames into bursts of at most options.maxFramesPerBurst, with the given profile. */
    fun bursts(frames: List<Frame>, profile: ModemProfile = options.profile): List<PhyBurst> =
        Packetizer.bursts(frames, options.withProfile(profile))
}

object Packetizer {
    /** ChunkIndex used by the HEADER frame; also how a NACK asks for the header again. */
    const val HEADER_CHUNK = 0xFFFF

    fun packetize(payload: Payload, sessionId: Int, options: TransferOptions = TransferOptions()): OutgoingTransfer {
        require(sessionId in 0..0xFFFF)
        val original = payload.bytes
        val deflated = if (options.compression == CompressionMode.AUTO && original.isNotEmpty()) Deflate.compress(original) else null
        val compressed = deflated != null && deflated.size < original.size
        val wire = if (compressed) deflated!! else original
        val p = options.chunkPayloadSize
        val total = maxOf(1, (wire.size + p - 1) / p)
        require(total < HEADER_CHUNK) { "payload too large: $total chunks" }
        val header = SessionHeader(
            contentType = payload.type,
            compressed = compressed,
            wireLength = wire.size,
            originalLength = original.size,
            crc32 = SessionHeader.crcOf(original),
            chunkPayloadSize = p,
            name = payload.name,
        )
        val headerFrame = Frame(FrameType.HEADER, sessionId, HEADER_CHUNK, total, header.encode(p))
        val data = List(total) { i ->
            Frame(FrameType.DATA, sessionId, i, total, wire.copyOfRange(minOf(wire.size, i * p), minOf(wire.size, (i + 1) * p)))
        }
        return OutgoingTransfer(sessionId, header, headerFrame, data, options)
    }

    fun bursts(frames: List<Frame>, options: TransferOptions): List<PhyBurst> =
        frames.chunked(options.contentFramesPerBurst).map { group ->
            val station = options.station?.frame(group.first().sessionId, group.first().totalChunks)
            BurstCodec.encodeFrames(
                if (station != null) listOf(station) + group else group,
                options.profile,
                payloadCapacity = options.chunkPayloadSize,
                parityRatio = options.effectiveParityRatio,
                interleaveDepth = options.effectiveInterleaveDepth,
            )
        }

    sealed interface Reassembly {
        data class Ok(val payload: Payload) : Reassembly
        data class Corrupt(val reason: String) : Reassembly
    }

    /** Rebuild the payload from a complete chunk set and verify the CRC-32. */
    fun reassemble(header: SessionHeader, chunks: Map<Int, ByteArray>, totalChunks: Int): Reassembly {
        val wire = ByteArray(header.wireLength)
        var pos = 0
        for (i in 0 until totalChunks) {
            val c = chunks[i] ?: return Reassembly.Corrupt("chunk $i missing")
            if (pos + c.size > wire.size) return Reassembly.Corrupt("chunk $i overflows wire length")
            c.copyInto(wire, pos)
            pos += c.size
        }
        if (pos != wire.size) return Reassembly.Corrupt("wire length $pos != ${header.wireLength}")
        val original = if (header.compressed) {
            runCatching { Deflate.decompress(wire, header.originalLength) }.getOrElse { return Reassembly.Corrupt("inflate: ${it.message}") }
        } else wire
        if (SessionHeader.crcOf(original) != header.crc32) return Reassembly.Corrupt("crc32 mismatch")
        return Reassembly.Ok(Payload(header.contentType, original, header.name))
    }
}
