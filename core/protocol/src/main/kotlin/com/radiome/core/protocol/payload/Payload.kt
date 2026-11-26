package com.radiome.core.protocol.payload

import java.util.zip.Deflater
import java.util.zip.Inflater

enum class ContentType(val code: Int) {
    TEXT(1),
    IMAGE_WEBP(2),
    BINARY(3),
    /** Several WebP sizes of one picture, smallest first ([com.radiome.core.protocol.image.ProgressiveImage]). */
    IMAGE_PROGRESSIVE(4),
    /** AES-GCM envelope around another payload ([com.radiome.core.protocol.crypto.SecureEnvelope]). */
    ENCRYPTED(5),
    /** GPS fix ([Position]); only ever sent encrypted. */
    POSITION(6),
    ;

    val isImage: Boolean get() = this == IMAGE_WEBP || this == IMAGE_PROGRESSIVE

    companion object {
        fun fromCode(code: Int): ContentType? = entries.firstOrNull { it.code == code }
    }
}

/** Application payload: what the user sends and what the receiver finally gets back. */
class Payload(val type: ContentType, val bytes: ByteArray, val name: String = "") {
    fun asText(): String = bytes.toString(Charsets.UTF_8)

    override fun equals(other: Any?): Boolean =
        other is Payload && type == other.type && name == other.name && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = (type.hashCode() * 31 + name.hashCode()) * 31 + bytes.contentHashCode()

    override fun toString(): String = "Payload($type, ${bytes.size} bytes, name='$name')"

    companion object {
        fun text(text: String): Payload = Payload(ContentType.TEXT, text.toByteArray(Charsets.UTF_8))
    }
}

enum class CompressionMode {
    /** Deflate, and keep it only if it makes the payload smaller. */
    AUTO,
    NEVER,
}

/** Raw DEFLATE (no zlib header/trailer: the session header already carries a CRC-32). */
object Deflate {
    fun compress(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = java.io.ByteArrayOutputStream(data.size / 2 + 16)
            val buf = ByteArray(1024)
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    fun decompress(data: ByteArray, expectedSize: Int): ByteArray {
        val inflater = Inflater(true)
        try {
            // nowrap mode needs one extra dummy byte at the end of the input.
            inflater.setInput(data + 0)
            val out = ByteArray(expectedSize)
            var n = 0
            while (n < expectedSize && !inflater.finished()) {
                val r = inflater.inflate(out, n, expectedSize - n)
                if (r == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                n += r
            }
            require(n == expectedSize) { "inflated $n bytes, expected $expectedSize" }
            return out
        } finally {
            inflater.end()
        }
    }
}
