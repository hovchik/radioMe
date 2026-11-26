package com.radiome.core.dsp.filter

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Transposed direct-form-II biquad (RBJ Audio EQ Cookbook coefficients). */
class Biquad private constructor(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    companion object {
        fun lowPass(sampleRate: Double, cutoffHz: Double, q: Double): Biquad {
            val w0 = 2.0 * PI * cutoffHz / sampleRate
            val cw = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            return Biquad(
                b0 = (1.0 - cw) / 2.0 / a0,
                b1 = (1.0 - cw) / a0,
                b2 = (1.0 - cw) / 2.0 / a0,
                a1 = -2.0 * cw / a0,
                a2 = (1.0 - alpha) / a0,
            )
        }

        fun highPass(sampleRate: Double, cutoffHz: Double, q: Double): Biquad {
            val w0 = 2.0 * PI * cutoffHz / sampleRate
            val cw = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            return Biquad(
                b0 = (1.0 + cw) / 2.0 / a0,
                b1 = -(1.0 + cw) / a0,
                b2 = (1.0 + cw) / 2.0 / a0,
                a1 = -2.0 * cw / a0,
                a2 = (1.0 - alpha) / a0,
            )
        }
    }
}

/** Cascade of biquads; used for Butterworth band-pass filters (HP section + LP section). */
class BiquadCascade(private val stages: List<Biquad>) {
    fun process(x: Double): Double {
        var y = x
        for (s in stages) y = s.process(y)
        return y
    }

    fun processInPlace(samples: FloatArray) {
        for (i in samples.indices) samples[i] = process(samples[i].toDouble()).toFloat()
    }

    fun reset() = stages.forEach { it.reset() }

    companion object {
        /** Butterworth Q values for an even [order]. */
        private fun butterworthQs(order: Int): List<Double> {
            require(order >= 2 && order % 2 == 0) { "order must be even" }
            return (0 until order / 2).map { k -> 1.0 / (2.0 * cos(PI * (2 * k + 1) / (2.0 * order))) }
        }

        /** Band-pass built as an [order]-th order Butterworth high-pass followed by an [order]-th order low-pass. */
        fun butterworthBandPass(sampleRate: Double, lowHz: Double, highHz: Double, order: Int = 4): BiquadCascade {
            val qs = butterworthQs(order)
            return BiquadCascade(
                qs.map { Biquad.highPass(sampleRate, lowHz, it) } + qs.map { Biquad.lowPass(sampleRate, highHz, it) },
            )
        }
    }
}
