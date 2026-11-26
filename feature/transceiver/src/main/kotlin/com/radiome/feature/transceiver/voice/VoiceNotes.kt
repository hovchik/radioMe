package com.radiome.feature.transceiver.voice

import android.content.Context
import com.radiome.core.protocol.payload.VoiceNote
import com.radiome.core.voice.Codec2
import com.radiome.core.voice.VoicePlayer
import com.radiome.core.voice.VoiceRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Recording, encoding and playing voice notes. */
interface VoiceNotes {
    /** False when Codec2 could not be loaded on this phone. */
    val available: Boolean
    /** Starts the microphone; false when it could not be opened. */
    fun startRecording(): Boolean
    val isRecording: Boolean
    val elapsedMs: Long
    /** Stops recording and encodes what was said; null when nothing usable was recorded. */
    suspend fun stopRecording(): VoiceNote?
    /** Stops the microphone and throws the recording away. */
    fun cancelRecording()
    suspend fun play(note: VoiceNote)
    fun stopPlayback()
}

/** Codec2 700C (700 bit/s): about 88 bytes per second of speech. */
class AndroidVoiceNotes(context: Context, private val mode: Int = VoiceNote.MODE_700C) : VoiceNotes {
    private val recorder = VoiceRecorder(context.applicationContext)
    private val player = VoicePlayer(context.applicationContext)

    override val available: Boolean get() = Codec2.available
    override val isRecording: Boolean get() = recorder.isRecording
    override val elapsedMs: Long get() = recorder.elapsedMs

    override fun startRecording(): Boolean = available && recorder.start()

    override suspend fun stopRecording(): VoiceNote? = withContext(Dispatchers.Default) {
        val pcm = recorder.stop()
        if (pcm.size < Codec2.SAMPLE_RATE / 2) return@withContext null // under half a second
        Codec2(mode).use { it.encode(pcm) }
    }

    override fun cancelRecording() {
        if (recorder.isRecording) recorder.stop()
    }

    override suspend fun play(note: VoiceNote) {
        if (!available) return
        val pcm = withContext(Dispatchers.Default) { Codec2(note.mode).use { it.decode(note) } }
        player.play(pcm)
    }

    override fun stopPlayback() = player.stop()
}

/** For tests and phones without the native library. */
object NoVoiceNotes : VoiceNotes {
    override val available = false
    override fun startRecording() = false
    override val isRecording = false
    override val elapsedMs = 0L
    override suspend fun stopRecording(): VoiceNote? = null
    override fun cancelRecording() = Unit
    override suspend fun play(note: VoiceNote) = Unit
    override fun stopPlayback() = Unit
}
