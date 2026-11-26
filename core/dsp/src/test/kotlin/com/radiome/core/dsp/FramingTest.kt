package com.radiome.core.dsp

import com.radiome.core.dsp.framing.Frame
import com.radiome.core.dsp.framing.FrameCodec
import com.radiome.core.dsp.framing.FrameType
import com.radiome.core.dsp.framing.NackCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FramingTest {
    @Test
    fun `frame layout is byte exact`() {
        val f = Frame(FrameType.DATA, 0xBEEF, 14, 40, byteArrayOf(1, 2, 3))
        val b = FrameCodec.encode(f, payloadCapacity = 8)
        assertEquals(19, b.size)
        assertEquals(0xA5, b[0].toInt() and 0xFF)
        assertEquals(0x11, b[1].toInt())
        assertEquals(listOf(0xBE, 0xEF, 0, 14, 0, 40, 3, 1, 2, 3, 0, 0, 0, 0, 0), b.drop(2).take(15).map { it.toInt() and 0xFF })
        assertEquals(FrameCodec.Result.Valid(f), FrameCodec.decode(b))
    }

    @Test
    fun `crc rejects a single flipped bit`() {
        val b = FrameCodec.encode(Frame(FrameType.ACK, 1, 0, 1, ByteArray(0)))
        b[20] = (b[20].toInt() xor 0x04).toByte()
        assertTrue(FrameCodec.decode(b) is FrameCodec.Result.Invalid)
    }

    @Test
    fun `nack round trips sparse and dense loss sets`() {
        val sparse = listOf(3, 900, 40_000)
        assertEquals(sparse, NackCodec.encode(sparse).flatMap(NackCodec::decode))
        val dense = (100 until 400).filter { it % 3 != 0 }
        val frames = NackCodec.encode(dense)
        assertEquals(1, frames.size, "a 300-chunk span fits in one 48-byte bitmap")
        assertTrue(frames.all { it.size <= FrameCodec.DEFAULT_PAYLOAD })
        assertEquals(dense, frames.flatMap(NackCodec::decode))
        val huge = (0 until 2000).toList()
        assertEquals(huge, NackCodec.encode(huge).flatMap(NackCodec::decode))
    }
}
