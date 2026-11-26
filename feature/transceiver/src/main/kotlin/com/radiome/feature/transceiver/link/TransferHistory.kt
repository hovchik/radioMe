package com.radiome.feature.transceiver.link

import com.radiome.data.TransferRecord
import com.radiome.data.TransferRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Read side of the transfer history for the UI. */
interface TransferHistory {
    fun observe(): Flow<List<TransferRecord>>
    suspend fun delete(id: Long)
}

class RoomTransferHistory(private val repository: TransferRepository) : TransferHistory {
    override fun observe(): Flow<List<TransferRecord>> = repository.observeHistory()

    override suspend fun delete(id: Long) = withContext(Dispatchers.IO) { repository.delete(id) }
}
