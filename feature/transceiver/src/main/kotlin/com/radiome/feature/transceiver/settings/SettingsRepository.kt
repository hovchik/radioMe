package com.radiome.feature.transceiver.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException

/** [RadioSettings] in a Preferences DataStore. */
class SettingsRepository(
    private val store: DataStore<Preferences>,
    scope: CoroutineScope,
) {
    val settingsFlow: Flow<RadioSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }

    /** Latest settings; starts at the defaults until the file has been read. */
    val settings: StateFlow<RadioSettings> = settingsFlow.stateIn(scope, SharingStarted.Eagerly, RadioSettings())

    suspend fun load(): RadioSettings = settingsFlow.first()

    /**
     * For callers that must answer synchronously (the service asks for its dependencies on the main thread). Reads
     * the file only if the eager load has not finished yet, which is a few milliseconds after process start.
     */
    fun current(): RadioSettings = if (loaded) settings.value else runBlocking { load() }.also { loaded = true }

    @Volatile private var loaded = false

    init {
        scope.launch { settingsFlow.first(); loaded = true }
    }

    suspend fun update(change: (RadioSettings) -> RadioSettings): RadioSettings {
        var result = RadioSettings()
        store.edit { prefs ->
            result = change(prefs.toSettings()).sanitized()
            prefs.write(result)
        }
        return result
    }

    private fun Preferences.toSettings(): RadioSettings {
        val d = RadioSettings()
        return RadioSettings(
            leadInMs = this[LEAD_IN] ?: d.leadInMs,
            defaultProfile = enumOr(this[PROFILE], d.defaultProfile),
            parityPercent = this[PARITY]?.takeIf { it > 0 },
            interleaveDepth = this[INTERLEAVE]?.takeIf { it > 0 },
            chunkPayloadSize = this[CHUNK] ?: d.chunkPayloadSize,
            autoNack = this[AUTO_NACK] ?: d.autoNack,
            sendAck = this[SEND_ACK] ?: d.sendAck,
            stepDownToRobust = this[STEP_DOWN] ?: d.stepDownToRobust,
            turnaroundMs = this[TURNAROUND] ?: d.turnaroundMs,
            routePreference = enumOr(this[ROUTE], d.routePreference),
            outputGainPercent = this[GAIN] ?: d.outputGainPercent,
            imageMaxEdge = enumOr(this[IMAGE_EDGE], d.imageMaxEdge),
            imageQuality = this[IMAGE_QUALITY] ?: d.imageQuality,
            imageGrayscale = this[IMAGE_GRAY] ?: d.imageGrayscale,
            imageProgressive = this[IMAGE_PROGRESSIVE] ?: d.imageProgressive,
            callsign = this[CALLSIGN] ?: d.callsign,
            quickMessages = this[QUICK]?.split(QUICK_SEPARATOR) ?: d.quickMessages,
            encryptMessages = this[ENCRYPT] ?: d.encryptMessages,
            beaconIntervalMin = this[BEACON_MIN] ?: d.beaconIntervalMin,
            beaconProfile = enumOr(this[BEACON_PROFILE], d.beaconProfile),
            adaptiveSpeed = this[ADAPTIVE] ?: d.adaptiveSpeed,
            groupMode = this[GROUP_MODE] ?: d.groupMode,
            showWaterfall = this[WATERFALL] ?: d.showWaterfall,
            largeText = this[LARGE_TEXT] ?: d.largeText,
            haptics = this[HAPTICS] ?: d.haptics,
            pttMode = enumOr(this[PTT], d.pttMode),
        ).sanitized()
    }

    private fun MutablePreferences.write(s: RadioSettings) {
        this[LEAD_IN] = s.leadInMs
        this[PROFILE] = s.defaultProfile.name
        // 0 means "profile default".
        this[PARITY] = s.parityPercent ?: 0
        this[INTERLEAVE] = s.interleaveDepth ?: 0
        this[CHUNK] = s.chunkPayloadSize
        this[AUTO_NACK] = s.autoNack
        this[SEND_ACK] = s.sendAck
        this[STEP_DOWN] = s.stepDownToRobust
        this[TURNAROUND] = s.turnaroundMs
        this[ROUTE] = s.routePreference.name
        this[GAIN] = s.outputGainPercent
        this[IMAGE_EDGE] = s.imageMaxEdge.name
        this[IMAGE_QUALITY] = s.imageQuality
        this[IMAGE_GRAY] = s.imageGrayscale
        this[IMAGE_PROGRESSIVE] = s.imageProgressive
        this[CALLSIGN] = s.callsign
        this[QUICK] = s.quickMessages.joinToString(QUICK_SEPARATOR)
        this[ENCRYPT] = s.encryptMessages
        this[BEACON_MIN] = s.beaconIntervalMin
        this[BEACON_PROFILE] = s.beaconProfile.name
        this[ADAPTIVE] = s.adaptiveSpeed
        this[GROUP_MODE] = s.groupMode
        this[WATERFALL] = s.showWaterfall
        this[LARGE_TEXT] = s.largeText
        this[HAPTICS] = s.haptics
        this[PTT] = s.pttMode.name
    }

    private companion object {
        val LEAD_IN = intPreferencesKey("lead_in_ms")
        val PROFILE = stringPreferencesKey("default_profile")
        val PARITY = intPreferencesKey("parity_percent")
        val INTERLEAVE = intPreferencesKey("interleave_depth")
        val CHUNK = intPreferencesKey("chunk_payload")
        val AUTO_NACK = booleanPreferencesKey("auto_nack")
        val SEND_ACK = booleanPreferencesKey("send_ack")
        val STEP_DOWN = booleanPreferencesKey("step_down_robust")
        val TURNAROUND = intPreferencesKey("turnaround_ms")
        val ROUTE = stringPreferencesKey("route")
        val GAIN = intPreferencesKey("output_gain_percent")
        val IMAGE_EDGE = stringPreferencesKey("image_max_edge")
        val IMAGE_QUALITY = intPreferencesKey("image_quality")
        val IMAGE_GRAY = booleanPreferencesKey("image_grayscale")
        val IMAGE_PROGRESSIVE = booleanPreferencesKey("image_progressive")
        val CALLSIGN = stringPreferencesKey("callsign")
        val QUICK = stringPreferencesKey("quick_messages")
        val ENCRYPT = booleanPreferencesKey("encrypt_messages")
        val BEACON_MIN = intPreferencesKey("beacon_interval_min")
        val BEACON_PROFILE = stringPreferencesKey("beacon_profile")
        val ADAPTIVE = booleanPreferencesKey("adaptive_speed")
        val GROUP_MODE = booleanPreferencesKey("group_mode")
        val WATERFALL = booleanPreferencesKey("show_waterfall")
        val LARGE_TEXT = booleanPreferencesKey("large_text")
        val HAPTICS = booleanPreferencesKey("haptics")
        val PTT = stringPreferencesKey("ptt_mode")
        const val QUICK_SEPARATOR = "\n"

        inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
            name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
    }
}
