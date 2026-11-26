package com.radiome.core.protocol

import com.radiome.core.protocol.payload.ContentType
import com.radiome.core.protocol.payload.VoiceNote
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.random.Random

class VoiceNoteTest {
    @Test
    fun framesArePackedTightlyAndComeBackIntact() {
        val r = Random(7)
        // 700C: 28 bits per frame in 4 bytes, the low nibble of the last byte unused.
        val frames = List(250) { ByteArray(4).also { r.nextBytes(it); it[3] = (it[3].toInt() and 0xF0).toByte() } }
        val note = VoiceNote(VoiceNote.MODE_700C, 28, frames)
        val bytes = note.encode()
        assertEquals(VoiceNote.HEADER + 875, bytes.size) // 10 s: 875 bytes instead of 1000
        assertEquals(10_000, note.durationMs())
        val back = VoiceNote.decode(bytes)!!
        assertEquals(VoiceNote.MODE_700C, back.mode)
        assertEquals(250, back.frames.size)
        frames.indices.forEach { assertArrayEquals(frames[it], back.frames[it]) }
        assertEquals(ContentType.VOICE, note.toPayload().type)
    }

    @Test
    fun truncatedOrForeignDataIsRejected() {
        val bytes = VoiceNote(VoiceNote.MODE_700C, 28, List(10) { ByteArray(4) }).encode()
        assertNull(VoiceNote.decode(bytes.copyOf(bytes.size - 2)))
        assertNull(VoiceNote.decode(byteArrayOf(9, 8, 28, 0, 1, 0, 0, 0, 0)))
        assertEquals(0, VoiceNote.decode(VoiceNote(VoiceNote.MODE_700C, 28, emptyList()).encode())!!.frames.size)
    }
}
