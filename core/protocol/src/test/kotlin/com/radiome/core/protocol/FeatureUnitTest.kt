package com.radiome.core.protocol

import com.radiome.core.dsp.framing.FrameType
import com.radiome.core.dsp.modem.ModemProfile
import com.radiome.core.protocol.image.ImageEncoder
import com.radiome.core.protocol.image.ImagePipeline
import com.radiome.core.protocol.image.ImagePreset
import com.radiome.core.protocol.image.MaxEdge
import com.radiome.core.protocol.image.ProgressiveImage
import com.radiome.core.protocol.image.RasterImage
import com.radiome.core.protocol.link.Callsign
import com.radiome.core.protocol.link.LinkTest
import com.radiome.core.protocol.link.LinkTestReport
import com.radiome.core.protocol.link.ProbeResult
import com.radiome.core.protocol.link.StationInfo
import com.radiome.core.protocol.payload.ContentType
import com.radiome.core.protocol.payload.Payload
import com.radiome.core.protocol.session.AirtimeEstimator
import com.radiome.core.protocol.session.Packetizer
import com.radiome.core.protocol.session.TransferOptions
import com.radiome.core.dsp.framing.BurstCodec
import com.radiome.core.dsp.modem.MfskModemEngine
import com.radiome.core.dsp.modem.RawBurst
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class FeatureUnitTest {
    @Test
    fun `callsigns are normalised and station frames round trip`() {
        assertEquals("EK1A/P", Callsign.normalize("  ek1a/p "))
        assertEquals("ABCDEFGHIJ", Callsign.normalize("abcdefghijklmn"))
        assertEquals("R2D2", Callsign.normalize("r2 d2!"))
        val info = StationInfo("EK1A", "EK2B", broadcast = true)
        assertEquals(info, StationInfo.decode(info.encode()))
        val all = StationInfo("EK1A")
        assertEquals(all, StationInfo.decode(all.encode()))
        assertTrue(all.isFor("anyone"))
        assertTrue(info.isFor("ek2b"))
        assertFalse(info.isFor("EK3C"))
        assertFalse(info.isFor(""))
        assertNull(StationInfo.decode(byteArrayOf(0, 20)))
    }

    @Test
    fun `every burst starts with the station frame and airtime counts it`() {
        val station = StationInfo("EK1A", "EK2B")
        val opts = TransferOptions(profile = ModemProfile.FAST, maxFramesPerBurst = 8, station = station)
        val t = Packetizer.packetize(Payload(ContentType.BINARY, Random(1).nextBytes(700)), 42, opts)
        val bursts = t.bursts(t.allFrames())
        val engine = MfskModemEngine()
        var frames = 0
        for (b in bursts) {
            val decoded = BurstCodec.decodeFrames(RawBurst(b.header, b.body, BooleanArray(b.body.size), 20.0, 0, 0, 0)).map { it.frame!! }
            assertEquals(FrameType.STATION, decoded.first().type)
            assertEquals(station, StationInfo.decode(decoded.first().payload))
            assertTrue(decoded.drop(1).none { it.type == FrameType.STATION })
            assertTrue(decoded.size <= 8)
            frames += decoded.size - 1
        }
        assertEquals(t.allFrames().size, frames)
        val estimate = AirtimeEstimator().framesMs(t.allFrames().size, opts)
        assertEquals(bursts.sumOf { engine.airtimeMs(it.header) }, estimate)
    }

    @Test
    fun `progressive image previews the small level then the start of the big one`() {
        val small = Random(2).nextBytes(150)
        val big = Random(3).nextBytes(900)
        val packed = ProgressiveImage.pack(listOf(small, big))
        assertEquals(listOf(small.toList(), big.toList()), ProgressiveImage.unpack(packed)!!.map { it.toList() })
        assertArrayEquals(big, ProgressiveImage.finalImage(packed))

        // Only the first 400 bytes arrived.
        val have = 400
        val wire = packed.copyOf().also { it.fill(0, have, it.size) }
        val p = ProgressiveImage.preview(wire) { from, until -> until <= have }!!
        assertArrayEquals(small, p.complete)
        assertEquals(0, p.completeLevel)
        assertFalse(p.isFinal)
        assertEquals(have - (1 + 4 + small.size), p.partial!!.size)
        assertTrue(p.partialFraction in 0.2f..0.3f)

        // The table itself is missing: nothing to show.
        assertNull(ProgressiveImage.preview(wire) { _, _ -> false })
        val done = ProgressiveImage.preview(packed) { _, _ -> true }!!
        assertTrue(done.isFinal)
        assertNull(done.partial)
    }

    @Test
    fun `pipeline sends a preview level when asked`() {
        val webp = ImageEncoder { img, q -> ByteArray(60 + img.width * img.height * q / 200) { 1 } }
        val src = RasterImage(800, 600, IntArray(800 * 600) { 0xFF8080FF.toInt() })
        val p = ImagePipeline(webp).prepare(src, ImagePreset(MaxEdge.PX_240, 25, progressive = true))
        assertEquals(ContentType.IMAGE_PROGRESSIVE, p.payload.type)
        val levels = ProgressiveImage.unpack(p.payload.bytes)!!
        assertEquals(2, levels.size)
        assertTrue(levels[0].size * 3 <= levels[1].size)
        assertEquals(p.sizeBytes, p.payload.bytes.size)
        val plain = ImagePipeline(webp).prepare(src, ImagePreset(MaxEdge.PX_240, 25))
        assertEquals(ContentType.IMAGE_WEBP, plain.payload.type)
    }

    @Test
    fun `link test report round trips and recommends the fastest clean profile`() {
        val clean = LinkTestReport(
            listOf(
                ProbeResult(ModemProfile.FAST, 6, 6, 2, 9, 14.5),
                ProbeResult(ModemProfile.STANDARD, 4, 4, 0, 0, 15.0),
                ProbeResult(ModemProfile.ROBUST, 2, 2, 0, 0, 15.0),
            ),
        )
        assertEquals(clean, LinkTest.decodeReport(LinkTest.encodeReport(clean)))
        val rec = clean.recommend()
        assertEquals(ModemProfile.FAST, rec.profile)
        // 1.5 errors per frame -> 2 * ceil(2 * 1.5 + 1) = 8 parity bytes of 59 -> 13.6% -> 20% floor.
        assertEquals(20, rec.parityPercent)

        val noisy = LinkTestReport(
            listOf(
                ProbeResult(ModemProfile.FAST, 6, 4, 4, 30, 2.0),
                ProbeResult(ModemProfile.STANDARD, 4, 4, 4, 20, 2.5),
                ProbeResult(ModemProfile.ROBUST, 2, 2, 0, 0, 3.0),
            ),
        ).recommend()
        assertEquals(ModemProfile.STANDARD, noisy.profile)
        // 5 errors per frame -> 2 * 11 = 22 bytes -> 37.3% -> 40%.
        assertEquals(40, noisy.parityPercent)

        val silent = LinkTestReport(LinkTest.order.map { ProbeResult(it, LinkTest.probeCount(it), 0, 0, 0, Double.NaN) })
        assertEquals(silent, LinkTest.decodeReport(LinkTest.encodeReport(silent)))
        val r = silent.recommend()
        assertEquals(ModemProfile.ROBUST, r.profile)
        assertEquals(50, r.parityPercent)
        assertTrue(r.reason.startsWith("Nothing was heard"))
        assertNotNull(LinkTest.probeFrames(1, ModemProfile.FAST).single { it.chunkIndex == 0 })
    }

    @Test
    fun `adaptive rate steps down on loss and up after clean runs`() {
        val r = com.radiome.core.protocol.link.AdaptiveRate(ModemProfile.STANDARD)
        assertEquals(ModemProfile.STANDARD, r.onOutcome(ModemProfile.STANDARD, 0.0, false))
        assertEquals(ModemProfile.FAST, r.onOutcome(ModemProfile.STANDARD, 0.0, false))
        assertEquals(ModemProfile.FAST, r.onOutcome(ModemProfile.FAST, 5.0, false))
        assertEquals(ModemProfile.STANDARD, r.onOutcome(ModemProfile.FAST, 30.0, false))
        assertEquals(ModemProfile.STANDARD, r.onOutcome(ModemProfile.FAST, 0.0, false), "a stale outcome is ignored")
        assertEquals(ModemProfile.ROBUST, r.onOutcome(ModemProfile.STANDARD, 0.0, true))
        assertEquals(ModemProfile.ROBUST, r.onOutcome(ModemProfile.ROBUST, 100.0, true), "never below Robust")
        r.reset(ModemProfile.FAST)
        assertEquals(ModemProfile.FAST, r.current)
    }
}
