package com.radiome.core.dsp.modem

import com.radiome.core.dsp.filter.BiquadCascade
import com.radiome.core.dsp.math.Goertzel
import com.radiome.core.dsp.math.Gray
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Non-coherent MFSK receiver with continuous listening.
 *
 * Pipeline: front-end band-pass (400-2600 Hz) -> chirp matched filter (sync search) -> ROBUST header symbols ->
 * body symbols in the profile named by the header -> [RawBurst].
 *
 * Per symbol: Goertzel bank over the guard-trimmed window, hard decision on the strongest tone, early/late gate on the
 * winning tone to track sample-clock drift, and an energy test against a running reference that flags squelch
 * drop-outs as erasures for the Reed-Solomon decoder.
 */
class MfskDemodulator(
    private val config: ModemConfig = ModemConfig(),
    private val listener: DemodulatorListener? = null,
) : Demodulator {
    private class ProfileBank(val profile: ModemProfile) {
        val tones = Array(profile.toneCount) { Goertzel(profile.toneHz(it), ModemConfig.SAMPLE_RATE) }
        val window = profile.windowSamples
        val guard = profile.guardSamples
        /** Early/late gate offset. */
        val gate = maxOf(profile.guardSamples, profile.windowSamples / 8)
        val windowSq = window.toDouble() * window
    }

    private val banks = ModemProfile.entries.associateWith { ProfileBank(it) }
    private val frontEnd = BiquadCascade.butterworthBandPass(ModemConfig.SAMPLE_RATE.toDouble(), 400.0, 2600.0, 4)
    private val correlator = ChirpCorrelator()
    private val erasureFactor = 10.0.pow(config.erasureThresholdDb / 10.0)

    private var buf = FloatArray(1 shl 18)
    private var len = 0
    private var bufStartAbs = 0L

    private var state = RxState.LISTENING
    private var searchPos = 0
    private var bestRho = 0.0
    private var bestPos = -1
    private var lockPos = 0

    // Symbol decoding state.
    private var bank: ProfileBank = banks.getValue(PhyHeader.HEADER_PROFILE)
    private var symPos = 0.0
    private var symbolsDone = 0
    private var symbolsTotal = 0
    private var unpacker: SymbolUnpacker? = null
    private var header: PhyHeader? = null
    /** Running estimate of signal power per sample (A^2 / 2). */
    private var levelPower = 0.0
    private var sigAcc = 0.0
    private var noiseAcc = 0.0
    private var erasedSymbols = 0
    private var headerErased = 0
    private var syncCorrelation = 0.0
    private var levelDbfs = Double.NEGATIVE_INFINITY

    override val telemetry: DemodTelemetry
        get() = DemodTelemetry(
            state = state,
            levelDbfs = levelDbfs,
            snrDb = currentSnrDb(),
            syncCorrelation = syncCorrelation,
            header = header,
            symbolsDone = if (state == RxState.RECEIVING) symbolsDone else 0,
            symbolsTotal = if (state == RxState.RECEIVING) symbolsTotal else 0,
            erasedSymbols = erasedSymbols,
        )

    override fun process(samples: FloatArray, offset: Int, length: Int): List<RawBurst> {
        append(samples, offset, length)
        val out = ArrayList<RawBurst>(1)
        while (true) {
            val progressed = when (state) {
                RxState.LISTENING -> search()
                RxState.SYNC_LOCKED, RxState.RECEIVING -> decodeSymbols(out)
            }
            if (!progressed) break
        }
        compact()
        return out
    }

    override fun flush(): List<RawBurst> {
        val pad = FloatArray(correlator.fftSize + 4 * ModemProfile.ROBUST.symbolSamples)
        return process(pad)
    }

    override fun reset() {
        frontEnd.reset()
        len = 0
        bufStartAbs = 0
        resetToListening(0)
        levelDbfs = Double.NEGATIVE_INFINITY
    }

    // ---------------------------------------------------------------- buffering

    private fun append(samples: FloatArray, offset: Int, length: Int) {
        if (len + length > buf.size) {
            var cap = buf.size
            while (cap < len + length) cap *= 2
            buf = buf.copyOf(cap)
        }
        var sq = 0.0
        for (i in offset until offset + length) {
            val x = samples[i]
            sq += x * x
            buf[len++] = frontEnd.process(x.toDouble()).toFloat()
        }
        if (length > 0) {
            val rms = sqrt(sq / length)
            levelDbfs = if (rms <= 0.0) Double.NEGATIVE_INFINITY else 20.0 * log10(rms * sqrt(2.0))
        }
    }

    private fun compact() {
        val keepFrom = when (state) {
            RxState.LISTENING -> if (bestPos >= 0) minOf(searchPos, bestPos) else searchPos
            else -> symPos.toInt() - 2 * bank.gate - 64
        }.coerceIn(0, len)
        if (keepFrom < 1 shl 16) return
        System.arraycopy(buf, keepFrom, buf, 0, len - keepFrom)
        len -= keepFrom
        bufStartAbs += keepFrom
        searchPos -= keepFrom
        if (bestPos >= 0) bestPos -= keepFrom
        lockPos -= keepFrom
        symPos -= keepFrom
    }

    // ---------------------------------------------------------------- sync search

    private fun search(): Boolean {
        val n = correlator.fftSize
        val l = correlator.templateLength
        while (searchPos + n <= len) {
            correlator.correlate(buf, searchPos)
            val rho = correlator.output
            for (i in 0 until correlator.hop) {
                val pos = searchPos + i
                val r = rho[i]
                if (r > config.syncThreshold && r > bestRho) {
                    bestRho = r
                    bestPos = pos
                }
                if (bestPos >= 0 && pos - bestPos > l / 4) {
                    lock(bestPos, bestRho)
                    return true
                }
            }
            searchPos += correlator.hop
        }
        return false
    }

    private fun lock(pos: Int, rho: Double) {
        val l = correlator.templateLength
        var energy = 0.0
        for (i in pos until pos + l) energy += buf[i].toDouble() * buf[i]
        levelPower = energy / l
        lockPos = pos
        syncCorrelation = rho
        bestRho = 0.0
        bestPos = -1
        header = null
        sigAcc = 0.0
        noiseAcc = 0.0
        erasedSymbols = 0
        startSection(PhyHeader.HEADER_PROFILE, PhyHeader.SYMBOLS, PhyHeader.ENCODED_SIZE, (pos + l).toDouble())
        state = RxState.SYNC_LOCKED
        listener?.onSyncDetected(rho)
    }

    private fun startSection(profile: ModemProfile, symbols: Int, bytes: Int, startPos: Double) {
        bank = banks.getValue(profile)
        symPos = startPos
        symbolsDone = 0
        symbolsTotal = symbols
        unpacker = SymbolUnpacker(bytes, profile.bitsPerSymbol)
    }

    private fun resetToListening(nextSearch: Int) {
        state = RxState.LISTENING
        searchPos = nextSearch.coerceAtLeast(0)
        bestRho = 0.0
        bestPos = -1
        unpacker = null
    }

    // ---------------------------------------------------------------- symbols

    private fun decodeSymbols(out: MutableList<RawBurst>): Boolean {
        val b = bank
        val unpack = unpacker ?: return false
        var progressed = false
        while (symbolsDone < symbolsTotal) {
            val w = (symPos + b.guard).roundToInt()
            if (w + b.window + b.gate + 1 > len) return progressed
            decodeOne(b, w, unpack)
            progressed = true
            symbolsDone++
            if (state == RxState.RECEIVING) listener?.onProgress(symbolsDone, symbolsTotal)
        }
        if (state == RxState.SYNC_LOCKED) finishHeader(unpack) else finishBody(unpack, out)
        return true
    }

    private fun decodeOne(b: ProfileBank, w: Int, unpack: SymbolUnpacker) {
        var maxE = -1.0
        var k = 0
        var sum = 0.0
        for (t in b.tones.indices) {
            val e = b.tones[t].power(buf, w, b.window)
            sum += e
            if (e > maxE) {
                maxE = e
                k = t
            }
        }
        val expected = levelPower * b.windowSq / 2.0
        val erased = maxE < expected * erasureFactor
        var adjust = 0.0
        if (erased) {
            erasedSymbols++
        } else {
            val early = b.tones[k].power(buf, w - b.gate, b.window)
            val late = b.tones[k].power(buf, w + b.gate, b.window)
            val err = (late - early) / (late + early + 1e-12)
            adjust = (TIMING_GAIN * err * b.gate).coerceIn(-b.gate / 4.0, b.gate / 4.0)
            levelPower = (1 - LEVEL_ALPHA) * levelPower + LEVEL_ALPHA * (maxE * 2.0 / b.windowSq)
            val noise = (sum - maxE) / (b.tones.size - 1)
            // Normalise both to per-sample power so profiles with different windows can be averaged.
            sigAcc += (maxE - noise).coerceAtLeast(0.0) / b.windowSq
            // A bin of W samples has noise bandwidth fs / W; rescale to the 2500 Hz reference bandwidth.
            noiseAcc += noise * REFERENCE_BANDWIDTH_HZ / (ModemConfig.SAMPLE_RATE.toDouble() / b.window) / b.windowSq
        }
        symPos += b.profile.symbolSamples + adjust
        unpack.push(Gray.decode(k), erased)
    }

    private fun finishHeader(unpack: SymbolUnpacker) {
        val decoded = PhyHeader.decode(unpack.bytes, unpack.erasurePositions())
        if (decoded == null) {
            listener?.onHeaderFailed()
            resetToListening(lockPos + correlator.templateLength)
            return
        }
        header = decoded
        state = RxState.RECEIVING
        headerErased = erasedSymbols
        listener?.onHeaderDecoded(decoded)
        startSection(decoded.profile, decoded.profile.symbolsFor(decoded.bodyBytes), decoded.bodyBytes, symPos)
    }

    private fun finishBody(unpack: SymbolUnpacker, out: MutableList<RawBurst>) {
        val h = header!!
        val burst = RawBurst(
            header = h,
            body = unpack.bytes,
            erasures = unpack.erasures,
            snrDb = currentSnrDb(),
            erasedSymbols = erasedSymbols - headerErased,
            totalSymbols = symbolsTotal,
            syncSample = bufStartAbs + lockPos,
        )
        out.add(burst)
        listener?.onBurst(burst)
        resetToListening(symPos.toInt())
    }

    private fun currentSnrDb(): Double =
        if (noiseAcc <= 0.0 || sigAcc <= 0.0) Double.NaN else 10.0 * log10(sigAcc / noiseAcc)

    private companion object {
        const val TIMING_GAIN = 0.08
        const val LEVEL_ALPHA = 0.05
        const val REFERENCE_BANDWIDTH_HZ = 2500.0
    }
}
