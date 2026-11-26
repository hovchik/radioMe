package com.radiome.core.dsp.fec

/**
 * Byte-level block interleaver across codewords. Codewords are grouped in blocks of [depth]; inside a block
 * byte j of every codeword is sent before byte j+1 of any codeword (column-major read of a depth x n matrix).
 * A burst of B consecutive corrupted bytes on air therefore hits each codeword of the block at most ceil(B / depth) times.
 * The last block may hold fewer than [depth] codewords.
 */
object BlockInterleaver {
    /** For each on-air position, the flat index (codeword * n + column) it carries. */
    fun airToCodewordIndex(codewordLength: Int, codewordCount: Int, depth: Int): IntArray {
        require(codewordLength > 0 && codewordCount >= 0 && depth >= 1)
        val map = IntArray(codewordLength * codewordCount)
        var air = 0
        var blockStart = 0
        while (blockStart < codewordCount) {
            val rows = minOf(depth, codewordCount - blockStart)
            for (col in 0 until codewordLength) {
                for (r in 0 until rows) {
                    map[air++] = (blockStart + r) * codewordLength + col
                }
            }
            blockStart += rows
        }
        return map
    }

    fun interleave(codewords: List<ByteArray>, depth: Int): ByteArray {
        if (codewords.isEmpty()) return ByteArray(0)
        val n = codewords[0].size
        require(codewords.all { it.size == n }) { "all codewords must have the same length" }
        val map = airToCodewordIndex(n, codewords.size, depth)
        return ByteArray(map.size) { air -> val flat = map[air]; codewords[flat / n][flat % n] }
    }

    fun deinterleave(air: ByteArray, codewordLength: Int, codewordCount: Int, depth: Int): List<ByteArray> {
        val map = airToCodewordIndex(codewordLength, codewordCount, depth)
        require(air.size == map.size) { "expected ${map.size} bytes, got ${air.size}" }
        val flat = ByteArray(map.size)
        for (i in map.indices) flat[map[i]] = air[i]
        return List(codewordCount) { flat.copyOfRange(it * codewordLength, (it + 1) * codewordLength) }
    }

    fun deinterleaveFlags(air: BooleanArray, codewordLength: Int, codewordCount: Int, depth: Int): List<BooleanArray> {
        val map = airToCodewordIndex(codewordLength, codewordCount, depth)
        require(air.size == map.size)
        val flat = BooleanArray(map.size)
        for (i in map.indices) flat[map[i]] = air[i]
        return List(codewordCount) { flat.copyOfRange(it * codewordLength, (it + 1) * codewordLength) }
    }
}
