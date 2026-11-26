package com.radiome.core.protocol.session

import com.radiome.core.protocol.payload.ContentType
import java.util.zip.CRC32

/**
 * Payload of the HEADER frame (chunkIndex = [Packetizer.HEADER_CHUNK]). It describes the whole transfer so the
 * receiver can reassemble, decompress and verify it.
 *
 * ```
 * offset size field
 *   0     1   header version (= 1)
 *   1     1   content type (TEXT=1, IMAGE_WEBP=2, BINARY=3)
 *   2     1   flags: bit0 = DEFLATE compressed
 *   3     4   wire length   u32  (bytes carried by the DATA chunks)
 *   7     4   original length u32 (after decompression)
 *  11     4   CRC-32 of the original bytes
 *  15     1   chunk payload size P
 *  16     1   name length (UTF-8 bytes)
 *  17     n   name, UTF-8, truncated to fit the frame
 * ```
 */
data class SessionHeader(
    val contentType: ContentType,
    val compressed: Boolean,
    val wireLength: Int,
    val originalLength: Int,
    val crc32: Long,
    val chunkPayloadSize: Int,
    val name: String = "",
) {
    fun encode(capacity: Int): ByteArray {
        val nameBytes = truncateUtf8(name, capacity - FIXED_SIZE)
        val out = ByteArray(FIXED_SIZE + nameBytes.size)
        out[0] = VERSION.toByte()
        out[1] = contentType.code.toByte()
        out[2] = (if (compressed) FLAG_DEFLATE else 0).toByte()
        putU32(out, 3, wireLength.toLong())
        putU32(out, 7, originalLength.toLong())
        putU32(out, 11, crc32)
        out[15] = chunkPayloadSize.toByte()
        out[16] = nameBytes.size.toByte()
        nameBytes.copyInto(out, FIXED_SIZE)
        return out
    }

    companion object {
        const val VERSION = 1
        const val FIXED_SIZE = 17
        private const val FLAG_DEFLATE = 0x01

        fun decode(b: ByteArray): SessionHeader? {
            if (b.size < FIXED_SIZE || b[0].toInt() != VERSION) return null
            val type = ContentType.fromCode(b[1].toInt() and 0xFF) ?: return null
            val nameLen = b[16].toInt() and 0xFF
            if (b.size < FIXED_SIZE + nameLen) return null
            val wire = getU32(b, 3)
            val orig = getU32(b, 7)
            if (wire > Int.MAX_VALUE || orig > Int.MAX_VALUE) return null
            val p = b[15].toInt() and 0xFF
            if (p == 0) return null
            return SessionHeader(
                contentType = type,
                compressed = (b[2].toInt() and FLAG_DEFLATE) != 0,
                wireLength = wire.toInt(),
                originalLength = orig.toInt(),
                crc32 = getU32(b, 11),
                chunkPayloadSize = p,
                name = String(b, FIXED_SIZE, nameLen, Charsets.UTF_8),
            )
        }

        fun crcOf(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

        /** UTF-8 encode, cutting at a code point boundary so the result is at most [maxBytes]. */
        fun truncateUtf8(s: String, maxBytes: Int): ByteArray {
            val full = s.toByteArray(Charsets.UTF_8)
            if (full.size <= maxBytes) return full
            var end = maxBytes.coerceAtLeast(0)
            while (end > 0 && (full[end].toInt() and 0xC0) == 0x80) end--
            return full.copyOf(end)
        }

        private fun putU32(b: ByteArray, at: Int, v: Long) {
            for (i in 0 until 4) b[at + i] = (v ushr (24 - 8 * i)).toByte()
        }

        private fun getU32(b: ByteArray, at: Int): Long {
            var v = 0L
            for (i in 0 until 4) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
            return v
        }
    }
}
