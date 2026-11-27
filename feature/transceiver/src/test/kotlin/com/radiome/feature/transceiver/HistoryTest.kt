package com.radiome.feature.transceiver

import com.radiome.core.protocol.link.Direction
import com.radiome.core.protocol.payload.ContentType
import com.radiome.data.TransferRecord
import com.radiome.data.TransferStatus
import com.radiome.feature.transceiver.link.Repeat
import com.radiome.feature.transceiver.link.ScheduledMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {
    private fun rec(
        id: Long,
        dir: Direction,
        status: TransferStatus,
        text: String = "msg $id",
        type: ContentType = ContentType.TEXT,
        peer: String? = null,
        name: String = "",
    ) = TransferRecord(
        id, 0x100 + id.toInt(), dir, status, type, name, text, text.toByteArray(), 1, 1, 0, 0.0, 0, "STANDARD", id * 10, id * 10, peer,
    )

    private val records = listOf(
        rec(1, Direction.OUTGOING, TransferStatus.DELIVERED, "hello Yerevan", peer = "EK2B"),
        rec(2, Direction.OUTGOING, TransferStatus.FAILED, "lost one"),
        rec(3, Direction.INCOMING, TransferStatus.COMPLETE, "reply", peer = "UA3X"),
        rec(4, Direction.OUTGOING, TransferStatus.CANCELLED, "stopped"),
        rec(5, Direction.OUTGOING, TransferStatus.FAILED, "", type = ContentType.POSITION),
        rec(6, Direction.INCOMING, TransferStatus.COMPLETE, "", type = ContentType.BINARY, name = "log.txt"),
    )

    @Test
    fun filtersAndSearch() {
        assertEquals(listOf(1L, 2L, 4L, 5L), History.filter(records, HistoryFilter.SENT, "").map { it.id })
        assertEquals(listOf(3L, 6L), History.filter(records, HistoryFilter.RECEIVED, "").map { it.id })
        assertEquals(listOf(2L, 4L, 5L), History.filter(records, HistoryFilter.FAILED, "").map { it.id })
        assertEquals(listOf(6L), History.filter(records, HistoryFilter.MEDIA, "").map { it.id })
        assertEquals(listOf(1L), History.filter(records, HistoryFilter.ALL, "yerevan").map { it.id })
        assertEquals(listOf(3L), History.filter(records, HistoryFilter.ALL, " ua3 ").map { it.id })
        assertEquals(listOf(6L), History.filter(records, HistoryFilter.ALL, "LOG").map { it.id })
        assertEquals(3, History.counts(records)[HistoryFilter.FAILED])
    }

    @Test
    fun onlyFailedMessagesWithBytesAreResentOnceAndPositionsNever() {
        assertEquals(listOf(2L, 4L), History.toResend(records, emptySet()).map { it.id })
        assertEquals(listOf(4L), History.toResend(records, setOf(2L)).map { it.id })
        assertFalse(History.canResend(records[4]))
        assertFalse(History.canResend(records[0]))
        assertTrue(History.canResend(records[1]))
    }

    @Test
    fun dueInLabels() {
        val now = 1_000_000L
        assertEquals("due now", History.dueIn(now - 5, now))
        assertEquals("in 1 min", History.dueIn(now + 10_000, now))
        assertEquals("in 15 min", History.dueIn(now + 15 * 60_000, now))
        assertEquals("in 2 h", History.dueIn(now + 120 * 60_000, now))
        assertEquals("in 2 h 5 min", History.dueIn(now + 125 * 60_000, now))
    }

    @Test
    fun repeatingMessagesSkipToTheNextSlot() {
        val m = ScheduledMessage(1, "x", null, "", null, dueAt = 1_000, repeat = Repeat.HOURLY)
        assertEquals(1_000 + Repeat.HOURLY.periodMs, m.nextAfter(1_000))
        // The radio was off for three and a half hours: the missed ones are not sent in a burst.
        assertEquals(1_000 + 4 * Repeat.HOURLY.periodMs, m.nextAfter(1_000 + 3 * Repeat.HOURLY.periodMs + 1_800_000))
        assertNull(m.copy(repeat = Repeat.ONCE).nextAfter(5_000))
    }
}
