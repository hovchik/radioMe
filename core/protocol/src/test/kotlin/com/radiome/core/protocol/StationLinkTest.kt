package com.radiome.core.protocol

import com.radiome.core.dsp.modem.ModemProfile
import com.radiome.core.protocol.arq.ArqConfig
import com.radiome.core.protocol.image.ProgressiveImage
import com.radiome.core.protocol.link.Direction
import com.radiome.core.protocol.link.LinkController
import com.radiome.core.protocol.link.LinkEvent
import com.radiome.core.protocol.link.StationInfo
import com.radiome.core.protocol.payload.ContentType
import com.radiome.core.protocol.payload.Payload
import com.radiome.core.protocol.session.TransferOptions
import com.radiome.core.dsp.sim.ChannelConfig
import com.radiome.core.dsp.sim.Dropout
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

/** Callsigns, addressing, link test and progressive previews over the simulated radio. */
class StationLinkTest {
    private class Station(val link: LinkController, val events: MutableList<LinkEvent>)

    private fun TestScope.station(air: SimulatedAir, name: String, callsign: String, seed: Int, arq: ArqConfig = ArqConfig()): Station {
        val link = LinkController(air.station(name), backgroundScope, arq, random = Random(seed), callsign = callsign)
        val events = ArrayList<LinkEvent>()
        backgroundScope.launch { link.events.collect { events += it } }
        link.start()
        return Station(link, events)
    }

    private suspend fun TestScope.awaitEvent(s: Station, maxSeconds: Int, what: (LinkEvent) -> Boolean): LinkEvent {
        val deadline = currentTime + maxSeconds * 1000L
        while (currentTime < deadline) {
            s.events.firstOrNull(what)?.let { return it }
            delay(250)
        }
        error("event not seen within $maxSeconds s; events: ${s.events}")
    }

    @Test
    fun `addressed message reaches only its station and is confirmed by callsign`() = runTest(timeout = 5.minutes) {
        val air = SimulatedAir().also { it.start(backgroundScope) }
        val a = station(air, "A", "EK1A", 1)
        val b = station(air, "B", "EK2B", 2)
        val c = station(air, "C", "EK3C", 3)
        delay(500)
        val sid = a.link.send(Payload.text("Meet at the north gate"), TransferOptions(station = StationInfo("", "ek2b")))

        val got = awaitEvent(b, 60) { it is LinkEvent.MessageReceived } as LinkEvent.MessageReceived
        assertEquals("EK1A", got.from)
        assertEquals("EK2B", got.to)
        val delivered = awaitEvent(a, 30) { it is LinkEvent.Delivered && it.sessionId == sid } as LinkEvent.Delivered
        assertEquals("EK2B", delivered.by)
        assertEquals(listOf("EK2B"), a.link.transfers.value.getValue(sid).ackedBy)
        assertEquals("EK1A", b.link.transfers.value.getValue(sid).peer)
        assertTrue(c.events.none { it is LinkEvent.MessageReceived || it is LinkEvent.NackSent || it is LinkEvent.AckSent })
        assertTrue(c.link.transfers.value.isEmpty(), "C must drop a transfer for EK2B")
        assertTrue(c.events.any { it is LinkEvent.StationHeard && it.callsign == "EK1A" }, "C still hears who is on the air")
    }

    @Test
    fun `a message for everyone is received by every station`() = runTest(timeout = 5.minutes) {
        val air = SimulatedAir().also { it.start(backgroundScope) }
        val a = station(air, "A", "EK1A", 4, ArqConfig(sendAck = false))
        val b = station(air, "B", "EK2B", 5, ArqConfig(sendAck = false))
        val c = station(air, "C", "", 6, ArqConfig(sendAck = false))
        delay(500)
        a.link.send(Payload.text("All stations: net at 20:00"))
        val gb = awaitEvent(b, 60) { it is LinkEvent.MessageReceived } as LinkEvent.MessageReceived
        val gc = awaitEvent(c, 60) { it is LinkEvent.MessageReceived } as LinkEvent.MessageReceived
        assertEquals("EK1A", gb.from)
        assertEquals(null, gb.to)
        assertEquals("All stations: net at 20:00", gc.payload.asText())
    }

    @Test
    fun `link test measures the probes and suggests a profile on both sides`() = runTest(timeout = 5.minutes) {
        val air = SimulatedAir().also { it.start(backgroundScope) }
        air.channel = { _, _, idx -> ChannelConfig(snrDb = 15.0, seed = 40L + idx) }
        val a = station(air, "A", "EK1A", 7)
        val b = station(air, "B", "EK2B", 8)
        delay(500)
        val sid = a.link.startLinkTest()
        val measured = awaitEvent(b, 90) { it is LinkEvent.LinkTestReported } as LinkEvent.LinkTestReported
        assertTrue(measured.measuredHere)
        assertEquals("EK1A", measured.peer)
        val heard = awaitEvent(a, 60) { it is LinkEvent.LinkTestReported } as LinkEvent.LinkTestReported
        assertEquals(sid, heard.sessionId)
        assertEquals("EK2B", heard.peer)
        assertEquals(measured.report, heard.report)
        println(heard.report)
        println(heard.report.recommend())
        for (r in heard.report.results) assertEquals(r.sent, r.received, "clean channel loses no probe: $r")
        assertEquals(ModemProfile.FAST, heard.report.recommend().profile)
        assertTrue(a.link.transfers.value.isEmpty() && b.link.transfers.value.isEmpty(), "a link test is not a transfer")
    }

    @Test
    fun `link test without an answer fails after the wait`() = runTest(timeout = 5.minutes) {
        val air = SimulatedAir().also { it.start(backgroundScope) }
        val a = station(air, "A", "EK1A", 9, ArqConfig(responseTimeoutMs = 3000))
        delay(500)
        a.link.startLinkTest()
        awaitEvent(a, 90) { it is LinkEvent.LinkTestFailed }
    }

    @Test
    fun `progressive image shows its preview before the transfer completes`() = runTest(timeout = 5.minutes) {
        val air = SimulatedAir().also { it.start(backgroundScope) }
        // The last part of the first over is lost, so the receiver sits on a partial image until the repair.
        air.channel = { from, _, idx ->
            if (from == "A" && idx == 0) ChannelConfig(snrDb = 12.0, dropouts = listOf(Dropout(14_000.0, 3_000.0)), seed = 11)
            else ChannelConfig(snrDb = 12.0, seed = 12L + idx)
        }
        val a = station(air, "A", "EK1A", 10)
        val b = station(air, "B", "EK2B", 11)
        delay(500)
        val preview = Random(5).nextBytes(120)
        val full = Random(6).nextBytes(1500)
        val photo = Payload(ContentType.IMAGE_PROGRESSIVE, ProgressiveImage.pack(listOf(preview, full)), "p.webp")
        val sid = a.link.send(photo, TransferOptions(profile = ModemProfile.FAST))

        awaitEvent(b, 90) { it is LinkEvent.CorrectionsNeeded }
        val t = b.link.transfers.value.getValue(sid)
        assertEquals(Direction.INCOMING, t.direction)
        val p = t.preview
        assertNotNull(p)
        assertTrue(p!!.complete.contentEquals(preview), "the small level arrived first")
        assertTrue(!p.isFinal)
        val got = awaitEvent(b, 120) { it is LinkEvent.MessageReceived } as LinkEvent.MessageReceived
        assertEquals(photo, got.payload)
    }

    @Test
    fun `broadcast merges both receivers' corrections into one repair and completes after both confirm`() = runTest(timeout = 10.minutes) {
        val air = SimulatedAir().also { it.start(backgroundScope) }
        // The first over loses different parts at B and at C.
        air.channel = { from, to, idx ->
            when {
                from == "A" && idx == 0 && to == "B" -> ChannelConfig(snrDb = 12.0, dropouts = listOf(Dropout(6_000.0, 1_500.0)), seed = 21)
                from == "A" && idx == 0 && to == "C" -> ChannelConfig(snrDb = 12.0, dropouts = listOf(Dropout(14_000.0, 1_500.0)), seed = 22)
                else -> ChannelConfig(snrDb = 12.0, seed = 30L + idx)
            }
        }
        val a = station(air, "A", "EK1A", 31)
        val b = station(air, "B", "EK2B", 32)
        val c = station(air, "C", "EK3C", 33)
        delay(500)
        val photo = Payload(ContentType.IMAGE_WEBP, Random(9).nextBytes(1500), "group.webp")
        val sid = a.link.send(photo, TransferOptions(profile = ModemProfile.FAST, station = StationInfo("", broadcast = true)))

        val nb = awaitEvent(b, 120) { it is LinkEvent.NackSent } as LinkEvent.NackSent
        val nc = awaitEvent(c, 120) { it is LinkEvent.NackSent } as LinkEvent.NackSent
        assertTrue(nb.missing.toSet() != nc.missing.toSet(), "the test needs different losses: ${nb.missing} / ${nc.missing}")
        assertEquals(photo, (awaitEvent(b, 180) { it is LinkEvent.MessageReceived } as LinkEvent.MessageReceived).payload)
        assertEquals(photo, (awaitEvent(c, 180) { it is LinkEvent.MessageReceived } as LinkEvent.MessageReceived).payload)

        val repairs = a.events.filterIsInstance<LinkEvent.Transmitted>().filter { it.kind == LinkEvent.Transmitted.Kind.REPAIR }
        assertEquals(1, repairs.size, "one merged repair, not one per receiver: $repairs")
        assertEquals((nb.missing + nc.missing).distinct().sorted(), repairs.single().chunks)
        val delivered = awaitEvent(a, 120) { it is LinkEvent.Delivered } as LinkEvent.Delivered
        assertEquals(setOf("EK2B", "EK3C"), delivered.by!!.split(", ").toSet())
        assertEquals(sid, delivered.sessionId)
        println(air.log)
    }
}
