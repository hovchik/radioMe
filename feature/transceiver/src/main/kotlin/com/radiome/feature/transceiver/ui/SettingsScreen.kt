package com.radiome.feature.transceiver.ui

import com.radiome.feature.transceiver.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.radiome.core.protocol.crypto.GroupKey
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.radiome.core.audio.PttMode
import com.radiome.core.audio.RoutePreference
import com.radiome.core.dsp.modem.ModemConfig
import com.radiome.core.dsp.modem.ModemProfile
import com.radiome.core.protocol.session.TransferOptions
import com.radiome.feature.transceiver.settings.RadioSettings
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settings: RadioSettings,
    onChange: ((RadioSettings) -> RadioSettings) -> Unit,
    onBeforeRevokeMicrophone: () -> Unit,
    groupKeyFingerprint: String?,
    keyBusy: Boolean,
    onSetPassphrase: (String) -> Unit,
    onClearKey: () -> Unit,
    /** USB adapter keying PTT, when one is in use. */
    pttDevice: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section(tr("Permissions"))
        PermissionsSection(onBeforeRevokeMicrophone)

        Section(tr("Station"))
        StationSection(settings, onChange)

        Section(tr("Group encryption"))
        EncryptionSection(settings, onChange, groupKeyFingerprint, keyBusy, onSetPassphrase, onClearKey)

        Section(tr("Radio"))
        Choice(tr("Audio route"), RoutePreference.entries, settings.routePreference, {
            when (it) {
                RoutePreference.AUTO -> tr("Auto")
                RoutePreference.ACOUSTIC -> tr("Speaker + mic")
                RoutePreference.CABLE -> tr("Cable")
            }
        }) { v -> onChange { it.copy(routePreference = v) } }
        Choice(tr("Transmit keying (PTT)"), PttMode.entries, settings.pttMode, {
            when (it) {
                PttMode.VOX -> tr("Radio's VOX")
                PttMode.VOX_TONE -> tr("Tone on right channel")
                PttMode.SERIAL_RTS -> tr("USB serial RTS")
                PttMode.SERIAL_DTR -> tr("USB serial DTR")
            }
        }) { v -> onChange { it.copy(pttMode = v) } }
        Text(
            when (settings.pttMode) {
                PttMode.VOX -> tr("The lead-in tone opens the radio's VOX. Works with any cable or acoustically.")
                PttMode.VOX_TONE -> tr("For cables whose PTT circuit listens to the right audio channel. Data stays on the left.")
                PttMode.SERIAL_RTS, PttMode.SERIAL_DTR ->
                    tr(
                        "For Digirig and other cables with a USB serial chip (CP210x, FTDI, CH34x, CDC). Plug it in before " +
                            "turning the radio on and allow USB access when Android asks.",
                    ) + (pttDevice?.let { tr(" In use: %s.", it) } ?: tr(" No adapter connected yet."))
            },
            style = MaterialTheme.typography.bodySmall,
        )
        IntSlider(
            tr("VOX lead-in"), settings.leadInMs, ModemConfig.MIN_LEAD_IN_MS, ModemConfig.MAX_LEAD_IN_MS, 50, "ms",
            tr("Pilot tone before each burst so the radio's VOX and the far squelch open in time."),
        ) { v -> onChange { it.copy(leadInMs = v) } }
        IntSlider(tr("Output gain"), settings.outputGainPercent, RadioSettings.MIN_GAIN, 100, 5, "%", null) { v ->
            onChange { it.copy(outputGainPercent = v) }
        }

        Section(tr("Modem"))
        Choice(tr("Default profile"), ModemProfile.entries, settings.defaultProfile, ::profileLabel) { v ->
            onChange { it.copy(defaultProfile = v) }
        }
        OptionalIntSlider(
            tr("FEC parity"), settings.parityPercent, RadioSettings.MIN_PARITY, RadioSettings.MAX_PARITY, 1, "%",
            tr("Reed-Solomon parity as a share of data. Off uses the profile default (50 / 33 / 20%)."),
        ) { v -> onChange { it.copy(parityPercent = v) } }
        OptionalIntSlider(
            tr("Interleave depth"), settings.interleaveDepth, 1, RadioSettings.MAX_INTERLEAVE, 1, "codewords",
            tr("Spreads a burst of lost audio over many codewords. Off uses the profile default."),
        ) { v -> onChange { it.copy(interleaveDepth = v) } }
        IntSlider(
            tr("Chunk size"), settings.chunkPayloadSize, TransferOptions.MIN_CHUNK, TransferOptions.MAX_CHUNK, 4, "bytes",
            tr("Smaller chunks waste less when one is lost; larger ones have less overhead."),
        ) { v -> onChange { it.copy(chunkPayloadSize = v) } }

        Section(tr("Display and feedback"))
        Toggle(tr("Waterfall"), settings.showWaterfall, tr("Live spectrum of what the phone hears, on the Radio tab.")) { v ->
            onChange { it.copy(showWaterfall = v) }
        }
        Toggle(tr("Larger text"), settings.largeText, tr("Makes all text bigger, on top of the system font size.")) { v ->
            onChange { it.copy(largeText = v) }
        }
        Toggle(tr("Vibrate on messages"), settings.haptics, tr("A short buzz when a message arrives or is confirmed.")) { v ->
            onChange { it.copy(haptics = v) }
        }

        Section(tr("Corrections (ARQ)"))
        Toggle(tr("Request missing chunks automatically"), settings.autoNack,
            tr("Off: the dashboard shows a Send correction request button instead.")) { v -> onChange { it.copy(autoNack = v) } }
        Toggle(tr("Confirm complete transfers"), settings.sendAck, null) { v -> onChange { it.copy(sendAck = v) } }
        Toggle(tr("Send repairs in Robust"), settings.stepDownToRobust, null) { v -> onChange { it.copy(stepDownToRobust = v) } }
        Toggle(
            tr("Group mode for messages to all"), settings.groupMode,
            tr("Several stations listening: wait for everyone's correction requests, repair once, and collect each confirmation."),
        ) { v -> onChange { it.copy(groupMode = v) } }
        IntSlider(
            tr("Turnaround"), settings.turnaroundMs, RadioSettings.MIN_TURNAROUND, RadioSettings.MAX_TURNAROUND, 100, "ms",
            tr("Quiet time before answering, so the other radio has stopped transmitting."),
        ) { v -> onChange { it.copy(turnaroundMs = v) } }
    }
}

@Composable
private fun StationSection(settings: RadioSettings, onChange: ((RadioSettings) -> RadioSettings) -> Unit) {
    var callsign by remember(settings.callsign) { mutableStateOf(settings.callsign) }
    OutlinedTextField(
        value = callsign,
        onValueChange = {
            callsign = it.uppercase()
            onChange { s -> s.copy(callsign = it) }
        },
        label = { Text(tr("Callsign")) },
        supportingText = {
            Text(tr("Sent in clear at the start of every burst. Others can address messages to it. Letters, digits and /, up to 10."))
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    var quick by remember(settings.quickMessages) { mutableStateOf(settings.quickMessages.joinToString("\n")) }
    OutlinedTextField(
        value = quick,
        onValueChange = { quick = it },
        label = { Text(tr("Quick messages, one per line")) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 3,
    )
    val changed = quick.lines().map { it.trim() }.filter { it.isNotEmpty() } != settings.quickMessages
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onChange { it.copy(quickMessages = quick.lines()) } }, enabled = changed) { Text(tr("Save quick messages")) }
        OutlinedButton(onClick = { onChange { it.copy(quickMessages = RadioSettings.DEFAULT_QUICK_MESSAGES) } }) { Text(tr("Defaults")) }
    }
    Toggle(tr("Images: preview first"), settings.imageProgressive, tr("A tiny copy goes out before the picture, so the receiver sees it early. Costs a few seconds of air time.")) { v ->
        onChange { it.copy(imageProgressive = v) }
    }
}

@Composable
private fun EncryptionSection(
    settings: RadioSettings,
    onChange: ((RadioSettings) -> RadioSettings) -> Unit,
    fingerprint: String?,
    busy: Boolean,
    onSetPassphrase: (String) -> Unit,
    onClearKey: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(
            tr("Encryption is not allowed on amateur radio bands. Use it only on licence-free (PMR446, FRS) or licensed " +
                "private channels. The callsign is always sent in clear."),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
        )
    }
    Text(
        if (fingerprint != null) tr("Group key set · fingerprint %s", fingerprint) else tr("No group key. Positions can't be sent or read until you set one."),
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        tr("Everyone in the group enters the same passphrase, then compares fingerprints by voice. The passphrase is not stored; " +
            "the derived key is kept wrapped by the phone's hardware keystore."),
        style = MaterialTheme.typography.bodySmall,
    )
    var pass by remember { mutableStateOf("") }
    OutlinedTextField(
        value = pass,
        onValueChange = { pass = it },
        label = { Text(tr("Group passphrase")) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                onSetPassphrase(pass)
                pass = ""
            },
            enabled = !busy && pass.length >= GroupKey.MIN_PASSPHRASE,
        ) { Text(if (busy) tr("Deriving key...") else if (fingerprint == null) tr("Set key") else tr("Replace key")) }
        if (fingerprint != null) OutlinedButton(onClick = onClearKey) { Text(tr("Remove key")) }
    }
    Toggle(
        tr("Encrypt messages and images too"), settings.encryptMessages,
        tr("Positions are always encrypted. This also seals texts, images and files with the group key."),
    ) { v -> onChange { it.copy(encryptMessages = v) } }
    Choice(tr("Send my position automatically"), RadioSettings.BEACON_INTERVALS, settings.beaconIntervalMin, {
        if (it == 0) tr("Off") else tr("%d min", it)
    }) { v -> onChange { it.copy(beaconIntervalMin = v) } }
    Choice(tr("Beacon profile"), ModemProfile.entries, settings.beaconProfile, ::profileLabel) { v -> onChange { it.copy(beaconProfile = v) } }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider()
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun <T> Choice(label: String, options: List<T>, selected: T, name: (T) -> String, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(name(o)) }) }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, help: String?, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            help?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Slider that commits when released, snapped to [step]. */
@Composable
private fun IntSlider(label: String, value: Int, min: Int, max: Int, step: Int, unit: String, help: String?, onChange: (Int) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value.toFloat()) }
    fun snapped() = (min + ((v - min) / step).roundToInt() * step).coerceIn(min, max)
    Column {
        Text("$label: ${snapped()} $unit", style = MaterialTheme.typography.bodyLarge)
        help?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Slider(
            value = v,
            onValueChange = { v = it },
            onValueChangeFinished = { onChange(snapped()) },
            valueRange = min.toFloat()..max.toFloat(),
        )
    }
}

@Composable
private fun OptionalIntSlider(label: String, value: Int?, min: Int, max: Int, step: Int, unit: String, help: String, onChange: (Int?) -> Unit) {
    Toggle("$label: custom", value != null, help) { on -> onChange(if (on) (value ?: (min + max) / 2) else null) }
    if (value != null) IntSlider(label, value, min, max, step, unit, null) { onChange(it) }
}
