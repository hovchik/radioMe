package com.radiome.core.protocol

import com.radiome.core.dsp.modem.ModemConfig
import com.radiome.core.protocol.arq.ArqConfig
import com.radiome.core.protocol.arq.SenderPhase
import com.radiome.core.protocol.link.AudioPort
import com.radiome.core.protocol.link.LinkController
import com.radiome.core.protocol.link.LinkEvent
import com.radiome.core.protocol.link.LinkState
import com.radiome.core.protocol.payload.Payload
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** The audio output can fail on a real device; the link must report it and keep working. */
class AudioFailureTest {
    private class FlakyPort : AudioPort {
        val inbox = Channel<FloatArray>(Channel.UNLIMITED)
        override val input: Flow<FloatArray> = inbox.receiveAsFlow()
        var failNext = true
        val played = ArrayList<FloatArray>()

        override suspend fun play(samples: FloatArray) {
            if (failNext) {
                failNext = false
                throw IllegalStateException("AudioTrack failed to initialise")
            }
            played += samples
            delay(samples.size * 1000L / ModemConfig.SAMPLE_RATE)
        }
    }

    @Test
    fun `a failing output is reported and the next transmission still plays`() = runTest {
        val port = FlakyPort()
        backgroundScope.launch {
            while (true) {
                delay(50)
                port.inbox.send(FloatArray(2400))
            }
        }
        val link = LinkController(port, backgroundScope, ArqConfig())
        val events = ArrayList<LinkEvent>()
        backgroundScope.launch { link.events.collect { events += it } }
        link.start()
        delay(200)

        val sid = link.send(Payload.text("first"))
        delay(2_000)
        assertTrue(events.any { it is LinkEvent.AudioOutputFailed && it.sessionId == sid }, "$events")
        assertTrue(events.any { it is LinkEvent.Failed && it.sessionId == sid }, "$events")
        assertEquals(SenderPhase.FAILED.name, link.transfers.value[sid]?.phase)
        assertEquals(LinkState.LISTENING, link.state.value)

        // The link is still alive: a test tone goes out at the pilot frequency.
        link.sendTestTone(500)
        delay(2_000)
        assertEquals(1, port.played.size)
        val tone = port.played.single()
        assertEquals(500 * ModemConfig.SAMPLE_RATE / 1000, tone.size)
        // Count zero crossings over the steady middle part: 2 per cycle at 1500 Hz.
        val mid = tone.copyOfRange(tone.size / 4, tone.size * 3 / 4)
        var crossings = 0
        for (i in 1 until mid.size) if ((mid[i - 1] < 0) != (mid[i] < 0)) crossings++
        val hz = crossings / 2.0 / (mid.size.toDouble() / ModemConfig.SAMPLE_RATE)
        assertTrue(abs(hz - ModemConfig.PILOT_HZ) < 10, "tone at $hz Hz")
        assertTrue(tone.maxOf { abs(it) } <= 1f)
    }
}
