package com.radiome.core.dsp.fec

/** CRC-16/CCITT-FALSE: poly 0x1021, init 0xFFFF, no reflection, no final XOR. Check("123456789") = 0x29B1. */
object Crc16 {
    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }
}

/** CRC-8 (poly 0x07, init 0x00), used for the PHY header. Check("123456789") = 0xF4. */
object Crc8 {
    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0
        for (i in offset until offset + length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) (crc shl 1) xor 0x07 else crc shl 1
                crc = crc and 0xFF
            }
        }
        return crc
    }
}
