package com.radiome.core.protocol

import com.radiome.core.dsp.framing.BurstCodec
import com.radiome.core.dsp.framing.CodewordStatus
import com.radiome.core.dsp.framing.FrameType
import com.radiome.core.dsp.framing.NackCodec
import com.radiome.core.dsp.modem.ModemProfile
import com.radiome.core.protocol.arq.ArqConfig
import com.radiome.core.protocol.arq.ReceiverAction
import com.radiome.core.protocol.arq.ReceiverSession
import com.radiome.core.protocol.arq.SenderAction
import com.radiome.core.protocol.arq.SenderPhase
import com.radiome.core.protocol.arq.SenderSession
import com.radiome.core.protocol.payload.ContentType
import com.radiome.core.protocol.payload.Payload
import com.radiome.core.protocol.session.Packetizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Frame-level ARQ state machines without audio. */
class ArqSessionTest {
    private val payload = Payload(ContentType.BINARY, Random(11).nextBytes(700), "x.bin")

    @Test
    fun `nack names exactly the missing chunks and the repair contains only those`() {
        val arq = ArqConfig()
        val sender = SenderSession(Packetizer.packetize(payload, 0x99), arq)
        val receiver = ReceiverSession(0x99)
        val lost = setOf(1, 7, 8)
        for (f in sender.transfer.allFrames()) if (f.chunkIndex !in lost) receiver.onFrame(f, CodewordStatus.OK, 0)
        sender.onTransmitted()

        val need = receiver.onOverEnded() as ReceiverAction.NeedsRepair
        assertEquals(lost.toList(), need.missing)
        val nack = receiver.nackFrames()
        assertTrue(nack.all { it.type == FrameType.NACK })
        val requested = nack.flatMap { NackCodec.decode(it.payload) }

        val repair = sender.onNack(requested) as SenderAction.Transmit
        assertEquals(ModemProfile.ROBUST, repair.profile, "repair steps down to ROBUST")
        val repairFrames = repair.bursts.flatMap { b -> BurstCodec.decode(rawOf(b)).map { it.data!! } }
        assertEquals(3, repairFrames.size)
        for (f in sender.transfer.framesFor(requested)) receiver.onFrame(f, CodewordStatus.OK, 0)
        sender.onTransmitted()

        val done = receiver.onOverEnded() as ReceiverAction.Completed
        assertEquals(payload, done.payload)
        assertEquals(SenderAction.Completed, sender.onAck())
        assertEquals(SenderPhase.COMPLETE, sender.phase)
    }

    @Test
    fun `lost header is requested with the sentinel index`() {
        val sender = SenderSession(Packetizer.packetize(payload, 5), ArqConfig())
        val receiver = ReceiverSession(5)
        sender.transfer.dataFrames.forEach { receiver.onFrame(it, CodewordStatus.OK, 0) }
        val need = receiver.onOverEnded() as ReceiverAction.NeedsRepair
        assertEquals(listOf(Packetizer.HEADER_CHUNK), need.missing)
        assertEquals(0.0, need.lossPercent, "all data is in, only metadata is missing")
        sender.onTransmitted()
        val repair = sender.onNack(need.missing) as SenderAction.Transmit
        assertEquals(1, repair.bursts.single().header.codewordCount)
        receiver.onFrame(sender.transfer.headerFrame, CodewordStatus.OK, 0)
        assertTrue(receiver.onOverEnded() is ReceiverAction.Completed)
    }

    @Test
    fun `silent receiver gets polled then the sender gives up`() {
        val sender = SenderSession(Packetizer.packetize(payload, 6), ArqConfig(maxPolls = 2))
        sender.onTransmitted()
        repeat(2) {
            val poll = sender.onResponseTimeout() as SenderAction.Transmit
            assertTrue(poll.isPoll && poll.chunks == listOf(Packetizer.HEADER_CHUNK))
            sender.onTransmitted()
        }
        assertTrue(sender.onResponseTimeout() is SenderAction.Failed)
        assertEquals(SenderPhase.FAILED, sender.phase)
    }

    @Test
    fun `repair rounds are bounded`() {
        val sender = SenderSession(Packetizer.packetize(payload, 8), ArqConfig(maxRepairRounds = 2))
        repeat(2) {
            sender.onTransmitted()
            assertTrue(sender.onNack(listOf(3)) is SenderAction.Transmit)
        }
        sender.onTransmitted()
        assertTrue(sender.onNack(listOf(3)) is SenderAction.Failed)
    }

    @Test
    fun `completed receiver re-confirms when polled`() {
        val sender = SenderSession(Packetizer.packetize(payload, 9), ArqConfig())
        val receiver = ReceiverSession(9)
        sender.transfer.allFrames().forEach { receiver.onFrame(it, CodewordStatus.OK, 0) }
        assertTrue((receiver.onOverEnded() as ReceiverAction.Completed).firstTime)
        receiver.onFrame(sender.transfer.headerFrame, CodewordStatus.OK, 0)
        assertEquals(false, (receiver.onOverEnded() as ReceiverAction.Completed).firstTime)
    }

    private fun rawOf(b: com.radiome.core.dsp.modem.PhyBurst) =
        com.radiome.core.dsp.modem.RawBurst(b.header, b.body, BooleanArray(b.body.size), 30.0, 0, 0, 0)
}

class ControlBurstTest {
    @Test
    fun `worst-case nack fits in a single burst`() {
        val rs = ReceiverSession(1)
        // Total of 65534 chunks with every other one missing: the largest correction request a session can produce.
        rs.onFrame(com.radiome.core.dsp.framing.Frame(FrameType.DATA, 1, 1, 65534, ByteArray(1)), CodewordStatus.OK, 0)
        val frames = rs.nackFrames()
        val bursts = com.radiome.core.protocol.session.Packetizer.bursts(frames, com.radiome.core.protocol.arq.controlOptions(ArqConfig()))
        assertEquals(1, bursts.size, "${frames.size} NACK frames")
    }
}

class SessionIdReuseTest {
    @Test
    fun `a different transfer with a reused id is detected`() {
        val a = Packetizer.packetize(Payload(ContentType.BINARY, Random(1).nextBytes(500)), 42)
        val b = Packetizer.packetize(Payload(ContentType.BINARY, Random(2).nextBytes(500)), 42)
        val c = Packetizer.packetize(Payload(ContentType.BINARY, Random(3).nextBytes(900)), 42)
        val rs = ReceiverSession(42)
        a.allFrames().forEach { rs.onFrame(it, CodewordStatus.OK, 0) }
        assertEquals(false, rs.conflictsWith(a.dataFrames[0]))
        assertEquals(false, rs.conflictsWith(a.headerFrame))
        assertEquals(true, rs.conflictsWith(b.headerFrame), "same size, different content")
        assertEquals(true, rs.conflictsWith(c.dataFrames[0]), "different chunk count")
    }

    @Test
    fun `restored complete session is confirmed but not delivered twice`() {
        val t = Packetizer.packetize(Payload.text("hello"), 5)
        val rs = ReceiverSession(5)
        t.allFrames().forEach { rs.onFrame(it, CodewordStatus.OK, 0) }
        assertTrue((rs.onOverEnded() as ReceiverAction.Completed).firstTime)
        val restored = ReceiverSession.restore(rs.snapshot())
        assertEquals(false, (restored.onOverEnded() as ReceiverAction.Completed).firstTime)
    }
}
