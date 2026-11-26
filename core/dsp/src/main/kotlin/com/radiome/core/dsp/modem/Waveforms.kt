package com.radiome.core.dsp.modem

import kotlin.math.PI

internal object Waveforms {
    /** Per-sample phase increments of the linear sync chirp. Shared by modulator and correlator template. */
    fun chirpPhaseIncrements(): DoubleArray {
        val n = ModemConfig.CHIRP_SAMPLES
        val f0 = ModemConfig.CHIRP_START_HZ
        val f1 = ModemConfig.CHIRP_END_HZ
        return DoubleArray(n) { i -> 2.0 * PI * (f0 + (f1 - f0) * i / n) / ModemConfig.SAMPLE_RATE }
    }

    /** Pack bytes MSB-first into symbols of [bits] bits; the last symbol is zero padded. */
    fun bytesToSymbols(bytes: ByteArray, bits: Int): IntArray {
        val count = (bytes.size * 8 + bits - 1) / bits
        val out = IntArray(count)
        var bitIndex = 0
        for (s in 0 until count) {
            var v = 0
            for (b in 0 until bits) {
                val bit = if (bitIndex < bytes.size * 8) {
                    (bytes[bitIndex ushr 3].toInt() ushr (7 - (bitIndex and 7))) and 1
                } else 0
                v = (v shl 1) or bit
                bitIndex++
            }
            out[s] = v
        }
        return out
    }
}

/** Incrementally unpacks symbols back into bytes, tracking which bytes touched an erased symbol. */
internal class SymbolUnpacker(byteCount: Int, private val bits: Int) {
    val bytes = ByteArray(byteCount)
    val erasures = BooleanArray(byteCount)
    private var bitIndex = 0

    fun push(value: Int, erased: Boolean) {
        val totalBits = bytes.size * 8
        val first = bitIndex
        for (b in bits - 1 downTo 0) {
            if (bitIndex < totalBits) {
                if ((value ushr b) and 1 == 1) {
                    val i = bitIndex ushr 3
                    bytes[i] = (bytes[i].toInt() or (1 shl (7 - (bitIndex and 7)))).toByte()
                }
            }
            bitIndex++
        }
        if (erased) {
            val last = minOf(bitIndex - 1, totalBits - 1)
            if (first < totalBits) for (i in (first ushr 3)..(last ushr 3)) erasures[i] = true
        }
    }

    fun erasurePositions(): IntArray = erasures.indices.filter { erasures[it] }.toIntArray()
}
