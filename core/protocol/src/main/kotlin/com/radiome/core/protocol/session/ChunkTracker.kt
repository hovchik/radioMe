package com.radiome.core.protocol.session

import com.radiome.core.dsp.framing.CodewordStatus
import com.radiome.core.dsp.framing.Frame
import com.radiome.core.dsp.framing.FrameType

enum class ChunkState {
    /** Not received yet, and nothing indicates it was lost (yet). */
    PENDING,
    /** Received clean. */
    OK,
    /** Received after Reed-Solomon repaired it. */
    REPAIRED,
    /** Known to be missing: a later chunk arrived, or the sender's over ended without it. */
    MISSING,
}

/** Persistable view of a partially received session (stored by the data layer for resumable repairs). */
data class ReceiverSnapshot(
    val sessionId: Int,
    val totalChunks: Int?,
    val header: SessionHeader?,
    val chunks: Map<Int, ByteArray>,
    val repairedChunks: Set<Int>,
    val fecCorrectedBytes: Int,
    val repairRounds: Int,
)

/**
 * Receiver-side bookkeeping for one session: which chunks are in, which were repaired by FEC, which are missing,
 * and the loss percentage = missing / totalChunks.
 */
class ChunkTracker(val sessionId: Int) {
    var totalChunks: Int? = null
        private set
    var header: SessionHeader? = null
        private set
    private val chunks = HashMap<Int, ByteArray>()
    private val repaired = HashSet<Int>()
    private var highestSeen = -1
    private var overEnded = false
    var fecCorrectedBytes = 0
        private set

    /** Returns true if the frame added something new. */
    fun accept(frame: Frame, status: CodewordStatus, correctedBytes: Int): Boolean {
        require(frame.sessionId == sessionId)
        if (frame.totalChunks > 0 && totalChunks == null) totalChunks = frame.totalChunks
        return when (frame.type) {
            FrameType.HEADER -> {
                if (header != null) return false
                header = SessionHeader.decode(frame.payload) ?: return false
                fecCorrectedBytes += correctedBytes
                true
            }
            FrameType.DATA -> {
                val total = totalChunks ?: return false
                if (frame.chunkIndex !in 0 until total || frame.chunkIndex in chunks) return false
                chunks[frame.chunkIndex] = frame.payload
                if (status == CodewordStatus.FEC_REPAIRED) repaired += frame.chunkIndex
                fecCorrectedBytes += correctedBytes
                highestSeen = maxOf(highestSeen, frame.chunkIndex)
                true
            }
            else -> false
        }
    }

    /** The sender finished an over: everything not received is now MISSING. */
    fun markOverEnded() {
        overEnded = true
    }

    fun beginRepairOver() {
        overEnded = false
    }

    val receivedCount: Int get() = chunks.size
    val repairedCount: Int get() = repaired.size
    val headerReceived: Boolean get() = header != null

    /** Missing DATA chunk indices (sorted). */
    val missingData: List<Int> get() = totalChunks?.let { t -> (0 until t).filter { it !in chunks } } ?: emptyList()

    /** What a NACK must ask for: missing DATA chunks plus the HEADER sentinel if the header never arrived. */
    val missingForNack: List<Int> get() = missingData + if (headerReceived) emptyList() else listOf(Packetizer.HEADER_CHUNK)

    val lossPercent: Double get() = totalChunks?.let { if (it == 0) 0.0 else missingData.size * 100.0 / it } ?: 100.0

    val isComplete: Boolean get() = headerReceived && totalChunks != null && chunks.size == totalChunks

    fun state(index: Int): ChunkState = when {
        index in repaired -> ChunkState.REPAIRED
        index in chunks -> ChunkState.OK
        overEnded || index < highestSeen -> ChunkState.MISSING
        else -> ChunkState.PENDING
    }

    fun states(): List<ChunkState> = List(totalChunks ?: 0) { state(it) }

    fun chunks(): Map<Int, ByteArray> = chunks

    /** Best-effort partial payload: received wire bytes with zeros where chunks are missing. */
    fun partialWire(): ByteArray? {
        val h = header ?: return null
        val total = totalChunks ?: return null
        val out = ByteArray(h.wireLength)
        for (i in 0 until total) chunks[i]?.copyInto(out, minOf(out.size, i * h.chunkPayloadSize))
        return out
    }

    /** True when every wire byte in [from, until) has arrived (always false before the HEADER frame). */
    fun hasBytes(from: Int, until: Int): Boolean {
        val h = header ?: return false
        if (from >= until) return true
        if (from < 0 || until > h.wireLength) return false
        val p = h.chunkPayloadSize
        for (c in from / p..(until - 1) / p) if (c !in chunks) return false
        return true
    }

    fun snapshot(repairRounds: Int): ReceiverSnapshot =
        ReceiverSnapshot(sessionId, totalChunks, header, HashMap(chunks), HashSet(repaired), fecCorrectedBytes, repairRounds)

    companion object {
        fun restore(s: ReceiverSnapshot): ChunkTracker = ChunkTracker(s.sessionId).apply {
            totalChunks = s.totalChunks
            header = s.header
            chunks.putAll(s.chunks)
            repaired.addAll(s.repairedChunks)
            fecCorrectedBytes = s.fecCorrectedBytes
            highestSeen = s.chunks.keys.maxOrNull() ?: -1
            overEnded = true
        }
    }
}
