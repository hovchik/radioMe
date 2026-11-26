package com.radiome.feature.transceiver.link

import com.radiome.core.audio.RouteMode
import com.radiome.core.audio.RoutePreference
import com.radiome.core.dsp.modem.DemodTelemetry
import com.radiome.core.protocol.crypto.GroupKey
import com.radiome.core.protocol.link.LinkEvent
import com.radiome.core.protocol.link.LinkState
import com.radiome.core.protocol.link.TransferTelemetry
import com.radiome.core.protocol.payload.Payload
import com.radiome.core.protocol.session.TransferOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.StateFlow

/** Where audio goes right now, for the status bar. */
data class RouteInfo(val mode: RouteMode, val input: String, val output: String)

/** What the UI needs from the radio. Implemented over [com.radiome.core.audio.TransceiverService]; faked in tests. */
interface TransceiverClient {
    /** True while the link is up (the foreground service is listening). */
    val running: StateFlow<Boolean>
    val linkState: StateFlow<LinkState>
    val rx: StateFlow<DemodTelemetry>
    val transfers: StateFlow<Map<Int, TransferTelemetry>>
    val events: Flow<LinkEvent>
    val route: StateFlow<RouteInfo?>
    /** Input spectrum rows for the waterfall; computed only while someone collects this. */
    val waterfall: Flow<FloatArray> get() = emptyFlow()
    /** USB serial adapter keying PTT, or null. */
    val pttDevice: StateFlow<String?>

    /** Starts the foreground service. Call while the UI is visible and RECORD_AUDIO is granted. */
    fun startListening()
    fun stopListening()

    /** Queue a transfer and record it in the history. Null when the radio is off. */
    suspend fun send(payload: Payload, options: TransferOptions): Int?
    suspend fun requestCorrections(sessionId: Int): Boolean
    suspend fun cancel(sessionId: Int)

    /** Settings that apply without restarting the link. */
    fun applyLive(route: RoutePreference, outputGain: Float)
    /** Callsign for the STATION frame on everything the running link sends. */
    fun setCallsign(callsign: String)

    /** Group key for end-to-end encryption on the running link (null removes it). */
    fun setGroupKey(key: GroupKey?)

    /** Location access was just granted; the running service takes on the location foreground type. */
    fun onLocationGranted() {}

    /** Start a two-station link test (see [com.radiome.core.protocol.link.LinkTest]). Null when the radio is off. */
    suspend fun startLinkTest(to: String?): Int?
    /** Restart the link so lead-in and ARQ settings take effect. No-op when the radio is off. */
    fun restartLink()

    /** Queue a 2 s pilot tone through the normal transmit path. False when the radio is off. */
    suspend fun sendTestTone(): Boolean

    /** Output volume and device, or null when the radio is off. */
    fun outputStatus(): OutputStatus?
}

data class OutputStatus(
    /** Media volume as a fraction of maximum. */
    val volume: Float,
    /** Media volume is zero: transmissions are silent. */
    val muted: Boolean,
    /** Device the last transmission played through, once something has been sent. */
    val lastDevice: String?,
)
