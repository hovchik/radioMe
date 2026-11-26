package com.radiome.feature.transceiver.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import com.radiome.core.audio.RoutePreference
import com.radiome.core.dsp.modem.ModemConfig
import com.radiome.core.dsp.modem.ModemProfile
import com.radiome.core.protocol.session.TransferOptions
import com.radiome.feature.transceiver.settings.RadioSettings
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(settings: RadioSettings, onChange: ((RadioSettings) -> RadioSettings) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section("Radio")
        Choice("Audio route", RoutePreference.entries, settings.routePreference, {
            when (it) {
                RoutePreference.AUTO -> "Auto"
                RoutePreference.ACOUSTIC -> "Speaker + mic"
                RoutePreference.CABLE -> "Cable"
            }
        }) { v -> onChange { it.copy(routePreference = v) } }
        IntSlider(
            "VOX lead-in", settings.leadInMs, ModemConfig.MIN_LEAD_IN_MS, ModemConfig.MAX_LEAD_IN_MS, 50, "ms",
            "Pilot tone before each burst so the radio's VOX and the far squelch open in time.",
        ) { v -> onChange { it.copy(leadInMs = v) } }
        IntSlider("Output gain", settings.outputGainPercent, RadioSettings.MIN_GAIN, 100, 5, "%", null) { v ->
            onChange { it.copy(outputGainPercent = v) }
        }

        Section("Modem")
        Choice("Default profile", ModemProfile.entries, settings.defaultProfile, ::profileLabel) { v ->
            onChange { it.copy(defaultProfile = v) }
        }
        OptionalIntSlider(
            "FEC parity", settings.parityPercent, RadioSettings.MIN_PARITY, RadioSettings.MAX_PARITY, 1, "%",
            "Reed-Solomon parity as a share of data. Off uses the profile default (50 / 33 / 20%).",
        ) { v -> onChange { it.copy(parityPercent = v) } }
        OptionalIntSlider(
            "Interleave depth", settings.interleaveDepth, 1, RadioSettings.MAX_INTERLEAVE, 1, "codewords",
            "Spreads a burst of lost audio over many codewords. Off uses the profile default.",
        ) { v -> onChange { it.copy(interleaveDepth = v) } }
        IntSlider(
            "Chunk size", settings.chunkPayloadSize, TransferOptions.MIN_CHUNK, TransferOptions.MAX_CHUNK, 4, "bytes",
            "Smaller chunks waste less when one is lost; larger ones have less overhead.",
        ) { v -> onChange { it.copy(chunkPayloadSize = v) } }

        Section("Corrections (ARQ)")
        Toggle("Request missing chunks automatically", settings.autoNack,
            "Off: the dashboard shows a Send correction request button instead.") { v -> onChange { it.copy(autoNack = v) } }
        Toggle("Confirm complete transfers", settings.sendAck, null) { v -> onChange { it.copy(sendAck = v) } }
        Toggle("Send repairs in Robust", settings.stepDownToRobust, null) { v -> onChange { it.copy(stepDownToRobust = v) } }
        IntSlider(
            "Turnaround", settings.turnaroundMs, RadioSettings.MIN_TURNAROUND, RadioSettings.MAX_TURNAROUND, 100, "ms",
            "Quiet time before answering, so the other radio has stopped transmitting.",
        ) { v -> onChange { it.copy(turnaroundMs = v) } }
    }
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
