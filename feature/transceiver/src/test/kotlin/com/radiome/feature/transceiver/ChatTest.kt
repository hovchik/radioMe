package com.radiome.feature.transceiver

import com.radiome.core.dsp.modem.ModemProfile
import com.radiome.core.protocol.link.Direction
import com.radiome.core.protocol.link.StationInfo
import com.radiome.core.protocol.payload.ContentType
import com.radiome.core.protocol.payload.Payload
import com.radiome.core.protocol.session.TransferOptions
import com.radiome.data.TransferRecord
import com.radiome.data.TransferStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTest {
    private fun rec(
        id: Long,
        dir: Direction,
        peer: String?,
        at: Long,
        text: String = "msg $id",
        type: ContentType = ContentType.TEXT,
        status: TransferStatus = if (dir == Direction.OUTGOING) TransferStatus.DELIVERED else TransferStatus.COMPLETE,
        broadcast: Boolean = false,
    ) = TransferRecord(
        id, 0x100 + id.toInt(), dir, status, type, "", text, text.toByteArray(), 1, 1, 0, 0.0, 0, "STANDARD", at, at,
        peer, broadcast = broadcast,
    )

    @Test
    fun recordsAreGroupedByStationWithBroadcastsInAllStations() {
        val records = listOf(
            rec(1, Direction.OUTGOING, "EK2B", 100),
            rec(2, Direction.INCOMING, "EK2B", 200),
            rec(3, Direction.INCOMING, "EK3C", 300, broadcast = true),
            rec(4, Direction.OUTGOING, null, 400),
            rec(5, Direction.INCOMING, "ek4d", 500),
            rec(6, Direction.INCOMING, "EK2B", 600, type = ContentType.POSITION),
        )
        val threads = Chat.threads(records, emptyList(), emptyMap(), defaultReadMs = 0)
        assertEquals(listOf(null, "EK4D", "EK2B"), threads.map { it.peer })
        assertEquals(listOf(3L, 4L), Chat.messages(records, null).map { it.id })
        // Positions belong to the map.
        assertEquals(listOf(1L, 2L), Chat.messages(records, "ek2b").map { it.id })
        assertEquals(2L, threads.last().last!!.id)
    }

    @Test
    fun unreadCountsIncomingAfterTheReadMarker() {
        val records = listOf(
            rec(1, Direction.INCOMING, "EK2B", 100),
            rec(2, Direction.INCOMING, "EK2B", 200),
            rec(3, Direction.OUTGOING, "EK2B", 300),
            rec(4, Direction.INCOMING, "EK2B", 400, status = TransferStatus.RECEIVING),
        )
        assertEquals(2, Chat.threads(records, emptyList(), emptyMap(), 0).last().unread)
        assertEquals(1, Chat.threads(records, emptyList(), mapOf("EK2B" to 150L), 0).last().unread)
        assertEquals(0, Chat.threads(records, emptyList(), emptyMap(), 1_000).last().unread)
    }

    @Test
    fun heardStationsWithoutMessagesAreListed() {
        val threads = Chat.threads(
            listOf(rec(1, Direction.INCOMING, "EK2B", 100)),
            listOf(HeardStation("EK9Z", 900, 10.0), HeardStation("EK2B", 50, 8.0)),
            emptyMap(), 0,
        )
        assertEquals(listOf(null, "EK9Z", "EK2B"), threads.map { it.peer })
        assertNull(threads[1].last)
        assertEquals(900L, threads[1].lastHeardMs)
    }

    @Test
    fun queuedItemsFollowTheirAddressee() {
        fun q(id: Long, to: String?) = QueuedItem(
            id, "q", Payload.text("q"),
            TransferOptions(profile = ModemProfile.STANDARD, station = StationInfo("EK1A", to)), autoProfile = false,
        )
        val queue = listOf(q(1, "EK2B"), q(2, null), q(3, "ek2b"))
        assertEquals(listOf(1L, 3L), Chat.queued(queue, "EK2B").map { it.id })
        assertEquals(listOf(2L), Chat.queued(queue, null).map { it.id })
    }

    @Test
    fun previewsAndReceipts() {
        assertEquals("You: hi", Chat.preview(rec(1, Direction.OUTGOING, "EK2B", 0, text = "hi")))
        assertEquals("Picture", Chat.preview(rec(2, Direction.INCOMING, "EK2B", 0, type = ContentType.IMAGE_WEBP)))
        assertEquals("Delivered", Chat.receipt(rec(3, Direction.OUTGOING, "EK2B", 0)))
        assertNull(Chat.receipt(rec(4, Direction.INCOMING, "EK2B", 0)))
        val failed = rec(5, Direction.OUTGOING, "EK2B", 0, status = TransferStatus.FAILED)
        assertEquals("Not delivered", Chat.receipt(failed))
        assertTrue(Chat.canResend(failed))
        assertFalse(Chat.canResend(rec(6, Direction.OUTGOING, "EK2B", 0)))
    }
}
