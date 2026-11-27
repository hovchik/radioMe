package com.radiome.feature.transceiver

import com.radiome.core.protocol.link.Direction
import com.radiome.core.protocol.payload.ContentType
import com.radiome.data.TransferRecord
import com.radiome.data.TransferStatus
import com.radiome.feature.transceiver.i18n.tr
import com.radiome.feature.transceiver.link.Repeat
import com.radiome.feature.transceiver.link.ScheduledMessage

/** What the History tab shows. */
enum class HistoryFilter(val label: String) {
    ALL("All"),
    SENT("Sent"),
    RECEIVED("Received"),
    FAILED("Not delivered"),
    MEDIA("Pictures and files"),
}

/** Pure helpers for the History tab, kept out of Compose so they can be unit tested. */
object History {
    /** Sent but not delivered (failed or cancelled), with the bytes still here to send again. Positions are stale by then. */
    fun canResend(r: TransferRecord): Boolean = Chat.canResend(r) && r.contentType != ContentType.POSITION

    fun matches(r: TransferRecord, filter: HistoryFilter): Boolean = when (filter) {
        HistoryFilter.ALL -> true
        HistoryFilter.SENT -> r.direction == Direction.OUTGOING
        HistoryFilter.RECEIVED -> r.direction == Direction.INCOMING
        HistoryFilter.FAILED -> r.direction == Direction.OUTGOING &&
            (r.status == TransferStatus.FAILED || r.status == TransferStatus.CANCELLED)
        HistoryFilter.MEDIA -> r.contentType?.isImage == true || r.contentType == ContentType.BINARY || r.contentType == ContentType.VOICE
    }

    /** Records for [filter] whose text, file name or station contains [query] (any case). */
    fun filter(records: List<TransferRecord>, filter: HistoryFilter, query: String): List<TransferRecord> {
        val q = query.trim()
        return records.filter { r ->
            matches(r, filter) && (
                q.isEmpty() ||
                    r.text?.contains(q, ignoreCase = true) == true ||
                    r.name.contains(q, ignoreCase = true) ||
                    r.peer?.contains(q, ignoreCase = true) == true
                )
        }
    }

    /** How many records each filter would show, for the chip labels. */
    fun counts(records: List<TransferRecord>): Map<HistoryFilter, Int> =
        HistoryFilter.entries.associateWith { f -> records.count { matches(it, f) } }

    /** Failed messages that can go out again and have not been sent again from here already, oldest first. */
    fun toResend(records: List<TransferRecord>, alreadyResent: Set<Long>): List<TransferRecord> =
        records.filter { canResend(it) && it.id !in alreadyResent }.sortedBy { it.updatedAt }

    /** Short name for a record in the queue and in notices. */
    fun label(r: TransferRecord): String = when {
        r.contentType == ContentType.TEXT && r.text != null -> r.text!!.take(40)
        r.contentType?.isImage == true -> tr("Image")
        r.contentType == ContentType.VOICE -> tr("Voice note")
        else -> r.name.ifBlank { tr("File") }
    }

    /** "in 5 min", "in 2 h 10 min", "now" for a scheduled message. */
    fun dueIn(dueAt: Long, now: Long): String {
        val min = (dueAt - now + 59_999) / 60_000
        return when {
            min <= 0 -> tr("due now")
            min < 60 -> tr("in %d min", min)
            min % 60 == 0L -> tr("in %d h", min / 60)
            else -> tr("in %d h %d min", min / 60, min % 60)
        }
    }

    fun repeatLabel(r: Repeat): String = when (r) {
        Repeat.ONCE -> tr("Once")
        Repeat.HOURLY -> tr("Every hour")
        Repeat.DAILY -> tr("Every day")
    }

    fun scheduledTitle(m: ScheduledMessage): String {
        val who = m.to.ifBlank { tr("All stations") }
        return if (m.isPosition) tr("My position to %s", who) else tr("\"%s\" to %s", m.label, who)
    }
}
