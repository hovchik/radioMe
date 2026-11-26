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
import com.radiome.data.TransferRecord
import com.radiome.feature.transceiver.Presenters
import com.radiome.data.TransferStatus
import java.text.DateFormat
import java.util.Date

/** Getting things out of the history: whole log, received files and pictures. */
class HistoryActions(
    val onDelete: (Long) -> Unit,
    val onShareLog: () -> Unit,
    val onSaveLog: () -> Unit,
    /** Save an image or file through the document picker. */
    val onSave: (TransferRecord) -> Unit,
    val onSaveToGallery: (TransferRecord) -> Unit,
)

@Composable
fun HistoryScreen(items: List<TransferRecord>, actions: HistoryActions, modifier: Modifier = Modifier) {
    if (items.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(tr("Nothing sent or received yet."), style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions.onShareLog) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Share log"))
                }
                OutlinedButton(onClick = actions.onSaveLog) { Text(tr("Save log as file")) }
            }
        }
        items(items, key = { it.id }) { HistoryRow(it, actions) }
    }
}

@Composable
private fun HistoryRow(r: TransferRecord, actions: HistoryActions) {
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
