package com.radiome.feature.transceiver

import com.radiome.core.dsp.modem.ModemProfile
import com.radiome.core.protocol.arq.ReceiverPhase
import com.radiome.core.protocol.arq.SenderPhase
import com.radiome.core.protocol.link.Direction
import com.radiome.core.protocol.link.LinkEvent
import com.radiome.core.protocol.link.TransferTelemetry
import com.radiome.core.protocol.payload.CompressionMode
import com.radiome.core.protocol.payload.Payload
import com.radiome.core.protocol.session.AirtimeEstimator
import com.radiome.core.protocol.session.ChunkState
import com.radiome.core.protocol.session.TransferOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentersTest {
    private val airtime = AirtimeEstimator()

    private fun telemetry(sid: Int, direction: Direction, phase: String, missing: List<Int> = emptyList(), started: Long = 0) =
        TransferTelemetry(
            sessionId = sid, direction = direction, phase = phase, name = "", totalChunks = 10,
            chunkStates = List(10) { if (it in missing) ChunkState.MISSING else ChunkState.OK },
            receivedChunks = 10 - missing.size, fecRepairedChunks = 1, fecCorrectedBytes = 3, missing = missing,
            lossPercent = missing.size * 10.0, repairRounds = 0, snrDb = 12.0, startedAtMs = started, updatedAtMs = started + 4000,
        )

    @Test
    fun inputLevelBands() {
        assertEquals(InputLevel.SILENT, Presenters.inputLevel(Double.NEGATIVE_INFINITY))
        assertEquals(InputLevel.SILENT, Presenters.inputLevel(-70.0))
        assertEquals(InputLevel.LOW, Presenters.inputLevel(-50.0))
        assertEquals(InputLevel.GOOD, Presenters.inputLevel(-20.0))
        assertEquals(InputLevel.HOT, Presenters.inputLevel(-1.0))
        assertEquals(0f, Presenters.levelFraction(Double.NEGATIVE_INFINITY))
        assertEquals(0.5f, Presenters.levelFraction(-30.0), 1e-6f)
        assertEquals(1f, Presenters.levelFraction(3.0))
    }

    @Test
    fun textEstimateMatchesWhatThePacketizerSends() {
        val options = TransferOptions(profile = ModemProfile.STANDARD)
        val repetitive = "CQ CQ CQ de RADIOME ".repeat(20)
        val e = Presenters.textEstimate(repetitive, options, airtime)!!
        assertTrue(e.compressed)
        assertTrue(e.wireBytes < e.bytes)
        assertEquals(airtime.payloadMs(e.wireBytes, options), e.airtimeMs)

        val raw = Presenters.textEstimate(repetitive, options.copy(compression = CompressionMode.NEVER), airtime)!!
        assertFalse(raw.compressed)
        assertEquals(raw.bytes, raw.wireBytes)
        assertTrue(raw.airtimeMs > e.airtimeMs)

        assertNull(Presenters.textEstimate("", options, airtime))
        // Robust is slower than Fast for the same text.
        val robust = Presenters.textEstimate("hello", options.copy(profile = ModemProfile.ROBUST), airtime)!!
        val fast = Presenters.textEstimate("hello", options.copy(profile = ModemProfile.FAST), airtime)!!
        assertTrue(robust.airtimeMs > fast.airtimeMs)
    }

    @Test
    fun cardsOfferTheRightActions() {
        val incomplete = Presenters.card(telemetry(1, Direction.INCOMING, ReceiverPhase.AWAITING_REPAIR.name, listOf(3, 4)))
        assertTrue(incomplete.canRequestCorrections)
        assertFalse(incomplete.canCancel)
        assertFalse(incomplete.finished)
        assertEquals(0.8f, incomplete.progress, 1e-6f)
        assertEquals(4000, incomplete.elapsedMs)

        val sending = Presenters.card(telemetry(2, Direction.OUTGOING, SenderPhase.AWAITING_RESPONSE.name))
        assertTrue(sending.canCancel)
        assertFalse(sending.canRequestCorrections)

        val done = Presenters.card(telemetry(3, Direction.INCOMING, ReceiverPhase.COMPLETE.name))
        assertTrue(done.finished)
        assertFalse(done.canRequestCorrections)
    }

    @Test
    fun runningTransfersComeFirstAndHiddenOnesAreDropped() {
        val list = listOf(
            telemetry(1, Direction.INCOMING, ReceiverPhase.COMPLETE.name, started = 9000),
            telemetry(2, Direction.INCOMING, ReceiverPhase.RECEIVING.name, started = 1000),
            telemetry(3, Direction.OUTGOING, SenderPhase.TRANSMITTING.name, started = 5000),
            telemetry(4, Direction.OUTGOING, SenderPhase.COMPLETE.name, started = 0),
        )
        val cards = Presenters.cards(list, hidden = setOf(4))
        assertEquals(listOf(3, 2, 1), cards.map { it.sessionId })
    }

    @Test
    fun eventMessages() {
        assertNotNull(Presenters.eventMessage(LinkEvent.MessageReceived(1, Payload.text("hi")), autoNack = true))
        assertTrue(Presenters.eventMessage(LinkEvent.Delivered(0x00AB), true)!!.contains("00AB"))
        assertNull(Presenters.eventMessage(LinkEvent.CorrectionsNeeded(1, listOf(1, 2), 20.0), autoNack = true))
        assertTrue(Presenters.eventMessage(LinkEvent.CorrectionsNeeded(1, listOf(1, 2), 20.0), autoNack = false)!!.startsWith("2 chunks"))
        assertNull(Presenters.eventMessage(LinkEvent.AckSent(1), true))
    }

    @Test
    fun statusCardShowsOneHintAtATime() {
        val base = RadioUi(running = true, inputLevel = InputLevel.GOOD)
        assertNull(base.hint)
        // A quiet input alone is shown by the meter, not by a hint that would flicker with room noise.
        assertNull(base.copy(inputLevel = InputLevel.LOW).hint)
        assertTrue(base.copy(inputLevel = InputLevel.HOT).hint!!.contains("clipping"))
        // Muted output wins over everything, even while transmitting.
        val muted = base.copy(outputMuted = true, inputLevel = InputLevel.HOT, linkState = com.radiome.core.protocol.link.LinkState.TRANSMITTING)
        assertTrue(muted.hint!!.contains("muted"))
        assertNull(base.copy(running = false, outputMuted = true).hint)
    }

    @Test
    fun audioFailureMessages() {
        val tone = Presenters.eventMessage(LinkEvent.AudioOutputFailed(com.radiome.core.protocol.link.LinkController.TEST_TONE_SESSION, "boom"), true)
        assertTrue(tone!!.startsWith("Test tone failed"))
        // For a transfer it ended, the Failed event that follows carries the message.
        assertNull(Presenters.eventMessage(LinkEvent.AudioOutputFailed(7, "boom", transferFailed = true), true))
        // A receiver's NACK or ACK that could not be played is reported on its own.
        assertTrue(Presenters.eventMessage(LinkEvent.AudioOutputFailed(7, "boom"), true)!!.contains("0007"))
    }

    @Test
    fun formatting() {
        assertEquals("4.5 s", Presenters.formatDuration(4500).replace(',', '.'))
        assertEquals("2 min 05 s", Presenters.formatDuration(125_000))
        assertEquals("900 B", Presenters.formatBytes(900))
    }
}
