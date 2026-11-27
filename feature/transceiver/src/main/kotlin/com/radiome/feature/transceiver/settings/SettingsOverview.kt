package com.radiome.feature.transceiver.settings

import com.radiome.core.audio.PttMode
import com.radiome.core.audio.RouteMode
import com.radiome.core.audio.RoutePreference
import com.radiome.feature.transceiver.link.RouteInfo

/** Sections of the Settings screen, in the order they appear. The overview links to them. */
enum class SettingsSection { STATION, CONNECTION, PERMISSIONS, LOCATION, SECURITY, MESSAGES, DISPLAY, ADVANCED, ABOUT }

/** How a part of the setup is doing, shown as a coloured dot. */
enum class Health {
    /** Ready to use. */
    OK,

    /** Works, but something is missing or worth checking. */
    ATTENTION,

    /** Blocks the radio from working. */
    PROBLEM,

    /** Nothing to judge yet, e.g. the radio is off. */
    IDLE,
}

/**
 * One line of the overview card. [title] and [detail] are English keys for `tr()`; [args] fill the detail's
 * placeholders so the translation tables stay keyed by the English template.
 */
data class OverviewItem(
    val section: SettingsSection,
    val health: Health,
    val title: String,
    val detail: String,
    val args: List<Any> = emptyList(),
)

/** Live radio state the Settings screen shows next to the connection options. */
data class SettingsRadioStatus(
    val running: Boolean = false,
    val route: RouteInfo? = null,
    /** Lead-in, ARQ or PTT settings changed while the radio was on. */
    val restartPending: Boolean = false,
    /** USB adapter keying PTT, when one is in use. */
    val pttDevice: String? = null,
)

/**
 * The four things that decide whether the radio works: who we are, how the radio is connected, the group key and
 * the permissions. Pure, so it is unit tested; the screen only paints it.
 */
fun settingsOverview(
    settings: RadioSettings,
    keyFingerprint: String?,
    radio: SettingsRadioStatus,
    permissions: List<PermissionRow>,
): List<OverviewItem> = listOf(
    stationItem(settings),
    connectionItem(settings, radio),
    securityItem(keyFingerprint, settings),
    permissionsItem(permissions),
)

/** The worst health among [items], ignoring idle ones; OK when nothing needs looking at. */
fun overallHealth(items: List<OverviewItem>): Health = when {
    items.any { it.health == Health.PROBLEM } -> Health.PROBLEM
    items.any { it.health == Health.ATTENTION } -> Health.ATTENTION
    else -> Health.OK
}

/** How many overview lines need the user: attention or problem. */
fun needsAttention(items: List<OverviewItem>): Int = items.count { it.health == Health.ATTENTION || it.health == Health.PROBLEM }

private fun stationItem(s: RadioSettings): OverviewItem =
    if (s.callsign.isBlank()) {
        OverviewItem(SettingsSection.STATION, Health.ATTENTION, "Callsign", "Not set. Other stations can't address you.")
    } else {
        OverviewItem(SettingsSection.STATION, Health.OK, "Callsign", "%s", listOf(s.callsign))
    }

private fun connectionItem(s: RadioSettings, radio: SettingsRadioStatus): OverviewItem {
    val section = SettingsSection.CONNECTION
    val route = radio.route
    val serial = s.pttMode == PttMode.SERIAL_RTS || s.pttMode == PttMode.SERIAL_DTR
    return when {
        !radio.running -> OverviewItem(section, Health.IDLE, "Radio connection", "%s · radio off", listOf(routeName(s.routePreference)))
        route == null -> OverviewItem(section, Health.IDLE, "Radio connection", "Starting...")
        route.pttMissing || (serial && radio.pttDevice == null) ->
            OverviewItem(section, Health.PROBLEM, "Radio connection", "USB PTT adapter not found")
        route.cableMissing -> OverviewItem(section, Health.ATTENTION, "Radio connection", "Cable not found, using speaker and mic")
        route.mode == RouteMode.CABLE -> OverviewItem(section, Health.OK, "Radio connection", "Cable · %s", listOf(route.output))
        else -> OverviewItem(section, Health.OK, "Radio connection", "Speaker and mic")
    }
}

private fun securityItem(fingerprint: String?, s: RadioSettings): OverviewItem = when {
    fingerprint == null && s.beaconIntervalMin > 0 ->
        OverviewItem(SettingsSection.SECURITY, Health.PROBLEM, "Group key", "Not set, so automatic positions can't be sent")
    fingerprint == null -> OverviewItem(SettingsSection.SECURITY, Health.ATTENTION, "Group key", "Not set. Positions can't be sent or read.")
    else -> OverviewItem(SettingsSection.SECURITY, Health.OK, "Group key", "Fingerprint %s", listOf(fingerprint))
}

private fun permissionsItem(rows: List<PermissionRow>): OverviewItem {
    val section = SettingsSection.PERMISSIONS
    fun denied(p: AppPermission) = rows.any { it.permission == p && it.status == PermissionStatus.DENIED }
    val missing = rows.count { it.status == PermissionStatus.DENIED }
    return when {
        denied(AppPermission.MICROPHONE) -> OverviewItem(section, Health.PROBLEM, "Permissions", "Microphone not allowed, the radio can't listen")
        missing == 1 -> OverviewItem(section, Health.ATTENTION, "Permissions", "1 not allowed")
        missing > 1 -> OverviewItem(section, Health.ATTENTION, "Permissions", "%d not allowed", listOf(missing))
        else -> OverviewItem(section, Health.OK, "Permissions", "All allowed")
    }
}

/** English key for an audio route choice. */
fun routeName(p: RoutePreference): String = when (p) {
    RoutePreference.AUTO -> "Auto"
    RoutePreference.ACOUSTIC -> "Speaker + mic"
    RoutePreference.CABLE -> "Cable"
}

/**
 * Settings back at their defaults, keeping what identifies this station: the callsign and the quick messages.
 * The group key lives in the keystore, not here, so it is kept as well.
 */
fun RadioSettings.restoredDefaults(): RadioSettings = RadioSettings(callsign = callsign, quickMessages = quickMessages)

/** The tuning settings under "Advanced" back at their defaults, everything else unchanged. */
fun RadioSettings.advancedDefaults(): RadioSettings {
    val d = RadioSettings()
    return copy(
        parityPercent = d.parityPercent,
        interleaveDepth = d.interleaveDepth,
        chunkPayloadSize = d.chunkPayloadSize,
        autoNack = d.autoNack,
        stepDownToRobust = d.stepDownToRobust,
        turnaroundMs = d.turnaroundMs,
    )
}

/** True when any setting under "Advanced" differs from its default. */
val RadioSettings.advancedChanged: Boolean get() = advancedDefaults() != this
