package io.github.liki4.peek.protocol

object Crc16 {
    /** CRC-16/XMODEM. poly=0x1021, init=0x0000, no reflect, no XOR-out. */
    fun xmodem(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) {
                    (crc shl 1) xor 0x1021
                } else {
                    crc shl 1
                }
                crc = crc and 0xFFFF
            }
        }
        return crc and 0xFFFF
    }
}
