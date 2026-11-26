package com.radiome.core.protocol.payload

/**
 * A voice note ([ContentType.VOICE]): Codec2 frames with their unused padding bits removed.
 *
 * ```
 * offset size field
 *   0     1   version (= 1)
 *   1     1   Codec2 mode (8 = 700C, 40 ms frames)
 *   2     1   bits per frame (28 for 700C)
 *   3     2   frame count, u16 big-endian
 *   5     ... frames back to back, each [bits per frame] bits, MSB first; the last byte is zero padded
 * ```
 *
 * Codec2 hands out each frame in whole bytes (700C: 28 bits in 4 bytes, MSB first). Packing them tightly saves
 * one byte in eight on air.
 */
class VoiceNote(val mode: Int, val bitsPerFrame: Int, val frames: List<ByteArray>) {
    init {
        require(bitsPerFrame in 1..255)
        require(frames.size <= 0xFFFF)
    }

    /** Frame length in bytes as Codec2 wants it back. */
    val bytesPerFrame: Int get() = (bitsPerFrame + 7) / 8

    fun durationMs(frameMs: Int = frameMsOf(mode)): Long = frames.size.toLong() * frameMs

    fun encode(): ByteArray {
        val out = ByteArray(HEADER + (frames.size * bitsPerFrame + 7) / 8)
        out[0] = VERSION.toByte()
        out[1] = mode.toByte()
        out[2] = bitsPerFrame.toByte()
        out[3] = (frames.size ushr 8).toByte()
        out[4] = frames.size.toByte()
        var bit = HEADER * 8
        for (f in frames) {
            for (i in 0 until bitsPerFrame) {
                if ((f[i / 8].toInt() ushr (7 - i % 8)) and 1 == 1) out[bit / 8] = (out[bit / 8].toInt() or (0x80 ushr (bit % 8))).toByte()
                bit++
            }
        }
        return out
    }

    fun toPayload(name: String = ""): Payload = Payload(ContentType.VOICE, encode(), name)

    companion object {
        const val VERSION = 1
        const val HEADER = 5
        const val MODE_700C = 8
        const val MODE_1200 = 5
        const val MODE_1300 = 4
        const val MODE_3200 = 0

        /** Codec2 frame length: 20 ms for 3200 and 2400, 40 ms for the low rates. */
        fun frameMsOf(mode: Int): Int = if (mode <= 1) 20 else 40

        fun decode(b: ByteArray): VoiceNote? {
            if (b.size < HEADER || b[0].toInt() != VERSION) return null
            val mode = b[1].toInt() and 0xFF
            val bits = b[2].toInt() and 0xFF
            val count = ((b[3].toInt() and 0xFF) shl 8) or (b[4].toInt() and 0xFF)
            if (bits == 0 || b.size < HEADER + (count * bits + 7) / 8) return null
            val bytes = (bits + 7) / 8
            var bit = HEADER * 8
            val frames = List(count) {
                val f = ByteArray(bytes)
                for (i in 0 until bits) {
                    if ((b[bit / 8].toInt() ushr (7 - bit % 8)) and 1 == 1) f[i / 8] = (f[i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
                    bit++
                }
                f
            }
            return VoiceNote(mode, bits, frames)
        }
    }
}
