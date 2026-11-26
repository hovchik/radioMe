package com.radiome.core.protocol.link

import com.radiome.core.dsp.framing.BurstCodec
import com.radiome.core.dsp.framing.Frame
import com.radiome.core.dsp.framing.FrameType
import com.radiome.core.dsp.framing.NackCodec
import com.radiome.core.dsp.modem.DemodTelemetry
import com.radiome.core.dsp.modem.MfskModemEngine
import com.radiome.core.dsp.modem.ModemConfig
import com.radiome.core.dsp.modem.PhyBurst
import com.radiome.core.dsp.modem.RawBurst
import com.radiome.core.dsp.modem.RxState
import com.radiome.core.protocol.arq.ArqConfig
import com.radiome.core.protocol.arq.ReceiverAction
import com.radiome.core.protocol.arq.ReceiverSession
import com.radiome.core.protocol.arq.SenderAction
import com.radiome.core.protocol.arq.SenderPhase
import com.radiome.core.protocol.arq.SenderSession
import com.radiome.core.protocol.arq.controlOptions
import com.radiome.core.protocol.payload.Payload
import com.radiome.core.protocol.session.ChunkState
import com.radiome.core.protocol.session.Packetizer
import com.radiome.core.protocol.session.TransferOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * One half-duplex radio station: modem + ARQ for any number of outgoing and incoming sessions.
 *
 * Threading: all state is confined to [scope], which must be single-threaded (for example
 * `Dispatchers.Default.limitedParallelism(1)`). Time is the audio clock (count of input samples), so timeouts
 * behave the same in real time and in virtual-time tests.
 *
 * Half-duplex rules:
 * - Listen before talk: a queued transmission waits until the demodulator is idle.
 * - While transmitting, captured audio is dropped (no self-decoding) and the demodulator is reset afterwards.
 * - The receiver answers (NACK or ACK) only after [ArqConfig.turnaroundMs] of quiet following the sender's over.
 */
class LinkController(
    private val audio: AudioPort,
    private val scope: CoroutineScope,
    val arq: ArqConfig = ArqConfig(),
    private val engine: MfskModemEngine = MfskModemEngine(),
    private val store: SessionStore = InMemorySessionStore(),
    private val random: Random = Random.Default,
) {
    private val fs = ModemConfig.SAMPLE_RATE
    private val demod = engine.createDemodulator()

    private val _state = MutableStateFlow(LinkState.LISTENING)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _rx = MutableStateFlow(DemodTelemetry())
    val rxTelemetry: StateFlow<DemodTelemetry> = _rx.asStateFlow()

    private val _transfers = MutableStateFlow<Map<Int, TransferTelemetry>>(emptyMap())
    val transfers: StateFlow<Map<Int, TransferTelemetry>> = _transfers.asStateFlow()

    private val _events = MutableSharedFlow<LinkEvent>(replay = 0, extraBufferCapacity = 256)
    val events: SharedFlow<LinkEvent> = _events.asSharedFlow()

    /** Either modem [bursts] or, for a test tone, [raw] samples. */
    private class TxJob(val sessionId: Int, val bursts: List<PhyBurst>, val onDone: () -> Unit, val raw: FloatArray? = null)

    private val txQueue = Channel<TxJob>(Channel.UNLIMITED)
    private var transmitting = false
    private var resetDemod = false
    private var currentPlay: Job? = null

    private var clock = 0L
    private var lastActivity = 0L
    private val receivers = LinkedHashMap<Int, ReceiverSession>()
    private val senders = LinkedHashMap<Int, SenderSession>()
    private val startedAt = HashMap<Int, Long>()
    private val pendingOverEnd = LinkedHashSet<Int>()
    /** Sender session -> quiet samples heard since its last transmission ended. */
    private val awaiting = LinkedHashMap<Int, Long>()
    private val lastSnr = HashMap<Int, Double>()

    fun start(): Job = scope.launch {
        launch { txLoop() }
        audio.input.collect { onInput(it) }
    }

    /** Queue [payload] for transmission. Returns the session id. */
    fun send(payload: Payload, options: TransferOptions = TransferOptions()): Int {
        val sid = newSessionId()
        val sender = SenderSession(Packetizer.packetize(payload, sid, options), arq)
        senders[sid] = sender
        startedAt[sid] = nowMs()
        enqueue(sid, sender.initialBursts()) {
            sender.onTransmitted()
            awaiting[sid] = 0
        }
        emit(LinkEvent.Transmitted(sid, sender.transfer.dataFrames.indices.toList(), options.profile, LinkEvent.Transmitted.Kind.INITIAL))
        publish()
        return sid
    }

    /** Manual "Send correction request" for an incomplete incoming session. */
    fun requestCorrections(sessionId: Int): Boolean {
        val rs = receivers[sessionId] ?: return false
        if (rs.tracker.isComplete) return false
        sendNack(rs)
        return true
    }

    /** Stop an outgoing session; aborts the burst on air if it belongs to it. */
    fun cancel(sessionId: Int) {
        senders[sessionId]?.cancel()
        awaiting.remove(sessionId)
        if (currentTxSession == sessionId) currentPlay?.cancel()
        publish()
    }

    /** Re-open a persisted partial transfer so later repair bursts merge into it. */
    fun restore(sessionId: Int): Boolean {
        val snap = store.load(sessionId) ?: return false
        receivers[sessionId] = ReceiverSession.restore(snap)
        startedAt.putIfAbsent(sessionId, nowMs())
        publish()
        return true
    }

    // ------------------------------------------------------------------------------------------------ TX

    private var currentTxSession = -1

    /**
     * Queue a plain [ModemConfig.PILOT_HZ] tone so the user can check that audio reaches the speaker or cable and
     * set the radio's VOX and volume. It waits for a clear channel like any other transmission.
     */
    fun sendTestTone(durationMs: Int = 2000) {
        val n = durationMs.coerceIn(100, 10_000) * fs / 1000
        val ramp = minOf(ModemConfig.FADE_SAMPLES, n / 2)
        val w = 2 * Math.PI * ModemConfig.PILOT_HZ / fs
        val amp = engine.config.amplitude
        val tone = FloatArray(n) { i ->
            val edge = minOf(i, n - 1 - i)
            val env = if (edge >= ramp) 1.0 else 0.5 - 0.5 * kotlin.math.cos(Math.PI * edge / ramp)
            (amp * env * kotlin.math.sin(w * i)).toFloat()
        }
        txQueue.trySend(TxJob(TEST_TONE_SESSION, emptyList(), {}, raw = tone))
    }

    private fun enqueue(sessionId: Int, bursts: List<PhyBurst>, onDone: () -> Unit) {
        txQueue.trySend(TxJob(sessionId, bursts, onDone))
    }

    private suspend fun txLoop() {
        for (job in txQueue) {
            if (senders[job.sessionId]?.phase == SenderPhase.CANCELLED) continue
            _state.first { it == LinkState.LISTENING }
            // cancel() may have been called while we waited for the channel.
            if (senders[job.sessionId]?.phase == SenderPhase.CANCELLED) continue
            transmitting = true
            currentTxSession = job.sessionId
            _state.value = LinkState.TRANSMITTING
            var failure: Exception? = null
            try {
                // A cancelled child (cancel()) just ends this job; cancelling the scope propagates as usual.
                coroutineScope {
                    currentPlay = launch {
                        val raw = job.raw
                        if (raw != null) audio.play(raw) else for (b in job.bursts) audio.play(engine.modulate(b))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The audio device failed. Keep the link alive for later transmissions and say why.
                failure = e
            } finally {
                currentPlay = null
                currentTxSession = -1
                transmitting = false
                resetDemod = true
                lastActivity = clock
                _state.value = LinkState.LISTENING
            }
            if (failure != null) {
                val reason = "audio output failed: ${failure.message ?: failure::class.simpleName}"
                val sender = senders[job.sessionId]
                val aborted = sender != null && sender.abort(reason) is SenderAction.Failed
                if (sender != null) awaiting.remove(job.sessionId)
                emit(LinkEvent.AudioOutputFailed(job.sessionId, reason, transferFailed = aborted))
                if (aborted) emit(LinkEvent.Failed(job.sessionId, reason))
            } else {
                job.onDone()
            }
            publish()
        }
    }

    // ------------------------------------------------------------------------------------------------ RX

    private fun onInput(block: FloatArray) {
        clock += block.size
        if (transmitting) return // half-duplex guard: never decode our own transmission
        if (resetDemod) {
            demod.reset()
            resetDemod = false
        }
        val bursts = demod.process(block)
        val t = demod.telemetry
        _rx.value = t
        for (b in bursts) handleBurst(b)
        val busy = t.state != RxState.LISTENING
        if (busy || bursts.isNotEmpty()) lastActivity = clock
        if (!transmitting) _state.value = if (busy) LinkState.RECEIVING else LinkState.LISTENING
        if (!busy) onQuiet(block.size.toLong())
        if (bursts.isNotEmpty()) publish()
    }

    private fun handleBurst(raw: RawBurst) {
        val nacks = LinkedHashMap<Int, MutableList<Int>>()
        val acks = LinkedHashSet<Int>()
        for (r in BurstCodec.decodeFrames(raw)) {
            val f = r.frame ?: continue
            when (f.type) {
                FrameType.DATA, FrameType.HEADER -> {
                    if (f.sessionId in senders) continue
                    val rs = receiverFor(f)
                    if (rs.onFrame(f, r.status, r.correctedBytes)) store.save(rs.snapshot())
                    pendingOverEnd += f.sessionId
                    lastSnr[f.sessionId] = raw.snrDb
                }
                FrameType.NACK -> runCatching { NackCodec.decode(f.payload) }.getOrNull()?.let {
                    nacks.getOrPut(f.sessionId) { ArrayList() } += it
                }
                FrameType.ACK -> acks += f.sessionId
            }
        }
        for ((sid, requested) in nacks) {
            val s = senders[sid] ?: continue
            awaiting.remove(sid)
            emit(LinkEvent.NackReceived(sid, requested.distinct().sorted()))
            onSenderAction(s, s.onNack(requested))
        }
        for (sid in acks) {
            val s = senders[sid] ?: continue
            awaiting.remove(sid)
            onSenderAction(s, s.onAck())
        }
    }

    /**
     * The receiver session for [f]: the one in memory, else a partial transfer persisted before a restart (so a
     * repair burst after a restart merges into the chunks already stored), else a new one. A session whose chunk
     * count or header does not match the frame is a reused id for a different transfer and is replaced.
     */
    private fun receiverFor(f: Frame): ReceiverSession {
        receivers[f.sessionId]?.let { if (!it.conflictsWith(f)) return it }
        val restored = store.load(f.sessionId)?.let(ReceiverSession::restore)?.takeUnless { it.conflictsWith(f) }
        val rs = restored ?: ReceiverSession(f.sessionId)
        receivers[f.sessionId] = rs
        pendingOverEnd.remove(f.sessionId)
        startedAt[f.sessionId] = nowMs()
        if (restored == null) emit(LinkEvent.ReceiveStarted(f.sessionId, f.totalChunks))
        return rs
    }

    private fun onQuiet(samples: Long) {
        val quietMs = (clock - lastActivity) * 1000 / fs
        if (pendingOverEnd.isNotEmpty() && quietMs >= arq.turnaroundMs) {
            val ids = pendingOverEnd.toList()
            pendingOverEnd.clear()
            ids.forEach(::overEnded)
            publish()
        }
        if (awaiting.isEmpty()) return
        val limit = arq.responseTimeoutMs.toLong() * fs / 1000
        val expired = ArrayList<Int>()
        for (e in awaiting.entries) {
            e.setValue(e.value + samples)
            if (e.value >= limit) expired += e.key
        }
        for (sid in expired) {
            awaiting.remove(sid)
            senders[sid]?.let { onSenderAction(it, it.onResponseTimeout()) }
        }
    }

    private fun overEnded(sid: Int) {
        val rs = receivers[sid] ?: return
        when (val a = rs.onOverEnded()) {
            is ReceiverAction.Completed -> {
                store.save(rs.snapshot())
                if (a.firstTime) emit(LinkEvent.MessageReceived(sid, a.payload))
                if (arq.sendAck) {
                    enqueue(sid, Packetizer.bursts(listOf(rs.ackFrame()), controlOptions(arq))) {}
                    emit(LinkEvent.AckSent(sid))
                }
            }
            is ReceiverAction.NeedsRepair -> {
                emit(LinkEvent.CorrectionsNeeded(sid, a.missing, a.lossPercent))
                if (arq.autoNack) sendNack(rs)
            }
            is ReceiverAction.Corrupt -> emit(LinkEvent.Failed(sid, a.reason))
        }
    }

    private fun sendNack(rs: ReceiverSession) {
        val missing = rs.tracker.missingForNack
        enqueue(rs.sessionId, Packetizer.bursts(rs.nackFrames(), controlOptions(arq))) {}
        store.save(rs.snapshot())
        emit(LinkEvent.NackSent(rs.sessionId, missing))
        publish()
    }

    private fun onSenderAction(s: SenderSession, action: SenderAction) {
        when (action) {
            is SenderAction.Transmit -> {
                enqueue(s.sessionId, action.bursts) {
                    s.onTransmitted()
                    awaiting[s.sessionId] = 0
                }
                val kind = if (action.isPoll) LinkEvent.Transmitted.Kind.POLL else LinkEvent.Transmitted.Kind.REPAIR
                emit(LinkEvent.Transmitted(s.sessionId, action.chunks, action.profile, kind))
            }
            SenderAction.Completed -> emit(LinkEvent.Delivered(s.sessionId))
            is SenderAction.Failed -> emit(LinkEvent.Failed(s.sessionId, action.reason))
            SenderAction.Ignore -> Unit
        }
        publish()
    }

    // ------------------------------------------------------------------------------------------------ telemetry

    private fun publish() {
        val now = nowMs()
        val map = LinkedHashMap<Int, TransferTelemetry>()
        for (s in senders.values) {
            val states = s.chunkStates()
            map[s.sessionId] = TransferTelemetry(
                sessionId = s.sessionId,
                direction = Direction.OUTGOING,
                phase = s.phase.name,
                name = s.transfer.header.name,
                totalChunks = s.transfer.totalChunks,
                chunkStates = states,
                receivedChunks = states.count { it != ChunkState.MISSING },
                fecRepairedChunks = 0,
                fecCorrectedBytes = 0,
                missing = s.lastRequested,
                lossPercent = s.lastRequested.count { it != Packetizer.HEADER_CHUNK } * 100.0 / s.transfer.totalChunks,
                repairRounds = s.repairRounds,
                snrDb = Double.NaN,
                startedAtMs = startedAt[s.sessionId] ?: now,
                updatedAtMs = now,
            )
        }
        for (r in receivers.values) {
            val t = r.tracker
            map[r.sessionId] = TransferTelemetry(
                sessionId = r.sessionId,
                direction = Direction.INCOMING,
                phase = r.phase.name,
                name = t.header?.name ?: "",
                totalChunks = t.totalChunks ?: 0,
                chunkStates = t.states(),
                receivedChunks = t.receivedCount,
                fecRepairedChunks = t.repairedCount,
                fecCorrectedBytes = t.fecCorrectedBytes,
                missing = t.missingForNack,
                lossPercent = t.lossPercent,
                repairRounds = r.repairRounds,
                snrDb = lastSnr[r.sessionId] ?: Double.NaN,
                startedAtMs = startedAt[r.sessionId] ?: now,
                updatedAtMs = now,
            )
        }
        _transfers.value = map
    }

    private fun emit(e: LinkEvent) {
        _events.tryEmit(e)
    }

    private fun nowMs(): Long = clock * 1000 / fs

    private fun newSessionId(): Int {
        while (true) {
            val id = random.nextInt(1, 0xFFFF)
            if (id !in senders && id !in receivers) return id
        }
    }

    companion object {
        /** Session id carried by events about a test tone (no real session uses it). */
        const val TEST_TONE_SESSION = -1
    }
}
