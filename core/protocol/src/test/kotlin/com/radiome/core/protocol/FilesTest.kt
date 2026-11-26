package com.radiome.core.protocol

import com.radiome.core.dsp.modem.ModemProfile
import com.radiome.core.protocol.payload.ContentType
import com.radiome.core.protocol.payload.Files
import com.radiome.core.protocol.session.Packetizer
import com.radiome.core.protocol.session.SessionHeader
import com.radiome.core.protocol.session.TransferOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FilesTest {
    @Test
    fun longNamesKeepTheirExtension() {
        assertEquals("route.gpx", Files.fitName("route.gpx", 31))
        val fitted = Files.fitName("Expedition to Aragats summit day two.gpx", 20)
        assertTrue(fitted.endsWith("~.gpx"), fitted)
        assertTrue(fitted.toByteArray().size <= 20)
        assertEquals("report.pdf", Files.fitName("/storage/emulated/0/report.pdf", 31))
        val armenian = Files.fitName("Հաշվետվություն երկար անուն.pdf", 16)
        assertTrue(armenian.toByteArray().size <= 16 && armenian.endsWith(".pdf"))
        assertEquals("file", Files.fitName("  ", 10))
    }

    @Test
    fun fileNameSurvivesTheHeader() {
        val p = 48
        val payload = Files.payload("Expedition to Aragats summit day two.gpx", ByteArray(300) { it.toByte() }, p - SessionHeader.FIXED_SIZE)
        assertEquals(ContentType.BINARY, payload.type)
        val t = Packetizer.packetize(payload, 7, TransferOptions(profile = ModemProfile.STANDARD, chunkPayloadSize = p))
        val decoded = SessionHeader.decode(t.headerFrame.payload)!!
        assertEquals(payload.name, decoded.name)
        assertTrue(decoded.name.endsWith(".gpx"))
        assertEquals("application/gpx+xml", Files.mimeType(decoded.name))
    }
}
