package com.radiome.feature.transceiver

import com.radiome.core.audio.RoutePreference
import com.radiome.core.dsp.modem.DemodTelemetry
import com.radiome.core.protocol.image.RasterImage
import com.radiome.core.protocol.link.LinkEvent
import com.radiome.core.protocol.link.LinkState
import com.radiome.core.protocol.link.TransferTelemetry
import com.radiome.core.protocol.payload.Payload
import com.radiome.core.protocol.session.TransferOptions
import com.radiome.data.TransferRecord
import com.radiome.data.StationPosition
import com.radiome.core.protocol.crypto.GroupKey
import com.radiome.core.protocol.payload.Position
import com.radiome.feature.transceiver.link.PositionHistory
import com.radiome.feature.transceiver.location.LocationSource
import com.radiome.feature.transceiver.settings.GroupKeyProvider
import com.radiome.feature.transceiver.image.ImageCodec
import com.radiome.feature.transceiver.link.OutputStatus
import com.radiome.feature.transceiver.link.RouteInfo
import com.radiome.feature.transceiver.link.TransceiverClient
import com.radiome.feature.transceiver.link.TransferHistory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeClient : TransceiverClient {
    override val running = MutableStateFlow(false)
    override val linkState = MutableStateFlow(LinkState.LISTENING)
    override val rx = MutableStateFlow(DemodTelemetry())
    override val transfers = MutableStateFlow<Map<Int, TransferTelemetry>>(emptyMap())
    override val events = MutableSharedFlow<LinkEvent>(extraBufferCapacity = 16)
    override val route: StateFlow<RouteInfo?> = MutableStateFlow(null)

    val sent = mutableListOf<Pair<Payload, TransferOptions>>()
    var nextSid = 0x1234
    var restarts = 0
    var live: Pair<RoutePreference, Float>? = null

    override fun startListening() { running.value = true }
    override fun stopListening() { running.value = false }
    override suspend fun send(payload: Payload, options: TransferOptions): Int? {
        if (!running.value) return null
        sent += payload to options
        return nextSid++
    }
    override suspend fun requestCorrections(sessionId: Int) = false
    override suspend fun cancel(sessionId: Int) = Unit
    override fun applyLive(route: RoutePreference, outputGain: Float) { live = route to outputGain }
    override fun restartLink() { restarts++ }
    var lastCallsign = ""
    var linkTests = 0
    override fun setCallsign(callsign: String) { lastCallsign = callsign }
    var lastGroupKey: GroupKey? = null
    override fun setGroupKey(key: GroupKey?) { lastGroupKey = key }
    override suspend fun startLinkTest(to: String?): Int? {
        if (!running.value) return null
        linkTests++
        return 0x4321
    }
    var muted = false
    var testTones = 0
    override suspend fun sendTestTone(): Boolean {
        if (!running.value) return false
        testTones++
        return true
    }
    override fun outputStatus(): OutputStatus? =
        if (running.value) OutputStatus(if (muted) 0f else 0.7f, muted, "Speaker") else null
}

class FakeHistory : TransferHistory {
    val items = MutableStateFlow<List<TransferRecord>>(emptyList())
    override fun observe(): Flow<List<TransferRecord>> = items
    override suspend fun delete(id: Long) { items.value = items.value.filterNot { it.id == id } }
}

/** "Encodes" to a byte count that grows with pixels and quality, like a real codec would. */
class FakeCodec(private val width: Int = 640, private val height: Int = 480) : ImageCodec {
    var encodes = 0
    /** When set, load() waits for it, to simulate a slow decode. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun load(uri: String, maxEdge: Int): RasterImage {
        gate?.await()
        return RasterImage(width, height, IntArray(width * height) { 0xFF336699.toInt() })
    }

    override fun encodeWebp(image: RasterImage, quality: Int): ByteArray {
        encodes++
        return ByteArray(maxOf(16, image.width * image.height * quality / 1000)) { 7 }
    }
}

class FakeKeys : GroupKeyProvider {
    override val key = MutableStateFlow<GroupKey?>(null)
    override suspend fun setPassphrase(passphrase: String): GroupKey =
        GroupKey.fromPassphrase(passphrase, iterations = 1000).also { key.value = it }
    override suspend fun clear() { key.value = null }
    override fun current(): GroupKey? = key.value
}

class FakeLocation : LocationSource {
    var permitted = true
    var fix: Position? = Position(40.1776, 44.5126, accuracyM = 5, timeSec = 1_795_000_000)
    override fun hasPermission() = permitted
    override suspend fun current(): Position? = if (permitted) fix else null
}

class FakePositions : PositionHistory {
    val items = MutableStateFlow<List<StationPosition>>(emptyList())
    override fun observe(): Flow<List<StationPosition>> = items
    override suspend fun clear() { items.value = emptyList() }
}

fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        check(System.currentTimeMillis() < end) { "condition not met within $timeoutMs ms" }
        Thread.sleep(5)
    }
}
