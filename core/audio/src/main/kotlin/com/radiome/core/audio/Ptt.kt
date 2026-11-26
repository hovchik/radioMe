package com.radiome.core.audio

/**
 * How the radio is keyed for transmit.
 *
 * - [VOX]: the radio's own VOX hears our audio (the lead-in tone opens it). Nothing to set up.
 * - [VOX_TONE]: output goes out in stereo with a steady tone on the right channel, for cable interfaces whose
 *   PTT circuit is driven by audio on that channel (many "VOX-PTT" and SignaLink-style cables).
 * - [SERIAL_RTS], [SERIAL_DTR]: a USB serial adapter's RTS or DTR line keys the radio (Digirig, CP210x, FTDI,
 *   CH34x and CDC-ACM adapters).
 */
enum class PttMode { VOX, VOX_TONE, SERIAL_RTS, SERIAL_DTR }

/** Keys the transmitter around each burst. */
interface PttKeyer : AutoCloseable {
    /** True when the keyer is ready (device found and permitted). */
    val ready: Boolean
    fun key(on: Boolean)
    override fun close() {}
}

/** USB control transfer, as passed to UsbDeviceConnection.controlTransfer. */
data class ControlTransfer(val requestType: Int, val request: Int, val value: Int, val index: Int)

/** USB serial chip families whose modem lines we know how to drive. */
enum class SerialChip { CP210X, FTDI, CH34X, CDC_ACM }

/**
 * Control transfers that set RTS/DTR on the common USB serial chips. Pure, so it is unit tested; the values follow
 * the chip vendors' application notes (CP210x AN571, FTDI D2XX SIO commands, CH340 datasheet, USB CDC PSTN 1.2).
 */
object SerialLines {
    fun chipFor(vendorId: Int, hasCdcInterface: Boolean): SerialChip? = when (vendorId) {
        0x10C4 -> SerialChip.CP210X // Silicon Labs, used by Digirig
        0x0403 -> SerialChip.FTDI
        0x1A86 -> SerialChip.CH34X // WCH
        else -> if (hasCdcInterface) SerialChip.CDC_ACM else null
    }

    /** Transfers to run once after opening, before the lines can be set. */
    fun open(chip: SerialChip, iface: Int): List<ControlTransfer> = when (chip) {
        SerialChip.CP210X -> listOf(ControlTransfer(0x41, 0x00, 0x0001, iface)) // IFC_ENABLE
        else -> emptyList()
    }

    /** Set both modem lines; the one not used for PTT stays low. */
    fun set(chip: SerialChip, iface: Int, rts: Boolean, dtr: Boolean): List<ControlTransfer> = when (chip) {
        // SET_MHS: bit 0 DTR, bit 1 RTS, bits 8/9 = write masks for them.
        SerialChip.CP210X -> listOf(ControlTransfer(0x41, 0x07, 0x0300 or (if (rts) 0x02 else 0) or (if (dtr) 0x01 else 0), iface))
        // SIO_SET_MODEM_CTRL, one line per request: high byte = mask, low byte = state. Index = port, 1-based.
        SerialChip.FTDI -> listOf(
            ControlTransfer(0x40, 0x01, 0x0100 or (if (dtr) 0x01 else 0), iface + 1),
            ControlTransfer(0x40, 0x01, 0x0200 or (if (rts) 0x02 else 0), iface + 1),
        )
        // Modem control: active-low bits, DTR = 0x20, RTS = 0x40.
        SerialChip.CH34X -> listOf(ControlTransfer(0x40, 0xA4, (((if (dtr) 0x20 else 0) or (if (rts) 0x40 else 0)).inv()) and 0xFF, 0))
        // SET_CONTROL_LINE_STATE: bit 0 DTR, bit 1 RTS.
        SerialChip.CDC_ACM -> listOf(ControlTransfer(0x21, 0x22, (if (rts) 0x02 else 0) or (if (dtr) 0x01 else 0), iface))
    }
}
