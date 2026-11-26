package com.radiome.core.protocol

import com.radiome.core.dsp.modem.ModemConfig
import com.radiome.core.dsp.modem.ModemProfile
import com.radiome.core.dsp.sim.ChannelConfig
import com.radiome.core.dsp.sim.Dropout
import com.radiome.core.protocol.arq.ArqConfig
import com.radiome.core.protocol.link.Direction
import com.radiome.core.protocol.link.InMemorySessionStore
import com.radiome.core.protocol.link.SessionStore
import com.radiome.core.protocol.link.LinkController
import com.radiome.core.protocol.link.LinkEvent
import com.radiome.core.protocol.payload.ContentType
import com.radiome.core.protocol.payload.Payload
import com.radiome.core.protocol.session.TransferOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

/** Two LinkControllers talking over the simulated radio channel, on a virtual clock. */
class TwoStationLinkTest {
    private class Station(val link: LinkController, val events: MutableList<LinkEvent>)

    private fun TestScope.station(
        air: SimulatedAir,
        name: String,
        arq: ArqConfig,
        seed: Int,
        store: SessionStore = InMemorySessionStore(),
    ): Station {
        val link = LinkController(air.station(name), backgroundScope, arq, store = store, random = Random(seed))
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

    private fun image(size: Int) = Payload(ContentType.IMAGE_WEBP, Random(21).nextBytes(size), "photo.webp")

    @Test
    fun `text is delivered and acknowledged without corrections`() = runTest(timeout = 5.minutes) {
        val air = SimulatedAir().also { it.start(backgroundScope) }
        val a = station(air, "A", ArqConfig(), 1)
        val b = station(air, "B", ArqConfig(), 2)
        delay(500)

        val text = "Team 2 at the pass, all well. Snow 40 cm, road closed after km 18. Next call 19:00."
        val sid = a.link.send(Payload.text(text), TransferOptions(profile = ModemProfile.STANDARD))

        val got = awaitEvent(b, 60) { it is LinkEvent.MessageReceived } as LinkEvent.MessageReceived
        assertEquals(sid, got.sessionId)
        assertEquals(text, got.payload.asText())
        awaitEvent(a, 30) { it is LinkEvent.Delivered && it.sessionId == sid }
        assertTrue(b.events.none { it is LinkEvent.NackSent })
        assertTrue(a.link.transfers.value.values.none { it.direction == Direction.INCOMING }, "A must not decode its own echo")
        println(air.log)
    }

    @Test
    fun `heavy dropout is NACKed and only the missing chunks are resent`() = runTest(timeout = 5.minutes) {
        val air = SimulatedAir().also { it.start(backgroundScope) }
        val engine = com.radiome.core.dsp.modem.MfskModemEngine()
        // First A->B transmission loses 1.2 s in the middle of the body; everything else is a decent channel.
        air.channel = { from, _, idx ->
            if (from == "A" && idx == 0) ChannelConfig(snrDb = 12.0, dropouts = listOf(Dropout(9_000.0, 1_200.0)), seed = 5)
            else ChannelConfig(snrDb = 12.0, seed = 6L + idx)
        }
        val a = station(air, "A", ArqConfig(), 3)
        val b = station(air, "B", ArqConfig(turnaroundMs = 2000), 4)
        delay(500)

        val photo = image(1900)
        val sid = a.link.send(photo, TransferOptions(profile = ModemProfile.FAST))

        val need = awaitEvent(b, 90) { it is LinkEvent.CorrectionsNeeded } as LinkEvent.CorrectionsNeeded
        println("B: missing ${need.missing}, loss ${"%.1f".format(need.lossPercent)}%")
        assertTrue(need.missing.isNotEmpty())
        val telemetry = b.link.transfers.value.getValue(sid)
        assertEquals(need.missing, telemetry.missing)
        assertEquals(need.lossPercent, telemetry.lossPercent)

        val nack = awaitEvent(a, 30) { it is LinkEvent.NackReceived } as LinkEvent.NackReceived
        assertEquals(need.missing, nack.requested)
        val repair = a.events.filterIsInstance<LinkEvent.Transmitted>().first { it.kind == LinkEvent.Transmitted.Kind.REPAIR }
        assertEquals(need.missing, repair.chunks, "selective repeat: only the missing chunks")
        assertEquals(ModemProfile.ROBUST, repair.profile)

        val got = awaitEvent(b, 120) { it is LinkEvent.MessageReceived } as LinkEvent.MessageReceived
        assertEquals(photo, got.payload)
        awaitEvent(a, 30) { it is LinkEvent.Delivered }
        assertEquals(0.0, b.link.transfers.value.getValue(sid).lossPercent)
        println(air.log)
    }

    @Test
    fun `manual correction request waits for the user`() = runTest(timeout = 5.minutes) {
        val air = SimulatedAir().also { it.start(backgroundScope) }
        air.channel = { from, _, idx ->
            if (from == "A" && idx == 0) ChannelConfig(snrDb = 12.0, dropouts = listOf(Dropout(4_000.0, 4_000.0)))
            else ChannelConfig(snrDb = 12.0, seed = idx.toLong())
        }
        val a = station(air, "A", ArqConfig(), 5)
        val b = station(air, "B", ArqConfig(autoNack = false), 6)
        delay(500)
        val sid = a.link.send(image(900), TransferOptions(profile = ModemProfile.STANDARD))

        awaitEvent(b, 90) { it is LinkEvent.CorrectionsNeeded }
        delay(15_000)
        assertTrue(b.events.none { it is LinkEvent.NackSent }, "no NACK until the user asks")
        assertTrue(b.link.requestCorrections(sid))
        awaitEvent(b, 120) { it is LinkEvent.MessageReceived }
        awaitEvent(a, 30) { it is LinkEvent.Delivered }
    }

    @Test
    fun `a lost NACK is recovered by the sender's header poll`() = runTest(timeout = 5.minutes) {
        val air = SimulatedAir().also { it.start(backgroundScope) }
        var firstNackDropped = false
        air.channel = { from, _, idx ->
            when {
                from == "A" && idx == 0 -> ChannelConfig(snrDb = 12.0, dropouts = listOf(Dropout(3_000.0, 1_500.0)))
                from == "B" && !firstNackDropped -> { firstNackDropped = true; null } // B's first NACK never arrives
                else -> ChannelConfig(snrDb = 12.0, seed = idx.toLong())
            }
        }
        val a = station(air, "A", ArqConfig(responseTimeoutMs = 6000), 7)
        val b = station(air, "B", ArqConfig(), 8)
        delay(500)
        val photo = image(1000)
        a.link.send(photo, TransferOptions(profile = ModemProfile.FAST))

        awaitEvent(a, 120) { it is LinkEvent.Transmitted && it.kind == LinkEvent.Transmitted.Kind.POLL }
        val got = awaitEvent(b, 120) { it is LinkEvent.MessageReceived } as LinkEvent.MessageReceived
        assertEquals(photo, got.payload)
        awaitEvent(a, 30) { it is LinkEvent.Delivered }
        assertEquals(2, b.events.count { it is LinkEvent.NackSent }, "B re-sent its NACK after the poll")
    }

    @Test
    fun `after a receiver restart the stored chunks are kept and only the rest is requested`() = runTest(timeout = 5.minutes) {
        val air = SimulatedAir().also { it.start(backgroundScope) }
        air.channel = { from, to, idx ->
            when {
                from == "A" && idx == 0 -> ChannelConfig(snrDb = 12.0, dropouts = listOf(Dropout(6_000.0, 1_500.0)))
                from == "A" && to == "B1" && idx > 0 -> null // B1 is "dead" after the first over
                else -> ChannelConfig(snrDb = 12.0, seed = idx.toLong())
            }
        }
        val store = InMemorySessionStore()
        val a = station(air, "A", ArqConfig(responseTimeoutMs = 6000), 9)
        // B1 receives the first over but never answers (manual NACK), then "the app restarts" as B2 on the same store.
        val b1 = station(air, "B1", ArqConfig(autoNack = false), 10, store)
        delay(500)
        val photo = image(1400)
        val sid = a.link.send(photo, TransferOptions(profile = ModemProfile.FAST))
        val need = awaitEvent(b1, 90) { it is LinkEvent.CorrectionsNeeded } as LinkEvent.CorrectionsNeeded
        assertTrue(need.missing.isNotEmpty())

        val b2 = station(air, "B2", ArqConfig(), 11, store)
        // A hears nothing, polls with its header; B2 restores the stored session and asks only for what is missing.
        val nack = awaitEvent(b2, 60) { it is LinkEvent.NackSent } as LinkEvent.NackSent
        assertEquals(sid, nack.sessionId)
        assertEquals(need.missing, nack.missing)
        val got = awaitEvent(b2, 120) { it is LinkEvent.MessageReceived } as LinkEvent.MessageReceived
        assertEquals(photo, got.payload)
        awaitEvent(a, 30) { it is LinkEvent.Delivered }
    }
}
