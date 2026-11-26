package com.radiome.core.protocol.image

/**
 * Progressive image payload ([com.radiome.core.protocol.payload.ContentType.IMAGE_PROGRESSIVE]): the same picture
 * encoded at increasing sizes, smallest first, so the receiver can show a blurry preview after a few chunks and
 * then the full image filling in as the rest arrives.
 *
 * ```
 * offset size field
 *   0     1      level count n (1..4)
 *   1     2n     length of each level, u16 big-endian, smallest first
 *   1+2n  ...    the levels' WebP bytes back to back
 * ```
 *
 * Sent uncompressed, so wire offsets equal payload offsets and a receiver can read levels from the chunks it has.
 */
object ProgressiveImage {
    const val MAX_LEVELS = 4

    fun pack(levels: List<ByteArray>): ByteArray {
        require(levels.size in 1..MAX_LEVELS)
        require(levels.all { it.size in 1..0xFFFF })
        val out = ByteArray(1 + 2 * levels.size + levels.sumOf { it.size })
        out[0] = levels.size.toByte()
        var at = 1 + 2 * levels.size
        levels.forEachIndexed { i, l ->
            out[1 + 2 * i] = (l.size ushr 8).toByte()
            out[2 + 2 * i] = l.size.toByte()
            l.copyInto(out, at)
            at += l.size
        }
        return out
    }

    /** Byte ranges of the levels, or null if the table is not readable yet or is invalid. */
    fun layout(bytes: ByteArray, available: (from: Int, until: Int) -> Boolean = { _, _ -> true }): List<IntRange>? {
        if (bytes.isEmpty() || !available(0, 1)) return null
        val n = bytes[0].toInt() and 0xFF
        if (n !in 1..MAX_LEVELS || bytes.size < 1 + 2 * n || !available(0, 1 + 2 * n)) return null
        var at = 1 + 2 * n
        val ranges = ArrayList<IntRange>(n)
        for (i in 0 until n) {
            val len = ((bytes[1 + 2 * i].toInt() and 0xFF) shl 8) or (bytes[2 + 2 * i].toInt() and 0xFF)
            if (len == 0 || at + len > bytes.size) return null
            ranges += at until at + len
            at += len
        }
        return ranges
    }

    fun unpack(bytes: ByteArray): List<ByteArray>? = layout(bytes)?.map { bytes.copyOfRange(it.first, it.last + 1) }

    /** The sharpest level, which is what history and gallery export show. */
    fun finalImage(bytes: ByteArray): ByteArray? = unpack(bytes)?.lastOrNull()

    /**
     * What a receiver can show from a partial transfer: [wire] holds the received bytes (zeros where chunks are
     * missing) and [available] says which byte ranges actually arrived.
     */
    fun preview(wire: ByteArray, available: (from: Int, until: Int) -> Boolean): ImagePreview? {
        val ranges = layout(wire, available) ?: return null
        val completeIndex = ranges.indexOfLast { available(it.first, it.last + 1) }
        val complete = ranges.getOrNull(completeIndex)?.let { wire.copyOfRange(it.first, it.last + 1) }
        // The next level, as far as its bytes arrive without a gap: decoders can draw the top rows of it.
        val next = ranges.getOrNull(completeIndex + 1)
        var partial: ByteArray? = null
        if (next != null) {
            var end = next.first
            while (end <= next.last && available(end, end + 1)) end++
            if (end - next.first >= MIN_PARTIAL_BYTES) partial = wire.copyOfRange(next.first, end)
        }
        if (complete == null && partial == null) return null
        return ImagePreview(
            complete = complete,
            completeLevel = completeIndex,
            levels = ranges.size,
            partial = partial,
            partialFraction = if (next == null || partial == null) 0f else partial.size.toFloat() / (next.last - next.first + 1),
        )
    }

    /** Enough of a WebP for its header and a first row of macroblocks. */
    const val MIN_PARTIAL_BYTES = 48
}

/** A partially received progressive image. */
class ImagePreview(
    /** Sharpest level that is complete, or null. */
    val complete: ByteArray?,
    /** Index of [complete], -1 when none. */
    val completeLevel: Int,
    val levels: Int,
    /** Gap-free start of the next level, for a decoder that draws incomplete images. */
    val partial: ByteArray?,
    val partialFraction: Float,
) {
    val isFinal: Boolean get() = completeLevel == levels - 1

    override fun equals(other: Any?): Boolean = other is ImagePreview && completeLevel == other.completeLevel &&
        levels == other.levels && complete.contentEquals(other.complete) && partial.contentEquals(other.partial)

    override fun hashCode(): Int = (completeLevel * 31 + levels) * 31 + (partial?.size ?: 0)
}
