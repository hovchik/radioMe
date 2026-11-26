package com.radiome.data

import com.radiome.core.protocol.link.Direction
import com.radiome.core.protocol.link.TransferTelemetry
import com.radiome.core.protocol.payload.ContentType
import com.radiome.core.protocol.payload.Payload
import com.radiome.core.protocol.payload.Position
import com.radiome.data.db.RadioDatabase
import com.radiome.data.db.TransferEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class TransferStatus {
    /** Outgoing, on air or waiting for the receiver's answer. */
    SENDING,
    /** Outgoing, the receiver confirmed with an ACK. */
    DELIVERED,
    /** Incoming, still arriving. */
    RECEIVING,
    /** Incoming, the sender's over ended with chunks missing; repair bursts can still complete it. */
    INCOMPLETE,
    /** Incoming, reassembled and CRC-32 verified. */
    COMPLETE,
    FAILED,
    CANCELLED,
}

/** History row as the UI sees it. */
data class TransferRecord(
    val id: Long,
    val sessionId: Int,
    val direction: Direction,
    val status: TransferStatus,
    val contentType: ContentType?,
    val name: String,
    val text: String?,
    val payload: ByteArray?,
    val totalChunks: Int,
    val receivedChunks: Int,
    val fecCorrectedBytes: Int,
    val lossPercent: Double,
    val repairRounds: Int,
    val profile: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /** Sender of an incoming transfer, addressee of an outgoing one; null = unknown or all stations. */
    val peer: String? = null,
    val encrypted: Boolean = false,
)

/**
 * History of sent and received transfers. Blocking DAO calls: use from a background thread (the link controller's
 * confined dispatcher, or Dispatchers.IO).
 */
class TransferRepository(
    private val db: RadioDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.transfers()

    fun observeHistory(): Flow<List<TransferRecord>> = dao.observeAll().map { list -> list.map { it.toRecord() } }

    fun byId(id: Long): TransferRecord? = dao.byId(id)?.toRecord()

    fun recordOutgoing(
        sessionId: Int,
        payload: Payload,
        totalChunks: Int,
        profile: String,
        peer: String? = null,
        encrypted: Boolean = false,
    ): Long {
        val t = now()
        return dao.insert(
            TransferEntity(
                sessionId = sessionId,
                direction = Direction.OUTGOING.name,
                status = TransferStatus.SENDING.name,
                contentType = payload.type.name,
                name = payload.name,
                text = displayText(payload),
                payload = payload.bytes,
                headerBlob = null,
                totalChunks = totalChunks,
                receivedChunks = 0,
                fecCorrectedBytes = 0,
                lossPercent = 0.0,
                repairRounds = 0,
                profile = profile,
                createdAt = t,
                updatedAt = t,
                peer = peer,
                encrypted = encrypted || payload.type == ContentType.POSITION,
            ),
        )
    }

    fun setOutgoingStatus(sessionId: Int, status: TransferStatus) = updateLatest(sessionId, Direction.OUTGOING) {
        it.copy(status = status.name)
    }

    /** [unreadable]: it came encrypted and could not be opened; [payload] is then the sealed envelope. */
    fun markReceived(
        sessionId: Int,
        payload: Payload,
        from: String? = null,
        encrypted: Boolean = false,
        unreadable: String? = null,
    ) = updateLatest(sessionId, Direction.INCOMING) {
        it.copy(
            peer = from ?: it.peer,
            encrypted = encrypted,
            status = TransferStatus.COMPLETE.name,
            contentType = payload.type.name,
            name = payload.name,
            text = unreadable?.let { why -> "Encrypted message, not readable here: $why" } ?: displayText(payload),
            payload = payload.bytes,
            receivedChunks = it.totalChunks,
            lossPercent = 0.0,
        )
    }

    fun setIncomingStatus(sessionId: Int, status: TransferStatus) = updateLatest(sessionId, Direction.INCOMING) {
        it.copy(status = status.name)
    }

    /** Copy live counters from the link's telemetry. */
    fun applyTelemetry(t: TransferTelemetry) = updateLatest(t.sessionId, t.direction) {
        it.copy(
            peer = if (t.direction == Direction.INCOMING) t.peer ?: it.peer else it.peer,
            totalChunks = if (t.totalChunks > 0) t.totalChunks else it.totalChunks,
            receivedChunks = if (t.direction == Direction.INCOMING) t.receivedChunks else it.receivedChunks,
            fecCorrectedBytes = t.fecCorrectedBytes,
            lossPercent = t.lossPercent,
            repairRounds = t.repairRounds,
        )
    }

    fun delete(id: Long) = dao.delete(id)

    private fun updateLatest(sessionId: Int, direction: Direction, change: (TransferEntity) -> TransferEntity) {
        db.runInTransaction {
            val e = dao.latest(sessionId, direction.name, now() - RECENT_WINDOW_MS) ?: return@runInTransaction
            dao.update(change(e).copy(updatedAt = now()))
        }
    }

    private fun displayText(p: Payload): String? = when (p.type) {
        ContentType.TEXT -> p.asText()
        ContentType.POSITION -> Position.decode(p.bytes)?.let { "Position %.5f, %.5f".format(java.util.Locale.ROOT, it.latitude, it.longitude) }
        else -> null
    }

    companion object {
        /** A session id is only matched to rows touched this recently (ids are 16-bit and get reused). */
        const val RECENT_WINDOW_MS = 12L * 60 * 60 * 1000
    }
}

internal fun TransferEntity.toRecord() = TransferRecord(
    id = id,
    sessionId = sessionId,
    direction = Direction.valueOf(direction),
    status = TransferStatus.valueOf(status),
    contentType = contentType?.let { runCatching { ContentType.valueOf(it) }.getOrNull() },
    name = name,
    text = text,
    payload = payload,
    totalChunks = totalChunks,
    receivedChunks = receivedChunks,
    fecCorrectedBytes = fecCorrectedBytes,
    lossPercent = lossPercent,
    repairRounds = repairRounds,
    profile = profile,
    createdAt = createdAt,
    updatedAt = updatedAt,
    peer = peer,
    encrypted = encrypted,
)
