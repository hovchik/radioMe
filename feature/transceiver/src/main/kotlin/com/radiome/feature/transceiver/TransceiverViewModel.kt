package com.radiome.feature.transceiver

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radiome.core.audio.volumeAdvice
import com.radiome.core.dsp.modem.ModemProfile
import com.radiome.core.protocol.image.ImagePipeline
import com.radiome.core.protocol.image.ImagePreset
import com.radiome.core.protocol.image.PreparedImage
import com.radiome.core.protocol.image.RasterImage
import com.radiome.core.protocol.payload.CompressionMode
import com.radiome.core.protocol.payload.Payload
import com.radiome.core.protocol.session.AirtimeEstimator
import com.radiome.data.TransferRecord
import com.radiome.feature.transceiver.image.ImageCodec
import com.radiome.feature.transceiver.link.TransceiverClient
import com.radiome.feature.transceiver.link.TransferHistory
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

    private val volume = flow {
        while (true) {
            emit(client.outputVolumeFraction()?.let(::volumeAdvice))
            delay(TICK_MS)
        }
    }

    val radio: StateFlow<RadioUi> = combine(
        combine(client.running, client.linkState, client.route, ::Triple),
        client.rx,
        client.transfers,
        volume,
        ticker,
    ) { (running, state, route), rx, transfers, vol, now ->
        for (t in transfers.values) if (Presenters.isFinished(t)) finishedSeen.putIfAbsent(t.sessionId, now)
        finishedSeen.keys.retainAll(transfers.keys)
        val hidden = finishedSeen.filterValues { now - it >= Presenters.FINISHED_VISIBLE_MS }.keys
        Presenters.radio(running, state, route, rx, transfers, vol, hidden)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RadioUi())

    val historyItems: StateFlow<List<TransferRecord>> =
        history.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun startListening() = client.startListening()

    fun stopListening() {
        client.stopListening()
        _restartPending.value = false
    }

    fun requestCorrections(sessionId: Int) = viewModelScope.launch {
        if (!client.requestCorrections(sessionId)) _messages.trySend("Nothing to request: the transfer is complete or gone.")
    }

    fun cancel(sessionId: Int) = viewModelScope.launch { client.cancel(sessionId) }

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

    fun onProfileChange(profile: ModemProfile) {
        _compose.update { it.copy(profile = profile, textEstimate = estimate(it.draft, profile)) }
        if (sourceImage != null) prepareImage()
    }

    private fun estimate(text: String, profile: ModemProfile) =
        Presenters.textEstimate(text, settings.value.transferOptions(profile), airtime)

    fun sendText() {
        val c = _compose.value
        if (c.draft.isBlank() || c.sending) return
        viewModelScope.launch {
            _compose.update { it.copy(sending = true) }
            val sid = client.send(Payload.text(c.draft), settings.value.transferOptions(c.profile))
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
            settingsRepository.update { it.copy(imageMaxEdge = preset.maxEdge, imageQuality = preset.quality, imageGrayscale = preset.grayscale) }
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
        if (c.sending || c.image?.busy == true) return
        viewModelScope.launch {
            _compose.update { it.copy(sending = true) }
            val options = settings.value.transferOptions(c.profile).copy(compression = CompressionMode.NEVER)
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
                Presenters.eventMessage(e, settings.value.autoNack)?.let { _messages.trySend(it) }
            }
        }
        viewModelScope.launch {
            val s = settingsRepository.load()
            _compose.update { it.copy(profile = s.defaultProfile, image = it.image?.copy(preset = s.imagePreset())) }
        }
    }

    private companion object {
        const val TICK_MS = 2_000L
    }
}
