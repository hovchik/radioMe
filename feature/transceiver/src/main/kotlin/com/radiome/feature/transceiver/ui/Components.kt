package com.radiome.feature.transceiver.ui

import com.radiome.feature.transceiver.i18n.tr
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import com.radiome.feature.transceiver.settings.Health
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.radiome.core.protocol.link.Direction
import com.radiome.core.protocol.session.ChunkState
import com.radiome.feature.transceiver.InputLevel
import com.radiome.feature.transceiver.Presenters
import com.radiome.feature.transceiver.TransferCard
import com.radiome.feature.transceiver.image.decodeImage
import com.radiome.feature.transceiver.image.decodePartialImage
import com.radiome.core.protocol.image.ImagePreview
import com.radiome.core.protocol.image.ProgressiveImage
import com.radiome.core.protocol.payload.ContentType
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size

/** Horizontal input level bar over -60..0 dBFS, with the "good" range shaded. */
@Composable
fun LevelMeter(dbfs: Double, level: InputLevel, modifier: Modifier = Modifier) {
    val fraction = Presenters.levelFraction(dbfs)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val good = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    val bar = when (level) {
        InputLevel.SILENT, InputLevel.LOW -> MaterialTheme.colorScheme.outline
        InputLevel.GOOD -> RadioColors.ok
        InputLevel.HOT -> RadioColors.missing
    }
    val label = if (dbfs.isInfinite() || dbfs.isNaN()) tr("no signal") else "%.0f dBFS".format(dbfs)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(14.dp)
            .semantics { contentDescription = "Input level $label" },
    ) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        // -40..-3 dBFS is the comfortable range.
        val goodStart = size.width * (20f / 60f)
        val goodEnd = size.width * (57f / 60f)
        drawRect(good, topLeft = Offset(goodStart, 0f), size = Size(goodEnd - goodStart, size.height))
        drawRoundRect(bar, size = Size(size.width * fraction, size.height), cornerRadius = r)
    }
}

/** One cell per chunk, coloured by state; wraps to as many rows as needed. */
@Composable
fun ChunkGrid(states: List<ChunkState>, modifier: Modifier = Modifier) {
    if (states.isEmpty()) {
        // Same height as one row of cells, so the card does not grow when the chunk count becomes known.
        Spacer(modifier.fillMaxWidth().height(8.dp))
        return
    }
    val columns = 40
    val rows = (states.size + columns - 1) / columns
    val ok = RadioColors.ok
    val repaired = RadioColors.repaired
    val missing = RadioColors.missing
    val pending = RadioColors.pending
    Canvas(
        modifier
            .fillMaxWidth()
            .height((rows * 8).dp)
            .semantics { contentDescription = "Chunk map, ${states.size} chunks" },
    ) {
        val cell = size.width / columns
        val h = size.height / rows
        states.forEachIndexed { i, s ->
            val color = when (s) {
                ChunkState.OK -> ok
                ChunkState.REPAIRED -> repaired
                ChunkState.MISSING -> missing
                ChunkState.PENDING -> pending
            }
            drawRect(
                color,
                topLeft = Offset((i % columns) * cell + 1f, (i / columns) * h + 1f),
                size = Size(cell - 2f, h - 2f),
            )
        }
    }
}

@Composable
fun TransferCardView(
    card: TransferCard,
    onRequestCorrections: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val outgoing = card.direction == Direction.OUTGOING
                IconBadge(if (outgoing) Icons.AutoMirrored.Filled.Send else Icons.Filled.Email)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        card.name.ifBlank { card.sessionLabel },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val who = when {
                        !outgoing -> card.peer?.let { tr("From %s", it) } ?: tr("Receiving")
                        else -> card.peer?.let { tr("To %s", it) } ?: tr("To all stations")
                    }
                    Text(who, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Spacer(Modifier.width(8.dp))
                StatusPill(phaseLabel(card.phase), phaseHealth(card.phase))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(progress = { card.progress }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Text("${(card.progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
            }
            card.preview?.let { ProgressivePreview(it, Modifier.size(160.dp)) }
            ChunkGrid(card.chunkStates)
            // Fixed set of single-line rows: numbers change while chunks arrive, the card's height does not.
            val snr = if (card.snrDb.isNaN()) "-" else "%.1f dB".format(card.snrDb)
            MetricRow(
                listOf(
                    tr("Chunks") to "${card.receivedChunks}/${card.totalChunks}",
                    tr("Loss") to "%.1f%%".format(card.lossPercent),
                    "SNR" to snr,
                ),
            )
            StatLine("FEC fixed ${card.fecCorrectedBytes} B in ${card.fecRepairedChunks} chunks · repair rounds ${card.repairRounds}")
            val missing = if (card.missing.isEmpty()) tr("Missing: none") else
                "Missing ${card.missing.size}: " + card.missing.take(12).joinToString(", ") + if (card.missing.size > 12) " ..." else ""
            StatLine(missing, if (card.missing.isEmpty()) null else RadioColors.missing)
            if (card.ackedBy.isNotEmpty()) StatLine("Confirmed by " + card.ackedBy.joinToString(", "), RadioColors.ok)
            // Keeps its height when the buttons come and go.
            Row(Modifier.heightIn(min = 40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (card.canRequestCorrections) {
                    FilledTonalButton(onClick = onRequestCorrections) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(tr("Send correction request"))
                    }
                }
                if (card.canCancel) {
                    TextButton(onClick = onCancel) {
                        Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(tr("Cancel"))
                    }
                }
            }
        }
    }
}

/** Status colour for a transfer phase. */
fun phaseHealth(phase: String): Health = when (phase) {
    "COMPLETE" -> Health.OK
    "AWAITING_REPAIR", "AWAITING_RESPONSE" -> Health.ATTENTION
    "CORRUPT", "FAILED" -> Health.PROBLEM
    "CANCELLED" -> Health.IDLE
    else -> Health.OK
}

@Composable
private fun StatLine(text: String, color: Color? = null) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color ?: MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

fun phaseLabel(phase: String): String = when (phase) {
    "RECEIVING" -> tr("Receiving")
    "AWAITING_REPAIR" -> tr("Incomplete")
    "COMPLETE" -> tr("Complete")
    "CORRUPT" -> tr("Corrupt")
    "TRANSMITTING" -> tr("On air")
    "AWAITING_RESPONSE" -> tr("Waiting for reply")
    "FAILED" -> tr("Failed")
    "CANCELLED" -> tr("Cancelled")
    else -> phase.lowercase().replaceFirstChar { it.uppercase() }
}

/** Shows WebP (or any decodable) bytes scaled up with nearest-neighbour so small images stay crisp. */
@Composable
fun PayloadImage(bytes: ByteArray, description: String, modifier: Modifier = Modifier) {
    val bitmap = remember(bytes) { decodeImage(bytes)?.asImageBitmap() }
    if (bitmap == null) {
        Text(tr("Image could not be decoded"), color = RadioColors.missing, modifier = modifier)
        return
    }
    Image(
        bitmap = bitmap,
        contentDescription = description,
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None,
        modifier = modifier,
    )
}

/**
 * A progressive image on its way: the small level scaled up (smooth, so it reads as a blurry preview) with the
 * rows of the full picture that have arrived drawn over it.
 */
@Composable
fun ProgressivePreview(preview: ImagePreview, modifier: Modifier = Modifier) {
    val base = remember(preview.complete) { preview.complete?.let { decodeImage(it)?.asImageBitmap() } }
    val top = remember(preview.partial?.size) { preview.partial?.let { decodePartialImage(it)?.asImageBitmap() } }
    Box(modifier.semantics { contentDescription = tr("Image arriving, preview") }) {
        val fill = Modifier.matchParentSize()
        base?.let {
            Image(it, null, fill, contentScale = ContentScale.Fit, filterQuality = if (preview.isFinal) FilterQuality.None else FilterQuality.High)
        }
        top?.let { Image(it, null, fill, contentScale = ContentScale.Fit, filterQuality = FilterQuality.None) }
    }
}

/** Bytes to display for a stored image payload: a progressive one shows its sharpest level. */
fun displayImageBytes(type: ContentType?, bytes: ByteArray): ByteArray =
    if (type == ContentType.IMAGE_PROGRESSIVE) ProgressiveImage.finalImage(bytes) ?: bytes else bytes

val RoundedSmall = RoundedCornerShape(8.dp)

