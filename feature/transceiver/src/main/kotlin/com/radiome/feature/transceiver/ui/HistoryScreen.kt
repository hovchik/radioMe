package com.radiome.feature.transceiver.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
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
import com.radiome.data.TransferStatus
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(items: List<TransferRecord>, onDelete: (Long) -> Unit, modifier: Modifier = Modifier) {
    if (items.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing sent or received yet.", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.id }) { HistoryRow(it, { onDelete(it.id) }) }
    }
}

@Composable
private fun HistoryRow(r: TransferRecord, onDelete: () -> Unit) {
    val time = remember(r.updatedAt) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.updatedAt)) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val dir = if (r.direction == Direction.OUTGOING) "Sent" else "Received"
                Column(Modifier.weight(1f)) {
                    val who = when (r.direction) {
                        Direction.INCOMING -> r.peer?.let { " from $it" } ?: ""
                        Direction.OUTGOING -> r.peer?.let { " to $it" } ?: ""
                    }
                    val lock = if (r.encrypted) " · encrypted" else ""
                    Text("$dir$who · ${statusLabel(r.status)}$lock", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "$time · %04X".format(r.sessionId) + (r.profile?.let { " · ${it.lowercase()}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
            }
            when {
                r.contentType == ContentType.TEXT && r.text != null -> Text(r.text!!, style = MaterialTheme.typography.bodyLarge)
                r.contentType?.isImage == true && r.payload != null ->
                    PayloadImage(displayImageBytes(r.contentType, r.payload!!), "Image ${r.name}", Modifier.fillMaxWidth().heightIn(max = 240.dp))
                r.payload != null -> Text("${r.payload!!.size} bytes ${r.name}")
                else -> Unit
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

private fun statusLabel(s: TransferStatus): String = when (s) {
    TransferStatus.SENDING -> "sending"
    TransferStatus.DELIVERED -> "delivered"
    TransferStatus.RECEIVING -> "receiving"
    TransferStatus.INCOMPLETE -> "incomplete"
    TransferStatus.COMPLETE -> "complete"
    TransferStatus.FAILED -> "failed"
    TransferStatus.CANCELLED -> "cancelled"
}
