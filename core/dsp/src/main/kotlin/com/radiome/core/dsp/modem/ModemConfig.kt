package com.radiome.core.dsp.modem

/**
 * Physical-layer configuration shared by modulator and demodulator.
 *
 * Burst on air:
 * ```
 * | lead-in pilot 1500 Hz | sync chirp 700->2300 Hz | PHY header (ROBUST) | body (selected profile) | tail |
 * |   leadInMs (200-1500) |        100 ms           |  24 symbols, 480 ms |      ...                | 60ms |
 * ```
 */
data class ModemConfig(
    /** VOX / squelch lead-in pilot duration. */
    val leadInMs: Int = DEFAULT_LEAD_IN_MS,
    /** Peak amplitude of the generated waveform, 0..1 of full scale. */
    val amplitude: Float = 0.7f,
    /** Tail tone after the last symbol so a radio's VOX hang / squelch tail does not clip data. */
    val tailMs: Int = 60,
    /** Normalised chirp correlation (0..1) needed to declare sync. */
    val syncThreshold: Double = 0.45,
    /** A symbol whose strongest tone is this far (dB) below the running signal reference is flagged as an erasure. */
    val erasureThresholdDb: Double = -10.0,
) {
    init {
        require(leadInMs in MIN_LEAD_IN_MS..MAX_LEAD_IN_MS) { "leadInMs must be $MIN_LEAD_IN_MS..$MAX_LEAD_IN_MS" }
        require(amplitude > 0f && amplitude <= 1f)
        require(tailMs in 0..1000)
    }

    val leadInSamples: Int get() = leadInMs * SAMPLE_RATE / 1000
    val tailSamples: Int get() = tailMs * SAMPLE_RATE / 1000

    companion object {
        const val SAMPLE_RATE = 48_000
        const val DEFAULT_LEAD_IN_MS = 600
        const val MIN_LEAD_IN_MS = 200
        const val MAX_LEAD_IN_MS = 1500
        const val PILOT_HZ = 1500.0
        const val CHIRP_START_HZ = 700.0
        const val CHIRP_END_HZ = 2300.0
        const val CHIRP_SAMPLES = 4800 // 100 ms
        const val FADE_SAMPLES = 480 // 10 ms raised-cosine ramps

        /** Passband the modem is allowed to occupy. */
        const val PASSBAND_LOW_HZ = 600.0
        const val PASSBAND_HIGH_HZ = 2400.0
    }
}
