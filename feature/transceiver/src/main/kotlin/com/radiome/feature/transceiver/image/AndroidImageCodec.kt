package com.radiome.feature.transceiver.image

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.radiome.core.protocol.image.ImageEncoder
import com.radiome.core.protocol.image.RasterImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Loads picked images and encodes WebP. Everything platform specific about images lives here. */
interface ImageCodec : ImageEncoder {
    /** Decode the image at [uri] (a content:// URI from the photo picker), at most [maxEdge] px on its long side. */
    suspend fun load(uri: String, maxEdge: Int = LOAD_MAX_EDGE): RasterImage

    companion object {
        /** Big enough for the 320 px preset to downscale from, small enough to keep memory low. */
        const val LOAD_MAX_EDGE = 1024
    }
}

class AndroidImageCodec(context: Context) : ImageCodec {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override suspend fun load(uri: String, maxEdge: Int): RasterImage = withContext(Dispatchers.IO) {
        val bitmap = decode(Uri.parse(uri), maxEdge)
        try {
            bitmap.toRaster()
        } finally {
            bitmap.recycle()
        }
    }

    override fun encodeWebp(image: RasterImage, quality: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(image.argb, image.width, image.height, Bitmap.Config.ARGB_8888)
        try {
            val out = ByteArrayOutputStream(4096)
            @Suppress("DEPRECATION")
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            check(bitmap.compress(format, quality.coerceIn(0, 100), out)) { "WebP encoding failed" }
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun decode(uri: Uri, maxEdge: Int): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(resolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val long = maxOf(w, h)
                if (long > maxEdge) {
                    val scale = maxEdge.toDouble() / long
                    decoder.setTargetSize(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()))
                }
            }
        }
        // API 26-27: subsample by powers of two, then let the pipeline's area-average resize finish the job.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("could not decode image")
    }

    private fun Bitmap.toRaster(): RasterImage {
        val src = if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false)
        val px = IntArray(src.width * src.height)
        src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
        if (src !== this) src.recycle()
        return RasterImage(width, height, px)
    }
}

/** Decode received / prepared WebP bytes for display. Null if the bytes are not an image. */
fun decodeImage(bytes: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

/**
 * Decode the start of a WebP whose tail has not arrived yet: the rows that are there, the rest transparent.
 * Needs Android 9 (ImageDecoder); null on older versions or when not even the header is readable.
 */
fun decodePartialImage(bytes: ByteArray): Bitmap? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
    return try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setOnPartialImageListener { true }
        }
    } catch (e: Exception) {
        null
    }
}
