package com.radiome.core.protocol.arq

import com.radiome.core.dsp.framing.CodewordStatus
import com.radiome.core.dsp.framing.Frame
import com.radiome.core.dsp.framing.FrameCodec
import com.radiome.core.dsp.framing.FrameType
import com.radiome.core.dsp.framing.NackCodec
import com.radiome.core.dsp.modem.ModemProfile
import com.radiome.core.dsp.modem.PhyBurst
import com.radiome.core.protocol.payload.Payload
import com.radiome.core.protocol.session.ChunkState
import com.radiome.core.protocol.session.ChunkTracker
import com.radiome.core.protocol.session.OutgoingTransfer
import com.radiome.core.protocol.session.Packetizer
import com.radiome.core.protocol.session.ReceiverSnapshot
import com.radiome.core.protocol.session.TransferOptions

/** Options for NACK / ACK / poll bursts. */
/**
 * Options for NACK / ACK / poll bursts. A whole correction request always fits one burst
 * (255 frames x 360 chunks per bitmap frame > 65535 chunks), so the sender never acts on half a NACK.
 */
internal fun controlOptions(arq: ArqConfig) =
    TransferOptions(profile = arq.controlProfile, chunkPayloadSize = FrameCodec.DEFAULT_PAYLOAD, maxFramesPerBurst = 255)

// ------------------------------------------------------------------------------------------------ receiver

enum class ReceiverPhase { RECEIVING, AWAITING_REPAIR, COMPLETE, CORRUPT }

sealed interface ReceiverAction {
    /** All chunks in and verified. [firstTime] is false when a poll made us confirm an already-finished transfer. */
    data class Completed(val payload: Payload, val firstTime: Boolean) : ReceiverAction
    data class NeedsRepair(val missing: List<Int>, val lossPercent: Double) : ReceiverAction
    data class Corrupt(val reason: String) : ReceiverAction
}

class ReceiverSession(val sessionId: Int, val tracker: ChunkTracker = ChunkTracker(sessionId), repairRounds: Int = 0) {
    var phase: ReceiverPhase = if (tracker.isComplete) ReceiverPhase.COMPLETE else ReceiverPhase.RECEIVING
        private set
    var repairRounds: Int = repairRounds
        private set
    var payload: Payload? = null
        private set

    fun onFrame(frame: Frame, status: CodewordStatus, correctedBytes: Int): Boolean = tracker.accept(frame, status, correctedBytes)

    /** The sender's over ended (turnaround elapsed): decide between ACK and NACK. */
    fun onOverEnded(): ReceiverAction {
        tracker.markOverEnded()
        if (phase == ReceiverPhase.COMPLETE) payload?.let { return ReceiverAction.Completed(it, firstTime = false) }
        if (!tracker.isComplete) {
            phase = ReceiverPhase.AWAITING_REPAIR
            return ReceiverAction.NeedsRepair(tracker.missingForNack, tracker.lossPercent)
        }
        return when (val r = Packetizer.reassemble(tracker.header!!, tracker.chunks(), tracker.totalChunks!!)) {
            is Packetizer.Reassembly.Ok -> {
                phase = ReceiverPhase.COMPLETE
                payload = r.payload
                ReceiverAction.Completed(r.payload, firstTime = true)
            }
            is Packetizer.Reassembly.Corrupt -> {
                phase = ReceiverPhase.CORRUPT
                ReceiverAction.Corrupt(r.reason)
            }
        }
    }

    fun nackFrames(): List<Frame> {
        repairRounds++
        tracker.beginRepairOver()
        return NackCodec.encode(tracker.missingForNack, FrameCodec.DEFAULT_PAYLOAD)
            .map { Frame(FrameType.NACK, sessionId, 0, tracker.totalChunks ?: 0, it) }
    }

    fun ackFrame(): Frame = Frame(FrameType.ACK, sessionId, 0, tracker.totalChunks ?: 0, ByteArray(0))

    fun snapshot(): ReceiverSnapshot = tracker.snapshot(repairRounds)

    companion object {
        fun restore(s: ReceiverSnapshot) = ReceiverSession(s.sessionId, ChunkTracker.restore(s), s.repairRounds)
    }
}

// ------------------------------------------------------------------------------------------------ sender

enum class SenderPhase { TRANSMITTING, AWAITING_RESPONSE, COMPLETE, FAILED, CANCELLED }

sealed interface SenderAction {
    data class Transmit(val bursts: List<PhyBurst>, val chunks: List<Int>, val profile: ModemProfile, val isPoll: Boolean) : SenderAction
    data object Completed : SenderAction
    data class Failed(val reason: String) : SenderAction
    data object Ignore : SenderAction
}

class SenderSession(val transfer: OutgoingTransfer, private val arq: ArqConfig) {
    val sessionId: Int get() = transfer.sessionId
    var phase = SenderPhase.TRANSMITTING
        private set
    var repairRounds = 0
        private set
    private var polls = 0
    var lastRequested: List<Int> = emptyList()
        private set

    fun initialBursts(): List<PhyBurst> = transfer.bursts(transfer.allFrames())

    fun onTransmitted() {
        if (phase == SenderPhase.TRANSMITTING) phase = SenderPhase.AWAITING_RESPONSE
    }

    fun onNack(requested: List<Int>): SenderAction {
        if (phase == SenderPhase.COMPLETE || phase == SenderPhase.FAILED || phase == SenderPhase.CANCELLED) return SenderAction.Ignore
        val wanted = requested.filter { it == Packetizer.HEADER_CHUNK || it in transfer.dataFrames.indices }.distinct().sorted()
        if (wanted.isEmpty()) return SenderAction.Ignore
        repairRounds++
        polls = 0
        lastRequested = wanted
        if (repairRounds > arq.maxRepairRounds) return fail("gave up after ${arq.maxRepairRounds} repair rounds")
        val profile = if (arq.stepDownToRobust) ModemProfile.ROBUST else transfer.options.profile
        phase = SenderPhase.TRANSMITTING
        return SenderAction.Transmit(transfer.bursts(transfer.framesFor(wanted), profile), wanted, profile, isPoll = false)
    }

    fun onAck(): SenderAction {
        if (phase == SenderPhase.COMPLETE || phase == SenderPhase.CANCELLED) return SenderAction.Ignore
        phase = SenderPhase.COMPLETE
        lastRequested = emptyList()
        return SenderAction.Completed
    }

    /** No NACK/ACK heard within the response window. */
    fun onResponseTimeout(): SenderAction {
        if (phase != SenderPhase.AWAITING_RESPONSE) return SenderAction.Ignore
        polls++
        if (polls > arq.maxPolls) return fail("no response from receiver")
        phase = SenderPhase.TRANSMITTING
        val opts = controlOptions(arq)
        val bursts = Packetizer.bursts(listOf(transfer.headerFrame), opts.copy(chunkPayloadSize = transfer.options.chunkPayloadSize))
        return SenderAction.Transmit(bursts, listOf(Packetizer.HEADER_CHUNK), arq.controlProfile, isPoll = true)
    }

    fun cancel() {
        phase = SenderPhase.CANCELLED
    }

    fun chunkStates(): List<ChunkState> = List(transfer.totalChunks) { i ->
        when {
            phase == SenderPhase.COMPLETE -> ChunkState.OK
            i in lastRequested -> ChunkState.MISSING
            else -> ChunkState.OK
        }
    }

    private fun fail(reason: String): SenderAction {
        phase = SenderPhase.FAILED
        return SenderAction.Failed(reason)
    }
}
