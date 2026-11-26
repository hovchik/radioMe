package com.radiome.core.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import com.radiome.core.dsp.modem.ModemConfig
import com.radiome.core.protocol.link.AudioPort
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors

data class AudioEngineConfig(
    /** Capture block size handed to the demodulator (50 ms at 48 kHz). */
    val blockSamples: Int = 2400,
    /** Number of pooled capture blocks; if the consumer falls this far behind, blocks are dropped, not allocated. */
    val poolSize: Int = 16,
    /** Receiver stays muted this long after our own transmission ends (echo / VOX hang). */
    val muteTailMs: Int = 150,
    /** Digital gain applied to transmitted samples. */
    val outputGain: Float = 1f,
)

/**
 * [AudioPort] on top of AudioRecord and AudioTrack, 48 kHz mono 16-bit.
 *
 * - Capture uses `UNPROCESSED` when the device supports it (no AGC, noise suppression or echo cancellation), else
 *   `VOICE_RECOGNITION`, and switches off any platform effect that still attaches to the session.
 * - Capture runs on its own urgent-audio thread and recycles a fixed pool of blocks: no allocation per block.
 * - Playback runs on a dedicated urgent-audio thread in streaming mode and returns only once the samples have
 *   actually been played. While it runs, and for [AudioEngineConfig.muteTailMs] after, capture is muted.
 * - [input] must have a single collector (the link controller).
 */
class AndroidAudioEngine(
    context: Context,
    private val config: AudioEngineConfig = AudioEngineConfig(),
) : AudioPort, Closeable {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val sampleRate = ModemConfig.SAMPLE_RATE

    private val guard = HalfDuplexGuard(config.muteTailMs * sampleRate / 1000)
    private val pool = ArrayBlockingQueue<FloatArray>(config.poolSize).apply {
        repeat(config.poolSize) { offer(FloatArray(config.blockSamples)) }
    }
    private val filled = Channel<FloatArray>(config.poolSize)

    @Volatile var outputGain: Float = config.outputGain

    @Volatile var preferredInput: AudioDeviceInfo? = null
        set(value) {
            field = value
            record?.preferredDevice = value
        }

    @Volatile var preferredOutput: AudioDeviceInfo? = null
        set(value) {
            field = value
            track?.preferredDevice = value
        }

    /** Audio source actually in use, for diagnostics. */
    val usesUnprocessedSource: Boolean =
        audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

    @Volatile var droppedBlocks: Long = 0
        private set

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    @Volatile private var capturing = false
    private var captureThread: Thread? = null

    private val txExecutor = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            r.run()
        }, "radiome-tx")
    }
    private val txDispatcher = txExecutor.asCoroutineDispatcher()
    private val writeBuffer = ShortArray(4800)

    override val input: Flow<FloatArray> = flow {
        for (block in filled) {
            try {
                emit(block)
            } finally {
                pool.offer(block)
            }
        }
    }

    /** Starts capture. The caller must hold RECORD_AUDIO. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun startCapture() {
        if (capturing) return
        val source = if (usesUnprocessedSource) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf * 4, sampleRate * 2 / 2)) // at least 500 ms
            .build()
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialise" }
        disableEffects(rec.audioSessionId)
        preferredInput?.let { rec.preferredDevice = it }
        record = rec
        capturing = true
        rec.startRecording()
        captureThread = Thread({ captureLoop(rec) }, "radiome-rx").apply { start() }
    }

    @Synchronized
    fun stopCapture() {
        capturing = false
        captureThread?.join(500)
        captureThread = null
        record?.run {
            runCatching { stop() }
            release()
        }
        record = null
    }

    private fun captureLoop(rec: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val n = config.blockSamples
        val pcm = ShortArray(n)
        while (capturing) {
            var read = 0
            while (read < n && capturing) {
                val r = rec.read(pcm, read, n - read, AudioRecord.READ_BLOCKING)
                if (r < 0) break
                read += r
            }
            if (read < n) continue
            if (guard.shouldDrop(n)) continue
            val block = pool.poll()
            if (block == null) {
                droppedBlocks++
                continue
            }
            Pcm.pcm16ToFloat(pcm, n, block)
            if (!filled.trySend(block).isSuccess) {
                pool.offer(block)
                droppedBlocks++
            }
        }
    }

    override suspend fun play(samples: FloatArray) = withContext(txDispatcher) {
        val t = ensureTrack()
        guard.beginTransmit()
        try {
            val start = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            t.play()
            var pos = 0
            while (pos < samples.size) {
                ensureActive()
                val n = minOf(writeBuffer.size, samples.size - pos)
                Pcm.floatToPcm16(samples, pos, writeBuffer, n, outputGain)
                var off = 0
                while (off < n) {
                    val w = t.write(writeBuffer, off, n - off, AudioTrack.WRITE_BLOCKING)
                    check(w >= 0) { "AudioTrack.write failed: $w" }
                    off += w
                }
                pos += n
            }
            // Wait until the last sample has left the device, not just the buffer.
            val deadline = System.nanoTime() + (samples.size * 1_000_000_000L / sampleRate) + 2_000_000_000L
            while (((t.playbackHeadPosition.toLong() and 0xFFFFFFFFL) - start) < samples.size && System.nanoTime() < deadline) {
                ensureActive()
                Thread.sleep(5)
            }
        } finally {
            runCatching {
                t.pause()
                t.flush()
            }
            guard.endTransmit()
        }
    }

    private fun ensureTrack(): AudioTrack {
        track?.let { return it }
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuf * 2, sampleRate / 5 * 2)) // at least 200 ms
            .build()
        check(t.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack failed to initialise" }
        preferredOutput?.let { t.preferredDevice = it }
        track = t
        return t
    }

    private fun disableEffects(sessionId: Int) {
        if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(sessionId)?.enabled = false
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId)?.enabled = false
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId)?.enabled = false
    }

    /** Media volume as a fraction of maximum, for the "set the phone volume to ~70%" warning. */
    fun outputVolumeFraction(): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    override fun close() {
        stopCapture()
        filled.close()
        track?.release()
        track = null
        txDispatcher.close()
    }
}

/** Advice for the settings / dashboard volume warning. */
enum class VolumeAdvice { TOO_LOW, OK, CLIPPING_RISK }

fun volumeAdvice(fraction: Float): VolumeAdvice = when {
    fraction < 0.4f -> VolumeAdvice.TOO_LOW
    fraction > 0.9f -> VolumeAdvice.CLIPPING_RISK
    else -> VolumeAdvice.OK
}
