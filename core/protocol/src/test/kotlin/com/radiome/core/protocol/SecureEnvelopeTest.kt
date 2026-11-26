package com.radiome.core.protocol

import com.radiome.core.protocol.arq.ArqConfig
import com.radiome.core.protocol.crypto.GroupKey
import com.radiome.core.protocol.crypto.SecureEnvelope
import com.radiome.core.protocol.link.LinkController
import com.radiome.core.protocol.link.LinkEvent
import com.radiome.core.protocol.payload.ContentType
import com.radiome.core.protocol.payload.Payload
import com.radiome.core.protocol.payload.Position
import com.radiome.core.protocol.session.Packetizer
import com.radiome.core.protocol.session.TransferOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

class SecureEnvelopeTest {
    // Fewer PBKDF2 rounds keep the tests fast; the derivation is the same.
    private val key = GroupKey.fromPassphrase("correct horse battery", iterations = 1000)
    private val other = GroupKey.fromPassphrase("another group secret", iterations = 1000)
    private val fix = Position(40.1776, 44.5126, altitudeM = 990, accuracyM = 8, speedMps = 1.5, courseDeg = 90.0, timeSec = 1_795_000_000)

    @Test
    fun `position round trips at 1e-7 degree resolution`() {
        val back = Position.decode(fix.encode())!!
        assertEquals(fix.latitude, back.latitude, 1e-7)
        assertEquals(fix.longitude, back.longitude, 1e-7)
        assertEquals(990, back.altitudeM)
        assertEquals(8, back.accuracyM)
        assertEquals(1.5, back.speedMps!!, 1e-9)
        assertEquals(90.0, back.courseDeg!!, 1.5)
        assertEquals(fix.timeSec, back.timeSec)
        val unknown = Position.decode(Position(-33.9, 151.2, timeSec = 5).encode())!!
        assertNull(unknown.altitudeM)
        assertNull(unknown.speedMps)
    }

    @Test
    fun `sealed position reveals nothing and opens only with the key and the right sender`() {
        val plain = fix.encode()
        val sealed = SecureEnvelope.seal(fix.toPayload(), key, "EK1A")
        assertEquals(ContentType.ENCRYPTED, sealed.type)
        assertEquals("", sealed.name)
        assertFalse(sealed.bytes.toList().windowed(4).contains(plain.copyOfRange(1, 5).toList()), "no latitude bytes in clear")
        assertEquals(SecureEnvelope.sealedSize(Position.SIZE), sealed.bytes.size)
        // A short text and a position are the same size on air.
        assertEquals(sealed.bytes.size, SecureEnvelope.seal(Payload.text("OK, on my way"), key, "EK1A").bytes.size)
        // Same fix twice: different nonce, different ciphertext.
        assertNotEquals(sealed.bytes.toList(), SecureEnvelope.seal(fix.toPayload(), key, "EK1A").bytes.toList())

        val ok = SecureEnvelope.open(sealed.bytes, key, "EK1A") as SecureEnvelope.Opened.Ok
        assertEquals(ContentType.POSITION, ok.payload.type)
        assertEquals(fix.latitude, Position.decode(ok.payload.bytes)!!.latitude, 1e-7)

        assertTrue(SecureEnvelope.open(sealed.bytes, other, "EK1A") is SecureEnvelope.Opened.WrongKey)
        assertTrue(SecureEnvelope.open(sealed.bytes, null, "EK1A") is SecureEnvelope.Opened.WrongKey)
        assertEquals(SecureEnvelope.Opened.Forged, SecureEnvelope.open(sealed.bytes, key, "EK9X"), "replayed under another callsign")
        val flipped = sealed.bytes.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertEquals(SecureEnvelope.Opened.Forged, SecureEnvelope.open(flipped, key, "EK1A"))
    }

    @Test
    fun `keys from the same passphrase match and fingerprints differ between groups`() {
        val again = GroupKey.fromPassphrase("correct horse battery", iterations = 1000)
        assertEquals(key, again)
        assertEquals(key.fingerprint, again.fingerprint)
        assertNotEquals(key.fingerprint, other.fingerprint)
        assertTrue(Regex("[0-9A-F]{4}-[0-9A-F]{4}").matches(key.fingerprint))
        assertThrows(IllegalArgumentException::class.java) { GroupKey.fromPassphrase("short") }
    }

    @Test
    fun `a sealed position fits a single chunk at 64 bytes`() {
        val sealed = SecureEnvelope.seal(fix.toPayload(), key, "EK1A")
        assertEquals(1, Packetizer.packetize(sealed, 1, TransferOptions(chunkPayloadSize = 64)).totalChunks)
    }

    private class Station(val link: LinkController, val events: MutableList<LinkEvent>)

    private fun TestScope.station(air: SimulatedAir, name: String, call: String, seed: Int, k: GroupKey?): Station {
        val link = LinkController(air.station(name), backgroundScope, ArqConfig(), random = Random(seed), callsign = call)
        link.groupKey = k
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
    fun `encrypted beacon over the air is opened by the group and not by an outsider`() = runTest(timeout = 5.minutes) {
        val air = SimulatedAir().also { it.start(backgroundScope) }
        val a = station(air, "A", "EK1A", 1, key)
        val b = station(air, "B", "EK2B", 2, key)
        val c = station(air, "C", "EK3C", 3, other)
        delay(500)
        a.link.send(fix.toPayload(), TransferOptions(chunkPayloadSize = 64))
        val gb = awaitEvent(b, 60) { it is LinkEvent.MessageReceived } as LinkEvent.MessageReceived
        assertTrue(gb.encrypted)
        assertNull(gb.unreadable)
        assertEquals(ContentType.POSITION, gb.payload.type)
        assertEquals(fix.longitude, Position.decode(gb.payload.bytes)!!.longitude, 1e-7)
        assertEquals("EK1A", gb.from)

        val gc = awaitEvent(c, 60) { it is LinkEvent.MessageReceived } as LinkEvent.MessageReceived
        assertTrue(gc.encrypted)
        assertEquals(ContentType.ENCRYPTED, gc.payload.type)
        assertTrue(gc.unreadable!!.contains("another group key"))

        // No key: a position cannot go out at all.
        val none = station(air, "D", "EK4D", 4, null)
        assertThrows(IllegalStateException::class.java) { none.link.send(fix.toPayload()) }
    }
}
