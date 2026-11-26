package com.radiome.feature.transceiver.link

import com.radiome.core.protocol.payload.Position
import com.radiome.core.protocol.session.TransferOptions
import com.radiome.feature.transceiver.location.LocationSource
import com.radiome.feature.transceiver.settings.GroupKeyProvider
import com.radiome.feature.transceiver.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Sends this phone's GPS position, always sealed with the group key, on demand and every
 * [com.radiome.feature.transceiver.settings.RadioSettings.beaconIntervalMin] minutes while the radio is on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PositionBeacon(
    private val client: TransceiverClient,
    private val settings: SettingsRepository,
    private val keys: GroupKeyProvider,
    private val location: LocationSource,
    private val scope: CoroutineScope,
) {
    sealed interface Result {
        data class Sent(val position: Position, val sessionId: Int) : Result
        data object NoKey : Result
        data object NoPermission : Result
        data object NoFix : Result
        data object RadioOff : Result
    }

    private val _last = MutableStateFlow<Result?>(null)
    /** Outcome of the latest attempt, manual or periodic. */
    val last: StateFlow<Result?> = _last.asStateFlow()

    private var job: Job? = null

    /** Start the periodic beacon (idempotent). */
    fun start() {
        if (job != null) return
        job = scope.launch {
            combine(client.running, settings.settings) { on, s -> if (on) s.beaconIntervalMin else 0 }
                .distinctUntilChanged()
                .collectLatest { minutes ->
                    if (minutes <= 0) return@collectLatest
                    while (true) {
                        sendNow()
                        delay(minutes * 60_000L)
                    }
                }
        }
    }

    suspend fun sendNow(): Result {
        val r = attempt()
        _last.value = r
        return r
    }

    private suspend fun attempt(): Result {
        if (keys.key.value == null) return Result.NoKey
        if (!client.running.value) return Result.RadioOff
        if (!location.hasPermission()) return Result.NoPermission
        val fix = location.current() ?: return Result.NoFix
        val s = settings.settings.value
        // 64-byte chunks: the sealed fix (63 bytes) is a single DATA frame.
        val options = s.transferOptions(s.beaconProfile).copy(chunkPayloadSize = TransferOptions.MAX_CHUNK, encrypt = true)
        val sid = client.send(fix.toPayload(), options) ?: return Result.RadioOff
        return Result.Sent(fix, sid)
    }
}
