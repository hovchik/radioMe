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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.SuggestionChip
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
import com.radiome.feature.transceiver.HeardStation
import com.radiome.feature.transceiver.FileDraftUi
import kotlinx.coroutines.flow.Flow
import com.radiome.feature.transceiver.ImageDraftUi
import com.radiome.feature.transceiver.LinkTestUi
import com.radiome.feature.transceiver.QueuedItem
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
    linkTest: LinkTestUi,
    heard: List<HeardStation>,
    quickMessages: List<String>,
    onLinkTest: () -> Unit,
    onApplyLinkTest: () -> Unit,
    onDismissLinkTest: () -> Unit,
    onToChange: (String) -> Unit,
    onQuickMessage: (String) -> Unit,
    queue: List<QueuedItem>,
    queueActions: QueueActions,
    fileActions: FileActions,
    /** Spectrum rows, or null when the waterfall is turned off. */
    waterfall: Flow<FloatArray>?,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { StatusCard(radio, restartPending, onStart, onStop, onRestart, onTestTone, onLinkTest, linkTest.running) }
        if (radio.running && waterfall != null) item { WaterfallCard(waterfall) }
        if (linkTest.visible) item { LinkTestCard(linkTest, onApplyLinkTest, onDismissLinkTest) }
        items(radio.transfers, key = { it.sessionId * 2 + it.direction.ordinal }) { card ->
            TransferCardView(card, { onRequestCorrections(card.sessionId) }, { onCancel(card.sessionId) })
        }
        item {
            ComposeCard(
                compose, radio.running, onDraftChange, onProfileChange, onSendText, onPickImage,
                onImagePreset, onClearImage, onSendImage, heard, quickMessages, onToChange, onQuickMessage, queueActions, fileActions,
            )
        }
        if (queue.isNotEmpty()) item { QueueCard(queue, radio.running, queueActions.onRemove) }
    }
}

/** Sending a small file: pick it with the document picker, then send it now or queue it. */
class FileActions(
    val onPick: () -> Unit,
    val onClear: () -> Unit,
    val onSend: () -> Unit,
    val onQueue: () -> Unit,
)

/** What the compose card needs for Auto speed and the send queue. */
class QueueActions(
    val onAuto: (Boolean) -> Unit,
    val onQueueText: () -> Unit,
    val onQueueImage: () -> Unit,
    val onRemove: (Long) -> Unit,
)

@Composable
private fun QueueCard(queue: List<QueuedItem>, running: Boolean, onRemove: (Long) -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Queue (${queue.size})", style = MaterialTheme.typography.titleMedium)
            Text(
                if (running) "Sent one after another; the next starts when the previous one is delivered or fails."
                else "Waiting for the radio to be turned on.",
                style = MaterialTheme.typography.bodySmall,
            )
            queue.forEachIndexed { i, q ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${i + 1}. ${q.label}" + (q.options.station?.destination?.let { " · to $it" } ?: "") +
                            if (q.autoProfile) " · auto" else " · ${profileLabel(q.options.profile)}",
                        Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = { onRemove(q.id) }) { Icon(Icons.Filled.Close, contentDescription = "Remove from queue") }
                }
            }
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
    onLinkTest: () -> Unit,
    linkTestRunning: Boolean,
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
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onLinkTest, enabled = !linkTestRunning && radio.linkState != LinkState.TRANSMITTING) {
                        Text("Link test")
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkTestCard(test: LinkTestUi, onApply: () -> Unit, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val title = when {
                    test.running -> "Link test running"
                    test.measuredHere -> "Link test from ${test.peer ?: "another station"}"
                    else -> "Link test" + (test.peer?.let { " with $it" } ?: "")
                }
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (!test.running) IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close link test") }
            }
            when {
                test.running -> {
                    Text(
                        "Sending probe bursts in Fast, Standard and Robust (about 25 s), then waiting for the other station's report.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                test.error != null -> Text(test.error, color = MaterialTheme.colorScheme.error)
                test.report != null -> {
                    for (r in test.report.results) {
                        val snr = if (r.snrDb.isNaN()) "not heard" else "SNR %.1f dB".format(r.snrDb)
                        val fec = if (r.received == 0) "" else " · FEC %.1f B/frame".format(r.errorsPerFrame)
                        Text(
                            "${profileLabel(r.profile)}: ${r.received}/${r.sent} frames · $snr$fec",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (r.lost > 0) RadioColors.missing else MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    test.recommendation?.let { rec ->
                        Text("Suggested: ${Presenters.recommendationLabel(rec)}", style = MaterialTheme.typography.titleSmall)
                        Text(rec.reason, style = MaterialTheme.typography.bodySmall)
                        if (test.measuredHere) {
                            Text("This measures their transmissions to you. The report went back to them.", style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = onApply) { Text("Use ${Presenters.recommendationLabel(rec)}") }
                    }
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
    heard: List<HeardStation>,
    quickMessages: List<String>,
    onToChange: (String) -> Unit,
    onQuickMessage: (String) -> Unit,
    queueActions: QueueActions,
    fileActions: FileActions,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Send", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = compose.to,
                onValueChange = onToChange,
                label = { Text("To (callsign)") },
                placeholder = { Text("All stations") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (heard.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = compose.to.isBlank(), onClick = { onToChange("") }, label = { Text("All") })
                    heard.forEach { h ->
                        FilterChip(selected = compose.to == h.callsign, onClick = { onToChange(h.callsign) }, label = { Text(h.callsign) })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = compose.auto, onClick = { queueActions.onAuto(!compose.auto) }, label = { Text("Auto") })
                ModemProfile.entries.forEach { p ->
                    FilterChip(
                        selected = !compose.auto && compose.profile == p,
                        onClick = { onProfileChange(p) },
                        label = { Text(profileLabel(p)) },
                    )
                }
            }
            val image = compose.image
            val file = compose.file
            if (file != null) {
                FileDraft(file, compose.sending, running, fileActions)
                return@Column
            }
            if (image == null && quickMessages.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    quickMessages.forEach { q ->
                        SuggestionChip(onClick = { onQuickMessage(q) }, enabled = running && !compose.sending, label = { Text(q) })
                    }
                }
            }
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
                    OutlinedButton(onClick = fileActions.onPick) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("File")
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = queueActions.onQueueText, enabled = compose.draft.isNotBlank()) { Text("Queue") }
                    Button(onClick = onSendText, enabled = running && compose.draft.isNotBlank() && !compose.sending) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Send")
                    }
                }
            } else {
                ImageDraft(image, compose.sending, running, onImagePreset, onClearImage, onSendImage, queueActions.onQueueImage)
            }
        }
    }
}

@Composable
private fun FileDraft(file: FileDraftUi, sending: Boolean, running: Boolean, actions: FileActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.titleSmall)
            val zip = if (file.estimate.compressed) ", ${Presenters.formatBytes(file.estimate.wireBytes)} compressed" else ""
            Text(
                "${Presenters.formatBytes(file.sizeBytes)}$zip · ${file.estimate.chunks} chunks · about " +
                    "${Presenters.formatDuration(file.estimate.airtimeMs)} on air",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        IconButton(onClick = actions.onClear) { Icon(Icons.Filled.Close, contentDescription = "Remove file") }
    }
    if (file.large) {
        Text(
            "This is a large file for a radio link. Make sure the channel is free for that long.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Button(onClick = actions.onSend, enabled = running && !sending, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text("Send file")
    }
    OutlinedButton(onClick = actions.onQueue, modifier = Modifier.fillMaxWidth()) { Text("Add file to queue") }
}

@Composable
private fun ImageDraft(
    image: ImageDraftUi,
    sending: Boolean,
    running: Boolean,
    onImagePreset: (ImagePreset) -> Unit,
    onClearImage: () -> Unit,
    onSendImage: () -> Unit,
    onQueueImage: () -> Unit,
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Preview first")
            Text("A tiny copy goes out first so the receiver sees the picture early.", style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = preset.progressive, onCheckedChange = { onImagePreset(preset.copy(progressive = it)) })
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
    OutlinedButton(onClick = onQueueImage, enabled = image.bytes != null && !image.busy, modifier = Modifier.fillMaxWidth()) {
        Text("Add image to queue")
    }
}

fun profileLabel(p: ModemProfile): String = when (p) {
    ModemProfile.ROBUST -> "Robust"
    ModemProfile.STANDARD -> "Standard"
    ModemProfile.FAST -> "Fast"
}
