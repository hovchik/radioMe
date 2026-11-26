package com.radiome.core.protocol

import com.radiome.core.protocol.image.ImageEncoder
import com.radiome.core.protocol.image.ImagePipeline
import com.radiome.core.protocol.image.ImagePreset
import com.radiome.core.protocol.image.ImageProcessor
import com.radiome.core.protocol.image.MaxEdge
import com.radiome.core.protocol.image.RasterImage
import com.radiome.core.protocol.payload.ContentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImagePipelineTest {
    /** Stand-in for Android's WebP encoder: size grows with pixels and quality, like the real one. */
    private val fakeWebp = ImageEncoder { img, q -> ByteArray(60 + img.width * img.height * q / 200) { (it * 31).toByte() } }

    private fun gradient(w: Int, h: Int) = RasterImage(w, h, IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        (0xFF shl 24) or ((x * 255 / w) shl 16) or ((y * 255 / h) shl 8) or 0x40
    })

    @Test
    fun `resize keeps aspect ratio and never upscales`() {
        val r = ImageProcessor.resize(gradient(4000, 3000), 320)
        assertEquals(320, r.width)
        assertEquals(240, r.height)
        val small = gradient(100, 50)
        assertTrue(ImageProcessor.resize(small, 320) === small)
    }

    @Test
    fun `area average preserves mean colour`() {
        val src = RasterImage(4, 4, IntArray(16) { if (it % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() })
        val r = ImageProcessor.resize(src, 2)
        assertTrue(r.argb.all { (it ushr 16 and 0xFF) in 126..128 })
    }

    @Test
    fun `grayscale uses luma`() {
        val g = ImageProcessor.grayscale(RasterImage(1, 1, intArrayOf(0xFF00FF00.toInt())))
        val y = g.argb[0] and 0xFF
        assertEquals(150, y)
        assertEquals(y, g.argb[0] ushr 8 and 0xFF)
    }

    @Test
    fun `prepare reports exact size and airtime that grow with the preset`() {
        val pipeline = ImagePipeline(fakeWebp)
        val src = gradient(1600, 1200)
        val small = pipeline.prepare(src, ImagePreset(MaxEdge.PX_128, quality = 20), name = "a.webp")
        val large = pipeline.prepare(src, ImagePreset(MaxEdge.PX_320, quality = 20))
        assertEquals(ContentType.IMAGE_WEBP, small.payload.type)
        assertEquals(small.sizeBytes, small.payload.bytes.size)
        assertEquals(128 to 96, small.width to small.height)
        assertTrue(large.sizeBytes > small.sizeBytes && large.airtimeMs > small.airtimeMs)
        println("128px: ${small.sizeBytes} B / ${small.airtimeMs} ms, 320px: ${large.sizeBytes} B / ${large.airtimeMs} ms")
        assertThrows(IllegalArgumentException::class.java) { ImagePreset(quality = 80) }
    }
}
