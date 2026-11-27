package com.radiome.feature.transceiver.ui

import com.radiome.feature.transceiver.i18n.tr
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.radiome.core.audio.RouteMode
import com.radiome.core.dsp.modem.ModemProfile
import com.radiome.core.dsp.modem.RxState
import com.radiome.core.protocol.image.ImagePreset
import com.radiome.core.protocol.image.MaxEdge
import com.radiome.core.protocol.link.LinkState
import com.radiome.feature.transceiver.ComposeUi
import com.radiome.feature.transceiver.FileDraftUi
import com.radiome.feature.transceiver.HeardStation
import com.radiome.feature.transceiver.ImageDraftUi
import com.radiome.feature.transceiver.LinkTestUi
import com.radiome.feature.transceiver.Presenters
import com.radiome.feature.transceiver.QueuedItem
import com.radiome.feature.transceiver.RadioUi
import com.radiome.feature.transceiver.RecordingUi
import com.radiome.feature.transceiver.VoiceDraftUi
import com.radiome.feature.transceiver.link.Repeat
import com.radiome.feature.transceiver.settings.Health
import kotlinx.coroutines.flow.Flow
import kotlin.math.roundToInt

/**
 * The Radio tab, in the same language as Settings: a status card on top whose colour says how the radio is doing,
 * what is on the air right now, then the Send card and the queue.
 */
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
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { StatusCard(radio, onStart, onStop, onTestTone, onLinkTest, linkTest.running) }
        if (restartPending && radio.running) {
            item {
                NoticeCard(
                    Icons.Filled.Refresh,
                    tr("New lead-in or ARQ settings apply after a restart."),
                    Health.ATTENTION,
                    actionLabel = tr("Restart"),
                    onAction = onRestart,
                )
            }
        }
        if (radio.running && waterfall != null) item { WaterfallCard(waterfall) }
        if (linkTest.visible) item { LinkTestCard(linkTest, onApplyLinkTest, onDismissLinkTest) }
        if (radio.transfers.isNotEmpty()) item { SectionTitle(tr("On the air now")) }
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
    SettingsCard(
        icon = Icons.AutoMirrored.Filled.List,
        title = tr("Queue (%d)", queue.size),
        summary = if (running) tr("Sent one after another; the next starts when the previous one is delivered or fails.")
        else tr("Waiting for the radio to be turned on."),
        status = { StatusPill(if (running) tr("Sending") else tr("Waiting"), if (running) Health.OK else Health.IDLE) },
    ) {
        queue.forEachIndexed { i, q ->
            if (i > 0) RowDivider()
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(28.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text("${i + 1}", style = MaterialTheme.typography.labelMedium) }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(q.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(
                            q.options.station?.destination?.let { tr("To %s", it) } ?: tr("All stations"),
                            if (q.autoProfile) tr("Auto") else profileLabel(q.options.profile),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                IconButton(onClick = { onRemove(q.id) }) { Icon(Icons.Filled.Close, contentDescription = tr("Remove from queue")) }
            }
        }
    }
}

@Composable
private fun StatusCard(
    radio: RadioUi,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onTestTone: () -> Unit,
    onLinkTest: () -> Unit,
    linkTestRunning: Boolean,
) {
    val transmitting = radio.linkState == LinkState.TRANSMITTING
    val receiving = radio.linkState == LinkState.RECEIVING || radio.rxState != RxState.LISTENING
    val hint = radio.hint
    val health = when {
        !radio.running -> Health.IDLE
        hint != null -> Health.ATTENTION
        else -> Health.OK
    }
    val title = when {
        !radio.running -> tr("Radio off")
        transmitting -> tr("Transmitting")
        receiving -> tr("Receiving")
        else -> tr("Listening")
    }
    val routeText = radio.route?.let {
        val mode = when {
            it.cableMissing -> tr("Cable not found")
            it.mode == RouteMode.CABLE -> tr("Cable")
            else -> tr("Acoustic")
        }
        val out = radio.outputDevice ?: it.output
        "$mode · in: ${it.input} · out: $out"
    } ?: if (radio.running) tr("Audio route: starting") else tr("Start listening to receive. Sending also needs the radio on.")
    // Every row below is always present while the radio runs and has a fixed number of lines, so live telemetry
    // changes the text but never the card's height.
    StatusHeroCard(
        health = health,
        icon = RadioIcons.radio,
        title = title,
        subtitle = routeText,
        trailing = {
            if (radio.running) {
                OutlinedButton(onClick = onStop) { Text(tr("Stop")) }
            } else {
                Button(onClick = onStart) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Start listening"))
                }
            }
        },
    ) {
        if (!radio.running) return@StatusHeroCard
        LevelMeter(radio.levelDbfs, radio.inputLevel)
        val level = if (radio.levelDbfs.isInfinite() || radio.levelDbfs.isNaN()) "-" else "%.0f dBFS".format(radio.levelDbfs)
        val snr = if (radio.snrDb.isNaN()) "-" else "%.1f dB".format(radio.snrDb)
        MetricRow(listOf(tr("Input level") to level, "SNR" to snr, tr("Sync") to "%.2f".format(radio.syncCorrelation)))
        val inBurst = radio.rxState != RxState.LISTENING && !transmitting
        LinearProgressIndicator(progress = { if (inBurst) radio.burstProgress else 0f }, modifier = Modifier.fillMaxWidth())
        OneLine(
            when {
                transmitting -> "Sending tones" + (radio.outputDevice?.let { " through $it" } ?: "")
                inBurst -> "Burst %d%% · erased symbols %d".format((radio.burstProgress * 100).toInt(), radio.erasedSymbols)
                else -> tr("Waiting for a burst")
            },
        )
        Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            if (hint != null) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = RadioColors.repaired, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                hint ?: tr("No problems with the sound."),
                style = MaterialTheme.typography.bodySmall,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onTestTone, enabled = !transmitting) {
                Icon(Icons.Filled.Notifications, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr("Test tone"))
            }
            FilledTonalButton(onClick = onLinkTest, enabled = !linkTestRunning && !transmitting) {
                Icon(Icons.Filled.Build, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr("Link test"))
            }
        }
    }
}

@Composable
private fun LinkTestCard(test: LinkTestUi, onApply: () -> Unit, onDismiss: () -> Unit) {
    val title = when {
        test.running -> tr("Link test running")
        test.measuredHere -> tr("Link test from %s", test.peer ?: tr("another station"))
        else -> test.peer?.let { tr("Link test with %s", it) } ?: tr("Link test")
    }
    SettingsCard(
        icon = Icons.Filled.Build,
        title = title,
        summary = tr("Which speed gets through to the other station"),
        status = {
            when {
                test.running -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else -> IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = tr("Close link test")) }
            }
        },
    ) {
        when {
            test.running -> {
                HelpText(tr("Sending probe bursts in Fast, Standard and Robust (about 25 s), then waiting for the other station's report."))
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            }
            test.error != null -> HelpText(test.error, color = MaterialTheme.colorScheme.error)
            test.report != null -> {
                test.report.results.forEachIndexed { i, r ->
                    if (i > 0) RowDivider()
                    val snr = if (r.snrDb.isNaN()) tr("not heard") else "SNR %.1f dB".format(r.snrDb)
                    val fec = if (r.received == 0) "" else " · FEC %.1f B/frame".format(r.errorsPerFrame)
                    val health = when {
                        r.received == 0 -> Health.PROBLEM
                        r.lost > 0 -> Health.ATTENTION
                        else -> Health.OK
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(health)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(profileLabel(r.profile), style = MaterialTheme.typography.bodyLarge)
                            Text("$snr$fec", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${r.received}/${r.sent}", style = MaterialTheme.typography.titleSmall, color = healthColor(health))
                    }
                }
                test.recommendation?.let { rec ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(tr("Suggested: %s", Presenters.recommendationLabel(rec)), style = MaterialTheme.typography.titleSmall)
                            Text(rec.reason, style = MaterialTheme.typography.bodySmall)
                            if (test.measuredHere) {
                                Text(tr("This measures their transmissions to you. The report went back to them."), style = MaterialTheme.typography.bodySmall)
                            }
                            Button(onClick = onApply) {
                                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(tr("Use %s", Presenters.recommendationLabel(rec)))
                            }
                        }
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

/** A chip that shows a check mark when chosen, like the Settings choices. */
@Composable
internal fun CheckChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
    )
}

/** One line on what the chosen speed means. */
internal fun speedHelp(auto: Boolean, profile: ModemProfile): String = when {
    auto -> tr("Picks the speed from how well the last messages got through.")
    profile == ModemProfile.ROBUST -> tr("Slowest, but gets through weak and noisy signals.")
    profile == ModemProfile.STANDARD -> tr("A good balance for most links.")
    else -> tr("Quickest, for strong and clear signals.")
}

@OptIn(ExperimentalLayoutApi::class)
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
    SettingsCard(
        icon = Icons.AutoMirrored.Filled.Send,
        title = tr("Send"),
        summary = if (compose.to.isBlank()) tr("To all stations") else tr("To %s", compose.to),
        status = { if (!running) StatusPill(tr("Radio off"), Health.IDLE) },
    ) {
        GroupLabel(tr("Recipient"))
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                    CheckChip(compose.to.isBlank(), tr("All")) { onToChange("") }
                    heard.forEach { h -> CheckChip(compose.to == h.callsign, h.callsign) { onToChange(h.callsign) } }
                }
            }
        }
        GroupLabel(tr("Speed"))
        Column(Modifier.padding(horizontal = 16.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CheckChip(compose.auto, tr("Auto")) { queueActions.onAuto(!compose.auto) }
                ModemProfile.entries.forEach { p -> CheckChip(!compose.auto && compose.profile == p, profileLabel(p)) { onProfileChange(p) } }
            }
            Text(speedHelp(compose.auto, compose.profile), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.size(8.dp))
        RowDivider()
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val image = compose.image
            val file = compose.file
            val voice = compose.voice
            when {
                file != null -> FileDraft(file, compose.sending, running, fileActions, onSchedule)
                compose.recording.active -> Recording(compose.recording, voiceActions)
                voice != null -> VoiceDraft(voice, compose.sending, running, voiceActions, onSchedule)
                image != null -> ImageDraft(image, compose.sending, running, onImagePreset, onClearImage, onSendImage, queueActions.onQueueImage, onSchedule)
                else -> {
                    if (quickMessages.isNotEmpty()) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            quickMessages.forEach { q ->
                                SuggestionChip(onClick = { onQuickMessage(q) }, enabled = running && !compose.sending, label = { Text(q) })
                            }
                        }
                    }
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AttachChip(RadioIcons.image, tr("Image"), onPickImage)
                        AttachChip(RadioIcons.file, tr("File"), fileActions.onPick)
                        if (compose.voiceAvailable) AttachChip(RadioIcons.mic, tr("Voice"), voiceActions.onRecord)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        if (onSchedule != null) {
                            IconButton(onClick = onSchedule, enabled = compose.draft.isNotBlank()) {
                                Icon(RadioIcons.schedule, contentDescription = tr("Schedule"))
                            }
                        }
                        OutlinedButton(onClick = queueActions.onQueueText, enabled = compose.draft.isNotBlank()) { Text(tr("Queue")) }
                        Button(onClick = onSendText, enabled = running && compose.draft.isNotBlank() && !compose.sending) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(tr("Send"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
    )
}

@Composable
internal fun Recording(recording: RecordingUi, actions: VoiceActions) {
    AttachmentTile(RadioIcons.mic, tr("Recording... %d s", recording.elapsedMs / 1000), tr("Up to 30 seconds")) {}
    LinearProgressIndicator(
        progress = { (recording.elapsedMs / 30_000f).coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.error,
    )
    Button(onClick = actions.onStop, modifier = Modifier.fillMaxWidth()) { Text(tr("Stop recording")) }
}

@Composable
internal fun VoiceDraft(voice: VoiceDraftUi, sending: Boolean, running: Boolean, actions: VoiceActions, onSchedule: (() -> Unit)? = null) {
    AttachmentTile(
        RadioIcons.mic,
        tr("Voice note %d s", (voice.durationMs + 500) / 1000),
        tr("%s · about %s on air", Presenters.formatBytes(voice.estimate.wireBytes), Presenters.formatDuration(voice.estimate.airtimeMs)),
    ) {
        IconButton(onClick = actions.onPlay) { Icon(Icons.Filled.PlayArrow, contentDescription = tr("Play")) }
        IconButton(onClick = actions.onClear) { Icon(Icons.Filled.Close, contentDescription = tr("Remove voice note")) }
    }
    DraftButtons(tr("Send voice note"), running && !sending, actions.onSend, actions.onQueue, true, onSchedule)
}

@Composable
internal fun FileDraft(file: FileDraftUi, sending: Boolean, running: Boolean, actions: FileActions, onSchedule: (() -> Unit)? = null) {
    val zip = if (file.estimate.compressed) ", ${Presenters.formatBytes(file.estimate.wireBytes)} compressed" else ""
    AttachmentTile(
        RadioIcons.file,
        file.name,
        tr(
            "%s · %d chunks · about %s on air",
            Presenters.formatBytes(file.sizeBytes) + zip, file.estimate.chunks, Presenters.formatDuration(file.estimate.airtimeMs),
        ),
    ) {
        IconButton(onClick = actions.onClear) { Icon(Icons.Filled.Close, contentDescription = tr("Remove file")) }
    }
    if (file.large) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = RadioColors.repaired, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                tr("This is a large file for a radio link. Make sure the channel is free for that long."),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    DraftButtons(tr("Send file"), running && !sending, actions.onSend, actions.onQueue, true, onSchedule)
}

@OptIn(ExperimentalLayoutApi::class)
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
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(160.dp)) {
            Box(contentAlignment = Alignment.Center) {
                if (image.bytes != null) {
                    PayloadImage(image.bytes, tr("Image preview as it will be sent"), Modifier.size(160.dp))
                } else if (image.busy) {
                    CircularProgressIndicator(Modifier.size(40.dp))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (image.bytes != null) {
                Text("${image.width}x${image.height}", style = MaterialTheme.typography.titleSmall)
                Text(Presenters.formatBytes(image.sizeBytes), style = MaterialTheme.typography.bodySmall)
                Text(tr("about %s on air", Presenters.formatDuration(image.airtimeMs)), style = MaterialTheme.typography.bodySmall)
            }
        }
        IconButton(onClick = onClearImage) { Icon(Icons.Filled.Close, contentDescription = tr("Remove image")) }
    }
    image.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    val preset = image.preset
    Text(tr("Size"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MaxEdge.entries.forEach { e -> CheckChip(preset.maxEdge == e, "${e.pixels} px") { onImagePreset(preset.copy(maxEdge = e)) } }
    }
    // Re-encode once the thumb is released, not on every drag step.
    var quality by remember(preset.quality) { mutableFloatStateOf(preset.quality.toFloat()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr("Quality"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text("${quality.roundToInt()}%", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
    Slider(
        value = quality,
        onValueChange = { quality = it },
        onValueChangeFinished = { onImagePreset(preset.copy(quality = quality.roundToInt())) },
        valueRange = ImagePreset.MIN_QUALITY.toFloat()..ImagePreset.MAX_QUALITY.toFloat(),
        steps = (ImagePreset.MAX_QUALITY - ImagePreset.MIN_QUALITY) / 5 - 1,
    )
    InlineSwitch(tr("Grayscale"), tr("Smaller, so it goes out faster."), preset.grayscale) { onImagePreset(preset.copy(grayscale = it)) }
    InlineSwitch(tr("Preview first"), tr("A tiny copy goes out first so the receiver sees the picture early."), preset.progressive) {
        onImagePreset(preset.copy(progressive = it))
    }
    DraftButtons(tr("Send image"), running && image.bytes != null && !image.busy && !sending, onSendImage, onQueueImage, image.bytes != null && !image.busy, onSchedule)
}

/** A switch row inside an already padded column: tapping anywhere on the row flips it. */
@Composable
private fun InlineSwitch(title: String, help: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Under a draft: the main Send button, then Queue and Schedule side by side. */
@Composable
private fun DraftButtons(
    sendLabel: String,
    canSend: Boolean,
    onSend: () -> Unit,
    onQueue: () -> Unit,
    canQueue: Boolean,
    onSchedule: (() -> Unit)?,
) {
    Button(onClick = onSend, enabled = canSend, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(sendLabel)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onQueue, enabled = canQueue, modifier = Modifier.weight(1f)) {
            Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(tr("Add to queue"), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onSchedule != null) {
            OutlinedButton(onClick = onSchedule, enabled = canQueue, modifier = Modifier.weight(1f)) {
                Icon(RadioIcons.schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr("Schedule"), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

fun profileLabel(p: ModemProfile): String = when (p) {
    ModemProfile.ROBUST -> tr("Robust")
    ModemProfile.STANDARD -> tr("Standard")
    ModemProfile.FAST -> tr("Fast")
}
