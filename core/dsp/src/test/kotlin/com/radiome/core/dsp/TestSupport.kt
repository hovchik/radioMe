package com.radiome.core.dsp

import com.radiome.core.dsp.framing.BurstCodec
import com.radiome.core.dsp.framing.CodewordStatus
import com.radiome.core.dsp.framing.Frame
import com.radiome.core.dsp.framing.FrameCodec
import com.radiome.core.dsp.framing.FrameType
import com.radiome.core.dsp.modem.Demodulator
import com.radiome.core.dsp.modem.RawBurst
import kotlin.random.Random

/** Test-side helpers; the production packetizer / chunk tracker / ARQ state machine arrive in core:protocol (Phase 2). */
object TestSupport {
    fun chunk(sessionId: Int, data: ByteArray, payload: Int = FrameCodec.DEFAULT_PAYLOAD): List<Frame> {
        val total = (data.size + payload - 1) / payload
        return List(total) { i ->
            Frame(FrameType.DATA, sessionId, i, total, data.copyOfRange(i * payload, minOf(data.size, (i + 1) * payload)))
        }
    }

    /** Feed audio in irregular block sizes like a real AudioRecord loop would. */
    fun receive(demod: Demodulator, audio: FloatArray, seed: Long = 5): List<RawBurst> {
        val rnd = Random(seed)
        val out = ArrayList<RawBurst>()
        var pos = 0
        while (pos < audio.size) {
            val n = minOf(audio.size - pos, 256 + rnd.nextInt(4096))
            out += demod.process(audio, pos, n)
            pos += n
        }
        out += demod.flush()
        return out
    }

    /** "Image-like" payload: a compressible header followed by high-entropy bytes, like a small WebP. */
    fun sampleImage(size: Int, seed: Int = 99): ByteArray {
        val rnd = Random(seed)
        val riff = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()
        return ByteArray(size) { if (it < riff.size) riff[it] else rnd.nextInt(256).toByte() }
    }
}

/** Minimal receiver-side bookkeeping used by the tests to compute loss and drive the NACK round trip. */
class ReceiveTracker(val totalChunks: Int) {
    val chunks = HashMap<Int, ByteArray>()
    var fecCorrectedBytes = 0
        private set
    var repairedFrames = 0
        private set

    fun merge(raw: RawBurst) {
        for (r in BurstCodec.decodeFrames(raw)) {
            val f = r.frame ?: continue
            if (f.type != FrameType.DATA) continue
            chunks[f.chunkIndex] = f.payload
            fecCorrectedBytes += r.correctedBytes
            if (r.status == CodewordStatus.FEC_REPAIRED) repairedFrames++
        }
    }

    val missing: List<Int> get() = (0 until totalChunks).filter { it !in chunks }
    val lossPercent: Double get() = missing.size * 100.0 / totalChunks
    val complete: Boolean get() = missing.isEmpty()

    fun assemble(): ByteArray {
        check(complete)
        return (0 until totalChunks).map { chunks.getValue(it) }.reduce { a, b -> a + b }
    }
}
