package com.radiome.core.protocol.link

import com.radiome.core.dsp.framing.BurstCodec
import com.radiome.core.dsp.framing.Frame
import com.radiome.core.dsp.framing.FrameType
import com.radiome.core.dsp.framing.FrameCodec
import com.radiome.core.dsp.framing.NackCodec
import com.radiome.core.dsp.modem.DemodTelemetry
import com.radiome.core.dsp.modem.MfskModemEngine
import com.radiome.core.dsp.math.Spectrogram
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
import com.radiome.core.protocol.crypto.GroupKey
import com.radiome.core.protocol.crypto.SecureEnvelope
import com.radiome.core.protocol.payload.CompressionMode
import com.radiome.core.protocol.image.ProgressiveImage
import com.radiome.core.protocol.payload.ContentType
import com.radiome.core.protocol.payload.Payload
import com.radiome.core.protocol.session.ChunkState
import com.radiome.core.protocol.session.ChunkTracker
import com.radiome.core.protocol.session.AirtimeEstimator
import com.radiome.core.protocol.session.Packetizer
import com.radiome.core.protocol.session.TransferOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.BufferOverflow
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
 *
 * Station identification: with a [callsign] set, every burst starts with a STATION frame (callsign and the
 * addressee). Transfers addressed to another callsign are ignored; ones without an addressee are for everyone.
 */
class LinkController(
    private val audio: AudioPort,
    private val scope: CoroutineScope,
    val arq: ArqConfig = ArqConfig(),
    private val engine: MfskModemEngine = MfskModemEngine(),
    private val store: SessionStore = InMemorySessionStore(),
    private val random: Random = Random.Default,
    callsign: String = "",
) {
    /** This station's callsign; blank sends no STATION frames. Set it on the link thread. */
    var callsign: String = Callsign.normalize(callsign)
        set(value) {
            field = Callsign.normalize(value)
        }

    /**
     * Pre-shared group key for end-to-end encryption. Positions are only ever sent sealed with it, other payloads
     * when [TransferOptions.encrypt] is set. Set it on the link thread.
     */
    var groupKey: GroupKey? = null

    private val fs = ModemConfig.SAMPLE_RATE
    private val demod = engine.createDemodulator()

    private val _state = MutableStateFlow(LinkState.LISTENING)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _rx = MutableStateFlow(DemodTelemetry())
    val rxTelemetry: StateFlow<DemodTelemetry> = _rx.asStateFlow()

    /** Set while a waterfall is on screen; the spectrum costs an FFT ten times a second, so it is off otherwise. */
    @Volatile var waterfallEnabled: Boolean = false
    private val spectrogram = Spectrogram(ModemConfig.SAMPLE_RATE)
    private val _waterfall = MutableSharedFlow<FloatArray>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Spectrum rows of the input ([Spectrogram]: 300-2700 Hz, dB), about ten per second while enabled. */
    val waterfall: SharedFlow<FloatArray> = _waterfall.asSharedFlow()

    private val _transfers = MutableStateFlow<Map<Int, TransferTelemetry>>(emptyMap())
    val transfers: StateFlow<Map<Int, TransferTelemetry>> = _transfers.asStateFlow()

    private val _events = MutableSharedFlow<LinkEvent>(replay = 0, extraBufferCapacity = 256)
    val events: SharedFlow<LinkEvent> = _events.asSharedFlow()

    /** Either modem [bursts] or, for a test tone, [raw] samples. */
    private class TxJob(
        val sessionId: Int,
        val bursts: List<PhyBurst>,
        val onDone: () -> Unit,
        val raw: FloatArray? = null,
        val onFailed: () -> Unit = {},
    )

    private val txQueue = Channel<TxJob>(Channel.UNLIMITED)
    private var transmitting = false
    private var resetDemod = false
    private var currentPlay: Job? = null

    private var clock = 0L
    private var lastActivity = 0L
    private val receivers = LinkedHashMap<Int, ReceiverSession>()
    private val senders = LinkedHashMap<Int, SenderSession>()
    private val startedAt = HashMap<Int, Long>()
    /** Incoming session -> quiet (ms) to wait before answering: the turnaround, plus a random backoff in broadcasts. */
    private val pendingOverEnd = LinkedHashMap<Int, Int>()
    /** Broadcast sender session -> chunks requested by any receiver since the last repair. */
    private val broadcastNacks = HashMap<Int, java.util.TreeSet<Int>>()
    /** Sender session -> quiet samples heard since its last transmission ended. */
    private val awaiting = LinkedHashMap<Int, Long>()
    private val lastSnr = HashMap<Int, Double>()
    /** Incoming session -> the sending station, from its STATION frames. */
    private val peers = HashMap<Int, StationInfo>()
    /** Outgoing session -> callsigns that confirmed it. */
    private val ackedBy = HashMap<Int, MutableList<String>>()
    /** Link tests this station runs, and quiet samples heard since the probes went out. */
    private val ownTests = HashSet<Int>()
    private val testWait = LinkedHashMap<Int, Long>()
    /** Link tests heard from another station, answered after the turnaround. */
    private val probes = LinkedHashMap<Int, ProbeCollector>()
    private val pendingProbes = LinkedHashSet<Int>()
    /** Incoming sessions whose first burst slot decoded at least once, so their addressee is known. */
    private val identified = HashSet<Int>()
    /** Complete sessions held back until a burst tells us whom they are for. */
    private val withheld = HashSet<Int>()
    /** Sample clock at the first probe of each link test we are measuring. */
    private val probeStart = HashMap<Int, Long>()
    /** Air time of a whole probe sequence (all profiles, with STATION frames), for the answer deadline. */
    private val probeSequenceMs: Long by lazy {
        val est = AirtimeEstimator(engine)
        LinkTest.order.sumOf { p ->
            est.framesMs(
                LinkTest.probeCount(p),
                TransferOptions(
                    profile = p, chunkPayloadSize = LinkTest.PAYLOAD, parityRatio = LinkTest.PROBE_PARITY,
                    maxFramesPerBurst = 255, station = StationInfo("X"),
                ),
            )
        }
    }

    fun start(): Job = scope.launch {
        launch { txLoop() }
        audio.input.collect { onInput(it) }
    }

    /**
     * Queue [payload] for transmission. Returns the session id. Throws [IllegalStateException] when the payload must
     * be encrypted (a position, or [TransferOptions.encrypt]) and there is no [groupKey].
     */
    fun send(payload: Payload, options: TransferOptions = TransferOptions()): Int {
        val seal = options.encrypt || payload.type == ContentType.POSITION
        val onAir = if (!seal) payload else {
            val key = groupKey ?: throw IllegalStateException("no group key: set one in Settings to send encrypted")
            SecureEnvelope.seal(payload, key, callsign)
        }
        // Ciphertext does not compress.
        val opts = stamped(if (seal) options.copy(compression = CompressionMode.NEVER) else options)
        val sid = newSessionId()
        val sender = SenderSession(Packetizer.packetize(onAir, sid, opts), arq)
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

    /**
     * Run a link test: one probe burst per profile. The other station measures them and reports back, which ends in
     * [LinkEvent.LinkTestReported] here (or [LinkEvent.LinkTestFailed] when no report arrives). [to] addresses the test
     * to one station; null lets any station answer.
     */
    fun startLinkTest(to: String? = null): Int {
        val sid = newSessionId()
        ownTests += sid
        val station = stamped(TransferOptions(station = to?.let { StationInfo("", it) })).station
        val bursts = LinkTest.order.flatMap { p ->
            val opts = TransferOptions(
                profile = p,
                chunkPayloadSize = LinkTest.PAYLOAD,
                parityRatio = LinkTest.PROBE_PARITY,
                maxFramesPerBurst = 255,
                station = station,
            )
            Packetizer.bursts(LinkTest.probeFrames(sid, p), opts)
        }
        emit(LinkEvent.LinkTestStarted(sid))
        enqueue(sid, bursts, onFailed = {
            ownTests.remove(sid)
            emit(LinkEvent.LinkTestFailed(sid, "the probes could not be played"))
        }) { testWait[sid] = 0 }
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

    private fun enqueue(sessionId: Int, bursts: List<PhyBurst>, onFailed: () -> Unit = {}, onDone: () -> Unit) {
        txQueue.trySend(TxJob(sessionId, bursts, onDone, onFailed = onFailed))
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
                job.onFailed()
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
        if (waterfallEnabled) for (row in spectrogram.process(block)) _waterfall.tryEmit(row)
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
        val results = BurstCodec.decodeFrames(raw)
        // STATION frames first: they say whether the rest of the burst is for us.
        val stations = HashMap<Int, StationInfo>()
        for (r in results) {
            val f = r.frame ?: continue
            if (f.type != FrameType.STATION) continue
            val info = StationInfo.decode(f.payload) ?: continue
            stations[f.sessionId] = info
            if (info.source.isNotEmpty()) emit(LinkEvent.StationHeard(f.sessionId, info.source, raw.snrDb))
        }
        // The first slot carries the STATION frame when the sender identifies itself. Once a first slot decodes, we
        // know who the session is for (or that the sender sends no STATION frames).
        results.firstOrNull()?.frame?.let { identified += it.sessionId }
        val nacks = LinkedHashMap<Int, MutableList<Int>>()
        val acks = LinkedHashMap<Int, String?>()
        for (r in results) {
            val f = r.frame ?: continue
            val station = stations[f.sessionId]
            val forUs = station == null || station.isFor(callsign)
            when (f.type) {
                FrameType.DATA, FrameType.HEADER -> {
                    if (f.sessionId in senders || f.sessionId in ownTests) continue
                    val known = station ?: peers[f.sessionId]
                    if (known != null && !known.isFor(callsign)) {
                        dropForeign(f.sessionId, known)
                        continue
                    }
                    val rs = receiverFor(f)
                    if (station != null) peers[f.sessionId] = station
                    if (rs.onFrame(f, r.status, r.correctedBytes)) store.save(rs.snapshot())
                    pendingOverEnd.getOrPut(f.sessionId) { answerDelayMs(f.sessionId) }
                    lastSnr[f.sessionId] = raw.snrDb
                }
                FrameType.NACK -> if (forUs) {
                    runCatching { NackCodec.decode(f.payload) }.getOrNull()?.let {
                        nacks.getOrPut(f.sessionId) { ArrayList() } += it
                    }
                }
                FrameType.ACK -> if (forUs) acks[f.sessionId] = station?.source?.ifEmpty { null }
                FrameType.PROBE -> {
                    if (f.sessionId in ownTests || !forUs) continue
                    probeStart.getOrPut(f.sessionId) { clock }
                    probes.getOrPut(f.sessionId) { ProbeCollector() }.add(raw.header.profile, f, r.status, r.correctedBytes, raw.snrDb)
                    if (station != null) peers[f.sessionId] = station
                    pendingProbes += f.sessionId
                }
                FrameType.PROBE_REPORT -> {
                    if (f.sessionId !in ownTests || !forUs) continue
                    val report = LinkTest.decodeReport(f.payload) ?: continue
                    ownTests.remove(f.sessionId)
                    testWait.remove(f.sessionId)
                    emit(LinkEvent.LinkTestReported(f.sessionId, report, station?.source?.ifEmpty { null }, measuredHere = false))
                }
                FrameType.STATION -> Unit
            }
        }
        for ((sid, requested) in nacks) {
            val s = senders[sid] ?: continue
            if (s.isBroadcast) {
                // Several receivers answer one after another: collect them all, repair once.
                broadcastNacks.getOrPut(sid) { java.util.TreeSet() } += requested
                awaiting[sid] = 0
                emit(LinkEvent.NackReceived(sid, requested.distinct().sorted()))
                continue
            }
            awaiting.remove(sid)
            emit(LinkEvent.NackReceived(sid, requested.distinct().sorted()))
            onSenderAction(s, s.onNack(requested))
        }
        for ((sid, by) in acks) {
            val s = senders[sid] ?: continue
            awaiting.remove(sid)
            // In a broadcast an unidentified confirmation still counts, so it gets a placeholder name.
            val who = by ?: if (s.isBroadcast) "?" else null
            if (who != null) ackedBy.getOrPut(sid) { ArrayList() }.let { if (who !in it) it += who }
            if (s.isBroadcast) {
                // Others may still be missing chunks; the session completes when a response window stays quiet.
                awaiting[sid] = 0
                emit(LinkEvent.AckReceived(sid, by))
                publish()
                continue
            }
            onSenderAction(s, s.onAck(), by)
        }
    }

    /** A transfer turned out to be addressed to another station: forget whatever we started collecting. */
    private fun dropForeign(sid: Int, info: StationInfo) {
        peers[sid] = info
        if (receivers.remove(sid) != null) {
            pendingOverEnd.remove(sid)
            publish()
        }
    }

    /** Our callsign on everything we send, plus the addressee the caller chose. */
    private fun stamped(o: TransferOptions): TransferOptions {
        val st = o.station
        val to = st?.destination?.let(Callsign::normalize)?.ifEmpty { null }
        if (callsign.isEmpty() && to == null && st?.broadcast != true) return o.copy(station = null)
        return o.copy(station = StationInfo(callsign, to, st?.broadcast ?: false))
    }

    /** NACK / ACK / report bursts, identified when we have a callsign and addressed back to [to]. */
    private fun controlBursts(frames: List<Frame>, to: String?): List<PhyBurst> {
        val base = controlOptions(arq)
        val opts = if (callsign.isEmpty()) base else base.copy(station = StationInfo(callsign, to))
        return Packetizer.bursts(frames, opts)
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
        if (receivers[f.sessionId] != null) peers.remove(f.sessionId) // a reused id: the old sender's identity is stale
        receivers[f.sessionId] = rs
        pendingOverEnd.remove(f.sessionId)
        startedAt[f.sessionId] = nowMs()
        if (restored == null) emit(LinkEvent.ReceiveStarted(f.sessionId, f.totalChunks))
        return rs
    }

    private fun onQuiet(samples: Long) {
        val quietMs = (clock - lastActivity) * 1000 / fs
        if (pendingOverEnd.isNotEmpty()) {
            val ids = pendingOverEnd.filterValues { quietMs >= it }.keys.toList()
            if (ids.isNotEmpty()) {
                ids.forEach { pendingOverEnd.remove(it) }
                ids.forEach(::overEnded)
                publish()
            }
        }
        if (pendingProbes.isNotEmpty() && quietMs >= arq.turnaroundMs) {
            // Answer once the whole probe sequence is over: the last profile was heard, or there has been time for
            // all of it. A burst lost to a fade leaves a quiet gap while the tester is still transmitting.
            val ready = pendingProbes.filter { sid ->
                val heardLast = probes[sid]?.has(LinkTest.order.last()) == true
                val elapsedMs = (clock - (probeStart[sid] ?: clock)) * 1000 / fs
                heardLast || elapsedMs >= probeSequenceMs + arq.turnaroundMs
            }
            pendingProbes.removeAll(ready.toSet())
            ready.forEach(::answerLinkTest)
        }
        val limit = arq.responseTimeoutMs.toLong() * fs / 1000
        if (testWait.isNotEmpty()) {
            val expiredTests = ArrayList<Int>()
            for (e in testWait.entries) {
                e.setValue(e.value + samples)
                if (e.value >= 2 * limit) expiredTests += e.key
            }
            for (sid in expiredTests) {
                testWait.remove(sid)
                ownTests.remove(sid)
                emit(LinkEvent.LinkTestFailed(sid, "no report from the other station"))
            }
        }
        if (awaiting.isEmpty()) return
        val collect = broadcastCollectMs().toLong() * fs / 1000
        val broadcastLimit = maxOf(limit, collect + 2L * fs)
        val expired = ArrayList<Int>()
        val repairNow = ArrayList<Int>()
        for (e in awaiting.entries) {
            e.setValue(e.value + samples)
            val broadcast = senders[e.key]?.isBroadcast == true
            when {
                broadcast && !broadcastNacks[e.key].isNullOrEmpty() && e.value >= collect -> repairNow += e.key
                e.value >= (if (broadcast) broadcastLimit else limit) -> expired += e.key
            }
        }
        for (sid in repairNow) {
            awaiting.remove(sid)
            val wanted = broadcastNacks.remove(sid)!!.toList()
            senders[sid]?.let { onSenderAction(it, it.onNack(wanted)) }
        }
        for (sid in expired) {
            awaiting.remove(sid)
            val s = senders[sid] ?: continue
            val confirmed = ackedBy[sid].orEmpty()
            if (s.isBroadcast && confirmed.isNotEmpty()) {
                // A quiet window after confirmations: everyone who could hear us has what they need.
                onSenderAction(s, s.onAck(), confirmed.joinToString(", "))
            } else {
                onSenderAction(s, s.onResponseTimeout())
            }
        }
    }

    /** Quiet before a receiver answers [sid]: the turnaround, plus a random slot when it is a broadcast. */
    private fun answerDelayMs(sid: Int): Int =
        arq.turnaroundMs + if (peers[sid]?.broadcast == true && arq.broadcastBackoffMs > 0) random.nextInt(arq.broadcastBackoffMs + 1) else 0

    /** How long a broadcast sender keeps listening for more correction requests before repairing. */
    private fun broadcastCollectMs(): Int = arq.turnaroundMs + arq.broadcastBackoffMs + BROADCAST_MARGIN_MS

    private fun answerLinkTest(sid: Int) {
        probeStart.remove(sid)
        val report = probes.remove(sid)?.report() ?: return
        val peer = peers[sid]?.source?.ifEmpty { null }
        emit(LinkEvent.LinkTestReported(sid, report, peer, measuredHere = true))
        enqueue(sid, controlBursts(listOf(Frame(FrameType.PROBE_REPORT, sid, 0, 0, LinkTest.encodeReport(report))), peer)) {}
    }

    private fun received(sid: Int, payload: Payload, peer: StationInfo?): LinkEvent.MessageReceived {
        val from = peer?.source?.ifEmpty { null }
        if (payload.type != ContentType.ENCRYPTED) return LinkEvent.MessageReceived(sid, payload, from, peer?.destination)
        val why = when (val o = SecureEnvelope.open(payload.bytes, groupKey, from ?: "")) {
            is SecureEnvelope.Opened.Ok -> return LinkEvent.MessageReceived(sid, o.payload, from, peer?.destination, encrypted = true)
            is SecureEnvelope.Opened.WrongKey -> if (groupKey == null) "no group key set" else "sealed with another group key (id %04X)".format(o.keyId)
            SecureEnvelope.Opened.Forged -> "failed the integrity check (tampered, or replayed under another callsign)"
            SecureEnvelope.Opened.Malformed -> "malformed envelope"
        }
        return LinkEvent.MessageReceived(sid, payload, from, peer?.destination, encrypted = true, unreadable = why)
    }

    private fun overEnded(sid: Int) {
        val rs = receivers[sid] ?: return
        val peer = peers[sid]
        when (val a = rs.onOverEnded()) {
            is ReceiverAction.Completed -> {
                store.save(rs.snapshot())
                if (peer == null && sid !in identified) {
                    // Every first slot was lost, so the STATION frame may have said this is for someone else. Ask
                    // for the header again: the sender repeats it in a burst that starts with its STATION frame.
                    withheld += sid
                    val ask = NackCodec.encode(listOf(Packetizer.HEADER_CHUNK), FrameCodec.DEFAULT_PAYLOAD)
                        .map { Frame(FrameType.NACK, sid, 0, rs.tracker.totalChunks ?: 0, it) }
                    enqueue(sid, controlBursts(ask, null)) {}
                    emit(LinkEvent.NackSent(sid, listOf(Packetizer.HEADER_CHUNK)))
                    return
                }
                val firstTime = a.firstTime || withheld.remove(sid)
                if (firstTime) emit(received(sid, a.payload, peer))
                if (arq.sendAck) {
                    enqueue(sid, controlBursts(listOf(rs.ackFrame()), peer?.source)) {}
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
        enqueue(rs.sessionId, controlBursts(rs.nackFrames(), peers[rs.sessionId]?.source)) {}
        store.save(rs.snapshot())
        emit(LinkEvent.NackSent(rs.sessionId, missing))
        publish()
    }

    private fun onSenderAction(s: SenderSession, action: SenderAction, by: String? = null) {
        when (action) {
            is SenderAction.Transmit -> {
                enqueue(s.sessionId, action.bursts) {
                    s.onTransmitted()
                    awaiting[s.sessionId] = 0
                }
                val kind = if (action.isPoll) LinkEvent.Transmitted.Kind.POLL else LinkEvent.Transmitted.Kind.REPAIR
                emit(LinkEvent.Transmitted(s.sessionId, action.chunks, action.profile, kind))
            }
            SenderAction.Completed -> emit(LinkEvent.Delivered(s.sessionId, by))
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
                peer = s.transfer.options.station?.destination,
                ackedBy = ackedBy[s.sessionId]?.toList() ?: emptyList(),
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
                peer = peers[r.sessionId]?.source?.ifEmpty { null },
                preview = previewOf(t),
            )
        }
        _transfers.value = map
    }

    private fun previewOf(t: ChunkTracker): com.radiome.core.protocol.image.ImagePreview? {
        val h = t.header ?: return null
        if (h.contentType != ContentType.IMAGE_PROGRESSIVE || h.compressed) return null
        val wire = t.partialWire() ?: return null
        return ProgressiveImage.preview(wire, t::hasBytes)
    }

    private fun emit(e: LinkEvent) {
        _events.tryEmit(e)
    }

    private fun nowMs(): Long = clock * 1000 / fs

    private fun newSessionId(): Int {
        while (true) {
            val id = random.nextInt(1, 0xFFFF)
            if (id !in senders && id !in receivers && id !in ownTests && id !in probes) return id
        }
    }

    companion object {
        /** Session id carried by events about a test tone (no real session uses it). */
        const val TEST_TONE_SESSION = -1
        private const val BROADCAST_MARGIN_MS = 1500
    }
}
