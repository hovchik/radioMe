package com.radiome.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfers ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers WHERE id = :id")
    fun byId(id: Long): TransferEntity?

    @Query("SELECT * FROM transfers WHERE sessionId = :sessionId AND direction = :direction AND updatedAt >= :since ORDER BY createdAt DESC LIMIT 1")
    fun latest(sessionId: Int, direction: String, since: Long): TransferEntity?

    @Query("SELECT * FROM transfers WHERE direction = 'INCOMING' AND status IN ('RECEIVING', 'INCOMPLETE') ORDER BY updatedAt DESC")
    fun incompleteIncoming(): List<TransferEntity>

    @Insert
    fun insert(entity: TransferEntity): Long

    @Update
    fun update(entity: TransferEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertChunks(chunks: List<ChunkEntity>)

    @Query("SELECT * FROM chunks WHERE transferId = :transferId ORDER BY chunkIndex")
    fun chunks(transferId: Long): List<ChunkEntity>

    @Query("SELECT COUNT(*) FROM chunks WHERE transferId = :transferId")
    fun chunkCount(transferId: Long): Int

    @Query("DELETE FROM transfers WHERE id = :id")
    fun delete(id: Long)
}
