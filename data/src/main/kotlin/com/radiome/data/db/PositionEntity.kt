package com.radiome.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A decrypted GPS fix: one of ours that went out, or one a group member sent. */
@Entity(tableName = "positions", indices = [Index(value = ["callsign"])])
data class PositionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Sender's callsign; ours (possibly blank) for [own] fixes. */
    val callsign: String,
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Int?,
    val accuracyM: Int?,
    val speedMps: Double?,
    val courseDeg: Double?,
    /** When the GPS fix was taken (sender's clock), unix seconds. */
    val fixTimeSec: Long,
    /** When we stored it, ms. */
    val receivedAt: Long,
    val own: Boolean,
    val snrDb: Double?,
)

@Dao
interface PositionDao {
    @Insert
    fun insert(p: PositionEntity): Long

    /** Latest fix per station, newest first. */
    @Query("SELECT * FROM positions WHERE id IN (SELECT MAX(id) FROM positions GROUP BY own, callsign) ORDER BY receivedAt DESC")
    fun observeLatest(): Flow<List<PositionEntity>>

    @Query("SELECT MAX(fixTimeSec) FROM positions WHERE callsign = :callsign AND own = 0")
    fun lastFixTime(callsign: String): Long?

    @Query("SELECT * FROM positions WHERE callsign = :callsign AND own = :own ORDER BY id DESC LIMIT :limit")
    fun track(callsign: String, own: Boolean, limit: Int): List<PositionEntity>

    @Query("DELETE FROM positions WHERE receivedAt < :before")
    fun deleteOlderThan(before: Long)

    @Query("DELETE FROM positions")
    fun clear()
}
