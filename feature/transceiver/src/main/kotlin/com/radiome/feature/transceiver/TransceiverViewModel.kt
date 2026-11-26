package com.radiome.feature.transceiver

import com.radiome.feature.transceiver.i18n.tr
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radiome.core.dsp.modem.ModemProfile
import com.radiome.core.protocol.image.ImagePipeline
import com.radiome.core.protocol.image.ImagePreset
import com.radiome.core.protocol.image.PreparedImage
import com.radiome.core.protocol.image.RasterImage
import com.radiome.core.protocol.link.AdaptiveRate
import com.radiome.core.protocol.link.Direction
import com.radiome.core.protocol.link.LinkEvent
import com.radiome.core.protocol.session.Packetizer
import com.radiome.core.protocol.session.TransferOptions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.radiome.core.protocol.payload.CompressionMode
import com.radiome.core.protocol.payload.ContentType
import com.radiome.core.protocol.payload.Files
import com.radiome.core.protocol.session.SessionHeader
import com.radiome.feature.transceiver.share.HistoryExport
import com.radiome.feature.transceiver.voice.VoiceNotes
import com.radiome.core.protocol.payload.VoiceNote
import com.radiome.core.protocol.payload.Payload
import com.radiome.core.protocol.session.AirtimeEstimator
import com.radiome.data.TransferRecord
import com.radiome.feature.transceiver.image.ImageCodec
import com.radiome.feature.transceiver.link.TransceiverClient
import com.radiome.feature.transceiver.link.TransferHistory
import com.radiome.feature.transceiver.link.PositionBeacon
import com.radiome.feature.transceiver.link.PositionHistory
import com.radiome.feature.transceiver.settings.GroupKeyProvider
import com.radiome.core.protocol.crypto.GroupKey
import com.radiome.data.StationPosition
import com.radiome.feature.transceiver.settings.RadioSettings
import com.radiome.feature.transceiver.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class TransceiverViewModel @Inject constructor(
    private val client: TransceiverClient,
    private val history: TransferHistory,
    private val settingsRepository: SettingsRepository,
    private val codec: ImageCodec,
    private val positionHistory: PositionHistory,
    private val keys: GroupKeyProvider,
    private val beacon: PositionBeacon,
    private val voice: VoiceNotes,
) : ViewModel() {
    /** Where image work (resize, WebP encode) runs. Tests replace it. */
    internal var workDispatcher: CoroutineDispatcher = Dispatchers.Default
    internal var clock: () -> Long = System::currentTimeMillis

    private val airtime = AirtimeEstimator()
    private val pipeline = ImagePipeline(codec, airtime)

    val settings: StateFlow<RadioSettings> = settingsRepository.settings

    private val _messages = Channel<String>(Channel.BUFFERED)
    /** One-off notices for the snackbar. */
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val _restartPending = MutableStateFlow(false)
    /** Lead-in or ARQ settings changed while the radio was on: they apply after a link restart. */
    val restartPending: StateFlow<Boolean> = _restartPending.asStateFlow()

    // ------------------------------------------------------------------------------------------ dashboard

    /** Wall-clock time each finished transfer was first seen finished, to drop it from the dashboard later. */
    private val finishedSeen = HashMap<Int, Long>()

    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(clock())
            delay(TICK_MS)
        }
    }

    private val output = flow {
        while (true) {
            emit(client.outputStatus())
            delay(TICK_MS)
        }
    }

    /** Wall clock for "3 min ago" labels, ticking every couple of seconds while observed. */
    val now: StateFlow<Long> = ticker.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), System.currentTimeMillis())

    val radio: StateFlow<RadioUi> = combine(
        combine(client.running, client.linkState, client.route, ::Triple),
        client.rx,
        client.transfers,
        output,
        ticker,
    ) { (running, state, route), rx, transfers, out, now ->
        for (t in transfers.values) if (Presenters.isFinished(t)) finishedSeen.putIfAbsent(t.sessionId, now)
        finishedSeen.keys.retainAll(transfers.keys)
        val hidden = finishedSeen.filterValues { now - it >= Presenters.FINISHED_VISIBLE_MS }.keys
        Presenters.radio(running, state, route, rx, transfers, out, hidden)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RadioUi())

    val historyItems: StateFlow<List<TransferRecord>> =
        history.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _heard = MutableStateFlow<List<HeardStation>>(emptyList())
    /** Stations heard on the air (from their STATION frames), newest first. */
    val heardStations: StateFlow<List<HeardStation>> = _heard.asStateFlow()

    // ------------------------------------------------------------------------------------------ chat

    /** Messages that arrived before this screen was opened count as read. */
    private val chatOpenedAt = System.currentTimeMillis()
    private val chatRead = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** The chat list: "All stations", then one conversation per station. */
    val chatThreads: StateFlow<List<ChatThread>> = combine(historyItems, _heard, chatRead) { records, heard, read ->
        Chat.threads(records, heard, read, chatOpenedAt)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Open a conversation: what is composed from here on goes to [peer] (null = every station). */
    fun openChat(peer: String?) {
        onToChange(peer.orEmpty())
        markChatRead(peer)
    }

    fun markChatRead(peer: String?) {
        chatRead.update { it + (Chat.key(peer) to System.currentTimeMillis()) }
    }

    /** Send a failed or cancelled message again, to the same station, with the profile chosen now. */
    fun resend(r: TransferRecord) {
        val bytes = r.payload ?: return
        val type = r.contentType ?: return
        if (!encryptionReady()) return
        viewModelScope.launch {
            warnIfMuted()
            var options = settings.value.transferOptions(_compose.value.profile, r.peer)
            if (type != ContentType.TEXT && type != ContentType.BINARY) options = options.copy(compression = CompressionMode.NEVER)
            if (dispatch(Payload(type, bytes, r.name), options) == null) _messages.trySend(tr("Turn the radio on first."))
        }
    }

    private val _linkTest = MutableStateFlow(LinkTestUi())
    val linkTest: StateFlow<LinkTestUi> = _linkTest.asStateFlow()

    /** Probe bursts to the station in the To field (or anyone, when it is blank); the result shows on the dashboard. */
    fun startLinkTest() = viewModelScope.launch {
        if (_linkTest.value.running) return@launch
        warnIfMuted()
        val sid = client.startLinkTest(_compose.value.to.ifBlank { null })
        if (sid == null) {
            _messages.trySend(tr("Turn the radio on first."))
        } else {
            _linkTest.value = LinkTestUi(running = true, sessionId = sid)
        }
    }

    /** Make the link test's suggestion the default profile and parity, and use it in the composer. */
    fun applyLinkTestRecommendation() {
        val r = _linkTest.value.recommendation ?: return
        updateSettings { it.copy(defaultProfile = r.profile, parityPercent = r.parityPercent) }
        rate.reset(r.profile)
        onProfileChange(r.profile, keepAuto = true)
        _messages.trySend(tr("Now sending %s by default.", Presenters.recommendationLabel(r)))
    }

    fun dismissLinkTest() {
        _linkTest.value = LinkTestUi()
    }

    // ------------------------------------------------------------------------------------------ auto speed

    private val rate = AdaptiveRate(settings.value.defaultProfile)

    private class Tracked(val profile: ModemProfile, var firstLoss: Double? = null)

    /** Our outgoing sessions, to learn from how they went. */
    private val tracked = HashMap<Int, Tracked>()

    fun onAutoSpeed(on: Boolean) {
        _compose.update { it.copy(auto = on) }
        if (on) onProfileChange(rate.current, keepAuto = true)
        viewModelScope.launch { settingsRepository.update { it.copy(adaptiveSpeed = on) } }
    }

    private fun learn(sid: Int, failed: Boolean) {
        val t = tracked.remove(sid) ?: return
        val next = rate.onOutcome(t.profile, if (failed) 100.0 else t.firstLoss ?: 0.0, failed)
        if (_compose.value.auto && next != _compose.value.profile) {
            onProfileChange(next, keepAuto = true)
            _messages.trySend(tr("Auto speed: now %s.", com.radiome.feature.transceiver.ui.profileLabel(next)))
        }
    }

    /** Every send goes through here so Auto speed can follow the outcome. */
    private suspend fun dispatch(payload: Payload, options: TransferOptions): Int? {
        val sid = client.send(payload, options) ?: return null
        tracked[sid] = Tracked(options.profile)
        ended.update { it - sid } // session ids are reused
        return sid
    }

    // ------------------------------------------------------------------------------------------ queue

    private val _queue = MutableStateFlow<List<QueuedItem>>(emptyList())
    /** Messages waiting to go out one after another: the next starts when the previous one has finished. */
    val queue: StateFlow<List<QueuedItem>> = _queue.asStateFlow()
    private var queueIds = 0L
    /** Outgoing sessions that were delivered or failed (from link events). */
    private val ended = MutableStateFlow<Set<Int>>(emptySet())

    fun queueText() {
        val c = _compose.value
        if (c.draft.isBlank() || !encryptionReady()) return
        enqueue(c.draft.take(40), Payload.text(c.draft), settings.value.transferOptions(c.profile, c.to), c.auto)
        _compose.update { it.copy(draft = "", textEstimate = null) }
    }

    fun queueImage() {
        val p = prepared ?: return
        val c = _compose.value
        if (c.image?.busy == true || !encryptionReady()) return
        val options = settings.value.transferOptions(c.profile, c.to).copy(compression = CompressionMode.NEVER)
        enqueue("Image ${p.width}x${p.height}, ${Presenters.formatBytes(p.sizeBytes)}", p.payload, options, c.auto)
        clearImage()
    }

    fun removeQueued(id: Long) {
        _queue.update { q -> q.filterNot { it.id == id } }
    }

    private fun enqueue(label: String, payload: Payload, options: TransferOptions, auto: Boolean) {
        _queue.update { it + QueuedItem(++queueIds, label, payload, options, auto) }
        if (!client.running.value) _messages.trySend(tr("Queued. It goes out when the radio is on."))
    }

    private suspend fun runQueue() {
        while (true) {
            val next = _queue.first { it.isNotEmpty() }.first()
            client.running.first { it }
            // One at a time: wait until none of our transfers is still on air or waiting for its answer.
            client.transfers.first { m -> m.values.none { it.direction == Direction.OUTGOING && !Presenters.isFinished(it) } }
            if (_queue.value.firstOrNull()?.id != next.id) continue // removed while we waited
            val options = if (next.autoProfile) next.options.copy(profile = rate.current) else next.options
            // The group key may have been removed since this was queued: drop it with a notice rather than send it in clear.
            if (options.encrypt && keys.key.value == null) {
                _queue.update { q -> q.filterNot { it.id == next.id } }
                _messages.trySend(tr("Removed \"%s\" from the queue: it was to go encrypted, but no group key is set now.", next.label))
                continue
            }
            val sid = try {
                dispatch(next.payload, options)
            } catch (e: IllegalStateException) {
                _queue.update { q -> q.filterNot { it.id == next.id } }
                _messages.trySend(tr("Could not send \"%s\": %s", next.label, e.message ?: ""))
                continue
            } ?: continue // the radio went off; try again when it is back
            _queue.update { q -> q.filterNot { it.id == next.id } }
            withTimeoutOrNull(QUEUE_ITEM_TIMEOUT_MS) {
                // Done when it is delivered or failed, or when its link goes away (radio stopped or restarted), so the
                // rest of the queue does not wait for the timeout.
                var seen = false
                combine(client.transfers, ended, client.running) { m, done, running ->
                    val t = m[sid]
                    val gone = seen && t == null
                    if (t != null) seen = true
                    sid in done || t?.let(Presenters::isFinished) == true || gone || !running
                }.first { it }
            }
        }
    }

    private fun onLinkEvent(e: LinkEvent) {
        // Position beacons go out from PositionBeacon, not through dispatch(), so only sessions in `tracked` are
        // messages the user sent; their confirmations buzz, a beacon's do not.
        val noteworthy = (e is LinkEvent.MessageReceived && e.payload.type != ContentType.POSITION) ||
            (e is LinkEvent.Delivered && e.sessionId in tracked)
        if (noteworthy && settings.value.haptics) _buzz.trySend(Unit)
        when (e) {
            is LinkEvent.NackReceived -> tracked[e.sessionId]?.let { t ->
                if (t.firstLoss == null) {
                    val total = client.transfers.value[e.sessionId]?.totalChunks ?: 0
                    if (total > 0) t.firstLoss = e.requested.count { it != Packetizer.HEADER_CHUNK } * 100.0 / total
                }
            }
            is LinkEvent.Delivered -> {
                ended.update { it + e.sessionId }
                learn(e.sessionId, failed = false)
            }
            is LinkEvent.Failed -> {
                ended.update { it + e.sessionId }
                learn(e.sessionId, failed = true)
            }
            is LinkEvent.StationHeard -> _heard.update { Presenters.heard(it, e.callsign, clock(), e.snrDb) }
            is LinkEvent.LinkTestReported -> {
                val ours = _linkTest.value.sessionId == e.sessionId && !e.measuredHere
                // Somebody else's test that we measured shows too, unless our own test is still running.
                if (ours || (e.measuredHere && !_linkTest.value.running)) {
                    _linkTest.value = LinkTestUi(
                        sessionId = e.sessionId,
                        report = e.report,
                        recommendation = e.report.recommend(),
                        peer = e.peer,
                        measuredHere = e.measuredHere,
                    )
                }
            }
            is LinkEvent.LinkTestFailed -> if (_linkTest.value.sessionId == e.sessionId) {
                _linkTest.value = LinkTestUi(sessionId = e.sessionId, error = tr("No report came back. Is the other station listening?"))
            }
            else -> Unit
        }
    }

    // ------------------------------------------------------------------------------------------ map and encryption

    /** Latest decrypted fix per station, ours included. */
    val positions: StateFlow<List<StationPosition>> =
        positionHistory.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val groupKey: StateFlow<GroupKey?> = keys.key

    private val _keyBusy = MutableStateFlow(false)
    /** Deriving a key from a passphrase takes a moment. */
    val keyBusy: StateFlow<Boolean> = _keyBusy.asStateFlow()

    fun setGroupPassphrase(passphrase: String) {
        if (_keyBusy.value) return
        viewModelScope.launch {
            _keyBusy.value = true
            try {
                val k = keys.setPassphrase(passphrase)
                client.setGroupKey(k)
                _messages.trySend(tr("Group key set. Fingerprint %s: compare it with the other stations.", k.fingerprint))
            } catch (e: IllegalArgumentException) {
                _messages.trySend(tr("Use a passphrase of at least %d characters.", GroupKey.MIN_PASSPHRASE))
            } finally {
                _keyBusy.value = false
            }
        }
    }

    fun clearGroupKey() = viewModelScope.launch {
        keys.clear()
        client.setGroupKey(null)
        _messages.trySend(tr("Group key removed. Positions can no longer be sent or read."))
    }

    /** The location prompt was answered with a yes: let the running radio keep locating in the background, then send. */
    fun onLocationPermissionGranted() {
        client.onLocationGranted()
        sendPositionNow()
    }

    fun sendPositionNow() = viewModelScope.launch {
        _messages.trySend(Presenters.beaconMessage(beacon.sendNow()))
    }

    fun clearPositions() = viewModelScope.launch { positionHistory.clear() }

    /** False (with a notice) when settings ask for encryption but no group key is set. */
    private fun encryptionReady(): Boolean {
        if (settings.value.encryptMessages && keys.key.value == null) {
            _messages.trySend(tr("Encryption is on but no group key is set. Set one in Settings or turn encryption off."))
            return false
        }
        return true
    }

    fun startListening() = client.startListening()

    fun stopListening() {
        client.stopListening()
        _restartPending.value = false
    }

    fun requestCorrections(sessionId: Int) = viewModelScope.launch {
        if (!client.requestCorrections(sessionId)) _messages.trySend(tr("Nothing to request: the transfer is complete or gone."))
    }

    fun cancel(sessionId: Int) = viewModelScope.launch { client.cancel(sessionId) }

    /** Plays the pilot tone so the user can hear the output and set the radio's VOX. */
    fun testTone() = viewModelScope.launch {
        if (client.outputStatus()?.muted == true) _messages.trySend(MUTED)
        if (!client.sendTestTone()) _messages.trySend(tr("Turn the radio on first."))
    }

    private fun warnIfMuted() {
        if (client.outputStatus()?.muted == true) _messages.trySend(MUTED)
    }

    // ------------------------------------------------------------------------------------------ compose

    private val _compose = MutableStateFlow(ComposeUi(profile = settings.value.defaultProfile, voiceAvailable = voice.available))
    val compose: StateFlow<ComposeUi> = _compose.asStateFlow()

    private var sourceImage: RasterImage? = null
    private var prepared: PreparedImage? = null
    /** Decoding the picked file. Preset changes must not cancel it. */
    private var loadJob: Job? = null
    /** Resize + WebP encode with the current preset; restarted on every preset change. */
    private var encodeJob: Job? = null

    fun onDraftChange(text: String) {
        _compose.update { it.copy(draft = text, textEstimate = estimate(text, it.profile)) }
    }

    /** Addressee for texts, images and link tests. Blank sends to every station. */
    fun onToChange(to: String) {
        _compose.update { it.copy(to = to.uppercase().take(10)) }
    }

    /** One-tap preset: goes out right away with the chosen profile and addressee, leaving the draft alone. */
    fun sendQuickMessage(text: String) {
        if (text.isBlank() || !encryptionReady()) return
        viewModelScope.launch {
            warnIfMuted()
            val c = _compose.value
            if (dispatch(Payload.text(text), settings.value.transferOptions(c.profile, c.to)) == null) {
                _messages.trySend(tr("Turn the radio on first."))
            }
        }
    }

    private var profileChosen = false

    /** A manual choice turns Auto speed off; [keepAuto] is for Auto's own changes. */
    fun onProfileChange(profile: ModemProfile, keepAuto: Boolean = false) {
        profileChosen = true
        if (!keepAuto && _compose.value.auto) {
            viewModelScope.launch { settingsRepository.update { it.copy(adaptiveSpeed = false) } }
        }
        _compose.update { it.copy(profile = profile, auto = it.auto && keepAuto, textEstimate = estimate(it.draft, profile)) }
        voiceNote?.let { n ->
            _compose.update { it.copy(voice = it.voice?.copy(estimate = Presenters.bytesEstimate(n.encode(), voiceOptions(it), airtime))) }
        }
        if (sourceImage != null) prepareImage()
    }

    private fun estimate(text: String, profile: ModemProfile) =
        Presenters.textEstimate(text, settings.value.transferOptions(profile), airtime)

    fun sendText() {
        val c = _compose.value
        if (c.draft.isBlank() || c.sending || !encryptionReady()) return
        viewModelScope.launch {
            _compose.update { it.copy(sending = true) }
            warnIfMuted()
            val sid = dispatch(Payload.text(c.draft), settings.value.transferOptions(c.profile, c.to))
            if (sid == null) {
                _messages.trySend(tr("Turn the radio on first."))
                _compose.update { it.copy(sending = false) }
            } else {
                _compose.update { it.copy(draft = "", textEstimate = null, sending = false) }
            }
        }
    }

    /** [uri] is the content:// URI the photo picker returned. */
    fun onImagePicked(uri: String) {
        clearFile()
        clearVoice()
        loadJob?.cancel()
        encodeJob?.cancel()
        sourceImage = null
        prepared = null
        _compose.update { it.copy(image = ImageDraftUi(preset = settings.value.imagePreset(), busy = true)) }
        loadJob = viewModelScope.launch {
            val raster = runCatching { codec.load(uri) }.getOrElse {
                if (it is CancellationException) throw it
                _compose.update { c -> c.copy(image = c.image?.copy(busy = false, error = tr("Could not open that image."))) }
                return@launch
            }
            sourceImage = raster
            // Encode in its own job so a preset change from here on cancels only the encode.
            prepareImage()
        }
    }

    fun onImagePresetChange(preset: ImagePreset) {
        _compose.update { it.copy(image = it.image?.copy(preset = preset)) }
        viewModelScope.launch {
            settingsRepository.update {
                it.copy(
                    imageMaxEdge = preset.maxEdge,
                    imageQuality = preset.quality,
                    imageGrayscale = preset.grayscale,
                    imageProgressive = preset.progressive,
                )
            }
        }
        prepareImage()
    }

    private fun prepareImage() {
        // Still loading: the load finishes by encoding with whatever preset is current then.
        if (sourceImage == null) return
        encodeJob?.cancel()
        encodeJob = viewModelScope.launch { prepareNow() }
    }

    private suspend fun prepareNow() {
        val src = sourceImage ?: return
        val c = _compose.value
        val preset = c.image?.preset ?: settings.value.imagePreset()
        _compose.update { it.copy(image = it.image?.copy(busy = true, error = null)) }
        val result = runCatching {
            withContext(workDispatcher) { pipeline.prepare(src, preset, settings.value.transferOptions(c.profile)) }
        }
        result.onSuccess { p ->
            prepared = p
            _compose.update {
                it.copy(image = ImageDraftUi(p.payload.bytes, p.width, p.height, p.sizeBytes, p.airtimeMs, preset, busy = false))
            }
        }.onFailure { e ->
            if (e is CancellationException) throw e
            _compose.update { it.copy(image = it.image?.copy(busy = false, error = tr("Could not encode the image."))) }
        }
    }

    // ------------------------------------------------------------------------------------------ files

    private var fileBytes: ByteArray? = null

    /** A file the user picked; the UI reads it from the document picker. */
    fun attachFile(name: String, bytes: ByteArray) {
        if (bytes.isEmpty()) {
            _messages.trySend(tr("That file is empty."))
            return
        }
        if (bytes.size > Files.MAX_BYTES) {
            _messages.trySend(tr("Files up to %d KB can be sent. This one is %s.", Files.MAX_BYTES / 1024, Presenters.formatBytes(bytes.size)))
            return
        }
        clearImage()
        clearVoice()
        val options = fileOptions(_compose.value)
        val fitted = Files.fitName(name, options.chunkPayloadSize - SessionHeader.FIXED_SIZE)
        fileBytes = bytes
        _compose.update { it.copy(file = FileDraftUi(fitted, bytes.size, Presenters.bytesEstimate(bytes, options, airtime))) }
    }

    fun onFileTooLarge(sizeBytes: Long) {
        _messages.trySend(tr("Files up to %d KB can be sent. This one is %s.", Files.MAX_BYTES / 1024, Presenters.formatBytes(sizeBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())))
    }

    /** The whole history as plain text, for sharing or saving. */
    fun historyText(): String = HistoryExport.text(historyItems.value)

    val pttDevice: StateFlow<String?> get() = client.pttDevice

    /** Input spectrum for the waterfall; the link computes it only while this is collected. */
    val waterfall: Flow<FloatArray> get() = client.waterfall

    private val _buzz = Channel<Unit>(Channel.CONFLATED)
    /** A short vibration is due: a message arrived or was confirmed (when haptics are on). */
    val buzz: Flow<Unit> = _buzz.receiveAsFlow()

    fun notify(message: String) {
        _messages.trySend(message)
    }

    fun clearFile() {
        fileBytes = null
        _compose.update { it.copy(file = null) }
    }

    private fun fileOptions(c: ComposeUi) = settings.value.transferOptions(c.profile, c.to)

    private fun filePayload(): Payload? {
        val bytes = fileBytes ?: return null
        val f = _compose.value.file ?: return null
        return Payload(ContentType.BINARY, bytes, f.name)
    }

    fun sendFile() {
        val payload = filePayload() ?: return
        val c = _compose.value
        if (c.sending || !encryptionReady()) return
        viewModelScope.launch {
            _compose.update { it.copy(sending = true) }
            warnIfMuted()
            val sid = dispatch(payload, fileOptions(c))
            _compose.update { it.copy(sending = false) }
            if (sid == null) _messages.trySend(tr("Turn the radio on first.")) else clearFile()
        }
    }

    fun queueFile() {
        val payload = filePayload() ?: return
        val c = _compose.value
        if (!encryptionReady()) return
        enqueue("${payload.name}, ${Presenters.formatBytes(payload.bytes.size)}", payload, fileOptions(c), c.auto)
        clearFile()
    }

    // ------------------------------------------------------------------------------------------ voice notes

    private var voiceNote: VoiceNote? = null
    private var recordJob: Job? = null

    /** Starts the microphone. The UI asks for the permission first. */
    fun startVoiceRecording() {
        if (_compose.value.recording.active) return
        if (!voice.available) {
            _messages.trySend(tr("Voice notes are not available on this phone."))
            return
        }
        if (!voice.startRecording()) {
            _messages.trySend(tr("Allow the microphone to record a voice note."))
            return
        }
        clearImage()
        clearFile()
        clearVoice()
        _compose.update { it.copy(recording = RecordingUi(active = true)) }
        recordJob = viewModelScope.launch {
            while (voice.isRecording) {
                _compose.update { it.copy(recording = RecordingUi(active = true, elapsedMs = voice.elapsedMs)) }
                delay(RECORD_TICK_MS)
            }
            // The recorder stopped by itself at its time limit.
            if (_compose.value.recording.active) finishRecording()
        }
    }

    fun stopVoiceRecording() {
        if (!_compose.value.recording.active) return
        recordJob?.cancel()
        recordJob = null
        viewModelScope.launch { finishRecording() }
    }

    private suspend fun finishRecording() {
        val note = voice.stopRecording()
        _compose.update { it.copy(recording = RecordingUi()) }
        if (note == null || note.frames.isEmpty()) {
            _messages.trySend(tr("Too short. Tap the button, speak, then tap it again."))
            return
        }
        voiceNote = note
        _compose.update { it.copy(voice = VoiceDraftUi(note.durationMs(), Presenters.bytesEstimate(note.encode(), voiceOptions(it), airtime))) }
    }

    /** Codec2 frames do not compress, so deflate is skipped. */
    private fun voiceOptions(c: ComposeUi) = settings.value.transferOptions(c.profile, c.to).copy(compression = CompressionMode.NEVER)

    fun clearVoice() {
        voiceNote = null
        voice.stopPlayback()
        _compose.update { it.copy(voice = null) }
    }

    fun sendVoice() {
        val note = voiceNote ?: return
        val c = _compose.value
        if (c.sending || !encryptionReady()) return
        viewModelScope.launch {
            _compose.update { it.copy(sending = true) }
            warnIfMuted()
            val sid = dispatch(note.toPayload(), voiceOptions(c))
            _compose.update { it.copy(sending = false) }
            if (sid == null) _messages.trySend(tr("Turn the radio on first.")) else clearVoice()
        }
    }

    fun queueVoice() {
        val note = voiceNote ?: return
        val c = _compose.value
        if (!encryptionReady()) return
        enqueue(tr("Voice note %d s", seconds(note.durationMs())), note.toPayload(), voiceOptions(c), c.auto)
        clearVoice()
    }

    /** Plays the recorded draft. */
    fun playVoiceDraft() {
        val note = voiceNote ?: return
        viewModelScope.launch { voice.play(note) }
    }

    /** Plays a voice note from the history (sent or received). */
    fun playVoice(bytes: ByteArray) {
        val note = VoiceNote.decode(bytes)
        if (note == null) {
            _messages.trySend(tr("That voice note could not be read."))
            return
        }
        if (!voice.available) {
            _messages.trySend(tr("Voice notes are not available on this phone."))
            return
        }
        viewModelScope.launch { voice.play(note) }
    }

    fun stopVoicePlayback() = voice.stopPlayback()

    private fun seconds(ms: Long) = (ms + 500) / 1000

    override fun onCleared() {
        recordJob?.cancel()
        voice.stopPlayback()
        voice.cancelRecording()
    }

    fun clearImage() {
        loadJob?.cancel()
        encodeJob?.cancel()
        sourceImage = null
        prepared = null
        _compose.update { it.copy(image = null) }
    }

    fun sendImage() {
        val p = prepared ?: return
        val c = _compose.value
        if (c.sending || c.image?.busy == true || !encryptionReady()) return
        viewModelScope.launch {
            _compose.update { it.copy(sending = true) }
            val options = settings.value.transferOptions(c.profile, c.to).copy(compression = CompressionMode.NEVER)
            warnIfMuted()
            val sid = dispatch(p.payload, options)
            _compose.update { it.copy(sending = false) }
            if (sid == null) _messages.trySend(tr("Turn the radio on first.")) else clearImage()
        }
    }

    // ------------------------------------------------------------------------------------------ settings

    fun updateSettings(change: (RadioSettings) -> RadioSettings) {
        viewModelScope.launch {
            val old = settings.value
            val new = settingsRepository.update(change)
            client.applyLive(new.routePreference, new.outputGain)
            if (new.callsign != old.callsign) client.setCallsign(new.callsign)
            if (client.running.value && new.needsLinkRestart(old)) _restartPending.value = true
            _compose.update { it.copy(textEstimate = estimate(it.draft, it.profile)) }
        }
    }

    fun restartLink() {
        client.restartLink()
        _restartPending.value = false
    }

    fun deleteHistory(id: Long) = viewModelScope.launch { history.delete(id) }

    init {
        viewModelScope.launch {
            client.events.collect { e ->
                onLinkEvent(e)
                Presenters.eventMessage(e, settings.value.autoNack)?.let { _messages.trySend(it) }
            }
        }
        viewModelScope.launch {
            // Only fill in the stored default profile; never overwrite a choice the user made while the settings
            // file was still loading. (Image presets are read from settings when an image is picked.)
            val s = settingsRepository.load()
            rate.reset(s.defaultProfile)
            if (!profileChosen) {
                _compose.update { it.copy(profile = s.defaultProfile, auto = s.adaptiveSpeed, textEstimate = estimate(it.draft, s.defaultProfile)) }
            }
        }
        viewModelScope.launch { runQueue() }
    }

    private companion object {
        const val TICK_MS = 2_000L
        const val RECORD_TICK_MS = 250L
        const val QUEUE_ITEM_TIMEOUT_MS = 30 * 60_000L
        const val MUTED = "Media volume is muted, so the radio will hear nothing. Turn it up to about 70%."
    }
}
