package com.radiome.feature.transceiver.share

import com.radiome.core.protocol.link.Direction
import com.radiome.core.protocol.payload.ContentType
import com.radiome.data.TransferRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Plain-text log of the history, oldest first, for sharing or saving. Images and files are listed by name and size. */
object HistoryExport {
    fun text(records: List<TransferRecord>, zone: TimeZone = TimeZone.getDefault()): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { timeZone = zone }
        val sb = StringBuilder("RadioMe history, ${records.size} entries\n\n")
        for (r in records.sortedBy { it.createdAt }) {
            val dir = if (r.direction == Direction.OUTGOING) "Sent" else "Received"
            val who = when {
                r.peer == null -> ""
                r.direction == Direction.OUTGOING -> " to ${r.peer}"
                else -> " from ${r.peer}"
            }
            val flags = listOfNotNull(
                r.status.name.lowercase(),
                r.profile?.lowercase(),
                "encrypted".takeIf { r.encrypted },
                r.deliveredTo.takeIf { it.isNotEmpty() }?.joinToString(prefix = "confirmed by ", separator = ", "),
            ).joinToString(", ")
            sb.append("[").append(fmt.format(Date(r.createdAt))).append("] ").append(dir).append(who).append(" (").append(flags).append(")\n")
            sb.append("  ").append(body(r)).append("\n")
        }
        return sb.toString()
    }

    private fun body(r: TransferRecord): String = when {
        r.contentType == ContentType.TEXT && r.text != null -> r.text!!.replace("\n", "\n  ")
        r.contentType?.isImage == true -> "Image${name(r)}, ${size(r)}"
        r.contentType == ContentType.POSITION -> "Position"
        r.contentType == ContentType.BINARY -> "File${name(r)}, ${size(r)}"
        else -> "(nothing readable)"
    }

    private fun name(r: TransferRecord) = r.name.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
    private fun size(r: TransferRecord) = "${r.payload?.size ?: 0} B"

    /** File name for a saved image or file from the history. */
    fun suggestedName(r: TransferRecord): String = when {
        r.name.isNotBlank() && r.contentType == ContentType.BINARY -> r.name
        r.contentType?.isImage == true -> "radiome-%04x.webp".format(r.sessionId)
        else -> "radiome-%04x.bin".format(r.sessionId)
    }
}
