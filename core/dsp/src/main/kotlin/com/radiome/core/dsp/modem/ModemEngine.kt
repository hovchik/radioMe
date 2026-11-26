package com.radiome.core.dsp.modem

/** Swappable modem: anything that turns a [PhyBurst] into audio and audio back into [RawBurst]s. */
interface ModemEngine {
    val sampleRate: Int

    fun modulate(burst: PhyBurst): FloatArray

    fun createDemodulator(listener: DemodulatorListener? = null): Demodulator

    /** Exact on-air duration in milliseconds of a burst with this header (lead-in and tail included). */
    fun airtimeMs(header: PhyHeader): Long
}

enum class RxState { LISTENING, SYNC_LOCKED, RECEIVING }

data class DemodTelemetry(
    val state: RxState = RxState.LISTENING,
    /** Input level of the most recent block, dBFS (0 dBFS = full-scale sine). */
    val levelDbfs: Double = Double.NEGATIVE_INFINITY,
    /** Running SNR of the current/last burst, dB in 2500 Hz. NaN when unknown. */
    val snrDb: Double = Double.NaN,
    val syncCorrelation: Double = 0.0,
    val header: PhyHeader? = null,
    val symbolsDone: Int = 0,
    val symbolsTotal: Int = 0,
    val erasedSymbols: Int = 0,
)

interface DemodulatorListener {
    fun onSyncDetected(correlation: Double) {}
    fun onHeaderDecoded(header: PhyHeader) {}
    fun onHeaderFailed() {}
    fun onProgress(symbolsDone: Int, symbolsTotal: Int) {}
    fun onBurst(burst: RawBurst) {}
}

/** Streaming receiver. Feed it audio in any block size; completed bursts are returned (and sent to the listener). */
interface Demodulator {
    val telemetry: DemodTelemetry

    fun process(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset): List<RawBurst>

    /** Push silence through so a burst that ends exactly at the end of the input completes. */
    fun flush(): List<RawBurst>

    fun reset()
}

class MfskModemEngine(val config: ModemConfig = ModemConfig()) : ModemEngine {
    private val modulator = MfskModulator(config)

    override val sampleRate: Int = ModemConfig.SAMPLE_RATE

    override fun modulate(burst: PhyBurst): FloatArray = modulator.modulate(burst)

    override fun createDemodulator(listener: DemodulatorListener?): Demodulator = MfskDemodulator(config, listener)

    override fun airtimeMs(header: PhyHeader): Long = modulator.burstSamples(header) * 1000L / sampleRate

    /** Sample offset (from burst start) where body byte [byteIndex] begins on air. */
    fun bodyByteOffsetSamples(header: PhyHeader, byteIndex: Int): Long {
        val p = header.profile
        val start = config.leadInSamples + ModemConfig.CHIRP_SAMPLES + PhyHeader.SYMBOLS * PhyHeader.HEADER_PROFILE.symbolSamples
        return start + (byteIndex.toLong() * 8 / p.bitsPerSymbol) * p.symbolSamples
    }
}
