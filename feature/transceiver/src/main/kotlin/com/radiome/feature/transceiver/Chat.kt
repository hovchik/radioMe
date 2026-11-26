package com.radiome.feature.transceiver

import com.radiome.core.protocol.link.Direction
import com.radiome.core.protocol.payload.ContentType
import com.radiome.core.protocol.payload.VoiceNote
import com.radiome.data.TransferRecord
import com.radiome.data.TransferStatus
import com.radiome.feature.transceiver.i18n.tr

// The chat screen's view of the history: one conversation per station, plus one for messages to and from every
// station. Pure functions over TransferRecord so they are unit tested on the JVM.

/** One row of the chat list. [peer] null is the "All stations" conversation. */
data class ChatThread(
    val peer: String?,
    /** Newest message in it, or null for a station we only heard. */
    val last: TransferRecord?,
    val unread: Int,
    /** When the station's callsign was last decoded, if it was. */
    val lastHeardMs: Long? = null,
) {
    val key: String get() = Chat.key(peer)
    val activityMs: Long get() = maxOf(last?.updatedAt ?: 0L, lastHeardMs ?: 0L)
}

object Chat {
    /** Read markers are keyed by callsign; "" is the "All stations" conversation. */
    fun key(peer: String?): String = peer?.trim()?.uppercase().orEmpty()

    /** Positions belong to the map, not the chat. */
    fun isChat(r: TransferRecord): Boolean = r.contentType != ContentType.POSITION

    /** Which conversation a record belongs to: the other station's callsign, or null for "All stations". */
    fun peerOf(r: TransferRecord): String? = when {
        r.direction == Direction.INCOMING && r.broadcast -> null
        else -> r.peer?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
    }

    /**
     * The chat list. "All stations" is always first; the rest are newest activity first. Stations we heard but never
     * exchanged a message with are listed too, so starting a chat is one tap. Incoming messages newer than a
     * conversation's read marker (or [defaultReadMs] when it has none) count as unread.
     */
    fun threads(
        records: List<TransferRecord>,
        heard: List<HeardStation>,
        readMarkers: Map<String, Long>,
        defaultReadMs: Long,
    ): List<ChatThread> {
        val byPeer = records.filter(::isChat).groupBy(::peerOf)
        val heardAt = heard.associate { it.callsign.uppercase() to it.lastHeardMs }
        fun thread(peer: String?, list: List<TransferRecord>): ChatThread {
            val read = readMarkers[key(peer)] ?: defaultReadMs
            return ChatThread(
                peer = peer,
                last = list.maxByOrNull { it.createdAt },
                unread = list.count { it.direction == Direction.INCOMING && it.status == TransferStatus.COMPLETE && it.updatedAt > read },
                lastHeardMs = peer?.let { heardAt[it] },
            )
        }
        val all = thread(null, byPeer[null].orEmpty())
        val stations = (byPeer.keys.filterNotNull() + heardAt.keys).distinct().map { thread(it, byPeer[it].orEmpty()) }
        return listOf(all) + stations.sortedByDescending { it.activityMs }
    }

    /** Messages of one conversation, oldest first. */
    fun messages(records: List<TransferRecord>, peer: String?): List<TransferRecord> {
        val k = key(peer).ifEmpty { null }
        return records.filter { isChat(it) && peerOf(it) == k }.sortedWith(compareBy({ it.createdAt }, { it.id }))
    }

    /** Queued items addressed to this conversation (they are not in the history until they go out). */
    fun queued(queue: List<QueuedItem>, peer: String?): List<QueuedItem> {
        val k = key(peer)
        return queue.filter { key(it.options.station?.destination) == k }
    }

    /** One-line summary for the chat list. */
    fun preview(r: TransferRecord): String {
        val body = when {
            r.status == TransferStatus.RECEIVING || r.status == TransferStatus.INCOMPLETE -> tr("Receiving...")
            r.contentType == ContentType.TEXT -> r.text.orEmpty()
            r.contentType?.isImage == true -> tr("Picture")
            r.contentType == ContentType.VOICE -> tr("Voice note %d s", voiceSeconds(r))
            r.contentType == ContentType.BINARY -> tr("File %s", r.name.ifBlank { tr("without name") })
            r.contentType == ContentType.ENCRYPTED -> tr("Encrypted message")
            else -> r.text ?: r.name
        }.replace('\n', ' ')
        return if (r.direction == Direction.OUTGOING) tr("You: %s", body) else body
    }

    fun voiceSeconds(r: TransferRecord): Long =
        r.payload?.let { VoiceNote.decode(it)?.durationMs() }?.plus(500)?.div(1000) ?: 0L

    /** Delivery line under a sent message. */
    fun receipt(r: TransferRecord): String? {
        if (r.direction != Direction.OUTGOING) return null
        val who = r.deliveredTo.map { if (it == "?") tr("a station without callsign") else it }
        return when (r.status) {
            TransferStatus.DELIVERED -> if (who.isEmpty()) tr("Delivered") else tr("Delivered to %s", who.joinToString(", "))
            TransferStatus.SENDING -> if (who.isEmpty()) tr("Sent, waiting for confirmation") else tr("Confirmed so far by %s", who.joinToString(", "))
            TransferStatus.FAILED -> tr("Not delivered")
            TransferStatus.CANCELLED -> tr("Cancelled")
            else -> null
        }
    }

    /** A failed or cancelled message we still hold the bytes of can go out again. */
    fun canResend(r: TransferRecord): Boolean =
        r.direction == Direction.OUTGOING && r.payload != null && r.contentType != null &&
            (r.status == TransferStatus.FAILED || r.status == TransferStatus.CANCELLED)
}
