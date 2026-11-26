package com.radiome.core.dsp.math

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max

/**
 * Rows for a live waterfall: every [hopSamples] input samples, the power of the last [fftSize] samples (Hann window)
 * between [lowHz] and [highHz], in [bins] equal bands, as dB relative to a full-scale sine.
 */
class Spectrogram(
    private val sampleRate: Int,
    private val fftSize: Int = 4096,
    private val hopSamples: Int = sampleRate / 10,
    val lowHz: Double = 300.0,
    val highHz: Double = 2700.0,
    val bins: Int = 96,
) {
    private val fft = Fft(fftSize)
    private val window = DoubleArray(fftSize) { 0.5 - 0.5 * cos(2 * PI * it / (fftSize - 1)) }
    private val ring = FloatArray(fftSize)
    private var writeAt = 0
    private var sinceRow = 0
    private val re = DoubleArray(fftSize)
    private val im = DoubleArray(fftSize)
    // FFT bin range of each output band.
    private val hzPerBin = sampleRate.toDouble() / fftSize
    private val edges = IntArray(bins + 1) { b -> ((lowHz + (highHz - lowHz) * b / bins) / hzPerBin).toInt() }
    // Full-scale sine through the Hann window: amplitude 1 gives |X| = N/4 at its bin.
    private val fullScale = (fftSize / 4.0) * (fftSize / 4.0)

    /** Feed audio; returns the rows completed by this block (usually none or one). */
    fun process(block: FloatArray): List<FloatArray> {
        var rows: MutableList<FloatArray>? = null
        for (s in block) {
            ring[writeAt] = s
            writeAt = (writeAt + 1) % fftSize
            if (++sinceRow >= hopSamples) {
                sinceRow = 0
                (rows ?: ArrayList<FloatArray>(1).also { rows = it }).add(row())
            }
        }
        return rows ?: emptyList()
    }

    fun reset() {
        ring.fill(0f)
        writeAt = 0
        sinceRow = 0
    }

    private fun row(): FloatArray {
        for (i in 0 until fftSize) {
            re[i] = ring[(writeAt + i) % fftSize] * window[i]
            im[i] = 0.0
        }
        fft.forward(re, im)
        return FloatArray(bins) { b ->
            var peak = 0.0
            for (k in edges[b] until max(edges[b] + 1, edges[b + 1])) peak = max(peak, re[k] * re[k] + im[k] * im[k])
            (10 * log10(peak / fullScale + 1e-12)).toFloat()
        }
    }

    /** Frequency at the middle of band [b]. */
    fun bandHz(b: Int): Double = lowHz + (highHz - lowHz) * (b + 0.5) / bins
}
