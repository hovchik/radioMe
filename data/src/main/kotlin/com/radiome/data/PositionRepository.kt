package com.radiome.data

import com.radiome.core.protocol.payload.Position
import com.radiome.data.db.PositionEntity
import com.radiome.data.db.RadioDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A station on the map. */
data class StationPosition(
    val callsign: String,
    val position: Position,
    val receivedAt: Long,
    val own: Boolean,
    val snrDb: Double?,
)

/**
 * Decrypted positions. Blocking DAO calls: use from a background thread. Fixes that are not newer than the last
 * one from the same callsign are dropped, so a recorded beacon played back later cannot move anyone on the map.
 */
class PositionRepository(
    db: RadioDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.positions()

    fun observeLatest(): Flow<List<StationPosition>> = dao.observeLatest().map { list -> list.map { it.toStation() } }

    /** Returns false when the fix was rejected as stale, replayed or from the future. */
    fun addReceived(callsign: String, p: Position, snrDb: Double?): Boolean {
        val last = dao.lastFixTime(callsign)
        if (last != null && p.timeSec <= last) return false
        if (p.timeSec * 1000 > now() + MAX_CLOCK_SKEW_MS) return false
        dao.insert(entity(callsign, p, own = false, snrDb = snrDb))
        return true
    }

    fun addOwn(callsign: String, p: Position) {
        dao.insert(entity(callsign, p, own = true, snrDb = null))
    }

    fun forgetOlderThan(ageMs: Long) = dao.deleteOlderThan(now() - ageMs)

    fun clear() = dao.clear()

    private fun entity(callsign: String, p: Position, own: Boolean, snrDb: Double?) = PositionEntity(
        callsign = callsign,
        latitude = p.latitude,
        longitude = p.longitude,
        altitudeM = p.altitudeM,
        accuracyM = p.accuracyM,
        speedMps = p.speedMps,
        courseDeg = p.courseDeg,
        fixTimeSec = p.timeSec,
        receivedAt = now(),
        own = own,
        snrDb = snrDb?.takeUnless { it.isNaN() },
    )

    private fun PositionEntity.toStation() = StationPosition(
        callsign = callsign,
        position = Position(latitude, longitude, altitudeM, accuracyM, speedMps, courseDeg, fixTimeSec),
        receivedAt = receivedAt,
        own = own,
        snrDb = snrDb,
    )

    companion object {
        /** Senders' clocks may be off a little; a fix more than this far in the future is refused. */
        const val MAX_CLOCK_SKEW_MS = 10 * 60 * 1000L
    }
}
