package com.radiome.core.dsp.modem

import com.radiome.core.dsp.fec.Crc8
import com.radiome.core.dsp.fec.ReedSolomon

/**
 * PHY header, always sent with the ROBUST profile right after the sync chirp, so the receiver learns the body
 * profile and FEC geometry without prior configuration.
 *
 * ```
 * byte 0 : [7:6] version (=1) | [5:4] profile id | [3:0] interleave depth - 1
 * byte 1 : k     codeword data bytes (= link frame length)
 * byte 2 : nsym  RS parity bytes per codeword
 * byte 3 : codeword count in this burst (1..255)
 * byte 4 : CRC-8 over bytes 0..3
 * bytes 5..8 : RS(9,5) parity (corrects 2 byte errors or 4 erasures)
 * ```
 * 9 bytes = 72 bits = 24 ROBUST symbols = 480 ms.
 */
data class PhyHeader(
    val profile: ModemProfile,
    val interleaveDepth: Int,
    val codewordDataBytes: Int,
    val paritySymbols: Int,
    val codewordCount: Int,
    val version: Int = VERSION,
) {
    init {
        require(interleaveDepth in 1..16) { "interleave depth must be 1..16" }
        require(codewordDataBytes in 1..253)
        require(paritySymbols in 2..254 && codewordDataBytes + paritySymbols <= 255) { "codeword longer than 255" }
        require(codewordCount in 1..255) { "codeword count must be 1..255" }
        require(version in 0..3)
    }

    val codewordLength: Int get() = codewordDataBytes + paritySymbols
    val bodyBytes: Int get() = codewordLength * codewordCount

    fun encode(): ByteArray {
        val raw = ByteArray(RAW_SIZE)
        raw[0] = ((version shl 6) or (profile.id shl 4) or (interleaveDepth - 1)).toByte()
        raw[1] = codewordDataBytes.toByte()
        raw[2] = paritySymbols.toByte()
        raw[3] = codewordCount.toByte()
        raw[4] = Crc8.compute(raw, 0, 4).toByte()
        return rs.encode(raw)
    }

    companion object {
        const val VERSION = 1
        const val RAW_SIZE = 5
        const val PARITY = 4
        const val ENCODED_SIZE = RAW_SIZE + PARITY
        val HEADER_PROFILE = ModemProfile.ROBUST
        val SYMBOLS: Int = HEADER_PROFILE.symbolsFor(ENCODED_SIZE)

        private val rs = ReedSolomon(PARITY)

        /** Returns the header or null if it cannot be recovered / validated. */
        fun decode(encoded: ByteArray, erasures: IntArray = IntArray(0)): PhyHeader? {
            if (encoded.size != ENCODED_SIZE) return null
            var res = rs.decode(encoded, erasures)
            if (!res.success && erasures.isNotEmpty()) res = rs.decode(encoded)
            val raw = res.data(PARITY) ?: return null
            if (Crc8.compute(raw, 0, 4) != (raw[4].toInt() and 0xFF)) return null
            val b0 = raw[0].toInt() and 0xFF
            val version = b0 ushr 6
            if (version != VERSION) return null
            val profile = ModemProfile.fromId((b0 ushr 4) and 0x3) ?: return null
            val depth = (b0 and 0xF) + 1
            val k = raw[1].toInt() and 0xFF
            val nsym = raw[2].toInt() and 0xFF
            val count = raw[3].toInt() and 0xFF
            return runCatching { PhyHeader(profile, depth, k, nsym, count, version) }.getOrNull()
        }
    }
}

/** What the modulator puts on air: a header plus the already FEC-encoded, interleaved body bytes. */
class PhyBurst(val header: PhyHeader, val body: ByteArray) {
    init {
        require(body.size == header.bodyBytes) { "body is ${body.size} bytes, header says ${header.bodyBytes}" }
    }
}

/** What the demodulator hands up: hard-decision body bytes plus per-byte erasure flags. */
class RawBurst(
    val header: PhyHeader,
    val body: ByteArray,
    val erasures: BooleanArray,
    /** Mean SNR over the burst, dB in a 2500 Hz reference bandwidth. */
    val snrDb: Double,
    val erasedSymbols: Int,
    val totalSymbols: Int,
    /** Absolute input sample index where the sync chirp started. */
    val syncSample: Long,
)
