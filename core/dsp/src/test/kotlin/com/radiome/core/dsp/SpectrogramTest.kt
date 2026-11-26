package com.radiome.core.dsp

import com.radiome.core.dsp.math.Spectrogram
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class SpectrogramTest {
    @Test
    fun toneShowsUpInItsBandAtTheRightLevel() {
        val sr = 48_000
        val s = Spectrogram(sr)
        val tone = 1500.0
        val block = FloatArray(sr) { (0.5 * sin(2 * PI * tone * it / sr)).toFloat() }
        val rows = s.process(block)
        assertEquals(10, rows.size)
        val row = rows.last()
        val peak = row.indices.maxBy { row[it] }
        assertTrue(abs(s.bandHz(peak) - tone) <= (s.highHz - s.lowHz) / s.bins, "peak at ${s.bandHz(peak)} Hz")
        // Amplitude 0.5 is -6 dB; the Hann window's scalloping can cost up to about 1.5 dB.
        assertTrue(row[peak] in -8f..-5f, "level ${row[peak]}")
        // Far from the tone the band is quiet.
        assertTrue(row[0] < -60f, "floor ${row[0]}")
    }
}
