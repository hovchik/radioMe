package com.radiome.core.dsp.framing

/**
 * NACK payload: which chunk indices the receiver still needs. Two encodings, the encoder picks whichever covers
 * more indices per frame:
 * ```
 * bitmap : 0x00 | base u16 | bitmap bytes   (bit 7 of byte 0 = chunk base, bit 6 = base+1, ...)
 * list   : 0x01 | count u8 | count x u16
 * ```
 * Large loss sets are split across several NACK frames.
 */
object NackCodec {
    private const val MODE_BITMAP = 0
    private const val MODE_LIST = 1

    fun encode(missing: Collection<Int>, payloadCapacity: Int = FrameCodec.DEFAULT_PAYLOAD): List<ByteArray> {
        require(payloadCapacity >= 5)
        val sorted = missing.distinct().sorted()
        require(sorted.all { it in 0..0xFFFF })
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i < sorted.size) {
            val base = sorted[i]
            val span = (payloadCapacity - 3) * 8
            var bitmapCount = 0
            while (i + bitmapCount < sorted.size && sorted[i + bitmapCount] < base + span) bitmapCount++
            val listCount = minOf((payloadCapacity - 2) / 2, sorted.size - i, 255)
            if (bitmapCount >= listCount) {
                val last = sorted[i + bitmapCount - 1]
                val bytes = ByteArray(3 + (last - base) / 8 + 1)
                bytes[0] = MODE_BITMAP.toByte()
                FrameCodec.putU16(bytes, 1, base)
                for (j in 0 until bitmapCount) {
                    val off = sorted[i + j] - base
                    bytes[3 + off / 8] = (bytes[3 + off / 8].toInt() or (0x80 ushr (off % 8))).toByte()
                }
                out.add(bytes)
                i += bitmapCount
            } else {
                val bytes = ByteArray(2 + listCount * 2)
                bytes[0] = MODE_LIST.toByte()
                bytes[1] = listCount.toByte()
                for (j in 0 until listCount) FrameCodec.putU16(bytes, 2 + j * 2, sorted[i + j])
                out.add(bytes)
                i += listCount
            }
        }
        return out
    }

    fun decode(payload: ByteArray): List<Int> {
        require(payload.isNotEmpty()) { "empty NACK payload" }
        return when (payload[0].toInt()) {
            MODE_BITMAP -> {
                require(payload.size >= 3)
                val base = FrameCodec.getU16(payload, 1)
                val out = ArrayList<Int>()
                for (byte in 3 until payload.size) for (bit in 0 until 8) {
                    if ((payload[byte].toInt() and (0x80 ushr bit)) != 0) out.add(base + (byte - 3) * 8 + bit)
                }
                out
            }
            MODE_LIST -> {
                val count = payload[1].toInt() and 0xFF
                require(payload.size >= 2 + count * 2)
                List(count) { FrameCodec.getU16(payload, 2 + it * 2) }
            }
            else -> throw IllegalArgumentException("unknown NACK mode ${payload[0]}")
        }
    }
}
