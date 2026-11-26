package com.radiome.core.protocol.link

import com.radiome.core.dsp.framing.Frame
import com.radiome.core.dsp.framing.FrameType

/**
 * Who sent a burst and who it is for. Carried in clear in a STATION frame at the start of every burst, which also
 * satisfies station identification rules on amateur bands.
 *
 * ```
 * offset size field
 *   0     1   flags: bit 0 = broadcast session (many receivers, merged corrections)
 *   1     1   source length s (0..10)
 *   2     s   source callsign, ASCII
 *  2+s    1   destination length d (0..10, 0 = all stations)
 *  3+s    d   destination callsign, ASCII
 * ```
 */
data class StationInfo(
    val source: String,
    /** Null or blank: addressed to every station. */
    val destination: String? = null,
    val broadcast: Boolean = false,
) {
    val isForEveryone: Boolean get() = destination.isNullOrBlank()

    /** True when a station called [callsign] should act on this (it is for everyone or for that callsign). */
    fun isFor(callsign: String): Boolean = isForEveryone || Callsign.normalize(destination!!) == Callsign.normalize(callsign)

    fun encode(): ByteArray {
        val src = Callsign.normalize(source).toByteArray(Charsets.US_ASCII)
        val dst = Callsign.normalize(destination ?: "").toByteArray(Charsets.US_ASCII)
        val out = ByteArray(3 + src.size + dst.size)
        out[0] = (if (broadcast) FLAG_BROADCAST else 0).toByte()
        out[1] = src.size.toByte()
        src.copyInto(out, 2)
        out[2 + src.size] = dst.size.toByte()
        dst.copyInto(out, 3 + src.size)
        return out
    }

    fun frame(sessionId: Int, totalChunks: Int = 0): Frame = Frame(FrameType.STATION, sessionId, 0, totalChunks, encode())

    companion object {
        private const val FLAG_BROADCAST = 0x01

        fun decode(b: ByteArray): StationInfo? {
            if (b.size < 3) return null
            val s = b[1].toInt() and 0xFF
            if (s > Callsign.MAX_LENGTH || b.size < 3 + s) return null
            val d = b[2 + s].toInt() and 0xFF
            if (d > Callsign.MAX_LENGTH || b.size < 3 + s + d) return null
            val src = String(b, 2, s, Charsets.US_ASCII)
            val dst = String(b, 3 + s, d, Charsets.US_ASCII)
            if (!Callsign.isValid(src, allowBlank = true) || !Callsign.isValid(dst, allowBlank = true)) return null
            return StationInfo(src, dst.ifEmpty { null }, broadcast = (b[0].toInt() and FLAG_BROADCAST) != 0)
        }
    }
}

object Callsign {
    const val MAX_LENGTH = 10
    private val allowed = Regex("[A-Z0-9/-]*")

    /** Upper case, trimmed, only A-Z 0-9 / and -, at most [MAX_LENGTH] characters. */
    fun normalize(raw: String): String =
        raw.trim().uppercase().filter { it in 'A'..'Z' || it in '0'..'9' || it == '/' || it == '-' }.take(MAX_LENGTH)

    fun isValid(s: String, allowBlank: Boolean = false): Boolean =
        (allowBlank || s.isNotEmpty()) && s.length <= MAX_LENGTH && allowed.matches(s)
}
