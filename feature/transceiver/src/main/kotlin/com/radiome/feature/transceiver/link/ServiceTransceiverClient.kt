package com.radiome.feature.transceiver.link

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.radiome.core.audio.RoutePreference
import com.radiome.core.audio.TransceiverService
import com.radiome.core.dsp.modem.DemodTelemetry
import com.radiome.core.protocol.crypto.GroupKey
import com.radiome.core.protocol.link.LinkController
import com.radiome.core.protocol.link.LinkEvent
import com.radiome.core.protocol.link.LinkState
import com.radiome.core.protocol.link.TransferTelemetry
import com.radiome.core.protocol.payload.Payload
import com.radiome.core.protocol.session.TransferOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Binds to [TransceiverService] for the life of the process. Binding keeps the service object around but does not
 * turn the radio on; [startListening] does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceTransceiverClient(
    context: Context,
    private val recording: LinkRecording,
    private val scope: CoroutineScope,
) : TransceiverClient {
    private val appContext = context.applicationContext
    private val service = MutableStateFlow<TransceiverService?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service.value = (binder as? TransceiverService.LocalBinder)?.service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service.value = null
        }
    }

    init {
        appContext.bindService(Intent(appContext, TransceiverService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    private val link: StateFlow<LinkController?> = service
        .flatMapLatest { it?.link ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private fun <T> fromLink(initial: T, pick: (LinkController) -> Flow<T>): StateFlow<T> = link
        .flatMapLatest { it?.let(pick) ?: flowOf(initial) }
        .stateIn(scope, SharingStarted.Eagerly, initial)

    override val running: StateFlow<Boolean> = link.map { it != null }.stateIn(scope, SharingStarted.Eagerly, false)
    override val linkState: StateFlow<LinkState> = fromLink(LinkState.LISTENING) { it.state }
    override val rx: StateFlow<DemodTelemetry> = fromLink(DemodTelemetry()) { it.rxTelemetry }
    override val transfers: StateFlow<Map<Int, TransferTelemetry>> = fromLink(emptyMap()) { it.transfers }
    override val events: Flow<LinkEvent> = link.flatMapLatest { it?.events ?: emptyFlow() }
    override val route: StateFlow<RouteInfo?> = service
        .flatMapLatest { it?.route ?: flowOf(null) }
        .map { r -> r?.let { RouteInfo(it.mode, it.inputName, it.outputName) } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    override fun startListening() = TransceiverService.start(appContext)

    override fun stopListening() {
        service.value?.stopRadio() ?: TransceiverService.stop(appContext)
    }

    override suspend fun send(payload: Payload, options: TransferOptions): Int? =
        service.value?.onLink { recording.send(it, payload, options) }

    override suspend fun requestCorrections(sessionId: Int): Boolean =
        service.value?.onLink { it.requestCorrections(sessionId) } ?: false

    override suspend fun cancel(sessionId: Int) {
        service.value?.onLink { it.cancel(sessionId) }
    }

    override fun applyLive(route: RoutePreference, outputGain: Float) {
        service.value?.let {
            it.setRoutePreference(route)
            it.setOutputGain(outputGain)
        }
    }

    override fun setCallsign(callsign: String) {
        service.value?.setCallsign(callsign)
    }

    override fun onLocationGranted() {
        service.value?.refreshForegroundTypes()
    }

    override fun setGroupKey(key: GroupKey?) {
        service.value?.setGroupKey(key?.encoded())
    }

    override suspend fun startLinkTest(to: String?): Int? = service.value?.onLink { it.startLinkTest(to) }

    override fun restartLink() {
        service.value?.restartLink()
    }

    override suspend fun sendTestTone(): Boolean = service.value?.onLink { it.sendTestTone() } != null

    override fun outputStatus(): OutputStatus? = service.value?.audioEngine?.let {
        OutputStatus(it.outputVolumeFraction(), it.isOutputMuted(), it.lastOutputDevice)
    }
}
