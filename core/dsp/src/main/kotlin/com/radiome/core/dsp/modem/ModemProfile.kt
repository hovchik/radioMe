package com.radiome.core.dsp.modem

/**
 * MFSK speed/robustness profiles. All timing is expressed in samples at [ModemConfig.SAMPLE_RATE] (48 kHz).
 *
 * Tones are f_k = [baseHz] + k * [spacingHz]. The detection window ([windowSamples]) is an integer multiple of
 * 1 / spacing, so tones are orthogonal over the window. The remaining part of the symbol is a guard split evenly
 * before and after the window to absorb timing error, filter group delay and acoustic echo.
 *
 * | Profile  | M  | Tones (Hz)          | Spacing | Symbol  | Window | Baud | Raw bps |
 * |----------|----|---------------------|---------|---------|--------|------|---------|
 * | ROBUST   | 8  | 800, 1000 ... 2200  | 200 Hz  | 20.0 ms | 15 ms  | 50   | 150     |
 * | STANDARD | 16 | 750, 850 ... 2250   | 100 Hz  | 12.5 ms | 10 ms  | 80   | 320     |
 * | FAST     | 8  | 800, 1000 ... 2200  | 200 Hz  | 5.0 ms  | 5 ms   | 200  | 600     |
 */
enum class ModemProfile(
    val id: Int,
    val toneCount: Int,
    val baseHz: Double,
    val spacingHz: Double,
    val symbolSamples: Int,
    val windowSamples: Int,
    /** Default RS parity bytes as a fraction of codeword data bytes. */
    val defaultParityRatio: Double,
    /** Default interleaving depth (codewords per interleave block). */
    val defaultInterleaveDepth: Int,
) {
    ROBUST(id = 0, toneCount = 8, baseHz = 800.0, spacingHz = 200.0, symbolSamples = 960, windowSamples = 720, defaultParityRatio = 0.50, defaultInterleaveDepth = 8),
    STANDARD(id = 1, toneCount = 16, baseHz = 750.0, spacingHz = 100.0, symbolSamples = 600, windowSamples = 480, defaultParityRatio = 0.33, defaultInterleaveDepth = 6),
    FAST(id = 2, toneCount = 8, baseHz = 800.0, spacingHz = 200.0, symbolSamples = 240, windowSamples = 240, defaultParityRatio = 0.20, defaultInterleaveDepth = 4),
    ;

    val bitsPerSymbol: Int = Integer.numberOfTrailingZeros(toneCount)
    val guardSamples: Int get() = (symbolSamples - windowSamples) / 2
    val baud: Double get() = ModemConfig.SAMPLE_RATE.toDouble() / symbolSamples
    val rawBitsPerSecond: Double get() = baud * bitsPerSymbol
    val maxToneHz: Double get() = toneHz(toneCount - 1)

    fun toneHz(index: Int): Double = baseHz + index * spacingHz

    fun symbolsFor(bytes: Int): Int = (bytes * 8 + bitsPerSymbol - 1) / bitsPerSymbol

    companion object {
        fun fromId(id: Int): ModemProfile? = entries.firstOrNull { it.id == id }
    }
}
