package com.radiome.core.protocol

import com.radiome.core.dsp.framing.CodewordStatus
import com.radiome.core.dsp.framing.FrameType
import com.radiome.core.dsp.modem.MfskModemEngine
import com.radiome.core.dsp.modem.ModemProfile
import com.radiome.core.protocol.payload.CompressionMode
import com.radiome.core.protocol.payload.ContentType
import com.radiome.core.protocol.payload.Payload
import com.radiome.core.protocol.session.AirtimeEstimator
import com.radiome.core.protocol.session.ChunkState
import com.radiome.core.protocol.session.ChunkTracker
import com.radiome.core.protocol.session.Packetizer
import com.radiome.core.protocol.session.SessionHeader
import com.radiome.core.protocol.session.TransferOptions
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class PacketizerTest {
    private val text = "Checkpoint Bravo clear. Checkpoint Bravo clear. Moving to Charlie at 14:10. ".repeat(6)

    @Test
    fun `text is deflated only when that makes it smaller`() {
        val t = Packetizer.packetize(Payload.text(text), 1)
        assertTrue(t.header.compressed)
        assertTrue(t.header.wireLength < text.length / 2)

        val random = Payload(ContentType.BINARY, Random(1).nextBytes(300))
        val r = Packetizer.packetize(random, 2)
        assertFalse(r.header.compressed, "incompressible data must go uncompressed")
        assertEquals(300, r.header.wireLength)

        val never = Packetizer.packetize(Payload.text(text), 3, TransferOptions(compression = CompressionMode.NEVER))
        assertFalse(never.header.compressed)
    }

    @Test
    fun `session header round trips and truncates names on a utf-8 boundary`() {
        val h = SessionHeader(ContentType.IMAGE_WEBP, false, 1234, 1234, 0xDEADBEEFL, 48, "Карта_маршрута_Ереван.webp")
        val enc = h.encode(48)
        assertTrue(enc.size <= 48)
        val dec = SessionHeader.decode(enc)!!
        assertEquals(h.copy(name = dec.name), dec)
        assertTrue(h.name.startsWith(dec.name) && dec.name.isNotEmpty())
    }

    @Test
    fun `split and reassemble with crc check`() {
        val payload = Payload(ContentType.BINARY, Random(9).nextBytes(1000), "blob.bin")
        val t = Packetizer.packetize(payload, 0x42, TransferOptions(chunkPayloadSize = 40))
        assertEquals(25, t.totalChunks)
        assertEquals(Packetizer.HEADER_CHUNK, t.headerFrame.chunkIndex)
        assertTrue(t.dataFrames.all { it.type == FrameType.DATA && it.totalChunks == 25 && it.payload.size <= 40 })
        val chunks = t.dataFrames.associate { it.chunkIndex to it.payload }
        val ok = Packetizer.reassemble(t.header, chunks, 25) as Packetizer.Reassembly.Ok
        assertEquals(payload, ok.payload)

        val tampered = chunks.toMutableMap().apply { this[3] = this.getValue(3).copyOf().also { it[0] = (it[0] + 1).toByte() } }
        assertTrue(Packetizer.reassemble(t.header, tampered, 25) is Packetizer.Reassembly.Corrupt)
    }

    @Test
    fun `chunk tracker computes loss, states and survives a snapshot`() {
        val t = Packetizer.packetize(Payload(ContentType.BINARY, Random(3).nextBytes(480)), 7, TransferOptions(chunkPayloadSize = 48))
        val tracker = ChunkTracker(7)
        for (f in t.dataFrames) if (f.chunkIndex !in setOf(2, 5, 9)) {
            tracker.accept(f, if (f.chunkIndex == 4) CodewordStatus.FEC_REPAIRED else CodewordStatus.OK, if (f.chunkIndex == 4) 6 else 0)
        }
        assertEquals(10, tracker.totalChunks)
        assertEquals(listOf(2, 5, 9), tracker.missingData)
        assertEquals(30.0, tracker.lossPercent)
        assertEquals(listOf(2, 5, 9, Packetizer.HEADER_CHUNK), tracker.missingForNack, "header never arrived")
        assertEquals(ChunkState.MISSING, tracker.state(2), "a later chunk arrived, so 2 is known missing")
        assertEquals(ChunkState.PENDING, tracker.state(9), "nothing after 9 yet")
        assertEquals(ChunkState.REPAIRED, tracker.state(4))
        assertEquals(6, tracker.fecCorrectedBytes)
        tracker.markOverEnded()
        assertEquals(ChunkState.MISSING, tracker.state(9))

        val restored = ChunkTracker.restore(tracker.snapshot(1))
        assertEquals(tracker.missingForNack, restored.missingForNack)
        tracker.accept(t.headerFrame, CodewordStatus.OK, 0)
        restored.accept(t.headerFrame, CodewordStatus.OK, 0)
        for (i in listOf(2, 5, 9)) restored.accept(t.dataFrames[i], CodewordStatus.OK, 0)
        assertTrue(restored.isComplete)
        val ok = Packetizer.reassemble(restored.header!!, restored.chunks(), 10) as Packetizer.Reassembly.Ok
        assertArrayEquals(t.dataFrames.flatMap { it.payload.toList() }.toByteArray(), ok.payload.bytes)
    }

    @Test
    fun `airtime estimate equals the modulated length`() {
        val engine = MfskModemEngine()
        val est = AirtimeEstimator(engine)
        for (profile in ModemProfile.entries) {
            val opts = TransferOptions(profile = profile, maxFramesPerBurst = 20)
            val t = Packetizer.packetize(Payload(ContentType.BINARY, Random(4).nextBytes(2000)), 1, opts)
            val actual = t.bursts(t.allFrames()).sumOf { engine.modulate(it).size.toLong() * 1000 / 48_000 }
            val estimate = est.payloadMs(2000, opts)
            assertTrue(kotlin.math.abs(actual - estimate) <= 3, "$profile: estimate $estimate ms, actual $actual ms")
        }
    }
}
