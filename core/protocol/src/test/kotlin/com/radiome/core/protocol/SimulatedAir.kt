package com.radiome.core.protocol

import com.radiome.core.dsp.modem.ModemConfig
import com.radiome.core.dsp.sim.ChannelConfig
import com.radiome.core.dsp.sim.RadioChannelSimulator
import com.radiome.core.protocol.link.AudioPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.Random

/**
 * Real-time (virtual-clock) radio "air" between stations. Every transmission passes through a
 * [RadioChannelSimulator] chosen per (from, to, transmission number); each station also hears an echo of itself,
 * which the link's half-duplex guard must ignore.
 */
class SimulatedAir(private val blockSamples: Int = 2400, seed: Long = 1) {
    private val stations = ArrayList<Station>()
    private val rnd = Random(seed)
    private var txCounter = 0
    var channel: (from: String, to: String, txIndex: Int) -> ChannelConfig? = { _, _, _ -> ChannelConfig(snrDb = 15.0) }
    val log = ArrayList<String>()

    fun station(name: String): Station = Station(name).also { stations += it }

    fun start(scope: CoroutineScope): Job = scope.launch {
        val ms = blockSamples * 1000L / ModemConfig.SAMPLE_RATE
        while (true) {
            delay(ms)
            for (s in stations) s.inbox.send(s.nextBlock())
        }
    }

    inner class Station(val name: String) : AudioPort {
        internal val inbox = Channel<FloatArray>(Channel.UNLIMITED)
        override val input: Flow<FloatArray> = inbox.receiveAsFlow()
        private val pending = ArrayDeque<FloatArray>()
        private var headOffset = 0

        override suspend fun play(samples: FloatArray) {
            val idx = txCounter++
            log += "$name tx#$idx ${samples.size * 1000 / ModemConfig.SAMPLE_RATE} ms"
            for (other in stations) {
                if (other === this) {
                    other.pending.addLast(FloatArray(samples.size) { samples[it] * 0.5f }) // acoustic self-echo
                } else {
                    val cfg = channel(name, other.name, idx) ?: continue // null = this transmission never arrives
                    other.pending.addLast(RadioChannelSimulator(cfg).apply(samples))
                }
            }
            delay(samples.size * 1000L / ModemConfig.SAMPLE_RATE)
        }

        internal fun nextBlock(): FloatArray {
            val out = FloatArray(blockSamples) { (rnd.nextGaussian() * 3e-4).toFloat() }
            var i = 0
            while (i < blockSamples && pending.isNotEmpty()) {
                val head = pending.first()
                val n = minOf(blockSamples - i, head.size - headOffset)
                for (k in 0 until n) out[i + k] += head[headOffset + k]
                i += n
                headOffset += n
                if (headOffset == head.size) {
                    pending.removeFirst()
                    headOffset = 0
                }
            }
            return out
        }
    }
}
