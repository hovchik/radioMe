package com.radiome.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** One sent or received transfer (text or image), including incomplete ones that can still accept repairs. */
@Entity(
    tableName = "transfers",
    indices = [Index(value = ["sessionId", "direction"]), Index(value = ["updatedAt"])],
)
data class TransferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Int,
    /** Direction name: OUTGOING or INCOMING. */
    val direction: String,
    /** TransferStatus name. */
    val status: String,
    /** ContentType name, null until the HEADER frame arrives. */
    val contentType: String?,
    val name: String,
    /** Decoded UTF-8 for TEXT payloads, for quick display in history. */
    val text: String?,
    /** Full payload once complete (images are a few KB, so a BLOB is fine). */
    val payload: ByteArray?,
    /** Encoded SessionHeader, needed to resume and reassemble a partial transfer. */
    val headerBlob: ByteArray?,
    /** 0 while unknown. */
    val totalChunks: Int,
    val receivedChunks: Int,
    val fecCorrectedBytes: Int,
    val lossPercent: Double,
    val repairRounds: Int,
    val profile: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /** The other station's callsign: who sent an incoming transfer, who an outgoing one is for (null = all). */
    val peer: String? = null,
    /** It travelled sealed with the group key. */
    @ColumnInfo(defaultValue = "0") val encrypted: Boolean = false,
)

/** A received chunk of an incoming transfer. */
@Entity(
    tableName = "chunks",
    primaryKeys = ["transferId", "chunkIndex"],
    foreignKeys = [
        ForeignKey(entity = TransferEntity::class, parentColumns = ["id"], childColumns = ["transferId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class ChunkEntity(
    val transferId: Long,
    val chunkIndex: Int,
    val data: ByteArray,
    val fecRepaired: Boolean,
)
