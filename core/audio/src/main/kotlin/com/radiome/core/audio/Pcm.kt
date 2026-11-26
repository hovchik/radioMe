package com.radiome.core.audio

/** Allocation-free conversions between the modem's float samples and 16-bit PCM. */
object Pcm {
    private const val SCALE = 32767f
    private const val INV_SCALE = 1f / 32768f

    /** Converts [count] floats from [src] at [srcOffset] into [dst] starting at 0, applying [gain] and hard limiting. */
    fun floatToPcm16(src: FloatArray, srcOffset: Int, dst: ShortArray, count: Int, gain: Float = 1f) {
        for (i in 0 until count) {
            val v = src[srcOffset + i] * gain
            dst[i] = when {
                v >= 1f -> Short.MAX_VALUE
                v <= -1f -> Short.MIN_VALUE
                else -> (v * SCALE).toInt().toShort()
            }
        }
    }

    fun pcm16ToFloat(src: ShortArray, count: Int, dst: FloatArray) {
        for (i in 0 until count) dst[i] = src[i] * INV_SCALE
    }

    /**
     * Interleaved stereo: [src] on the left channel, a sine of [toneHz] at [toneLevel] on the right, for VOX-tone
     * PTT. [phase] carries the tone across calls; returns the new phase. [dst] needs 2 x [count] room.
     */
    fun floatToPcm16StereoWithTone(
        src: FloatArray, srcOffset: Int, dst: ShortArray, count: Int, gain: Float, toneHz: Double, toneLevel: Float,
        sampleRate: Int, phase: Double,
    ): Double {
        val step = 2 * Math.PI * toneHz / sampleRate
        var ph = phase
        for (i in 0 until count) {
            dst[2 * i] = clip(src[srcOffset + i] * gain)
            dst[2 * i + 1] = clip((toneLevel * Math.sin(ph)).toFloat())
            ph += step
            if (ph > 2 * Math.PI) ph -= 2 * Math.PI
        }
        return ph
    }

    private fun clip(v: Float): Short = when {
        v >= 1f -> Short.MAX_VALUE
        v <= -1f -> Short.MIN_VALUE
        else -> (v * SCALE).toInt().toShort()
    }
}
