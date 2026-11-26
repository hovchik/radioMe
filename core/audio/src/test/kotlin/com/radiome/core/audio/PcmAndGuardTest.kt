package com.radiome.core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmAndGuardTest {
    @Test
    fun pcmRoundTripAndLimiting() {
        val src = floatArrayOf(0f, 0.5f, -0.5f, 0.99f, 1.5f, -2f)
        val pcm = ShortArray(src.size)
        Pcm.floatToPcm16(src, 0, pcm, src.size)
        assertEquals(Short.MAX_VALUE, pcm[4])
        assertEquals(Short.MIN_VALUE, pcm[5])
        val back = FloatArray(src.size)
        Pcm.pcm16ToFloat(pcm, src.size, back)
        for (i in 0..3) assertEquals(src[i], back[i], 1e-4f)
    }

    @Test
    fun gainAndOffsetAreApplied() {
        val pcm = ShortArray(2)
        Pcm.floatToPcm16(floatArrayOf(9f, 0.25f, 0.5f), 1, pcm, 2, gain = 2f)
        assertArrayEquals(shortArrayOf(16383, Short.MAX_VALUE), pcm)
    }

    @Test
    fun guardMutesDuringTransmitAndTail() {
        val guard = HalfDuplexGuard(tailSamples = 5000)
        assertFalse(guard.shouldDrop(2400))
        guard.beginTransmit()
        assertTrue(guard.shouldDrop(2400))
        assertTrue(guard.shouldDrop(2400))
        guard.endTransmit()
        assertTrue("tail block 1", guard.shouldDrop(2400))
        assertTrue("tail block 2", guard.shouldDrop(2400))
        assertTrue("tail block 3 still overlaps the 5000-sample tail", guard.shouldDrop(2400))
        assertFalse(guard.shouldDrop(2400))
    }

    @Test
    fun volumeAdviceThresholds() {
        assertEquals(VolumeAdvice.TOO_LOW, volumeAdvice(0.2f))
        assertEquals(VolumeAdvice.OK, volumeAdvice(0.7f))
        assertEquals(VolumeAdvice.CLIPPING_RISK, volumeAdvice(1f))
    }
}
