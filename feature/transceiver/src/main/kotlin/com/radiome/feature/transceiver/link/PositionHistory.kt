package com.radiome.feature.transceiver.link

import com.radiome.data.PositionRepository
import com.radiome.data.StationPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Read side of the decrypted positions for the map. */
interface PositionHistory {
    /** Latest fix per station, ours included, newest first. */
    fun observe(): Flow<List<StationPosition>>

    /** Every fix of the last [windowMs], oldest first, for the trails on the map. */
    fun observeRecent(windowMs: Long): Flow<List<StationPosition>>
    suspend fun clear()
}

class RoomPositionHistory(private val repository: PositionRepository) : PositionHistory {
    override fun observe(): Flow<List<StationPosition>> = repository.observeLatest()
    override fun observeRecent(windowMs: Long): Flow<List<StationPosition>> = repository.observeRecent(windowMs)
    override suspend fun clear() = withContext(Dispatchers.IO) { repository.clear() }
}
