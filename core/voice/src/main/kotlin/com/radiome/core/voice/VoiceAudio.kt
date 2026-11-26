package com.radiome.core.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

/**
 * Records 8 kHz mono speech for a voice note on its own thread, up to [maxMs]. The caller must hold RECORD_AUDIO.
 *
 * With [context] it records from the phone's own microphone, so a cable to the radio is not used as the input.
 */
class VoiceRecorder(private val context: Context? = null, private val maxMs: Int = 30_000) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private val buffer = ByteArrayOutputStream()

    /** Milliseconds recorded so far. */
    @Volatile var elapsedMs: Long = 0
        private set

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(): Boolean {
        if (running) return true
        val rate = Codec2.SAMPLE_RATE
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val r = runCatching {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
        }.getOrNull() ?: return false
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        context?.let { builtIn(it, AudioManager.GET_DEVICES_INPUTS, AudioDeviceInfo.TYPE_BUILTIN_MIC) }?.let { r.setPreferredDevice(it) }
        buffer.reset()
        elapsedMs = 0
        record = r
        running = true
        r.startRecording()
        thread = Thread({
            val chunk = ShortArray(rate / 20)
            val bytes = ByteArray(chunk.size * 2)
            val limit = rate.toLong() * maxMs / 1000
            var total = 0L
            while (running && total < limit) {
                val n = r.read(chunk, 0, chunk.size)
                if (n <= 0) break
                for (i in 0 until n) {
                    bytes[2 * i] = chunk[i].toByte()
                    bytes[2 * i + 1] = (chunk[i].toInt() shr 8).toByte()
                }
                buffer.write(bytes, 0, 2 * n)
                total += n
                elapsedMs = total * 1000 / rate
            }
            running = false
        }, "radiome-voice-rec").also { it.start() }
        return true
    }

    /** True while recording; turns false by itself at [maxMs]. */
    val isRecording: Boolean get() = running

    /** Stops and returns the samples. */
    @Synchronized
    fun stop(): ShortArray {
        running = false
        thread?.join(1_000)
        thread = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
        val b = buffer.toByteArray()
        return ShortArray(b.size / 2) { ((b[2 * it].toInt() and 0xFF) or (b[2 * it + 1].toInt() shl 8)).toShort() }
    }
}

/**
 * Plays 8 kHz mono speech. With [context] it plays on the phone's own speaker, so a voice note is not sent into
 * the radio's audio input (and VOX) by accident.
 */
class VoicePlayer(private val context: Context? = null) {
    private var track: AudioTrack? = null

    @Synchronized
    fun play(pcm: ShortArray) {
        stop()
        if (pcm.isEmpty()) return
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(Codec2.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        context?.let { builtIn(it, AudioManager.GET_DEVICES_OUTPUTS, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) }?.let { t.setPreferredDevice(it) }
        t.write(pcm, 0, pcm.size)
        t.play()
        track = t
    }

    @Synchronized
    fun stop() {
        track?.let {
            runCatching { it.stop() }
            it.release()
        }
        track = null
    }
}

private fun builtIn(context: Context, flags: Int, type: Int): AudioDeviceInfo? =
    context.getSystemService(AudioManager::class.java)?.getDevices(flags)?.firstOrNull { it.type == type }
