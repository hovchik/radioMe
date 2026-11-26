package com.radiome.core.voice

import com.radiome.core.protocol.payload.VoiceNote

/**
 * Codec2 speech codec (8 kHz, 16-bit mono) through JNI. One instance per stream; not thread-safe. [close] it.
 */
class Codec2(val mode: Int = VoiceNote.MODE_700C) : AutoCloseable {
    private var handle: Long = nativeCreate(mode).also { require(it != 0L) { "Codec2 mode $mode is not available" } }

    val samplesPerFrame: Int = nativeSamplesPerFrame(handle)
    val bitsPerFrame: Int = nativeBitsPerFrame(handle)
    val bytesPerFrame: Int = nativeBytesPerFrame(handle)

    /** Encodes 8 kHz speech; a last partial frame is padded with silence. */
    fun encode(pcm: ShortArray): VoiceNote {
        val frame = ShortArray(samplesPerFrame)
        val frames = ArrayList<ByteArray>((pcm.size + samplesPerFrame - 1) / samplesPerFrame)
        var at = 0
        while (at < pcm.size) {
            val n = minOf(samplesPerFrame, pcm.size - at)
            pcm.copyInto(frame, 0, at, at + n)
            if (n < samplesPerFrame) frame.fill(0, n)
            val bits = ByteArray(bytesPerFrame)
            nativeEncode(handle, frame, bits)
            frames += bits
            at += n
        }
        return VoiceNote(mode, bitsPerFrame, frames)
    }

    fun decode(note: VoiceNote): ShortArray {
        require(note.mode == mode) { "note is mode ${note.mode}, codec is $mode" }
        val out = ShortArray(note.frames.size * samplesPerFrame)
        val frame = ShortArray(samplesPerFrame)
        note.frames.forEachIndexed { i, bits ->
            nativeDecode(handle, bits.copyOf(bytesPerFrame), frame)
            frame.copyInto(out, i * samplesPerFrame)
        }
        return out
    }

    override fun close() {
        if (handle != 0L) nativeDestroy(handle)
        handle = 0
    }

    companion object {
        const val SAMPLE_RATE = 8_000

        /** False when the native library could not be loaded (for example an unsupported CPU). */
        val available: Boolean = runCatching {
            System.loadLibrary("codec2")
            System.loadLibrary("radiome_voice")
        }.isSuccess

        @JvmStatic private external fun nativeCreate(mode: Int): Long
        @JvmStatic private external fun nativeDestroy(handle: Long)
        @JvmStatic private external fun nativeSamplesPerFrame(handle: Long): Int
        @JvmStatic private external fun nativeBitsPerFrame(handle: Long): Int
        @JvmStatic private external fun nativeBytesPerFrame(handle: Long): Int
        @JvmStatic private external fun nativeEncode(handle: Long, pcm: ShortArray, out: ByteArray)
        @JvmStatic private external fun nativeDecode(handle: Long, frame: ByteArray, pcm: ShortArray)
    }
}
