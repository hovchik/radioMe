package com.radiome.core.dsp.sim

import com.radiome.core.dsp.filter.BiquadCascade
import com.radiome.core.dsp.modem.ModemConfig
import java.util.Random
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

data class Dropout(val startMs: Double, val durationMs: Double)

data class ChannelConfig(
    /** AWGN SNR in a 2500 Hz reference bandwidth, relative to the mean signal power. null = no noise. */
    val snrDb: Double? = null,
    /** FM voice-channel audio filter. */
    val bandPass: Boolean = true,
    val bandLowHz: Double = 300.0,
    val bandHighHz: Double = 3000.0,
    /** Sample-clock mismatch between the two devices, parts per million (shifts timing and tone frequency). */
    val clockDriftPpm: Double = 0.0,
    val gain: Double = 1.0,
    /** If set, soft-clip with tanh(drive * x) / tanh(drive), emulating an over-driven radio audio stage. */
    val clipDrive: Double? = null,
    /** Explicit squelch/static drop-outs, times relative to the start of the transmitted signal. */
    val dropouts: List<Dropout> = emptyList(),
    /** Additional random drop-outs placed uniformly over the signal. */
    val randomDropoutCount: Int = 0,
    val randomDropoutMinMs: Double = 50.0,
    val randomDropoutMaxMs: Double = 150.0,
    val leadingSilenceMs: Int = 0,
    val trailingSilenceMs: Int = 0,
    val seed: Long = 1L,
)

/**
 * Radio channel model: clock drift -> gain -> AWGN -> 300-3000 Hz band-pass -> clipping -> drop-outs (zeroed audio
 * plus a faint noise floor, as a muted squelch would produce through a phone microphone).
 */
class RadioChannelSimulator(private val config: ChannelConfig) {
    var appliedDropouts: List<Dropout> = emptyList()
        private set

    fun apply(input: FloatArray): FloatArray {
        val fs = ModemConfig.SAMPLE_RATE
        val rnd = Random(config.seed)
        val signalPower = input.fold(0.0) { a, x -> a + x * x } / input.size.coerceAtLeast(1)

        val drifted = if (config.clockDriftPpm == 0.0) input else resample(input, 1.0 + config.clockDriftPpm * 1e-6)
        val lead = config.leadingSilenceMs * fs / 1000
        val trail = config.trailingSilenceMs * fs / 1000
        val out = FloatArray(lead + drifted.size + trail)
        for (i in drifted.indices) out[lead + i] = (drifted[i] * config.gain).toFloat()

        config.snrDb?.let { snr ->
            val noiseVar = signalPower * config.gain * config.gain / 10.0.pow(snr / 10.0) * (fs / 2.0) / 2500.0
            val sigma = sqrt(noiseVar)
            for (i in out.indices) out[i] += (rnd.nextGaussian() * sigma).toFloat()
        }
        if (config.bandPass) {
            BiquadCascade.butterworthBandPass(fs.toDouble(), config.bandLowHz, config.bandHighHz, 4).processInPlace(out)
        }
        config.clipDrive?.let { d ->
            val norm = tanh(d)
            for (i in out.indices) out[i] = (tanh(d * out[i]) / norm).toFloat()
        }

        val drops = config.dropouts.toMutableList()
        val signalMs = drifted.size * 1000.0 / fs
        repeat(config.randomDropoutCount) {
            val dur = config.randomDropoutMinMs + rnd.nextDouble() * (config.randomDropoutMaxMs - config.randomDropoutMinMs)
            drops.add(Dropout(rnd.nextDouble() * (signalMs - dur), dur))
        }
        for (d in drops) {
            val s = lead + (d.startMs * fs / 1000).toInt()
            val e = minOf(out.size, s + (d.durationMs * fs / 1000).toInt())
            for (i in s.coerceAtLeast(0) until e) out[i] = (rnd.nextGaussian() * 1e-4).toFloat()
        }
        appliedDropouts = drops
        return out
    }

    /** Linear-interpolation resampler: output sample i reads input at i * ratio. */
    private fun resample(x: FloatArray, ratio: Double): FloatArray {
        val n = ((x.size - 1) / ratio).toInt()
        return FloatArray(n) { i ->
            val t = i * ratio
            val j = t.toInt()
            val f = t - j
            if (j + 1 < x.size) (x[j] * (1 - f) + x[j + 1] * f).toFloat() else x[j]
        }
    }
}
