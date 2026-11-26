package com.radiome.core.protocol.payload

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * A GPS fix ([ContentType.POSITION]). The link only ever sends it inside a
 * [com.radiome.core.protocol.crypto.SecureEnvelope], so a listener learns nothing about where anyone is.
 *
 * ```
 * version u8 (= 1) | latitude i32 (1e-7 deg) | longitude i32 (1e-7 deg) | altitude i16 m (-32768 unknown)
 * | accuracy u16 m (65535 unknown) | speed u8 0.5 m/s (255 unknown) | course u8 (360/256 deg, with speed)
 * | fix time u32 unix seconds
 * ```
 * 19 bytes.
 */
data class Position(
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Int? = null,
    val accuracyM: Int? = null,
    val speedMps: Double? = null,
    val courseDeg: Double? = null,
    val timeSec: Long,
) {
    init {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0) { "coordinates out of range" }
    }

    fun encode(): ByteArray {
        val out = ByteArray(SIZE)
        out[0] = VERSION.toByte()
        putI32(out, 1, (latitude * 1e7).roundToLong().toInt())
        putI32(out, 5, (longitude * 1e7).roundToLong().toInt())
        val alt = altitudeM?.coerceIn(-32767, 32767) ?: -32768
        out[9] = (alt shr 8).toByte(); out[10] = alt.toByte()
        val acc = accuracyM?.coerceIn(0, 65534) ?: 65535
        out[11] = (acc ushr 8).toByte(); out[12] = acc.toByte()
        out[13] = (speedMps?.let { (it * 2).roundToInt().coerceIn(0, 254) } ?: 255).toByte()
        out[14] = (courseDeg?.let { (((it % 360 + 360) % 360) * 256 / 360).roundToInt() % 256 } ?: 0).toByte()
        putI32(out, 15, timeSec.coerceIn(0, 0xFFFFFFFFL).toInt())
        return out
    }

    fun toPayload(): Payload = Payload(ContentType.POSITION, encode())

    companion object {
        const val VERSION = 1
        const val SIZE = 19

        fun decode(b: ByteArray): Position? {
            if (b.size < SIZE || b[0].toInt() != VERSION) return null
            val lat = getI32(b, 1) / 1e7
            val lon = getI32(b, 5) / 1e7
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            val alt = ((b[9].toInt() shl 8) or (b[10].toInt() and 0xFF)).toShort().toInt()
            val acc = ((b[11].toInt() and 0xFF) shl 8) or (b[12].toInt() and 0xFF)
            val speed = b[13].toInt() and 0xFF
            val course = b[14].toInt() and 0xFF
            return Position(
                latitude = lat,
                longitude = lon,
                altitudeM = alt.takeIf { it != -32768 },
                accuracyM = acc.takeIf { it != 65535 },
                speedMps = if (speed == 255) null else speed / 2.0,
                courseDeg = if (speed == 255) null else course * 360.0 / 256,
                timeSec = getI32(b, 15).toLong() and 0xFFFFFFFFL,
            )
        }

        private fun putI32(b: ByteArray, at: Int, v: Int) {
            for (i in 0 until 4) b[at + i] = (v ushr (24 - 8 * i)).toByte()
        }

        private fun getI32(b: ByteArray, at: Int): Int {
            var v = 0
            for (i in 0 until 4) v = (v shl 8) or (b[at + i].toInt() and 0xFF)
            return v
        }
    }
}
