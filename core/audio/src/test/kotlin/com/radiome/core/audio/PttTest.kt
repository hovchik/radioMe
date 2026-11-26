package com.radiome.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PttTest {
    @Test
    fun chipsAreRecognisedByVendor() {
        assertEquals(SerialChip.CP210X, SerialLines.chipFor(0x10C4, false))
        assertEquals(SerialChip.FTDI, SerialLines.chipFor(0x0403, false))
        assertEquals(SerialChip.CH34X, SerialLines.chipFor(0x1A86, false))
        assertEquals(SerialChip.CDC_ACM, SerialLines.chipFor(0x2341, true))
        assertNull(SerialLines.chipFor(0x2341, false))
    }

    @Test
    fun cp210xSetsRtsWithMasks() {
        assertEquals(listOf(ControlTransfer(0x41, 0x07, 0x0302, 0)), SerialLines.set(SerialChip.CP210X, 0, rts = true, dtr = false))
        assertEquals(listOf(ControlTransfer(0x41, 0x07, 0x0300, 0)), SerialLines.set(SerialChip.CP210X, 0, rts = false, dtr = false))
        assertEquals(listOf(ControlTransfer(0x41, 0x00, 0x0001, 0)), SerialLines.open(SerialChip.CP210X, 0))
    }

    @Test
    fun otherChipsUseTheirOwnRequests() {
        assertEquals(
            listOf(ControlTransfer(0x40, 0x01, 0x0101, 1), ControlTransfer(0x40, 0x01, 0x0200, 1)),
            SerialLines.set(SerialChip.FTDI, 0, rts = false, dtr = true),
        )
        // CH34x lines are active low.
        assertEquals(listOf(ControlTransfer(0x40, 0xA4, 0xBF, 0)), SerialLines.set(SerialChip.CH34X, 0, rts = true, dtr = false))
        assertEquals(listOf(ControlTransfer(0x40, 0xA4, 0xFF, 0)), SerialLines.set(SerialChip.CH34X, 0, rts = false, dtr = false))
        assertEquals(listOf(ControlTransfer(0x21, 0x22, 0x02, 1)), SerialLines.set(SerialChip.CDC_ACM, 1, rts = true, dtr = false))
    }

    @Test
    fun voxToneGoesOnTheRightChannelOnly() {
        val src = FloatArray(480) { 0.25f }
        val dst = ShortArray(960)
        Pcm.floatToPcm16StereoWithTone(src, 0, dst, 480, 1f, 1000.0, 0.7f, 48_000, 0.0)
        assertTrue((0 until 480).all { dst[2 * it] == (0.25f * 32767).toInt().toShort() })
        val right = (0 until 480).map { dst[2 * it + 1].toInt() }
        assertTrue(abs(right.max() - (0.7 * 32767).toInt()) < 50)
    }
}
