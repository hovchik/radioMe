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
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { ScheduledCard(scheduled, running, now, actions, onNew = { newSchedule = true }) }
        if (items.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    Text(tr("Nothing sent or received yet."), style = MaterialTheme.typography.bodyLarge)
                }
            }
            return@LazyColumn
        }
        if (failedCount > 0) {
            item { FailedCard(failedCount, toResend.size, running, actions, onDelete = { confirmDeleteFailed = true }) }
        }
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
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text("${tr(f.label)} (${counts[f] ?: 0})") },
                    )
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions.onShareLog) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Share log"))
                }
                OutlinedButton(onClick = actions.onSaveLog) { Text(tr("Save log as file")) }
            }
        }
        if (shown.isEmpty()) {
            item {
                Text(
                    tr("Nothing matches."),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(shown, key = { it.id }) { HistoryRow(it, actions, resent = it.id in resent, running = running) }
    }
}

/** Messages that were not delivered: send them all again, or clear them out. */
@Composable
private fun FailedCard(failed: Int, toResend: Int, running: Boolean, actions: HistoryActions, onDelete: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                tr("%d messages not delivered", failed),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                when {
                    toResend == 0 -> tr("All of them were sent again already.")
                    running -> tr("Resend puts them in the queue, so they go out one after another.")
                    else -> tr("Resend queues them until the radio is on.")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.onResendAllFailed, enabled = toResend > 0) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Resend failed now (%d)", toResend))
                }
                OutlinedButton(onClick = onDelete) { Text(tr("Delete failed")) }
            }
        }
    }
}

/** Messages waiting for their time, with a way to make a new one. */
@Composable
private fun ScheduledCard(scheduled: List<ScheduledMessage>, running: Boolean, now: Long, actions: HistoryActions, onNew: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (scheduled.isEmpty()) tr("Scheduled") else tr("Scheduled (%d)", scheduled.size),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        if (scheduled.isEmpty()) tr("Send a message or your position at a set time.")
                        else if (running) tr("They go out by themselves at their time.")
                        else tr("The radio is off: due messages go out when you turn it on."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                FilledTonalButton(onClick = onNew) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Schedule"))
                }
            }
            scheduled.forEach { m ->
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (m.isPosition) Icons.Filled.LocationOn else Icons.Filled.DateRange,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(History.scheduledTitle(m), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        val time = remember(m.dueAt) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(m.dueAt)) }
                        val repeat = if (m.repeat == Repeat.ONCE) "" else " · ${History.repeatLabel(m.repeat)}"
                        Text("$time · ${History.dueIn(m.dueAt, now)}$repeat", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { actions.onSendScheduledNow(m.id) }) { Text(tr("Send now")) }
                    IconButton(onClick = { actions.onCancelScheduled(m.id) }) {
                        Icon(Icons.Filled.Close, contentDescription = tr("Cancel scheduled message"))
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(r: TransferRecord, actions: HistoryActions, resent: Boolean, running: Boolean) {
    val clipboard = LocalClipboardManager.current
    val time = remember(r.updatedAt) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.updatedAt)) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val dir = if (r.direction == Direction.OUTGOING) tr("Sent") else tr("Received")
                Column(Modifier.weight(1f)) {
                    val who = when (r.direction) {
                        Direction.INCOMING -> r.peer?.let { tr(" from %s", it) } ?: ""
                        Direction.OUTGOING -> r.peer?.let { tr(" to %s", it) } ?: ""
                    }
                    val lock = if (r.encrypted) tr(" · encrypted") else ""
                    Text("$dir$who · ${statusLabel(r.status)}$lock", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "$time · %04X".format(r.sessionId) + (r.profile?.let { " · ${it.lowercase()}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = { actions.onDelete(r.id) }) { Icon(Icons.Filled.Delete, contentDescription = tr("Delete")) }
            }
            when {
                r.contentType == ContentType.TEXT && r.text != null -> Text(r.text!!, style = MaterialTheme.typography.bodyLarge)
                r.contentType?.isImage == true && r.payload != null ->
                    PayloadImage(displayImageBytes(r.contentType, r.payload!!), "Image ${r.name}", Modifier.fillMaxWidth().heightIn(max = 240.dp))
                r.contentType == ContentType.BINARY && r.payload != null ->
                    Text(tr("File %s · %s", r.name.ifBlank { tr("without name") }, Presenters.formatBytes(r.payload!!.size)), style = MaterialTheme.typography.bodyLarge)
                r.contentType == ContentType.VOICE && r.payload != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { actions.onPlayVoice(r) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(tr("Play"))
                    }
                    Spacer(Modifier.width(12.dp))
                    val secs = (VoiceNote.decode(r.payload!!)?.durationMs() ?: 0L).plus(500) / 1000
                    Text(tr("Voice note %d s", secs), style = MaterialTheme.typography.bodyLarge)
                }
                r.payload != null -> Text("${r.payload!!.size} bytes ${r.name}")
                else -> Unit
            }
            val savable = r.payload != null && (r.contentType?.isImage == true || r.contentType == ContentType.BINARY)
            if (savable) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (r.contentType?.isImage == true) {
                        OutlinedButton(onClick = { actions.onSaveToGallery(r) }) { Text(tr("Save to gallery")) }
                    }
                    OutlinedButton(onClick = { actions.onSave(r) }) { Text(tr("Save as file")) }
                }
            }
            if (r.direction == Direction.OUTGOING) Receipt(r)
            val canResend = History.canResend(r)
            val copyable = r.contentType == ContentType.TEXT && r.text != null
            if (canResend || copyable) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (canResend) {
                        if (resent) {
                            Text(tr("Sent again"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        FilledTonalButton(onClick = { actions.onResend(r) }, enabled = running) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (resent) tr("Resend again") else tr("Resend now"))
                        }
                    }
                    if (copyable) {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(r.text!!)) }) { Text(tr("Copy text")) }
                    }
                }
            }
            if (r.totalChunks > 0) {
                Text(
                    "${r.receivedChunks.takeIf { r.direction == Direction.INCOMING } ?: r.totalChunks}/${r.totalChunks} chunks · " +
                        "FEC fixed ${r.fecCorrectedBytes} B · loss %.1f%% · repairs ${r.repairRounds}".format(r.lossPercent),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
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
