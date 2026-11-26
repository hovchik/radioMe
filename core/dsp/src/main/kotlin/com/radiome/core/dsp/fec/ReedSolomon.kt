package com.radiome.core.dsp.fec

/**
 * Systematic Reed-Solomon code over GF(2^8), shortened to any length n = k + nsym <= 255.
 *
 * - Field polynomial 0x11D, alpha = 2, first consecutive root b = 0:
 *   g(x) = (x - a^0)(x - a^1)...(x - a^(nsym-1)).
 * - Codeword layout: k data bytes followed by nsym parity bytes. Byte i is the coefficient of
 *   x^(n-1-i), so the first byte on the air is the highest-degree coefficient.
 * - Decoding corrects any combination of e errors and s erasures with 2e + s <= nsym
 *   (Berlekamp-Massey initialised with the erasure locator, Chien search, Forney).
 */
class ReedSolomon(val nsym: Int) {
    init {
        require(nsym in 2..254) { "nsym must be 2..254, was $nsym" }
    }

    /** Generator polynomial, highest degree first, generator[0] == 1. */
    private val generator: IntArray = run {
        var g = intArrayOf(1)
        for (i in 0 until nsym) {
            val next = IntArray(g.size + 1)
            val root = GF256.alphaPow(i)
            for (j in g.indices) {
                next[j] = next[j] xor g[j]
                next[j + 1] = next[j + 1] xor GF256.mul(g[j], root)
            }
            g = next
        }
        g
    }

    fun encode(data: ByteArray): ByteArray {
        require(data.size + nsym <= 255) { "codeword too long: ${data.size} + $nsym > 255" }
        val parity = IntArray(nsym)
        for (b in data) {
            val feedback = (b.toInt() and 0xFF) xor parity[0]
            for (j in 0 until nsym - 1) parity[j] = parity[j + 1]
            parity[nsym - 1] = 0
            if (feedback != 0) {
                for (j in 0 until nsym) parity[j] = parity[j] xor GF256.mul(generator[j + 1], feedback)
            }
        }
        val out = data.copyOf(data.size + nsym)
        for (j in 0 until nsym) out[data.size + j] = parity[j].toByte()
        return out
    }

    data class DecodeResult(
        val success: Boolean,
        /** Corrected codeword (data + parity) when [success]. */
        val codeword: ByteArray?,
        /** Number of byte positions whose value was changed. */
        val correctedBytes: Int,
    ) {
        fun data(nsym: Int): ByteArray? = codeword?.copyOf(codeword.size - nsym)
    }

    fun syndromes(codeword: ByteArray): IntArray {
        val s = IntArray(nsym)
        for (k in 0 until nsym) {
            val x = GF256.alphaPow(k)
            var acc = 0
            for (b in codeword) acc = GF256.mul(acc, x) xor (b.toInt() and 0xFF)
            s[k] = acc
        }
        return s
    }

    /**
     * Decode [received] (n bytes). [erasures] are byte positions (0-based, in codeword order) known to be
     * unreliable. Never throws for uncorrectable input: returns success = false instead.
     */
    fun decode(received: ByteArray, erasures: IntArray = IntArray(0)): DecodeResult {
        val n = received.size
        require(n in (nsym + 1)..255) { "invalid codeword length $n" }
        val cw = received.copyOf()
        val synd = syndromes(cw)
        if (synd.all { it == 0 }) return DecodeResult(true, cw, 0)

        val eras = erasures.filter { it in 0 until n }.distinct()
        if (eras.size > nsym) return DecodeResult(false, null, 0)

        // Erasure locator Gamma(x) = prod(1 + X_e x), lowest degree first.
        val size = nsym + 2
        val gamma = IntArray(size).also { it[0] = 1 }
        for (pos in eras) {
            val x = GF256.alphaPow(n - 1 - pos)
            for (d in size - 1 downTo 1) gamma[d] = gamma[d] xor GF256.mul(x, gamma[d - 1])
        }

        // Berlekamp-Massey for errors-and-erasures.
        val rho = eras.size
        var lambda = gamma.copyOf()
        var b = gamma.copyOf()
        var l = rho
        for (r in rho until nsym) {
            var delta = 0
            for (i in 0..minOf(l, r)) delta = delta xor GF256.mul(lambda[i], synd[r - i])
            val xb = IntArray(size)
            for (i in 1 until size) xb[i] = b[i - 1]
            if (delta == 0) {
                b = xb
            } else {
                val t = IntArray(size)
                for (i in 0 until size) t[i] = lambda[i] xor GF256.mul(delta, xb[i])
                if (2 * l <= r + rho) {
                    val inv = GF256.inv(delta)
                    b = IntArray(size) { GF256.mul(lambda[it], inv) }
                    l = r + 1 + rho - l
                } else {
                    b = xb
                }
                lambda = t
            }
        }

        var degree = 0
        for (i in size - 1 downTo 0) if (lambda[i] != 0) { degree = i; break }
        if (degree == 0 || 2 * (degree - rho) + rho > nsym) return DecodeResult(false, null, 0)

        // Chien search over the n valid positions.
        val positions = ArrayList<Int>(degree)
        for (pos in 0 until n) {
            val xInv = GF256.alphaPow(-(n - 1 - pos))
            if (evalLow(lambda, degree, xInv) == 0) positions.add(pos)
        }
        if (positions.size != degree) return DecodeResult(false, null, 0)

        // Error evaluator Omega(x) = S(x) * Lambda(x) mod x^nsym.
        val omega = IntArray(nsym)
        for (i in 0 until nsym) {
            var acc = 0
            for (j in 0..minOf(i, degree)) acc = acc xor GF256.mul(lambda[j], synd[i - j])
            omega[i] = acc
        }

        var corrected = 0
        for (pos in positions) {
            val x = GF256.alphaPow(n - 1 - pos)
            val xInv = GF256.inv(x)
            val num = evalLow(omega, nsym - 1, xInv)
            // Formal derivative: odd-power coefficients only.
            var den = 0
            var j = 1
            while (j <= degree) {
                den = den xor GF256.mul(lambda[j], GF256.pow(xInv, j - 1))
                j += 2
            }
            if (den == 0) return DecodeResult(false, null, 0)
            val magnitude = GF256.mul(x, GF256.div(num, den))
            if (magnitude != 0) {
                cw[pos] = ((cw[pos].toInt() and 0xFF) xor magnitude).toByte()
                corrected++
            }
        }

        if (syndromes(cw).any { it != 0 }) return DecodeResult(false, null, 0)
        return DecodeResult(true, cw, corrected)
    }

    private fun evalLow(poly: IntArray, degree: Int, x: Int): Int {
        var acc = 0
        for (i in degree downTo 0) acc = GF256.mul(acc, x) xor poly[i]
        return acc
    }

    companion object {
        /** Parity bytes for a codeword with [dataBytes] data bytes at [ratio] = parity / data (0.20..0.50 in the app). */
        fun parityFor(dataBytes: Int, ratio: Double): Int {
            require(ratio > 0.0 && ratio <= 1.0) { "ratio must be in (0, 1]" }
            var p = Math.round(dataBytes * ratio).toInt()
            if (p % 2 != 0) p += 1
            p = p.coerceAtLeast(2)
            return p.coerceAtMost(255 - dataBytes)
        }
    }
}
