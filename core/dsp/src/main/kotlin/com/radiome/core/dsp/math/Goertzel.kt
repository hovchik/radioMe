package com.radiome.core.dsp.math

import kotlin.math.PI
import kotlin.math.cos

/**
 * Single-bin Goertzel detector. [power] returns |X(f)|^2 over the window, so a sinusoid of
 * amplitude A exactly at [frequencyHz] yields (A * length / 2)^2.
 */
class Goertzel(val frequencyHz: Double, sampleRate: Int) {
    private val coeff = 2.0 * cos(2.0 * PI * frequencyHz / sampleRate)

    fun power(samples: FloatArray, start: Int, length: Int): Double {
        var s1 = 0.0
        var s2 = 0.0
        val end = start + length
        for (i in start until end) {
            val s = samples[i] + coeff * s1 - s2
            s2 = s1
            s1 = s
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2
    }
}
