package com.radiome.core.dsp.math

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place iterative radix-2 complex FFT. Allocation-free after construction, so it can be
 * reused from a hot audio loop (one instance per thread).
 */
class Fft(val size: Int) {
    init {
        require(size >= 2 && (size and (size - 1)) == 0) { "FFT size must be a power of two, was $size" }
    }

    private val levels = Integer.numberOfTrailingZeros(size)
    private val cosTable = DoubleArray(size / 2) { cos(2.0 * PI * it / size) }
    private val sinTable = DoubleArray(size / 2) { sin(2.0 * PI * it / size) }
    private val bitReverse = IntArray(size) { Integer.reverse(it) ushr (32 - levels) }

    fun forward(re: DoubleArray, im: DoubleArray) = transform(re, im, inverse = false)

    /** Inverse transform, scaled by 1/size. */
    fun inverse(re: DoubleArray, im: DoubleArray) {
        transform(re, im, inverse = true)
        val scale = 1.0 / size
        for (i in 0 until size) {
            re[i] *= scale
            im[i] *= scale
        }
    }

    private fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        require(re.size >= size && im.size >= size)
        for (i in 0 until size) {
            val j = bitReverse[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var i = 0
            while (i < size) {
                for (k in 0 until half) {
                    val c = cosTable[k * step]
                    val s = if (inverse) sinTable[k * step] else -sinTable[k * step]
                    val a = i + k
                    val b = a + half
                    val tr = re[b] * c - im[b] * s
                    val ti = re[b] * s + im[b] * c
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                i += len
            }
            len = len shl 1
        }
    }
}
