package com.radiome.feature.transceiver.ui

import com.radiome.feature.transceiver.i18n.tr
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
import androidx.compose.material.icons.filled.DateRange
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
import androidx.compose.runtime.mutableStateOf
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
import com.radiome.feature.transceiver.RecordingUi
import com.radiome.feature.transceiver.VoiceDraftUi
import androidx.compose.material.icons.filled.PlayArrow
import kotlinx.coroutines.flow.Flow
import com.radiome.feature.transceiver.ImageDraftUi
import com.radiome.feature.transceiver.LinkTestUi
import com.radiome.feature.transceiver.QueuedItem
import com.radiome.feature.transceiver.Presenters
import com.radiome.feature.transceiver.RadioUi
import com.radiome.feature.transceiver.link.Repeat
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
    voiceActions: VoiceActions,
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
                voiceActions,
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

/** Recording a voice note, then playing, sending or queueing it. */
class VoiceActions(
    val onRecord: () -> Unit,
    val onStop: () -> Unit,
    val onPlay: () -> Unit,
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
    /** Schedules whatever is in the composer for a later time. */
    val onSchedule: ((at: Long, repeat: Repeat) -> Unit)? = null,
)

@Composable
private fun QueueCard(queue: List<QueuedItem>, running: Boolean, onRemove: (Long) -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr("Queue (%d)", queue.size), style = MaterialTheme.typography.titleMedium)
            Text(
                if (running) tr("Sent one after another; the next starts when the previous one is delivered or fails.")
                else tr("Waiting for the radio to be turned on."),
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
                    IconButton(onClick = { onRemove(q.id) }) { Icon(Icons.Filled.Close, contentDescription = tr("Remove from queue")) }
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
                        !radio.running -> tr("Radio off")
                        radio.linkState == LinkState.TRANSMITTING -> tr("Transmitting")
                        radio.linkState == LinkState.RECEIVING || radio.rxState != RxState.LISTENING -> tr("Receiving")
                        else -> tr("Listening")
                    }
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                    val routeText = radio.route?.let {
                        val mode = if (it.mode == RouteMode.CABLE) tr("Cable") else tr("Acoustic")
                        val out = radio.outputDevice ?: it.output
                        "$mode · in: ${it.input} · out: $out"
                    } ?: if (radio.running) tr("Audio route: starting") else tr("Start listening to receive. Sending also needs the radio on.")
                    OneLine(routeText)
                }
                if (radio.running) {
                    OutlinedButton(onClick = onStop) { Text(tr("Stop")) }
                } else {
                    Button(onClick = onStart) { Text(tr("Start listening")) }
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
                        else -> tr("Waiting for a burst")
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
                        Text(tr("New lead-in or ARQ settings apply after a restart."), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        FilledTonalButton(onClick = onRestart) { Text(tr("Restart")) }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onTestTone, enabled = radio.linkState != LinkState.TRANSMITTING) { Text(tr("Test tone")) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onLinkTest, enabled = !linkTestRunning && radio.linkState != LinkState.TRANSMITTING) {
                        Text(tr("Link test"))
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
                    test.running -> tr("Link test running")
                    test.measuredHere -> tr("Link test from %s", test.peer ?: tr("another station"))
                    else -> test.peer?.let { tr("Link test with %s", it) } ?: tr("Link test")
                }
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (!test.running) IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = tr("Close link test")) }
            }
            when {
                test.running -> {
                    Text(
                        tr("Sending probe bursts in Fast, Standard and Robust (about 25 s), then waiting for the other station's report."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                test.error != null -> Text(test.error, color = MaterialTheme.colorScheme.error)
                test.report != null -> {
                    for (r in test.report.results) {
                        val snr = if (r.snrDb.isNaN()) tr("not heard") else "SNR %.1f dB".format(r.snrDb)
                        val fec = if (r.received == 0) "" else " · FEC %.1f B/frame".format(r.errorsPerFrame)
                        Text(
                            "${profileLabel(r.profile)}: ${r.received}/${r.sent} frames · $snr$fec",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (r.lost > 0) RadioColors.missing else MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    test.recommendation?.let { rec ->
                        Text(tr("Suggested: %s", Presenters.recommendationLabel(rec)), style = MaterialTheme.typography.titleSmall)
                        Text(rec.reason, style = MaterialTheme.typography.bodySmall)
                        if (test.measuredHere) {
                            Text(tr("This measures their transmissions to you. The report went back to them."), style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = onApply) { Text(tr("Use %s", Presenters.recommendationLabel(rec))) }
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
    voiceActions: VoiceActions,
) {
    var scheduling by remember { mutableStateOf(false) }
    val scheduleSink = queueActions.onSchedule
    if (scheduling && scheduleSink != null) {
        ScheduleDraftDialog(onDismiss = { scheduling = false }, onSchedule = { at, repeat ->
            scheduling = false
            scheduleSink(at, repeat)
        })
    }
    val onSchedule: (() -> Unit)? = if (scheduleSink != null) ({ scheduling = true }) else null
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Send"), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = compose.to,
                onValueChange = onToChange,
                label = { Text(tr("To (callsign)")) },
                placeholder = { Text(tr("All stations")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (heard.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = compose.to.isBlank(), onClick = { onToChange("") }, label = { Text(tr("All")) })
                    heard.forEach { h ->
                        FilterChip(selected = compose.to == h.callsign, onClick = { onToChange(h.callsign) }, label = { Text(h.callsign) })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = compose.auto, onClick = { queueActions.onAuto(!compose.auto) }, label = { Text(tr("Auto")) })
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
                FileDraft(file, compose.sending, running, fileActions, onSchedule)
                return@Column
            }
            if (compose.recording.active) {
                Recording(compose.recording, voiceActions)
                return@Column
            }
            compose.voice?.let {
                VoiceDraft(it, compose.sending, running, voiceActions, onSchedule)
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
                    label = { Text(tr("Message")) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                )
                compose.textEstimate?.let {
                    val zip = if (it.compressed) " (compressed from ${it.bytes} B)" else ""
                    Text(
                        tr("%s · %d chunks · about %s on air", Presenters.formatBytes(it.wireBytes) + zip, it.chunks, Presenters.formatDuration(it.airtimeMs)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPickImage) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(tr("Image"))
                    }
                    OutlinedButton(onClick = fileActions.onPick) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(tr("File"))
                    }
                    if (compose.voiceAvailable) {
                        OutlinedButton(onClick = voiceActions.onRecord) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(tr("Voice"))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    if (onSchedule != null) {
                        IconButton(onClick = onSchedule, enabled = compose.draft.isNotBlank()) {
                            Icon(Icons.Filled.DateRange, contentDescription = tr("Schedule"))
                        }
                    }
                    OutlinedButton(onClick = queueActions.onQueueText, enabled = compose.draft.isNotBlank()) { Text(tr("Queue")) }
                    Button(onClick = onSendText, enabled = running && compose.draft.isNotBlank() && !compose.sending) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(tr("Send"))
                    }
                }
            } else {
                ImageDraft(image, compose.sending, running, onImagePreset, onClearImage, onSendImage, queueActions.onQueueImage, onSchedule)
            }
        }
    }
}

@Composable
internal fun Recording(recording: RecordingUi, actions: VoiceActions) {
    Text(
        tr("Recording... %d s", recording.elapsedMs / 1000),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.error,
    )
    LinearProgressIndicator(
        progress = { (recording.elapsedMs / 30_000f).coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = actions.onStop, modifier = Modifier.fillMaxWidth()) { Text(tr("Stop recording")) }
}

@Composable
internal fun VoiceDraft(voice: VoiceDraftUi, sending: Boolean, running: Boolean, actions: VoiceActions, onSchedule: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = actions.onPlay) { Icon(Icons.Filled.PlayArrow, contentDescription = tr("Play")) }
        Text(
            tr(
                "Voice note · %d s · %s · about %s on air",
                (voice.durationMs + 500) / 1000, Presenters.formatBytes(voice.estimate.wireBytes), Presenters.formatDuration(voice.estimate.airtimeMs),
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = actions.onClear) { Icon(Icons.Filled.Close, contentDescription = tr("Remove voice note")) }
    }
    Button(onClick = actions.onSend, enabled = running && !sending, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text(tr("Send voice note"))
    }
    OutlinedButton(onClick = actions.onQueue, modifier = Modifier.fillMaxWidth()) { Text(tr("Add voice note to queue")) }
    ScheduleButton(onSchedule)
}

@Composable
internal fun FileDraft(file: FileDraftUi, sending: Boolean, running: Boolean, actions: FileActions, onSchedule: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.titleSmall)
            val zip = if (file.estimate.compressed) ", ${Presenters.formatBytes(file.estimate.wireBytes)} compressed" else ""
            Text(
                tr(
                    "%s · %d chunks · about %s on air",
                    Presenters.formatBytes(file.sizeBytes) + zip, file.estimate.chunks, Presenters.formatDuration(file.estimate.airtimeMs),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        IconButton(onClick = actions.onClear) { Icon(Icons.Filled.Close, contentDescription = tr("Remove file")) }
    }
    if (file.large) {
        Text(
            tr("This is a large file for a radio link. Make sure the channel is free for that long."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Button(onClick = actions.onSend, enabled = running && !sending, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text(tr("Send file"))
    }
    OutlinedButton(onClick = actions.onQueue, modifier = Modifier.fillMaxWidth()) { Text(tr("Add file to queue")) }
    ScheduleButton(onSchedule)
}

@Composable
internal fun ImageDraft(
    image: ImageDraftUi,
    sending: Boolean,
    running: Boolean,
    onImagePreset: (ImagePreset) -> Unit,
    onClearImage: () -> Unit,
    onSendImage: () -> Unit,
    onQueueImage: () -> Unit,
    onSchedule: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.Top) {
        if (image.bytes != null) {
            PayloadImage(image.bytes, tr("Image preview as it will be sent"), Modifier.size(160.dp))
        } else if (image.busy) {
            CircularProgressIndicator(Modifier.size(48.dp))
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onClearImage) { Icon(Icons.Filled.Close, contentDescription = tr("Remove image")) }
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
    Text(tr("Quality %d%%", quality.roundToInt()), style = MaterialTheme.typography.bodySmall)
    Slider(
        value = quality,
        onValueChange = { quality = it },
        onValueChangeFinished = { onImagePreset(preset.copy(quality = quality.roundToInt())) },
        valueRange = ImagePreset.MIN_QUALITY.toFloat()..ImagePreset.MAX_QUALITY.toFloat(),
        steps = (ImagePreset.MAX_QUALITY - ImagePreset.MIN_QUALITY) / 5 - 1,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr("Grayscale"), Modifier.weight(1f))
        Switch(checked = preset.grayscale, onCheckedChange = { onImagePreset(preset.copy(grayscale = it)) })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tr("Preview first"))
            Text(tr("A tiny copy goes out first so the receiver sees the picture early."), style = MaterialTheme.typography.bodySmall)
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
        Text(tr("Send image"))
    }
    OutlinedButton(onClick = onQueueImage, enabled = image.bytes != null && !image.busy, modifier = Modifier.fillMaxWidth()) {
        Text(tr("Add image to queue"))
    }
    ScheduleButton(onSchedule, enabled = image.bytes != null && !image.busy)
}

/** "Schedule…" under a draft; absent where scheduling is not offered. */
@Composable
private fun ScheduleButton(onSchedule: (() -> Unit)?, enabled: Boolean = true) {
    if (onSchedule == null) return
    OutlinedButton(onClick = onSchedule, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.DateRange, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text(tr("Schedule for later"))
    }
}

fun profileLabel(p: ModemProfile): String = when (p) {
    ModemProfile.ROBUST -> tr("Robust")
    ModemProfile.STANDARD -> tr("Standard")
    ModemProfile.FAST -> tr("Fast")
}
