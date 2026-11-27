package com.radiome.feature.transceiver.ui

import com.radiome.feature.transceiver.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.radiome.feature.transceiver.History
import com.radiome.feature.transceiver.HistoryFilter
import com.radiome.feature.transceiver.link.Repeat
import com.radiome.feature.transceiver.link.ScheduledMessage
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import com.radiome.feature.transceiver.settings.Health
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.radiome.core.protocol.link.Direction
import com.radiome.core.protocol.payload.ContentType
import com.radiome.core.protocol.payload.VoiceNote
import androidx.compose.material.icons.filled.PlayArrow
import com.radiome.data.TransferRecord
import com.radiome.feature.transceiver.Presenters
import com.radiome.data.TransferStatus
import java.text.DateFormat
import java.util.Date

/** Getting things out of the history, sending failed messages again, and scheduled messages. */
class HistoryActions(
    val onDelete: (Long) -> Unit,
    val onPlayVoice: (TransferRecord) -> Unit,
    val onShareLog: () -> Unit,
    val onSaveLog: () -> Unit,
    /** Save an image or file through the document picker. */
    val onSave: (TransferRecord) -> Unit,
    val onSaveToGallery: (TransferRecord) -> Unit,
    val onResend: (TransferRecord) -> Unit = {},
    val onResendAllFailed: () -> Unit = {},
    val onDeleteFailed: () -> Unit = {},
    val onScheduleText: (text: String, to: String, at: Long, repeat: Repeat) -> Unit = { _, _, _, _ -> },
    val onSchedulePosition: (at: Long, repeat: Repeat) -> Unit = { _, _ -> },
    val onCancelScheduled: (Long) -> Unit = {},
    val onSendScheduledNow: (Long) -> Unit = {},
)

@Composable
fun HistoryScreen(
    items: List<TransferRecord>,
    actions: HistoryActions,
    modifier: Modifier = Modifier,
    scheduled: List<ScheduledMessage> = emptyList(),
    resent: Set<Long> = emptySet(),
    running: Boolean = false,
    now: Long = System.currentTimeMillis(),
) {
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var newSchedule by remember { mutableStateOf(false) }
    var confirmDeleteFailed by remember { mutableStateOf(false) }
    val counts = remember(items) { History.counts(items) }
    val shown = remember(items, filter, query) { History.filter(items, filter, query) }
    val toResend = remember(items, resent) { History.toResend(items, resent) }
    val failedCount = counts[HistoryFilter.FAILED] ?: 0

    if (newSchedule) {
        NewScheduleDialog(
            initialTo = "",
            onDismiss = { newSchedule = false },
            onScheduleText = { text, to, at, repeat ->
                newSchedule = false
                actions.onScheduleText(text, to, at, repeat)
            },
            onSchedulePosition = { at, repeat ->
                newSchedule = false
                actions.onSchedulePosition(at, repeat)
            },
        )
    }
    if (confirmDeleteFailed) {
        AlertDialog(
            onDismissRequest = { confirmDeleteFailed = false },
            title = { Text(tr("Delete failed messages?")) },
            text = { Text(tr("%d messages that were not delivered will be removed from the history.", failedCount)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteFailed = false
                    actions.onDeleteFailed()
                }) { Text(tr("Delete")) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteFailed = false }) { Text(tr("Cancel")) } },
        )
    }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SummaryCard(counts, failedCount, actions) }
        if (failedCount > 0) {
            item { FailedCard(failedCount, toResend.size, running, actions, onDelete = { confirmDeleteFailed = true }) }
        }
        item { ScheduledCard(scheduled, running, now, actions, onNew = { newSchedule = true }) }
        if (items.isEmpty()) {
            item { EmptyState(Icons.AutoMirrored.Filled.List, tr("Nothing sent or received yet."), tr("Messages, pictures and files you send or receive are kept here.")) }
            return@LazyColumn
        }
        item { SectionTitle(tr("Messages")) }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(tr("Search messages, files, stations")) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = tr("Clear search")) }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryFilter.entries.forEach { f ->
                    CheckChip(filter == f, "${tr(f.label)} (${counts[f] ?: 0})") { filter = f }
                }
            }
        }
        if (shown.isEmpty()) {
            item { EmptyState(Icons.Filled.Search, tr("Nothing matches."), tr("Try another word or filter.")) }
        }
        items(shown, key = { it.id }) { HistoryRow(it, actions, resent = it.id in resent, running = running) }
    }
}

/** At the top: how much is here, how much failed, and getting the log out. */
@Composable
private fun SummaryCard(counts: Map<HistoryFilter, Int>, failed: Int, actions: HistoryActions) {
    val total = counts[HistoryFilter.ALL] ?: 0
    StatusHeroCard(
        health = when {
            failed > 0 -> Health.ATTENTION
            total == 0 -> Health.IDLE
            else -> Health.OK
        },
        icon = Icons.AutoMirrored.Filled.List,
        title = tr("History"),
        subtitle = when {
            total == 0 -> tr("Nothing sent or received yet.")
            failed > 0 -> tr("%d messages not delivered", failed)
            else -> tr("Everything you sent was delivered or is on its way.")
        },
    ) {
        MetricRow(
            listOf(
                tr("Sent") to "${counts[HistoryFilter.SENT] ?: 0}",
                tr("Received") to "${counts[HistoryFilter.RECEIVED] ?: 0}",
                tr("Not delivered") to "$failed",
            ),
        )
        if (total > 0) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = actions.onShareLog) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Share log"))
                }
                OutlinedButton(onClick = actions.onSaveLog) {
                    Icon(RadioIcons.file, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Save log as file"))
                }
            }
        }
    }
}

/** Messages that were not delivered: send them all again, or clear them out. */
@Composable
private fun FailedCard(failed: Int, toResend: Int, running: Boolean, actions: HistoryActions, onDelete: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("%d messages not delivered", failed), style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            toResend == 0 -> tr("All of them were sent again already.")
                            running -> tr("Resend puts them in the queue, so they go out one after another.")
                            else -> tr("Resend queues them until the radio is on.")
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.onResendAllFailed, enabled = toResend > 0) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Resend failed now (%d)", toResend))
                }
                OutlinedButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Delete failed"))
                }
            }
        }
    }
}

/** Messages waiting for their time, with a way to make a new one. */
@Composable
private fun ScheduledCard(scheduled: List<ScheduledMessage>, running: Boolean, now: Long, actions: HistoryActions, onNew: () -> Unit) {
    SettingsCard(
        icon = RadioIcons.schedule,
        title = if (scheduled.isEmpty()) tr("Scheduled") else tr("Scheduled (%d)", scheduled.size),
        summary = if (scheduled.isEmpty()) tr("Send a message or your position at a set time.")
        else if (running) tr("They go out by themselves at their time.")
        else tr("The radio is off: due messages go out when you turn it on."),
        status = {
            FilledTonalButton(onClick = onNew, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(tr("Schedule"))
            }
        },
    ) {
        scheduled.forEachIndexed { i, m ->
            if (i > 0) RowDivider()
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (m.isPosition) Icons.Filled.LocationOn else RadioIcons.schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(History.scheduledTitle(m), style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                    val time = remember(m.dueAt) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(m.dueAt)) }
                    val repeat = if (m.repeat == Repeat.ONCE) "" else " · ${History.repeatLabel(m.repeat)}"
                    Text(
                        "$time · ${History.dueIn(m.dueAt, now)}$repeat",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { actions.onSendScheduledNow(m.id) }) { Text(tr("Send now")) }
                IconButton(onClick = { actions.onCancelScheduled(m.id) }) {
                    Icon(Icons.Filled.Close, contentDescription = tr("Cancel scheduled message"))
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(r: TransferRecord, actions: HistoryActions, resent: Boolean, running: Boolean) {
    val clipboard = LocalClipboardManager.current
    val time = remember(r.updatedAt) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.updatedAt)) }
    var details by rememberSaveable(r.id) { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(contentIcon(r.contentType))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    val dir = if (r.direction == Direction.OUTGOING) tr("Sent") else tr("Received")
                    val who = when (r.direction) {
                        Direction.INCOMING -> r.peer?.let { tr(" from %s", it) } ?: ""
                        Direction.OUTGOING -> r.peer?.let { tr(" to %s", it) } ?: ""
                    }
                    Text("$dir$who", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val lock = if (r.encrypted) tr(" · encrypted") else ""
                    Text("$time$lock", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill(statusLabel(r.status), statusHealth(r.status))
                IconButton(onClick = { actions.onDelete(r.id) }) { Icon(Icons.Filled.Delete, contentDescription = tr("Delete")) }
            }
            Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    r.contentType == ContentType.TEXT && r.text != null -> Text(r.text!!, style = MaterialTheme.typography.bodyLarge)
                    r.contentType?.isImage == true && r.payload != null ->
                        PayloadImage(displayImageBytes(r.contentType, r.payload!!), "Image ${r.name}", Modifier.fillMaxWidth().heightIn(max = 240.dp))
                    r.contentType == ContentType.BINARY && r.payload != null ->
                        AttachmentTile(RadioIcons.file, r.name.ifBlank { tr("without name") }, Presenters.formatBytes(r.payload!!.size)) {}
                    r.contentType == ContentType.VOICE && r.payload != null -> {
                        val secs = (VoiceNote.decode(r.payload!!)?.durationMs() ?: 0L).plus(500) / 1000
                        AttachmentTile(RadioIcons.mic, tr("Voice note"), "$secs s") {
                            IconButton(onClick = { actions.onPlayVoice(r) }) { Icon(Icons.Filled.PlayArrow, contentDescription = tr("Play")) }
                        }
                    }
                    r.payload != null -> Text("${r.payload!!.size} bytes ${r.name}")
                    else -> Unit
                }
                if (r.direction == Direction.OUTGOING) Receipt(r)
                if (details) {
                    val chunks = if (r.totalChunks > 0) {
                        "${r.receivedChunks.takeIf { r.direction == Direction.INCOMING } ?: r.totalChunks}/${r.totalChunks} chunks · " +
                            "FEC fixed ${r.fecCorrectedBytes} B · loss %.1f%% · repairs ${r.repairRounds}".format(r.lossPercent)
                    } else {
                        null
                    }
                    Text(
                        listOfNotNull(
                            "%04X".format(r.sessionId) + (r.profile?.let { " · ${it.lowercase()}" } ?: ""),
                            chunks,
                        ).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val savable = r.payload != null && (r.contentType?.isImage == true || r.contentType == ContentType.BINARY)
            val canResend = History.canResend(r)
            val copyable = r.contentType == ContentType.TEXT && r.text != null
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canResend) {
                    FilledTonalButton(onClick = { actions.onResend(r) }, enabled = running) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (resent) tr("Resend again") else tr("Resend now"))
                    }
                    if (resent) Text(tr("Sent again"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                if (savable && r.contentType?.isImage == true) {
                    TextButton(onClick = { actions.onSaveToGallery(r) }) { Text(tr("Save to gallery")) }
                }
                if (savable) TextButton(onClick = { actions.onSave(r) }) { Text(tr("Save as file")) }
                if (copyable) TextButton(onClick = { clipboard.setText(AnnotatedString(r.text!!)) }) { Text(tr("Copy text")) }
                TextButton(onClick = { details = !details }) { Text(if (details) tr("Hide details") else tr("Details")) }
            }
        }
    }
}

/** The round icon for a record: text, picture, file, voice or position. */
private fun contentIcon(type: ContentType?): ImageVector = when {
    type == ContentType.TEXT -> Icons.Filled.Email
    type?.isImage == true -> RadioIcons.image
    type == ContentType.VOICE -> RadioIcons.mic
    type == ContentType.POSITION -> Icons.Filled.LocationOn
    else -> RadioIcons.file
}

private fun statusHealth(s: TransferStatus): Health = when (s) {
    TransferStatus.DELIVERED, TransferStatus.COMPLETE -> Health.OK
    TransferStatus.SENDING, TransferStatus.RECEIVING -> Health.ATTENTION
    TransferStatus.INCOMPLETE -> Health.ATTENTION
    TransferStatus.FAILED -> Health.PROBLEM
    TransferStatus.CANCELLED -> Health.IDLE
}

/** Delivery receipt for a sent message: who confirmed it, or where it stands. */
@Composable
private fun Receipt(r: TransferRecord) {
    val (text, color) = when (r.status) {
        TransferStatus.DELIVERED -> {
            val who = r.deliveredTo.map { if (it == "?") tr("a station without callsign") else it }
            (if (who.isEmpty()) tr("Delivered") else tr("Delivered to %s", who.joinToString(", "))) to RadioColors.ok
        }
        TransferStatus.SENDING -> (
            if (r.deliveredTo.isEmpty()) tr("Sent, waiting for confirmation") else tr("Confirmed so far by %s", r.deliveredTo.joinToString(", "))
            ) to MaterialTheme.colorScheme.onSurfaceVariant
        TransferStatus.FAILED -> tr("Not delivered") to RadioColors.missing
        TransferStatus.CANCELLED -> tr("Cancelled") to MaterialTheme.colorScheme.onSurfaceVariant
        else -> return
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}

private fun statusLabel(s: TransferStatus): String = when (s) {
    TransferStatus.SENDING -> tr("sending")
    TransferStatus.DELIVERED -> tr("delivered")
    TransferStatus.RECEIVING -> tr("receiving")
    TransferStatus.INCOMPLETE -> tr("incomplete")
    TransferStatus.COMPLETE -> tr("complete")
    TransferStatus.FAILED -> tr("failed")
    TransferStatus.CANCELLED -> tr("cancelled")
}
