package com.radiome.feature.transceiver.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.radiome.core.audio.PttMode
import com.radiome.core.audio.RouteMode
import com.radiome.core.audio.RoutePreference
import com.radiome.core.dsp.modem.ModemConfig
import com.radiome.core.dsp.modem.ModemProfile
import com.radiome.core.protocol.crypto.GroupKey
import com.radiome.core.protocol.link.Callsign
import com.radiome.core.protocol.session.TransferOptions
import com.radiome.feature.transceiver.i18n.tr
import com.radiome.feature.transceiver.settings.AppPermission
import com.radiome.feature.transceiver.settings.Health
import com.radiome.feature.transceiver.settings.OverviewItem
import com.radiome.feature.transceiver.settings.PermissionRow
import com.radiome.feature.transceiver.settings.PermissionStatus
import com.radiome.feature.transceiver.settings.RadioSettings
import com.radiome.feature.transceiver.settings.SettingsRadioStatus
import com.radiome.feature.transceiver.settings.SettingsSection
import com.radiome.feature.transceiver.settings.advancedChanged
import com.radiome.feature.transceiver.settings.advancedDefaults
import com.radiome.feature.transceiver.settings.needsAttention
import com.radiome.feature.transceiver.settings.overallHealth
import com.radiome.feature.transceiver.settings.restoredDefaults
import com.radiome.feature.transceiver.settings.routeName
import com.radiome.feature.transceiver.settings.settingsOverview
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** What the Settings screen can do beyond changing a setting. All optional so previews and tests stay short. */
data class SettingsActions(
    val onRestartLink: () -> Unit = {},
    val onTestTone: () -> Unit = {},
    /** Turns automatic speed on or off, live and stored. Null only changes the stored setting (tests). */
    val onAutoSpeed: ((Boolean) -> Unit)? = null,
)

/**
 * Settings, grouped by what the user is trying to do: an overview of what is set up and what is missing on top,
 * then one card per topic (station, radio connection, permissions, location, encryption, messages, display),
 * tuning options folded away under Advanced, and About at the bottom.
 */
@Composable
fun SettingsScreen(
    settings: RadioSettings,
    onChange: ((RadioSettings) -> RadioSettings) -> Unit,
    onBeforeRevokeMicrophone: () -> Unit,
    groupKeyFingerprint: String?,
    keyBusy: Boolean,
    onSetPassphrase: (String) -> Unit,
    onClearKey: () -> Unit,
    modifier: Modifier = Modifier,
    radio: SettingsRadioStatus = SettingsRadioStatus(),
    actions: SettingsActions = SettingsActions(),
    /** For tests and previews: fixed permission rows instead of asking the system. */
    permissionRowsOverride: List<PermissionRow>? = null,
) {
    val live = rememberPermissionsState()
    val permissions = permissionRowsOverride?.let { rows -> remember(rows) { PermissionsState(rows) {} } } ?: live
    val overview = settingsOverview(settings, groupKeyFingerprint, radio, permissions.rows)

    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val offsets = remember { mutableStateMapOf<SettingsSection, Int>() }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    fun jumpTo(section: SettingsSection) {
        if (section == SettingsSection.ADVANCED) advancedOpen = true
        offsets[section]?.let { y -> scope.launch { scroll.animateScrollTo(y) } }
    }
    fun Modifier.anchor(section: SettingsSection) = onGloballyPositioned { offsets[section] = it.positionInParent().y.roundToInt() }

    Column(
        modifier
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (radio.restartPending) RestartBanner(actions.onRestartLink)
        OverviewCard(overview, ::jumpTo)

        StationCard(settings, onChange, Modifier.anchor(SettingsSection.STATION))
        ConnectionCard(settings, onChange, radio, actions, Modifier.anchor(SettingsSection.CONNECTION))
        PermissionsCard(permissions, onBeforeRevokeMicrophone, Modifier.anchor(SettingsSection.PERMISSIONS))
        LocationCard(settings, onChange, groupKeyFingerprint, permissions.rows, ::jumpTo, Modifier.anchor(SettingsSection.LOCATION))
        SecurityCard(settings, onChange, groupKeyFingerprint, keyBusy, onSetPassphrase, onClearKey, Modifier.anchor(SettingsSection.SECURITY))
        MessagesCard(settings, onChange, actions.onAutoSpeed, Modifier.anchor(SettingsSection.MESSAGES))
        DisplayCard(settings, onChange, Modifier.anchor(SettingsSection.DISPLAY))
        AdvancedCard(settings, onChange, advancedOpen, { advancedOpen = it }, Modifier.anchor(SettingsSection.ADVANCED))
        AboutCard(onChange, Modifier.anchor(SettingsSection.ABOUT))
        Spacer(Modifier.size(8.dp))
    }
}

// ------------------------------------------------------------------------------------------------ overview

@Composable
private fun RestartBanner(onRestart: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(12.dp))
            Text(
                tr("Restart the radio to apply the new connection settings."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRestart) { Text(tr("Restart")) }
        }
    }
}

/** What is ready and what is missing, each line a shortcut to its card. */
@Composable
private fun OverviewCard(items: List<OverviewItem>, onOpen: (SettingsSection) -> Unit) {
    val health = overallHealth(items)
    val pending = needsAttention(items)
    val container = when (health) {
        Health.PROBLEM -> MaterialTheme.colorScheme.errorContainer
        Health.ATTENTION -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    Card(colors = CardDefaults.cardColors(containerColor = container), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (health == Health.OK) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = healthColor(health),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        when {
                            pending == 0 -> tr("Ready to go")
                            pending == 1 -> tr("1 thing needs your attention")
                            else -> tr("%d things need your attention", pending)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (pending == 0) tr("Station, connection, key and permissions are all set.") else tr("Tap a line to go to its setting."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            items.forEach { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button, onClickLabel = tr("Open")) { onOpen(item.section) }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(item.health)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tr(item.title), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            tr(item.detail, *item.args.map { if (it is String) tr(it) else it }.toTypedArray()),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ station

@Composable
private fun StationCard(settings: RadioSettings, onChange: ((RadioSettings) -> RadioSettings) -> Unit, modifier: Modifier) {
    SettingsCard(
        icon = Icons.Filled.Person,
        title = tr("Station"),
        summary = tr("Who you are on the air"),
        status = { if (settings.callsign.isBlank()) StatusPill(tr("No callsign"), Health.ATTENTION) },
        modifier = modifier,
    ) {
        var callsign by remember(settings.callsign) { mutableStateOf(settings.callsign) }
        val focus = LocalFocusManager.current
        val normalized = Callsign.normalize(callsign)
        OutlinedTextField(
            value = callsign,
            onValueChange = {
                callsign = it.uppercase()
                onChange { s -> s.copy(callsign = it) }
            },
            label = { Text(tr("Callsign")) },
            placeholder = { Text(tr("e.g. EK1ABC or TEAM/2")) },
            supportingText = {
                Text(
                    if (callsign.isNotBlank() && normalized != callsign.trim()) {
                        tr("Will be sent as %s. Letters, digits and /, up to 10.", normalized.ifEmpty { "-" })
                    } else {
                        tr("Sent in clear at the start of every burst, so others can address messages to you. Letters, digits and /, up to 10.")
                    },
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

// ------------------------------------------------------------------------------------------------ connection

@Composable
private fun ConnectionCard(
    settings: RadioSettings,
    onChange: ((RadioSettings) -> RadioSettings) -> Unit,
    radio: SettingsRadioStatus,
    actions: SettingsActions,
    modifier: Modifier,
) {
    val serial = settings.pttMode == PttMode.SERIAL_RTS || settings.pttMode == PttMode.SERIAL_DTR
    val route = radio.route
    SettingsCard(
        icon = Icons.Filled.Call,
        title = tr("Radio connection"),
        summary = tr("How the phone talks to your radio"),
        status = {
            when {
                !radio.running -> StatusPill(tr("Radio off"), Health.IDLE)
                route == null -> StatusPill(tr("Starting..."), Health.IDLE)
                route.pttMissing -> StatusPill(tr("No PTT adapter"), Health.PROBLEM)
                route.cableMissing -> StatusPill(tr("Cable not found"), Health.ATTENTION)
                route.mode == RouteMode.CABLE -> StatusPill(tr("Cable"), Health.OK)
                else -> StatusPill(tr("Acoustic"), Health.OK)
            }
        },
        modifier = modifier,
    ) {
        if (radio.running && route != null) {
            HelpText(tr("Now: in %1\$s · out %2\$s", route.input, route.output))
        }
        ChoiceRow(
            tr("Audio route"), RoutePreference.entries, settings.routePreference, { tr(routeName(it)) },
            help = when (settings.routePreference) {
                RoutePreference.AUTO -> tr("Uses a cable when one is plugged in, otherwise the speaker and mic.")
                RoutePreference.ACOUSTIC -> tr("Hold the phone next to the radio's speaker and mic. No cable needed.")
                RoutePreference.CABLE -> tr("Wired or USB-C audio cable to the radio's mic and speaker jacks.")
            },
        ) { v -> onChange { it.copy(routePreference = v) } }
        RowDivider()
        ChoiceRow(
            tr("Transmit keying (PTT)"), PttMode.entries, settings.pttMode, {
                when (it) {
                    PttMode.VOX -> tr("Radio's VOX")
                    PttMode.VOX_TONE -> tr("Tone on right channel")
                    PttMode.SERIAL_RTS -> tr("USB serial RTS")
                    PttMode.SERIAL_DTR -> tr("USB serial DTR")
                }
            },
            help = when (settings.pttMode) {
                PttMode.VOX -> tr("The lead-in tone opens the radio's VOX. Works with any cable or acoustically.")
                PttMode.VOX_TONE -> tr("For cables whose PTT circuit listens to the right audio channel. Data stays on the left.")
                PttMode.SERIAL_RTS, PttMode.SERIAL_DTR ->
                    tr(
                        "For Digirig and other cables with a USB serial chip (CP210x, FTDI, CH34x, CDC). Plug it in before " +
                            "turning the radio on and allow USB access when Android asks.",
                    )
            },
        ) { v -> onChange { it.copy(pttMode = v) } }
        if (serial) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                if (radio.pttDevice != null) {
                    StatusPill(tr("Adapter: %s", radio.pttDevice), Health.OK)
                } else {
                    StatusPill(tr("No adapter connected yet"), if (radio.running) Health.PROBLEM else Health.IDLE)
                }
            }
        }
        RowDivider()
        SliderRow(
            tr("VOX lead-in"), settings.leadInMs, ModemConfig.MIN_LEAD_IN_MS, ModemConfig.MAX_LEAD_IN_MS, 50, { "$it ms" },
            tr("Pilot tone before each burst so the radio's VOX and the far squelch open in time. Raise it if the start of messages is lost."),
            default = ModemConfig.DEFAULT_LEAD_IN_MS,
        ) { v -> onChange { it.copy(leadInMs = v) } }
        SliderRow(
            tr("Output gain"), settings.outputGainPercent, RadioSettings.MIN_GAIN, 100, 5, { "$it%" },
            tr("Lower it if the radio's transmit audio distorts over a cable."),
            default = RadioSettings().outputGainPercent,
        ) { v -> onChange { it.copy(outputGainPercent = v) } }
        RowDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(tr("Test tone"), style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (radio.running) tr("Plays the lead-in tone so you can set the radio's VOX and volume.") else tr("Turn the radio on to play a test tone."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = actions.onTestTone, enabled = radio.running) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr("Play"))
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ permissions

@Composable
private fun PermissionsCard(state: PermissionsState, onBeforeRevokeMicrophone: () -> Unit, modifier: Modifier) {
    val denied = state.rows.count { it.status == PermissionStatus.DENIED }
    val micDenied = state.rows.any { it.permission == AppPermission.MICROPHONE && it.status == PermissionStatus.DENIED }
    SettingsCard(
        icon = Icons.Filled.CheckCircle,
        title = tr("Permissions"),
        summary = tr("What RadioMe may use on this phone"),
        status = {
            when {
                micDenied -> StatusPill(tr("Microphone needed"), Health.PROBLEM)
                denied > 0 -> StatusPill(tr("%d not allowed", denied), Health.ATTENTION)
                else -> StatusPill(tr("All allowed"), Health.OK)
            }
        },
        modifier = modifier,
    ) {
        PermissionsSection(state, onBeforeRevokeMicrophone)
    }
}

// ------------------------------------------------------------------------------------------------ location

@Composable
private fun LocationCard(
    settings: RadioSettings,
    onChange: ((RadioSettings) -> RadioSettings) -> Unit,
    fingerprint: String?,
    permissions: List<PermissionRow>,
    onOpen: (SettingsSection) -> Unit,
    modifier: Modifier,
) {
    fun denied(p: AppPermission) = permissions.any { it.permission == p && it.status == PermissionStatus.DENIED }
    val blocker = when {
        fingerprint == null -> tr("Positions are always encrypted, so they need the group key.") to SettingsSection.SECURITY
        denied(AppPermission.LOCATION) -> tr("Allow location so RadioMe can find you.") to SettingsSection.PERMISSIONS
        denied(AppPermission.LOCATION_SERVICES) -> tr("Location services are off on this phone.") to SettingsSection.PERMISSIONS
        else -> null
    }
    SettingsCard(
        icon = Icons.Filled.LocationOn,
        title = tr("Location and map"),
        summary = tr("Sharing your position with the group"),
        status = {
            when {
                blocker != null -> StatusPill(tr("Not ready"), Health.ATTENTION)
                settings.beaconIntervalMin > 0 -> StatusPill(tr("Every %d min", settings.beaconIntervalMin), Health.OK)
                else -> StatusPill(tr("On request"), Health.IDLE)
            }
        },
        modifier = modifier,
    ) {
        blocker?.let { (text, target) ->
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(target) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = RadioColors.repaired, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(tr("Fix"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            RowDivider()
        }
        ChoiceRow(
            tr("Send my position automatically"), RadioSettings.BEACON_INTERVALS, settings.beaconIntervalMin,
            { if (it == 0) tr("Off") else tr("%d min", it) },
            help = if (settings.beaconIntervalMin == 0) {
                tr("Off: your position goes out only when you tap Send position on the map.")
            } else {
                tr("While the radio is on. Each beacon takes a few seconds of air time.")
            },
        ) { v -> onChange { it.copy(beaconIntervalMin = v) } }
        RowDivider()
        ChoiceRow(
            tr("Beacon speed"), ModemProfile.entries, settings.beaconProfile, ::profileLabel,
            help = tr("Positions are tiny, so Robust costs little and reaches furthest."),
        ) { v -> onChange { it.copy(beaconProfile = v) } }
        RowDivider()
        HelpText(tr("Map tiles download over the internet and stay on the phone for offline use. Manage them in Map, Offline maps."))
    }
}

// ------------------------------------------------------------------------------------------------ security

@Composable
private fun SecurityCard(
    settings: RadioSettings,
    onChange: ((RadioSettings) -> RadioSettings) -> Unit,
    fingerprint: String?,
    busy: Boolean,
    onSetPassphrase: (String) -> Unit,
    onClearKey: () -> Unit,
    modifier: Modifier,
) {
    var confirmRemove by remember { mutableStateOf(false) }
    SettingsCard(
        icon = Icons.Filled.Lock,
        title = tr("Group encryption"),
        summary = tr("A shared key that seals positions, and messages if you choose"),
        status = { if (fingerprint != null) StatusPill(tr("Key set"), Health.OK) else StatusPill(tr("No key"), Health.ATTENTION) },
        modifier = modifier,
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Row(Modifier.padding(12.dp)) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    tr("Encryption is not allowed on amateur radio bands. Use it only on licence-free (PMR446, FRS) or licensed " +
                        "private channels. The callsign is always sent in clear."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        if (fingerprint != null) {
            InfoRow(tr("Key fingerprint"), fingerprint, tr("Read it out to the others. Everyone in the group must see the same one."))
        } else {
            HelpText(tr("No group key. Positions can't be sent or read until you set one."), color = MaterialTheme.colorScheme.onSurface)
        }
        var pass by remember { mutableStateOf("") }
        var show by remember { mutableStateOf(false) }
        val focus = LocalFocusManager.current
        val canSet = !busy && pass.length >= GroupKey.MIN_PASSPHRASE
        fun submit() {
            if (!canSet) return
            onSetPassphrase(pass)
            pass = ""
            focus.clearFocus()
        }
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text(if (fingerprint == null) tr("Group passphrase") else tr("New group passphrase")) },
            supportingText = {
                Text(
                    if (pass.isNotEmpty() && pass.length < GroupKey.MIN_PASSPHRASE) {
                        tr("%d more characters needed", GroupKey.MIN_PASSPHRASE - pass.length)
                    } else {
                        tr("Everyone enters the same passphrase. It is not stored; the derived key is kept in the phone's hardware keystore.")
                    },
                )
            },
            singleLine = true,
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) tr("Hide") else tr("Show")) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = ::submit, enabled = canSet) {
                Text(if (busy) tr("Deriving key...") else if (fingerprint == null) tr("Set key") else tr("Replace key"))
            }
            if (fingerprint != null) OutlinedButton(onClick = { confirmRemove = true }, enabled = !busy) { Text(tr("Remove key")) }
        }
        Spacer(Modifier.size(4.dp))
        RowDivider()
        SwitchRow(
            tr("Encrypt messages and images too"), settings.encryptMessages,
            if (fingerprint == null) {
                tr("Set a group key first.")
            } else {
                tr("Positions are always encrypted. This also seals texts, images and files with the group key.")
            },
            enabled = fingerprint != null || settings.encryptMessages,
        ) { v -> onChange { it.copy(encryptMessages = v) } }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(tr("Remove the group key?")) },
            text = { Text(tr("You won't be able to read or send positions or encrypted messages until you enter the passphrase again.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    onClearKey()
                }) { Text(tr("Remove key")) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(tr("Cancel")) } },
        )
    }
}

// ------------------------------------------------------------------------------------------------ messages

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MessagesCard(
    settings: RadioSettings,
    onChange: ((RadioSettings) -> RadioSettings) -> Unit,
    onAutoSpeed: ((Boolean) -> Unit)?,
    modifier: Modifier,
) {
    SettingsCard(
        icon = Icons.Filled.Email,
        title = tr("Messages and images"),
        summary = tr("Speed, quick replies and delivery"),
        modifier = modifier,
    ) {
        ChoiceRow(
            tr("Default speed"), ModemProfile.entries, settings.defaultProfile, { "${profileLabel(it)} · ${"%.0f".format(it.rawBitsPerSecond)} bps" },
            help = tr("Robust gets through noise and long distance; Fast suits a clear, close channel. You can change it for each message."),
        ) { v -> onChange { it.copy(defaultProfile = v) } }
        SwitchRow(
            tr("Pick the speed automatically"), settings.adaptiveSpeed,
            tr("Chooses Fast, Standard or Robust from how the last transfers went."),
        ) { v -> onAutoSpeed?.invoke(v) ?: onChange { it.copy(adaptiveSpeed = v) } }
        RowDivider()

        GroupLabel(tr("Quick messages"))
        HelpText(tr("One-tap messages on the compose card. %1\$d of %2\$d.", settings.quickMessages.size, RadioSettings.MAX_QUICK_MESSAGES))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            settings.quickMessages.forEach { q ->
                InputChip(
                    selected = false,
                    onClick = { onChange { s -> s.copy(quickMessages = s.quickMessages - q) } },
                    label = { Text(q, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = { Icon(Icons.Filled.Close, contentDescription = tr("Remove %s", q), modifier = Modifier.size(InputChipDefaults.IconSize)) },
                )
            }
        }
        var draft by remember { mutableStateOf("") }
        val full = settings.quickMessages.size >= RadioSettings.MAX_QUICK_MESSAGES
        val focus = LocalFocusManager.current
        fun add() {
            val t = draft.trim()
            if (t.isEmpty() || full) return
            onChange { s -> s.copy(quickMessages = s.quickMessages + t) }
            draft = ""
            focus.clearFocus()
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(RadioSettings.MAX_QUICK_LENGTH) },
                label = { Text(if (full) tr("List is full") else tr("New quick message")) },
                enabled = !full,
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = ::add, enabled = draft.isNotBlank() && !full) { Text(tr("Add")) }
        }
        if (settings.quickMessages != RadioSettings.DEFAULT_QUICK_MESSAGES) {
            TextButton(
                onClick = { onChange { it.copy(quickMessages = RadioSettings.DEFAULT_QUICK_MESSAGES) } },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) { Text(tr("Restore default quick messages")) }
        }
        RowDivider()

        GroupLabel(tr("Delivery"))
        SwitchRow(
            tr("Images: preview first"), settings.imageProgressive,
            tr("A tiny copy goes out before the picture, so the receiver sees it early. Costs a few seconds of air time."),
        ) { v -> onChange { it.copy(imageProgressive = v) } }
        SwitchRow(
            tr("Confirm complete transfers"), settings.sendAck,
            tr("Tells the sender when a message arrived whole, so it shows as delivered."),
        ) { v -> onChange { it.copy(sendAck = v) } }
        SwitchRow(
            tr("Group mode for messages to all"), settings.groupMode,
            tr("Several stations listening: wait for everyone's correction requests, repair once, and collect each confirmation."),
        ) { v -> onChange { it.copy(groupMode = v) } }
    }
}

// ------------------------------------------------------------------------------------------------ display

@Composable
private fun DisplayCard(settings: RadioSettings, onChange: ((RadioSettings) -> RadioSettings) -> Unit, modifier: Modifier) {
    SettingsCard(icon = Icons.Filled.Star, title = tr("Display and feedback"), summary = tr("How the app looks and alerts you"), modifier = modifier) {
        SwitchRow(tr("Waterfall"), settings.showWaterfall, tr("Live spectrum of what the phone hears, on the Radio tab.")) { v ->
            onChange { it.copy(showWaterfall = v) }
        }
        SwitchRow(tr("Larger text"), settings.largeText, tr("Makes all text bigger, on top of the system font size.")) { v ->
            onChange { it.copy(largeText = v) }
        }
        SwitchRow(tr("Vibrate on messages"), settings.haptics, tr("A short buzz when a message arrives or is confirmed.")) { v ->
            onChange { it.copy(haptics = v) }
        }
    }
}

// ------------------------------------------------------------------------------------------------ advanced

@Composable
private fun AdvancedCard(
    settings: RadioSettings,
    onChange: ((RadioSettings) -> RadioSettings) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val profile = settings.defaultProfile
    SettingsCard(
        icon = Icons.Filled.Build,
        title = tr("Advanced"),
        summary = tr("Error correction and timing. The defaults suit most radios."),
        status = { if (settings.advancedChanged) StatusPill(tr("Customised"), Health.ATTENTION) },
        collapsible = true,
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier,
    ) {
        GroupLabel(tr("Error correction"))
        OptionalSliderRow(
            tr("Custom FEC parity"), settings.parityPercent, RadioSettings.MIN_PARITY, RadioSettings.MAX_PARITY, 1, { "$it%" },
            tr("Reed-Solomon parity as a share of data. Off uses %1\$s's default, %2\$d%%.", profileLabel(profile), (profile.defaultParityRatio * 100).roundToInt()),
            fallback = (profile.defaultParityRatio * 100).roundToInt(),
        ) { v -> onChange { it.copy(parityPercent = v) } }
        OptionalSliderRow(
            tr("Custom interleave depth"), settings.interleaveDepth, 1, RadioSettings.MAX_INTERLEAVE, 1, { tr("%d codewords", it) },
            tr("Spreads a burst of lost audio over many codewords. Off uses %1\$s's default, %2\$d.", profileLabel(profile), profile.defaultInterleaveDepth),
            fallback = profile.defaultInterleaveDepth,
        ) { v -> onChange { it.copy(interleaveDepth = v) } }
        SliderRow(
            tr("Chunk size"), settings.chunkPayloadSize, TransferOptions.MIN_CHUNK, TransferOptions.MAX_CHUNK, 4, { tr("%d bytes", it) },
            tr("Smaller chunks waste less when one is lost; larger ones have less overhead."),
            default = RadioSettings().chunkPayloadSize,
        ) { v -> onChange { it.copy(chunkPayloadSize = v) } }
        RowDivider()
        GroupLabel(tr("Corrections (ARQ)"))
        SwitchRow(
            tr("Request missing chunks automatically"), settings.autoNack,
            tr("Off: the dashboard shows a Send correction request button instead."),
        ) { v -> onChange { it.copy(autoNack = v) } }
        SwitchRow(
            tr("Send repairs in Robust"), settings.stepDownToRobust,
            tr("Resent chunks use the slowest, toughest speed so they get through."),
        ) { v -> onChange { it.copy(stepDownToRobust = v) } }
        SliderRow(
            tr("Turnaround"), settings.turnaroundMs, RadioSettings.MIN_TURNAROUND, RadioSettings.MAX_TURNAROUND, 100, { "$it ms" },
            tr("Quiet time before answering, so the other radio has stopped transmitting."),
            default = RadioSettings().turnaroundMs,
        ) { v -> onChange { it.copy(turnaroundMs = v) } }
        if (settings.advancedChanged) {
            TextButton(onClick = { onChange { it.advancedDefaults() } }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(tr("Restore advanced defaults"))
            }
        }
    }
}

@Composable
private fun OptionalSliderRow(
    title: String,
    value: Int?,
    min: Int,
    max: Int,
    step: Int,
    format: (Int) -> String,
    help: String,
    fallback: Int,
    onChange: (Int?) -> Unit,
) {
    SwitchRow(title, value != null, help) { on -> onChange(if (on) (value ?: fallback.coerceIn(min, max)) else null) }
    if (value != null) SliderRow(title, value, min, max, step, format, null) { onChange(it) }
}

// ------------------------------------------------------------------------------------------------ about

@Composable
private fun AboutCard(onChange: ((RadioSettings) -> RadioSettings) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val version = remember { appVersion(context) }
    var confirmReset by remember { mutableStateOf(false) }
    SettingsCard(icon = Icons.Filled.Info, title = tr("About"), modifier = modifier) {
        InfoRow(tr("Version"), version)
        InfoRow(tr("Air protocol"), tr("MFSK with Reed-Solomon FEC"))
        InfoRow(
            tr("Open-source parts"), null,
            tr("Codec2 voice codec (LGPL 2.1), osmdroid maps (Apache 2.0), map data © OpenStreetMap contributors."),
        )
        InfoRow(tr("Language"), null, tr("Follows the phone's language: English, Armenian or Russian."))
        RowDivider()
        InfoRow(
            tr("Restore default settings"), null,
            tr("Keeps your callsign, quick messages and group key."),
            onClick = { confirmReset = true },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(tr("Restore default settings?")) },
            text = { Text(tr("Connection, location, message, display and advanced settings go back to their defaults. Your callsign, quick messages and group key are kept.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onChange { it.restoredDefaults() }
                }) { Text(tr("Restore")) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(tr("Cancel")) } },
        )
    }
}

private fun appVersion(context: Context): String = runCatching {
    @Suppress("DEPRECATION")
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull() ?: "-"
