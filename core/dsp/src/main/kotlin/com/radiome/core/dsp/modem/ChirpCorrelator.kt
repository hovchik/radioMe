package com.radiome.core.dsp.modem

import com.radiome.core.dsp.math.Fft
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * FFT (overlap-save) matched filter for the sync chirp. The template is the analytic chirp exp(j*phi[n]), so the
 * correlation magnitude is independent of the carrier phase after the radio channel. Output is normalised to
 * roughly 0..1 by the local signal energy, which makes detection independent of input level (built-in AGC).
 */
internal class ChirpCorrelator {
    val templateLength = ModemConfig.CHIRP_SAMPLES
    val fftSize = 16384
    /** Number of lag positions produced per block. */
    val hop = fftSize - templateLength + 1

    private val fft = Fft(fftSize)
    private val templateRe = DoubleArray(fftSize)
    private val templateIm = DoubleArray(fftSize)
    private val re = DoubleArray(fftSize)
    private val im = DoubleArray(fftSize)
    private val prefix = DoubleArray(fftSize + 1)
    val output = DoubleArray(hop)

    init {
        var phase = 0.0
        val inc = Waveforms.chirpPhaseIncrements()
        for (i in 0 until templateLength) {
            templateRe[i] = cos(phase)
            templateIm[i] = sin(phase)
            phase += inc[i]
        }
        fft.forward(templateRe, templateIm)
    }

    /** Fills [output] with normalised correlation for lags start .. start + hop - 1. Requires fftSize samples from [start]. */
    fun correlate(x: FloatArray, start: Int) {
        prefix[0] = 0.0
        for (i in 0 until fftSize) {
            val v = x[start + i].toDouble()
            re[i] = v
            im[i] = 0.0
            prefix[i + 1] = prefix[i] + v * v
        }
        fft.forward(re, im)
        for (i in 0 until fftSize) {
            // X * conj(C)
            val a = re[i]; val b = im[i]; val c = templateRe[i]; val d = templateIm[i]
            re[i] = a * c + b * d
            im[i] = b * c - a * d
        }
        fft.inverse(re, im)
        val half = templateLength / 2.0
        for (m in 0 until hop) {
            val energy = prefix[m + templateLength] - prefix[m]
            output[m] = if (energy <= 1e-9 * templateLength) 0.0 else (hypot(re[m], im[m]) / sqrt(energy * half)).coerceAtMost(1.0)
        }
    }
}
