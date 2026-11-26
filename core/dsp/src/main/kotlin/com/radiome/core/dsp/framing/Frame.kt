package com.radiome.core.dsp.framing

import com.radiome.core.dsp.fec.Crc16

enum class FrameType(val code: Int) {
    DATA(0x1),
    HEADER(0x2),
    NACK(0x3),
    ACK(0x4),
    /** Station identification: source callsign and optional destination, first frame of every burst. */
    STATION(0x5),
    /** Link-test probe (known filler at minimum parity, one burst per profile). */
    PROBE(0x6),
    /** Receiver's measurements of a link test. */
    PROBE_REPORT(0x7),
    ;

    companion object {
        fun fromCode(code: Int): FrameType? = entries.firstOrNull { it.code == code }
    }
}

class Frame(
    val type: FrameType,
    val sessionId: Int,
    val chunkIndex: Int,
    val totalChunks: Int,
    val payload: ByteArray,
) {
    init {
        require(sessionId in 0..0xFFFF) { "sessionId must fit 16 bits" }
        require(chunkIndex in 0..0xFFFF && totalChunks in 0..0xFFFF)
        require(payload.size <= 255)
    }

    override fun equals(other: Any?): Boolean =
        other is Frame && type == other.type && sessionId == other.sessionId && chunkIndex == other.chunkIndex &&
            totalChunks == other.totalChunks && payload.contentEquals(other.payload)

    override fun hashCode(): Int = ((type.hashCode() * 31 + sessionId) * 31 + chunkIndex) * 31 + payload.contentHashCode()

    override fun toString(): String = "Frame($type session=$sessionId chunk=$chunkIndex/$totalChunks len=${payload.size})"
}

/**
 * Link frame layout (all multi-byte fields big-endian). Every frame in one burst has the same payload capacity P,
 * so the frame is exactly the RS codeword data part (k = 11 + P).
 *
 * ```
 * offset  size  field
 *   0      1    magic        0xA5
 *   1      1    version:4 | frameType:4      (version = 1; DATA=1 HEADER=2 NACK=3 ACK=4 STATION=5 PROBE=6 REPORT=7)
 *   2      2    sessionId    u16
 *   4      2    chunkIndex   u16
 *   6      2    totalChunks  u16
 *   8      1    payloadLen   u8  (<= P, last chunk may be short)
 *   9      P    payload      zero padded to P
 *   9+P    2    CRC-16/CCITT-FALSE over bytes 0 .. 8+P
 * ---- followed on air by nsym Reed-Solomon parity bytes (added by BurstCodec) ----
 * ```
 */
object FrameCodec {
    const val MAGIC = 0xA5
    const val VERSION = 1
    const val OVERHEAD = 11
    const val DEFAULT_PAYLOAD = 48
    const val MIN_PAYLOAD = 16
    const val MAX_PAYLOAD = 200

    fun frameLength(payloadCapacity: Int) = OVERHEAD + payloadCapacity

    fun encode(frame: Frame, payloadCapacity: Int = DEFAULT_PAYLOAD): ByteArray {
        require(payloadCapacity in 0..MAX_PAYLOAD)
        require(frame.payload.size <= payloadCapacity) { "payload ${frame.payload.size} > capacity $payloadCapacity" }
        val out = ByteArray(frameLength(payloadCapacity))
        out[0] = MAGIC.toByte()
        out[1] = ((VERSION shl 4) or frame.type.code).toByte()
        putU16(out, 2, frame.sessionId)
        putU16(out, 4, frame.chunkIndex)
        putU16(out, 6, frame.totalChunks)
        out[8] = frame.payload.size.toByte()
        frame.payload.copyInto(out, 9)
        val crcAt = 9 + payloadCapacity
        putU16(out, crcAt, Crc16.compute(out, 0, crcAt))
        return out
    }

    sealed interface Result {
        data class Valid(val frame: Frame) : Result
        data class Invalid(val reason: String) : Result
    }

    fun decode(bytes: ByteArray): Result {
        if (bytes.size < OVERHEAD) return Result.Invalid("too short")
        val crcAt = bytes.size - 2
        if (Crc16.compute(bytes, 0, crcAt) != getU16(bytes, crcAt)) return Result.Invalid("crc")
        if (bytes[0].toInt() and 0xFF != MAGIC) return Result.Invalid("magic")
        val vt = bytes[1].toInt() and 0xFF
        if (vt ushr 4 != VERSION) return Result.Invalid("version")
        val type = FrameType.fromCode(vt and 0xF) ?: return Result.Invalid("type")
        val payloadLen = bytes[8].toInt() and 0xFF
        if (payloadLen > bytes.size - OVERHEAD) return Result.Invalid("length")
        return Result.Valid(
            Frame(type, getU16(bytes, 2), getU16(bytes, 4), getU16(bytes, 6), bytes.copyOfRange(9, 9 + payloadLen)),
        )
    }

    internal fun putU16(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 8).toByte()
        b[at + 1] = v.toByte()
    }

    internal fun getU16(b: ByteArray, at: Int): Int = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
}
