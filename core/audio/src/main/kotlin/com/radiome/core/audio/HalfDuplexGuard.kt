package com.radiome.core.audio

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps the receiver deaf while we transmit, and for a short tail afterwards so speaker ring-down, room echo or a
 * radio's VOX hang time cannot be decoded as an incoming burst. Called from the capture thread and the playback
 * thread, so the state is atomic.
 */
class HalfDuplexGuard(private val tailSamples: Int) {
    private val transmitting = AtomicBoolean(false)
    private val tailRemaining = AtomicInteger(0)

    val isTransmitting: Boolean get() = transmitting.get()

    fun beginTransmit() {
        transmitting.set(true)
    }

    fun endTransmit() {
        tailRemaining.set(tailSamples)
        transmitting.set(false)
    }

    /** True if a captured block of [blockSize] samples must be dropped instead of handed to the demodulator. */
    fun shouldDrop(blockSize: Int): Boolean {
        if (transmitting.get()) return true
        while (true) {
            val left = tailRemaining.get()
            if (left <= 0) return false
            if (tailRemaining.compareAndSet(left, maxOf(0, left - blockSize))) return true
        }
    }
}
