package com.radiome.core.dsp

import com.radiome.core.dsp.fec.BlockInterleaver
import com.radiome.core.dsp.framing.BurstCodec
import com.radiome.core.dsp.framing.Frame
import com.radiome.core.dsp.framing.FrameCodec
import com.radiome.core.dsp.framing.FrameType
import com.radiome.core.dsp.framing.NackCodec
import com.radiome.core.dsp.modem.ModemConfig
import com.radiome.core.dsp.modem.ModemProfile
import com.radiome.core.dsp.modem.MfskModemEngine
import com.radiome.core.dsp.sim.ChannelConfig
import com.radiome.core.dsp.sim.Dropout
import com.radiome.core.dsp.sim.RadioChannelSimulator
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/** encode -> modulate -> radio channel -> demodulate -> decode, no hardware. */
class RadioChannelEndToEndTest {
    private val engine = MfskModemEngine(ModemConfig())
    private val text = ("Field report 07: bridge on the north road is out, use the ford 2 km east. " +
        "Need 4 litres of fuel and a spare antenna at the relay hill by Thursday. Over.").repeat(3).toByteArray()

    private fun transmit(frames: List<Frame>, profile: ModemProfile, channel: ChannelConfig): ReceiveTracker {
        val audio = engine.modulate(BurstCodec.encodeFrames(frames, profile))
        val rx = RadioChannelSimulator(channel).apply(audio)
        val tracker = ReceiveTracker(frames.first().totalChunks)
        TestSupport.receive(engine.createDemodulator(), rx).forEach(tracker::merge)
        return tracker
    }

    // ---------------------------------------------------------------- 1. clean loopback

    @ParameterizedTest
    @EnumSource(ModemProfile::class)
    fun `1 clean loopback delivers text 100 percent`(profile: ModemProfile) {
        val frames = TestSupport.chunk(0x0101, text)
        val t = transmit(frames, profile, ChannelConfig(leadingSilenceMs = 500, trailingSilenceMs = 200))
        assertEquals(0.0, t.lossPercent)
        assertEquals(0, t.fecCorrectedBytes)
        assertArrayEquals(text, t.assemble())
    }

    @ParameterizedTest
    @EnumSource(value = ModemProfile::class, names = ["STANDARD", "FAST"])
    fun `1 clean loopback delivers a binary image 100 percent`(profile: ModemProfile) {
        val image = TestSupport.sampleImage(2400)
        val frames = TestSupport.chunk(0x0202, image)
        assertEquals(50, frames.size)
        val t = transmit(frames, profile, ChannelConfig(leadingSilenceMs = 300))
        assertEquals(0.0, t.lossPercent)
        assertArrayEquals(image, t.assemble())
    }

    // ---------------------------------------------------------------- 2. moderate noise + short drop-outs

    @ParameterizedTest
    @EnumSource(ModemProfile::class)
    fun `2 moderate noise drift and short dropouts are repaired by FEC with zero loss`(profile: ModemProfile) {
        for (seed in 0 until 3) {
            val image = TestSupport.sampleImage(1200, seed = 10 * profile.ordinal + seed)
            val frames = TestSupport.chunk(0x0303, image)
            val channel = ChannelConfig(
                snrDb = 3.0,
                clockDriftPpm = if (seed % 2 == 0) 150.0 else -150.0,
                randomDropoutCount = 4,
                randomDropoutMinMs = 50.0,
                randomDropoutMaxMs = 150.0,
                leadingSilenceMs = 400,
                seed = 1000L + 10 * profile.ordinal + seed,
            )
            val t = transmit(frames, profile, channel)
            println("$profile seed $seed: FEC corrected ${t.fecCorrectedBytes} bytes in ${t.repairedFrames}/${frames.size} frames, loss ${t.lossPercent}%")
            assertEquals(0.0, t.lossPercent, "seed $seed missing ${t.missing}")
            assertTrue(t.fecCorrectedBytes > 0, "channel should have needed FEC")
            assertArrayEquals(image, t.assemble())
        }
    }

    // ---------------------------------------------------------------- 3. heavy drop-out -> NACK -> selective repeat

    @Test
    fun `3 heavy dropout is measured, NACKed and repaired by selective retransmission`() {
        val profile = ModemProfile.FAST
        val payload = TestSupport.sampleImage(1900, seed = 5)
        val frames = TestSupport.chunk(0x0404, payload)
        val total = frames.size
        val burst = BurstCodec.encodeFrames(frames, profile)
        val h = burst.header

        // Knock out 1.2 s in the middle of the body.
        val bodyStartMs = engine.bodyByteOffsetSamples(h, 0) * 1000.0 / ModemConfig.SAMPLE_RATE
        val dropout = Dropout(startMs = bodyStartMs + 9_000.0, durationMs = 1_200.0)
        val rx = RadioChannelSimulator(ChannelConfig(snrDb = 10.0, dropouts = listOf(dropout), seed = 77)).apply(engine.modulate(burst))
        val receiver = ReceiveTracker(total)
        TestSupport.receive(engine.createDemodulator(), rx).forEach(receiver::merge)

        // Accurate loss calculation: the lost chunks are exactly the frames whose interleave block the drop-out overwhelmed.
        val missing = receiver.missing
        println("heavy dropout: missing $missing of $total (${"%.1f".format(receiver.lossPercent)}%)")
        assertTrue(missing.isNotEmpty(), "a 1.2 s drop-out must exceed FEC")
        assertEquals(missing.size * 100.0 / total, receiver.lossPercent)
        val hitChunks = chunksTouchedBy(dropout, h.codewordLength, total, h.interleaveDepth, profile)
        assertTrue(hitChunks.containsAll(missing), "lost $missing but drop-out only touched $hitChunks")
        assertTrue(receiver.chunks.keys.none { it in missing })

        // Receiver -> sender: NACK burst (ROBUST, after turnaround).
        val nackFrames = NackCodec.encode(missing).map { Frame(FrameType.NACK, 0x0404, 0, total, it) }
        val nackAudio = engine.modulate(BurstCodec.encodeFrames(nackFrames, ModemProfile.ROBUST))
        val nackRx = RadioChannelSimulator(ChannelConfig(snrDb = 10.0, seed = 78, leadingSilenceMs = 2000)).apply(nackAudio)
        val nackBursts = TestSupport.receive(engine.createDemodulator(), nackRx)
        val requested = nackBursts.flatMap { BurstCodec.decodeFrames(it) }
            .mapNotNull { it.frame }
            .filter { it.type == FrameType.NACK && it.sessionId == 0x0404 }
            .flatMap { NackCodec.decode(it.payload) }
        assertEquals(missing, requested, "sender must decode the exact missing list")

        // Sender -> receiver: only the requested chunks, stepped down to ROBUST.
        val repair = frames.filter { it.chunkIndex in requested }
        assertEquals(missing.size, repair.size)
        val repairBurst = BurstCodec.encodeFrames(repair, ModemProfile.ROBUST)
        assertEquals(missing.size, repairBurst.header.codewordCount)
        val repairRx = RadioChannelSimulator(ChannelConfig(snrDb = 6.0, seed = 79, randomDropoutCount = 1)).apply(engine.modulate(repairBurst))
        TestSupport.receive(engine.createDemodulator(), repairRx).forEach(receiver::merge)

        assertTrue(receiver.complete, "still missing ${receiver.missing}")
        assertEquals(0.0, receiver.lossPercent)
        assertArrayEquals(payload, receiver.assemble())
    }

    private fun chunksTouchedBy(d: Dropout, n: Int, count: Int, depth: Int, profile: ModemProfile): Set<Int> {
        val h = com.radiome.core.dsp.modem.PhyHeader(profile, depth, FrameCodec.frameLength(FrameCodec.DEFAULT_PAYLOAD), n - FrameCodec.frameLength(FrameCodec.DEFAULT_PAYLOAD), count)
        val map = BlockInterleaver.airToCodewordIndex(n, count, depth)
        val fs = ModemConfig.SAMPLE_RATE / 1000.0
        val startSample = d.startMs * fs
        val endSample = (d.startMs + d.durationMs) * fs
        return map.indices.filter { air ->
            val s = engine.bodyByteOffsetSamples(h, air)
            val e = engine.bodyByteOffsetSamples(h, air + 1) + profile.symbolSamples
            e >= startSample && s <= endSample
        }.map { map[it] / n }.toSet()
    }
}
