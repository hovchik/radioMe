package com.radiome.core.dsp.fec

/** Arithmetic over GF(2^8) with primitive polynomial x^8 + x^4 + x^3 + x^2 + 1 (0x11D), generator alpha = 2. */
object GF256 {
    const val PRIMITIVE_POLY = 0x11D

    private val exp = IntArray(512)
    private val log = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x
            log[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor PRIMITIVE_POLY
        }
        for (i in 255 until 512) exp[i] = exp[i - 255]
    }

    fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]

    fun div(a: Int, b: Int): Int {
        require(b != 0) { "division by zero in GF(256)" }
        return if (a == 0) 0 else exp[(log[a] + 255 - log[b]) % 255]
    }

    fun inv(a: Int): Int {
        require(a != 0) { "zero has no inverse" }
        return exp[255 - log[a]]
    }

    /** alpha^power for any integer power. */
    fun alphaPow(power: Int): Int = exp[((power % 255) + 255) % 255]

    fun pow(a: Int, n: Int): Int {
        if (n == 0) return 1
        if (a == 0) return 0
        val e = ((log[a].toLong() * n) % 255 + 255) % 255
        return exp[e.toInt()]
    }
}
