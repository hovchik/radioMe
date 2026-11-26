package com.radiome.feature.transceiver

import com.radiome.core.protocol.link.Direction
import com.radiome.core.protocol.payload.ContentType
import com.radiome.data.TransferRecord
import com.radiome.data.TransferStatus
import com.radiome.feature.transceiver.share.HistoryExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class HistoryExportTest {
    private fun record(
        id: Long, dir: Direction, type: ContentType, text: String? = null, name: String = "", bytes: ByteArray? = null,
        peer: String? = null, at: Long = 0, delivered: List<String> = emptyList(),
    ) = TransferRecord(
        id, 0x1000 + id.toInt(), dir, if (dir == Direction.OUTGOING) TransferStatus.DELIVERED else TransferStatus.COMPLETE,
        type, name, text, bytes, 1, 1, 0, 0.0, 0, "STANDARD", at, at, peer, encrypted = false, deliveredTo = delivered,
    )

    @Test
    fun logListsEntriesOldestFirst() {
        val utc = TimeZone.getTimeZone("UTC")
        val log = HistoryExport.text(
            listOf(
                record(2, Direction.INCOMING, ContentType.BINARY, name = "route.gpx", bytes = ByteArray(300), peer = "EK2B", at = 120_000),
                record(1, Direction.OUTGOING, ContentType.TEXT, text = "hello\nthere", peer = "EK2B", delivered = listOf("EK2B")),
            ),
            utc,
        )
        val lines = log.lines()
        assertEquals("RadioMe history, 2 entries", lines[0])
        assertEquals("[1970-01-01 00:00] Sent to EK2B (delivered, standard, confirmed by EK2B)", lines[2])
        assertEquals("  hello", lines[3])
        assertEquals("  there", lines[4])
        assertTrue(lines[5].startsWith("[1970-01-01 00:02] Received from EK2B"))
        assertEquals("  File route.gpx, 300 B", lines[6])
    }

    @Test
    fun savedNamesKeepTheFileName() {
        assertEquals("route.gpx", HistoryExport.suggestedName(record(1, Direction.INCOMING, ContentType.BINARY, name = "route.gpx")))
        assertEquals("radiome-1001.webp", HistoryExport.suggestedName(record(1, Direction.INCOMING, ContentType.IMAGE_WEBP)))
    }
}
