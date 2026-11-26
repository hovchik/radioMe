package com.radiome.feature.transceiver.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.radiome.core.audio.RouteMode
import com.radiome.core.dsp.modem.ModemProfile
import com.radiome.core.dsp.modem.RxState
import com.radiome.core.protocol.image.ImagePreset
import com.radiome.core.protocol.image.MaxEdge
import com.radiome.core.protocol.link.LinkState
import com.radiome.feature.transceiver.ComposeUi
import com.radiome.feature.transceiver.ImageDraftUi
import com.radiome.feature.transceiver.Presenters
import com.radiome.feature.transceiver.RadioUi
import kotlin.math.roundToInt

@Composable
fun RadioScreen(
    radio: RadioUi,
    compose: ComposeUi,
    restartPending: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onTestTone: () -> Unit,
    onDraftChange: (String) -> Unit,
    onProfileChange: (ModemProfile) -> Unit,
    onSendText: () -> Unit,
    onPickImage: () -> Unit,
    onImagePreset: (ImagePreset) -> Unit,
    onClearImage: () -> Unit,
    onSendImage: () -> Unit,
    onRequestCorrections: (Int) -> Unit,
    onCancel: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { StatusCard(radio, restartPending, onStart, onStop, onRestart, onTestTone) }
        items(radio.transfers, key = { it.sessionId * 2 + it.direction.ordinal }) { card ->
            TransferCardView(card, { onRequestCorrections(card.sessionId) }, { onCancel(card.sessionId) })
        }
        item {
            ComposeCard(
                compose, radio.running, onDraftChange, onProfileChange, onSendText, onPickImage,
                onImagePreset, onClearImage, onSendImage,
            )
        }
    }
}

@Composable
private fun StatusCard(
    radio: RadioUi,
    restartPending: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onTestTone: () -> Unit,
) {
    // Every row below is always present and single-line (or fixed at two lines), so live telemetry changes the
    // text but never the card's height.
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val title = when {
                        !radio.running -> "Radio off"
                        radio.linkState == LinkState.TRANSMITTING -> "Transmitting"
                        radio.linkState == LinkState.RECEIVING || radio.rxState != RxState.LISTENING -> "Receiving"
                        else -> "Listening"
                    }
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                    val routeText = radio.route?.let {
                        val mode = if (it.mode == RouteMode.CABLE) "Cable" else "Acoustic"
                        val out = radio.outputDevice ?: it.output
                        "$mode · in: ${it.input} · out: $out"
                    } ?: if (radio.running) "Audio route: starting" else "Start listening to receive. Sending also needs the radio on."
                    OneLine(routeText)
                }
                if (radio.running) {
                    OutlinedButton(onClick = onStop) { Text("Stop") }
                } else {
                    Button(onClick = onStart) { Text("Start listening") }
                }
            }
            if (radio.running) {
                LevelMeter(radio.levelDbfs, radio.inputLevel)
                val level = if (radio.levelDbfs.isInfinite() || radio.levelDbfs.isNaN()) "-" else "%.0f dBFS".format(radio.levelDbfs)
                val snr = if (radio.snrDb.isNaN()) "-" else "%.1f dB".format(radio.snrDb)
                OneLine("Level $level · SNR $snr · sync %.2f".format(radio.syncCorrelation))
                val inBurst = radio.rxState != RxState.LISTENING && radio.linkState != LinkState.TRANSMITTING
                LinearProgressIndicator(
                    progress = { if (inBurst) radio.burstProgress else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                OneLine(
                    when {
                        radio.linkState == LinkState.TRANSMITTING -> "Sending tones" + (radio.outputDevice?.let { " through $it" } ?: "")
                        inBurst -> "Burst %d%% · erased symbols %d".format((radio.burstProgress * 100).toInt(), radio.erasedSymbols)
                        else -> "Waiting for a burst"
                    },
                )
                Text(
                    radio.hint ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (restartPending) {
                        Text("New lead-in or ARQ settings apply after a restart.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        FilledTonalButton(onClick = onRestart) { Text("Restart") }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onTestTone, enabled = radio.linkState != LinkState.TRANSMITTING) { Text("Test tone") }
                }
            }
        }
    }
}

@Composable
private fun OneLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun ComposeCard(
    compose: ComposeUi,
    running: Boolean,
    onDraftChange: (String) -> Unit,
    onProfileChange: (ModemProfile) -> Unit,
    onSendText: () -> Unit,
    onPickImage: () -> Unit,
    onImagePreset: (ImagePreset) -> Unit,
    onClearImage: () -> Unit,
    onSendImage: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Send", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModemProfile.entries.forEach { p ->
                    FilterChip(
                        selected = compose.profile == p,
                        onClick = { onProfileChange(p) },
                        label = { Text(profileLabel(p)) },
                    )
                }
            }
            val image = compose.image
            if (image == null) {
                OutlinedTextField(
                    value = compose.draft,
                    onValueChange = onDraftChange,
                    label = { Text("Message") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                )
                compose.textEstimate?.let {
                    val zip = if (it.compressed) " (compressed from ${it.bytes} B)" else ""
                    Text(
                        "${Presenters.formatBytes(it.wireBytes)}$zip · ${it.chunks} chunks · about ${Presenters.formatDuration(it.airtimeMs)} on air",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onPickImage) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Image")
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = onSendText, enabled = running && compose.draft.isNotBlank() && !compose.sending) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Send")
                    }
                }
            } else {
                ImageDraft(image, compose.sending, running, onImagePreset, onClearImage, onSendImage)
            }
        }
    }
}

@Composable
private fun ImageDraft(
    image: ImageDraftUi,
    sending: Boolean,
    running: Boolean,
    onImagePreset: (ImagePreset) -> Unit,
    onClearImage: () -> Unit,
    onSendImage: () -> Unit,
) {
    Row(verticalAlignment = Alignment.Top) {
        if (image.bytes != null) {
            PayloadImage(image.bytes, "Image preview as it will be sent", Modifier.size(160.dp))
        } else if (image.busy) {
            CircularProgressIndicator(Modifier.size(48.dp))
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onClearImage) { Icon(Icons.Filled.Close, contentDescription = "Remove image") }
    }
    image.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (image.bytes != null) {
        Text(
            "${image.width}x${image.height} · ${Presenters.formatBytes(image.sizeBytes)} · about ${Presenters.formatDuration(image.airtimeMs)} on air",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    val preset = image.preset
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MaxEdge.entries.forEach { e ->
            FilterChip(
                selected = preset.maxEdge == e,
                onClick = { onImagePreset(preset.copy(maxEdge = e)) },
                label = { Text("${e.pixels} px") },
            )
        }
    }
    // Re-encode once the thumb is released, not on every drag step.
    var quality by remember(preset.quality) { mutableFloatStateOf(preset.quality.toFloat()) }
    Text("Quality ${quality.roundToInt()}%", style = MaterialTheme.typography.bodySmall)
    Slider(
        value = quality,
        onValueChange = { quality = it },
        onValueChangeFinished = { onImagePreset(preset.copy(quality = quality.roundToInt())) },
        valueRange = ImagePreset.MIN_QUALITY.toFloat()..ImagePreset.MAX_QUALITY.toFloat(),
        steps = (ImagePreset.MAX_QUALITY - ImagePreset.MIN_QUALITY) / 5 - 1,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Grayscale", Modifier.weight(1f))
        Switch(checked = preset.grayscale, onCheckedChange = { onImagePreset(preset.copy(grayscale = it)) })
    }
    Button(
        onClick = onSendImage,
        enabled = running && image.bytes != null && !image.busy && !sending,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text("Send image")
    }
}

fun profileLabel(p: ModemProfile): String = when (p) {
    ModemProfile.ROBUST -> "Robust"
    ModemProfile.STANDARD -> "Standard"
    ModemProfile.FAST -> "Fast"
}
