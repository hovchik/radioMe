package com.radiome.feature.transceiver

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radiome.core.dsp.modem.ModemProfile
import com.radiome.core.protocol.image.ImagePipeline
import com.radiome.core.protocol.image.ImagePreset
import com.radiome.core.protocol.image.PreparedImage
import com.radiome.core.protocol.image.RasterImage
import com.radiome.core.protocol.link.LinkEvent
import com.radiome.core.protocol.payload.CompressionMode
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

    private val _linkTest = MutableStateFlow(LinkTestUi())
    val linkTest: StateFlow<LinkTestUi> = _linkTest.asStateFlow()

    /** Probe bursts to the station in the To field (or anyone, when it is blank); the result shows on the dashboard. */
    fun startLinkTest() = viewModelScope.launch {
        if (_linkTest.value.running) return@launch
        warnIfMuted()
        val sid = client.startLinkTest(_compose.value.to.ifBlank { null })
        if (sid == null) {
            _messages.trySend("Turn the radio on first.")
        } else {
            _linkTest.value = LinkTestUi(running = true, sessionId = sid)
        }
    }

    /** Make the link test's suggestion the default profile and parity, and use it in the composer. */
    fun applyLinkTestRecommendation() {
        val r = _linkTest.value.recommendation ?: return
        updateSettings { it.copy(defaultProfile = r.profile, parityPercent = r.parityPercent) }
        onProfileChange(r.profile)
        _messages.trySend("Now sending ${Presenters.recommendationLabel(r)} by default.")
    }

    fun dismissLinkTest() {
        _linkTest.value = LinkTestUi()
    }

    private fun onLinkEvent(e: LinkEvent) {
        when (e) {
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
                _linkTest.value = LinkTestUi(sessionId = e.sessionId, error = "No report came back. Is the other station listening?")
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
                _messages.trySend("Group key set. Fingerprint ${k.fingerprint}: compare it with the other stations.")
            } catch (e: IllegalArgumentException) {
                _messages.trySend("Use a passphrase of at least ${GroupKey.MIN_PASSPHRASE} characters.")
            } finally {
                _keyBusy.value = false
            }
        }
    }

    fun clearGroupKey() = viewModelScope.launch {
        keys.clear()
        client.setGroupKey(null)
        _messages.trySend("Group key removed. Positions can no longer be sent or read.")
    }

    fun sendPositionNow() = viewModelScope.launch {
        _messages.trySend(Presenters.beaconMessage(beacon.sendNow()))
    }

    fun clearPositions() = viewModelScope.launch { positionHistory.clear() }

    /** False (with a notice) when settings ask for encryption but no group key is set. */
    private fun encryptionReady(): Boolean {
        if (settings.value.encryptMessages && keys.key.value == null) {
            _messages.trySend("Encryption is on but no group key is set. Set one in Settings or turn encryption off.")
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
        if (!client.requestCorrections(sessionId)) _messages.trySend("Nothing to request: the transfer is complete or gone.")
    }

    fun cancel(sessionId: Int) = viewModelScope.launch { client.cancel(sessionId) }

    /** Plays the pilot tone so the user can hear the output and set the radio's VOX. */
    fun testTone() = viewModelScope.launch {
        if (client.outputStatus()?.muted == true) _messages.trySend(MUTED)
        if (!client.sendTestTone()) _messages.trySend("Turn the radio on first.")
    }

    private fun warnIfMuted() {
        if (client.outputStatus()?.muted == true) _messages.trySend(MUTED)
    }

    // ------------------------------------------------------------------------------------------ compose

    private val _compose = MutableStateFlow(ComposeUi(profile = settings.value.defaultProfile))
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
            if (client.send(Payload.text(text), settings.value.transferOptions(c.profile, c.to)) == null) {
                _messages.trySend("Turn the radio on first.")
            }
        }
    }

    private var profileChosen = false

    fun onProfileChange(profile: ModemProfile) {
        profileChosen = true
        _compose.update { it.copy(profile = profile, textEstimate = estimate(it.draft, profile)) }
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
            val sid = client.send(Payload.text(c.draft), settings.value.transferOptions(c.profile, c.to))
            if (sid == null) {
                _messages.trySend("Turn the radio on first.")
                _compose.update { it.copy(sending = false) }
            } else {
                _compose.update { it.copy(draft = "", textEstimate = null, sending = false) }
            }
        }
    }

    /** [uri] is the content:// URI the photo picker returned. */
    fun onImagePicked(uri: String) {
        loadJob?.cancel()
        encodeJob?.cancel()
        sourceImage = null
        prepared = null
        _compose.update { it.copy(image = ImageDraftUi(preset = settings.value.imagePreset(), busy = true)) }
        loadJob = viewModelScope.launch {
            val raster = runCatching { codec.load(uri) }.getOrElse {
                if (it is CancellationException) throw it
                _compose.update { c -> c.copy(image = c.image?.copy(busy = false, error = "Could not open that image.")) }
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
            _compose.update { it.copy(image = it.image?.copy(busy = false, error = "Could not encode the image.")) }
        }
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
            val sid = client.send(p.payload, options)
            _compose.update { it.copy(sending = false) }
            if (sid == null) _messages.trySend("Turn the radio on first.") else clearImage()
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
            if (!profileChosen) _compose.update { it.copy(profile = s.defaultProfile, textEstimate = estimate(it.draft, s.defaultProfile)) }
        }
    }

    private companion object {
        const val TICK_MS = 2_000L
        const val MUTED = "Media volume is muted, so the radio will hear nothing. Turn it up to about 70%."
    }
}
