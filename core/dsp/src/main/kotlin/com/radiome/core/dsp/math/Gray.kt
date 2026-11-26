package com.radiome.core.dsp.math

/** Gray mapping so that a detection error into an adjacent tone flips a single bit. */
object Gray {
    fun encode(value: Int): Int = value xor (value ushr 1)

    fun decode(gray: Int): Int {
        var v = gray
        var shift = gray ushr 1
        while (shift != 0) {
            v = v xor shift
            shift = shift ushr 1
        }
        return v
    }
}
