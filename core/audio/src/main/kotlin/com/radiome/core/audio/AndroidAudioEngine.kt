package com.radiome.core.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
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
    /** How the radio is keyed; [PttMode.VOX_TONE] switches playback to stereo. */
    val pttMode: PttMode = PttMode.VOX,
    /** With a keyed PTT: time between keying and the first sample, for the radio to switch to transmit. */
    val pttLeadMs: Int = 150,
    /**
     * With a keyed PTT: hold after the last sample has left AudioTrack, so the end of the burst is not cut. USB audio
     * interfaces buffer 50-100 ms more after that point, so this has to cover their output latency too.
     */
    val pttTailMs: Int = 150,
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
            runCatching { record?.preferredDevice = value }
        }

    @Volatile var preferredOutput: AudioDeviceInfo? = null
        set(value) {
            field = value
            runCatching { track?.preferredDevice = value }
        }

    /** Times capture was reopened after the input device went away (cable unplugged), for diagnostics. */
    @Volatile var captureRestarts: Int = 0
        private set

    /** Audio source actually in use, for diagnostics. */
    val usesUnprocessedSource: Boolean =
        audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

    @Volatile var droppedBlocks: Long = 0
        private set

    @Volatile private var record: AudioRecord? = null
    @Volatile private var track: AudioTrack? = null
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
    private val stereo = config.pttMode == PttMode.VOX_TONE
    private val stereoBuffer = ShortArray(2 * 2400)
    private var tonePhase = 0.0

    /** Keys the radio around each transmission (serial RTS/DTR); null for VOX. */
    @Volatile var ptt: PttKeyer? = null

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
    @Synchronized
    fun startCapture() {
        if (capturing) return
        val rec = openRecord() // throws here, on the caller's thread, if the microphone can't be opened at all
        record = rec
        capturing = true
        captureThread = Thread({ captureLoop(rec) }, "radiome-rx").apply { start() }
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(): AudioRecord {
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
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            error("AudioRecord failed to initialise")
        }
        disableEffects(rec.audioSessionId)
        preferredInput?.let { rec.preferredDevice = it }
        rec.startRecording()
        return rec
    }

    @Synchronized
    fun stopCapture() {
        capturing = false
        // Unblocks a pending read; the capture thread releases the record on its way out.
        runCatching { record?.stop() }
        captureThread?.join(1_000)
        captureThread = null
        record = null
    }

    private fun captureLoop(first: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var rec: AudioRecord? = first
        val n = config.blockSamples
        val pcm = ShortArray(n)
        try {
            while (capturing) {
                val r0 = rec ?: reopenRecord() ?: continue
                rec = r0
                var read = 0
                var failed = false
                while (read < n && capturing) {
                    val r = r0.read(pcm, read, n - read, AudioRecord.READ_BLOCKING)
                    if (r < 0) {
                        failed = true
                        break
                    }
                    read += r
                }
                if (failed) {
                    // The input device went away (USB cable unplugged, ERROR_DEAD_OBJECT): reading again would fail
                    // at once and spin. Drop this AudioRecord and open a new one on the current route.
                    releaseQuietly(r0)
                    rec = null
                    continue
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
        } finally {
            rec?.let(::releaseQuietly)
        }
    }

    /** Opens a fresh AudioRecord after a failure, waiting a little first; null (and try again) while that fails. */
    private fun reopenRecord(): AudioRecord? {
        runCatching { Thread.sleep(REOPEN_DELAY_MS) }
        if (!capturing) return null
        return runCatching { openRecord() }.getOrNull()?.also {
            captureRestarts++
            record = it
            if (!capturing) runCatching { it.stop() }
        }
    }

    private fun releaseQuietly(r: AudioRecord) {
        runCatching { r.stop() }
        runCatching { r.release() }
    }

    /** Device the last transmission actually played through ("Speaker", "USB audio"...), for the status line. */
    @Volatile var lastOutputDevice: String? = null
        private set

    private val playbackAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    // Transient focus pauses music or video apps for the length of a burst, so they neither mix into the
    // transmitted audio nor mask it.
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(playbackAttributes)
        .setOnAudioFocusChangeListener { }
        .build()

    override suspend fun play(samples: FloatArray) = withContext(txDispatcher) {
        val t = ensureTrack()
        guard.beginTransmit()
        audioManager.requestAudioFocus(focusRequest)
        val keyer = ptt?.takeIf { it.ready }
        try {
            if (keyer != null) {
                keyer.key(true)
                Thread.sleep(config.pttLeadMs.toLong())
            }
            val start = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            t.play()
            var pos = 0
            while (pos < samples.size) {
                ensureActive()
                val buf: ShortArray
                val n: Int
                val shorts: Int
                if (stereo) {
                    n = minOf(stereoBuffer.size / 2, samples.size - pos)
                    tonePhase = Pcm.floatToPcm16StereoWithTone(
                        samples, pos, stereoBuffer, n, outputGain, VOX_TONE_HZ, VOX_TONE_LEVEL, sampleRate, tonePhase,
                    )
                    buf = stereoBuffer
                    shorts = 2 * n
                } else {
                    n = minOf(writeBuffer.size, samples.size - pos)
                    Pcm.floatToPcm16(samples, pos, writeBuffer, n, outputGain)
                    buf = writeBuffer
                    shorts = n
                }
                var off = 0
                while (off < shorts) {
                    val w = t.write(buf, off, shorts - off, AudioTrack.WRITE_BLOCKING)
                    check(w >= 0) { "AudioTrack.write failed: $w" }
                    off += w
                }
                pos += n
            }
            lastOutputDevice = t.routedDevice?.let(::describe)
            // Wait until the last sample has left the device, not just the buffer.
            val deadline = System.nanoTime() + (samples.size * 1_000_000_000L / sampleRate) + 2_000_000_000L
            while (((t.playbackHeadPosition.toLong() and 0xFFFFFFFFL) - start) < samples.size && System.nanoTime() < deadline) {
                ensureActive()
                Thread.sleep(5)
            }
        } catch (e: IllegalStateException) {
            // The output device went away mid-burst (cable unplugged, ERROR_DEAD_OBJECT). Drop the track so the next
            // burst opens a new one on the current route instead of failing the same way forever.
            if (track === t) track = null
            runCatching { t.release() }
            throw e
        } finally {
            runCatching {
                t.pause()
                t.flush()
            }
            if (keyer != null) {
                runCatching { Thread.sleep(config.pttTailMs.toLong()) }
                keyer.key(false)
            }
            audioManager.abandonAudioFocusRequest(focusRequest)
            guard.endTransmit()
        }
    }

    private fun ensureTrack(): AudioTrack {
        track?.let { return it }
        val mask = if (stereo) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(playbackAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(mask)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuf * 2, sampleRate / 5 * 2 * (if (stereo) 2 else 1))) // at least 200 ms
            .build()
        check(t.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack failed to initialise" }
        preferredOutput?.let { t.preferredDevice = it }
        track = t
        return t
    }

    private fun describe(d: AudioDeviceInfo): String = when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth " + d.productName
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB " + d.productName
        else -> d.productName?.toString()?.ifBlank { null } ?: "Audio output"
    }

    /** True when the media volume is all the way down: transmissions would be silent. */
    fun isOutputMuted(): Boolean = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0

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
        ptt?.close()
        txDispatcher.close()
    }

    private companion object {
        /** Right-channel keying tone for VOX-tone PTT: well inside any audio interface's passband. */
        const val VOX_TONE_HZ = 1_000.0
        const val VOX_TONE_LEVEL = 0.7f
        const val REOPEN_DELAY_MS = 200L
    }
}

/** Advice for the settings / dashboard volume warning. */
enum class VolumeAdvice { TOO_LOW, OK, CLIPPING_RISK }

fun volumeAdvice(fraction: Float): VolumeAdvice = when {
    fraction < 0.4f -> VolumeAdvice.TOO_LOW
    fraction > 0.9f -> VolumeAdvice.CLIPPING_RISK
    else -> VolumeAdvice.OK
}
