package com.radiome.data.store

import com.radiome.core.protocol.link.Direction
import com.radiome.core.protocol.link.SessionStore
import com.radiome.core.protocol.session.ReceiverSnapshot
import com.radiome.core.protocol.session.SessionHeader
import com.radiome.data.TransferRepository
import com.radiome.data.TransferStatus
import com.radiome.data.db.ChunkEntity
import com.radiome.data.db.RadioDatabase
import com.radiome.data.db.TransferEntity

/**
 * [SessionStore] backed by Room: every chunk the receiver accepts is persisted, so a partial transfer survives an
 * app restart and later repair bursts merge into it.
 */
class RoomSessionStore(
    private val db: RadioDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : SessionStore {
    private val dao = db.transfers()

    override fun save(snapshot: ReceiverSnapshot) {
        db.runInTransaction {
            val t = now()
            // A reused 16-bit id that describes different content starts a new row; the old history entry stays.
            val existing = dao.latest(snapshot.sessionId, Direction.INCOMING.name, t - TransferRepository.RECENT_WINDOW_MS)
                ?.takeIf { it.isSameTransferAs(snapshot) }
            val headerBlob = snapshot.header?.encode(HEADER_CAPACITY)
            val base = existing ?: TransferEntity(
                sessionId = snapshot.sessionId,
                direction = Direction.INCOMING.name,
                status = TransferStatus.RECEIVING.name,
                contentType = null,
                name = "",
                text = null,
                payload = null,
                headerBlob = null,
                totalChunks = 0,
                receivedChunks = 0,
                fecCorrectedBytes = 0,
                lossPercent = 100.0,
                repairRounds = 0,
                profile = null,
                createdAt = t,
                updatedAt = t,
            )
            val total = snapshot.totalChunks ?: 0
            val updated = base.copy(
                headerBlob = headerBlob ?: base.headerBlob,
                // Once the message is stored (TransferRepository.markReceived), keep its type and name: for a sealed
                // message they come from inside the envelope, and a later header repeat would put ENCRYPTED back.
                contentType = if (base.payload != null) base.contentType else snapshot.header?.contentType?.name ?: base.contentType,
                name = if (base.payload != null) base.name else snapshot.header?.name ?: base.name,
                totalChunks = total,
                receivedChunks = snapshot.chunks.size,
                fecCorrectedBytes = snapshot.fecCorrectedBytes,
                lossPercent = if (total > 0) (total - snapshot.chunks.size) * 100.0 / total else 100.0,
                repairRounds = snapshot.repairRounds,
                // Status (INCOMPLETE / COMPLETE) is owned by TransferRecorder, which sees the link's verdicts.
                updatedAt = t,
            )
            val id = if (existing == null) dao.insert(updated) else updated.id.also { dao.update(updated) }
            dao.insertChunks(
                snapshot.chunks.map { (index, data) -> ChunkEntity(id, index, data, index in snapshot.repairedChunks) },
            )
        }
    }

    private fun TransferEntity.isSameTransferAs(s: ReceiverSnapshot): Boolean {
        val total = s.totalChunks
        if (totalChunks > 0 && total != null && totalChunks != total) return false
        val stored = headerBlob?.let { SessionHeader.decode(it) } ?: return true
        val incoming = s.header ?: return true
        return stored.crc32 == incoming.crc32 && stored.wireLength == incoming.wireLength && stored.contentType == incoming.contentType
    }

    override fun load(sessionId: Int): ReceiverSnapshot? {
        val e = dao.latest(sessionId, Direction.INCOMING.name, now() - TransferRepository.RECENT_WINDOW_MS) ?: return null
        return e.toSnapshot()
    }

    override fun all(): List<ReceiverSnapshot> = dao.incompleteIncoming().map { it.toSnapshot() }

    private fun TransferEntity.toSnapshot(): ReceiverSnapshot {
        val chunks = dao.chunks(id)
        return ReceiverSnapshot(
            sessionId = sessionId,
            totalChunks = totalChunks.takeIf { it > 0 },
            header = headerBlob?.let { SessionHeader.decode(it) },
            chunks = chunks.associate { it.chunkIndex to it.data },
            repairedChunks = chunks.filter { it.fecRepaired }.map { it.chunkIndex }.toSet(),
            fecCorrectedBytes = fecCorrectedBytes,
            repairRounds = repairRounds,
        )
    }

    private companion object {
        /** Large enough that the stored header keeps the full name. */
        const val HEADER_CAPACITY = 200
    }
}
