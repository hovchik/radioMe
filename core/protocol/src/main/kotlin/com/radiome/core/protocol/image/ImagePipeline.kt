package com.radiome.core.protocol.image

import com.radiome.core.protocol.payload.CompressionMode
import com.radiome.core.protocol.payload.ContentType
import com.radiome.core.protocol.payload.Payload
import com.radiome.core.protocol.session.AirtimeEstimator
import com.radiome.core.protocol.session.TransferOptions
import kotlin.math.roundToInt

/** Platform-neutral ARGB_8888 raster (0xAARRGGBB per pixel, row-major). */
class RasterImage(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0 && argb.size == width * height)
    }
}

/** Lossy WebP encoder. Android implements it with Bitmap.compress(WEBP_LOSSY) in a later phase. */
fun interface ImageEncoder {
    /** [quality] is 0..100. */
    fun encodeWebp(image: RasterImage, quality: Int): ByteArray
}

enum class MaxEdge(val pixels: Int) { PX_128(128), PX_240(240), PX_320(320) }

data class ImagePreset(
    val maxEdge: MaxEdge = MaxEdge.PX_240,
    /** WebP quality, 10..40 %. */
    val quality: Int = 25,
    val grayscale: Boolean = false,
    /** Send a small preview first ([ProgressiveImage]), so the receiver sees the picture early. */
    val progressive: Boolean = false,
) {
    init {
        require(quality in MIN_QUALITY..MAX_QUALITY) { "quality must be $MIN_QUALITY..$MAX_QUALITY" }
    }

    companion object {
        const val MIN_QUALITY = 10
        const val MAX_QUALITY = 40
    }
}

/** Result shown in the compose screen before sending: exact byte size and air time. */
class PreparedImage(
    val payload: Payload,
    val width: Int,
    val height: Int,
    val sizeBytes: Int,
    val airtimeMs: Long,
)

object ImageProcessor {
    /** Area-average downscale so the longer edge is at most [maxEdge]. Never upscales. */
    fun resize(src: RasterImage, maxEdge: Int): RasterImage {
        val longEdge = maxOf(src.width, src.height)
        if (longEdge <= maxEdge) return src
        val scale = maxEdge.toDouble() / longEdge
        val ow = maxOf(1, (src.width * scale).roundToInt())
        val oh = maxOf(1, (src.height * scale).roundToInt())
        val out = IntArray(ow * oh)
        for (y in 0 until oh) {
            val sy0 = y * src.height / oh
            val sy1 = maxOf(sy0 + 1, (y + 1) * src.height / oh)
            for (x in 0 until ow) {
                val sx0 = x * src.width / ow
                val sx1 = maxOf(sx0 + 1, (x + 1) * src.width / ow)
                var a = 0L; var r = 0L; var g = 0L; var b = 0L
                for (sy in sy0 until sy1) for (sx in sx0 until sx1) {
                    val p = src.argb[sy * src.width + sx]
                    a += p ushr 24; r += (p ushr 16) and 0xFF; g += (p ushr 8) and 0xFF; b += p and 0xFF
                }
                val n = ((sy1 - sy0) * (sx1 - sx0)).toLong()
                out[y * ow + x] = ((a / n).toInt() shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
            }
        }
        return RasterImage(ow, oh, out)
    }

    /** BT.601 luma. */
    fun grayscale(src: RasterImage): RasterImage = RasterImage(
        src.width,
        src.height,
        IntArray(src.argb.size) {
            val p = src.argb[it]
            val y = (0.299 * ((p ushr 16) and 0xFF) + 0.587 * ((p ushr 8) and 0xFF) + 0.114 * (p and 0xFF)).roundToInt().coerceIn(0, 255)
            (p and 0xFF000000.toInt()) or (y shl 16) or (y shl 8) or y
        },
    )
}

class ImagePipeline(
    private val encoder: ImageEncoder,
    private val airtime: AirtimeEstimator = AirtimeEstimator(),
) {
    fun prepare(source: RasterImage, preset: ImagePreset, options: TransferOptions = TransferOptions(), name: String = ""): PreparedImage {
        var img = ImageProcessor.resize(source, preset.maxEdge.pixels)
        if (preset.grayscale) img = ImageProcessor.grayscale(img)
        val full = encoder.encodeWebp(img, preset.quality)
        var type = ContentType.IMAGE_WEBP
        var bytes = full
        if (preset.progressive) {
            val small = ImageProcessor.resize(img, previewEdge(preset.maxEdge))
            val preview = encoder.encodeWebp(small, ImagePreset.MIN_QUALITY)
            // A preview that costs as much as the picture itself is not worth sending.
            if (preview.size * 3 <= full.size && full.size <= 0xFFFF) {
                type = ContentType.IMAGE_PROGRESSIVE
                bytes = ProgressiveImage.pack(listOf(preview, full))
            }
        }
        // WebP is already entropy coded; deflating it again only costs CPU.
        val opts = options.copy(compression = CompressionMode.NEVER)
        return PreparedImage(
            payload = Payload(type, bytes, name),
            width = img.width,
            height = img.height,
            sizeBytes = bytes.size,
            airtimeMs = airtime.payloadMs(bytes.size, opts),
        )
    }

    companion object {
        /** Long edge of the progressive preview: about an eighth of the picture, at least 24 px. */
        fun previewEdge(maxEdge: MaxEdge): Int = maxOf(24, maxEdge.pixels / 8)
    }
}
