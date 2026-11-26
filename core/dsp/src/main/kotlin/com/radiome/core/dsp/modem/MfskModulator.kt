package com.radiome.core.dsp.modem

import com.radiome.core.dsp.math.Gray
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Continuous-phase MFSK modulator producing 48 kHz mono float PCM in [-1, 1]. */
class MfskModulator(private val config: ModemConfig = ModemConfig()) {
    private val chirpIncrements = Waveforms.chirpPhaseIncrements()

    fun burstSamples(header: PhyHeader): Int =
        config.leadInSamples + ModemConfig.CHIRP_SAMPLES +
            PhyHeader.SYMBOLS * PhyHeader.HEADER_PROFILE.symbolSamples +
            header.profile.symbolsFor(header.bodyBytes) * header.profile.symbolSamples +
            config.tailSamples

    fun modulate(burst: PhyBurst): FloatArray {
        val header = burst.header
        val out = FloatArray(burstSamples(header))
        val amp = config.amplitude.toDouble()
        val fs = ModemConfig.SAMPLE_RATE.toDouble()
        var phase = 0.0
        var pos = 0

        fun tone(freq: Double, samples: Int) {
            val inc = 2.0 * PI * freq / fs
            for (i in 0 until samples) {
                out[pos++] = (amp * sin(phase)).toFloat()
                phase += inc
                if (phase > 2.0 * PI) phase -= 2.0 * PI
            }
        }

        // Lead-in pilot (keys VOX, opens squelch).
        tone(ModemConfig.PILOT_HZ, config.leadInSamples)
        // Sync chirp.
        for (inc in chirpIncrements) {
            out[pos++] = (amp * sin(phase)).toFloat()
            phase += inc
            if (phase > 2.0 * PI) phase -= 2.0 * PI
        }
        // Header (always ROBUST) then body (selected profile).
        val hp = PhyHeader.HEADER_PROFILE
        for (v in Waveforms.bytesToSymbols(header.encode(), hp.bitsPerSymbol)) tone(hp.toneHz(Gray.encode(v)), hp.symbolSamples)
        val bp = header.profile
        for (v in Waveforms.bytesToSymbols(burst.body, bp.bitsPerSymbol)) tone(bp.toneHz(Gray.encode(v)), bp.symbolSamples)
        // Tail.
        tone(ModemConfig.PILOT_HZ, config.tailSamples)
        check(pos == out.size)

        applyFade(out, 0, minOf(ModemConfig.FADE_SAMPLES, config.leadInSamples), rising = true)
        val fadeOut = minOf(ModemConfig.FADE_SAMPLES, config.tailSamples)
        applyFade(out, out.size - fadeOut, fadeOut, rising = false)
        return out
    }

    private fun applyFade(buf: FloatArray, start: Int, length: Int, rising: Boolean) {
        for (i in 0 until length) {
            val g = 0.5 - 0.5 * cos(PI * i / length)
            buf[start + i] = (buf[start + i] * if (rising) g else 1.0 - g).toFloat()
        }
    }
}
