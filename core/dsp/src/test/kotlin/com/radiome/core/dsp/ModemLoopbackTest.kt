package com.radiome.core.dsp

import com.radiome.core.dsp.framing.BurstCodec
import com.radiome.core.dsp.framing.CodewordStatus
import com.radiome.core.dsp.modem.ModemConfig
import com.radiome.core.dsp.modem.ModemProfile
import com.radiome.core.dsp.modem.MfskModemEngine
import com.radiome.core.dsp.modem.PhyHeader
import com.radiome.core.dsp.sim.ChannelConfig
import com.radiome.core.dsp.sim.RadioChannelSimulator
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.math.abs

class ModemLoopbackTest {
    private val text = ("CQ CQ de field team. Water point at grid 38TMK 41 22, two jerrycans left. " +
        "Next check-in 18:30 on channel 7. Արև, Привет, 你好 – UTF-8 must survive.").toByteArray()

    @Test
    fun `all tones and the sync chirp sit inside the 600-2400 Hz passband`() {
        for (p in ModemProfile.entries) {
            assertTrue(p.toneHz(0) >= ModemConfig.PASSBAND_LOW_HZ && p.maxToneHz <= ModemConfig.PASSBAND_HIGH_HZ, "$p")
            assertEquals(0.0, (p.windowSamples * p.spacingHz / ModemConfig.SAMPLE_RATE) % 1.0, 1e-9, "$p tones not orthogonal")
        }
        assertTrue(ModemConfig.CHIRP_START_HZ >= 600 && ModemConfig.CHIRP_END_HZ <= 2400)
        assertTrue(ModemConfig.PILOT_HZ in 600.0..2400.0)
    }

    @Test
    fun `phy header round trip and repairs two corrupted bytes`() {
        val h = PhyHeader(ModemProfile.STANDARD, 6, 59, 20, 37)
        val enc = h.encode()
        assertEquals(PhyHeader.ENCODED_SIZE, enc.size)
        assertEquals(24, PhyHeader.SYMBOLS)
        enc[1] = (enc[1].toInt() xor 0xFF).toByte()
        enc[6] = (enc[6].toInt() xor 0x11).toByte()
        assertEquals(h, PhyHeader.decode(enc))
    }

    @ParameterizedTest
    @EnumSource(ModemProfile::class)
    fun `clean band-limited loopback decodes every frame without corrections`(profile: ModemProfile) {
        val engine = MfskModemEngine()
        val frames = TestSupport.chunk(0x1234, text)
        val audio = engine.modulate(BurstCodec.encodeFrames(frames, profile))
        val rx = RadioChannelSimulator(ChannelConfig(leadingSilenceMs = 700, trailingSilenceMs = 300)).apply(audio)

        val bursts = TestSupport.receive(engine.createDemodulator(), rx)
        assertEquals(1, bursts.size)
        assertEquals(profile, bursts[0].header.profile)
        val results = BurstCodec.decodeFrames(bursts[0])
        assertTrue(results.all { it.status == CodewordStatus.OK }, results.map { it.status }.toString())
        assertEquals(frames, results.map { it.frame })
    }

    @Test
    fun `decoding is independent of input level`() {
        val engine = MfskModemEngine()
        val frames = TestSupport.chunk(7, text)
        val audio = engine.modulate(BurstCodec.encodeFrames(frames, ModemProfile.STANDARD))
        for (gain in listOf(0.02, 0.3, 1.4)) {
            val rx = RadioChannelSimulator(ChannelConfig(gain = gain, clipDrive = if (gain > 1) 2.0 else null, leadingSilenceMs = 200)).apply(audio)
            val tracker = ReceiveTracker(frames.size)
            TestSupport.receive(engine.createDemodulator(), rx).forEach(tracker::merge)
            assertTrue(tracker.complete, "gain $gain lost ${tracker.missing}")
            assertArrayEquals(text, tracker.assemble())
        }
    }

    @Test
    fun `snr estimate tracks the channel`() {
        val engine = MfskModemEngine()
        val audio = engine.modulate(BurstCodec.encodeFrames(TestSupport.chunk(1, text), ModemProfile.STANDARD))
        for (snr in listOf(5.0, 15.0)) {
            val rx = RadioChannelSimulator(ChannelConfig(snrDb = snr, seed = 11)).apply(audio)
            val burst = TestSupport.receive(engine.createDemodulator(), rx).single()
            println("channel SNR $snr dB -> estimated ${"%.1f".format(burst.snrDb)} dB")
            assertTrue(abs(burst.snrDb - snr) < 3.0, "estimated ${burst.snrDb} for $snr")
        }
    }

    @Test
    fun `back to back bursts are both received and noise alone never syncs`() {
        val engine = MfskModemEngine(ModemConfig(leadInMs = 200))
        val a = engine.modulate(BurstCodec.encodeFrames(TestSupport.chunk(1, text), ModemProfile.FAST))
        val b = engine.modulate(BurstCodec.encodeFrames(TestSupport.chunk(2, text), ModemProfile.ROBUST))
        val noise = RadioChannelSimulator(ChannelConfig(snrDb = 0.0, seed = 3)).apply(FloatArray(48_000 * 3) { 0.0f } .also { it[0] = 0.5f })
        val demod = engine.createDemodulator()
        assertTrue(TestSupport.receive(demod, noise).isEmpty())
        val bursts = TestSupport.receive(demod, RadioChannelSimulator(ChannelConfig(snrDb = 12.0)).apply(a + FloatArray(4800) + b))
        assertEquals(listOf(ModemProfile.FAST, ModemProfile.ROBUST), bursts.map { it.header.profile })
    }
}
