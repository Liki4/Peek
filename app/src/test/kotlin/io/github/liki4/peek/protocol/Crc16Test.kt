package io.github.liki4.peek.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class Crc16Test {

    /** Standard CRC-16/XMODEM vector: "123456789" → 0x31C3. */
    @Test
    fun `xmodem standard vector`() {
        val data = "123456789".toByteArray(Charsets.US_ASCII)
        assertEquals(0x31C3, Crc16.xmodem(data))
    }

    @Test
    fun `xmodem empty input is zero`() {
        assertEquals(0, Crc16.xmodem(ByteArray(0)))
    }

    /** Round-trip each captured packet: CRC(everything-except-last-2-bytes) == last-2-bytes LE. */
    @Test
    fun `every captured packet validates CRC`() {
        val fixtures = listOf(
            "IDENTITY_TX" to Fixtures.IDENTITY_TX,
            "AUTH_TX" to Fixtures.AUTH_TX,
            "OBSERVE_106_4_TX" to Fixtures.OBSERVE_106_4_TX,
            "DEVICE_INFO_RX" to Fixtures.DEVICE_INFO_RX,
            "TRAIN_DATA_RX_RPM14" to Fixtures.TRAIN_DATA_RX_RPM14,
            "STATUS_PUSH_TRAINING" to Fixtures.STATUS_PUSH_TRAINING,
            "TRAIN_DATA_RX_FULL" to Fixtures.TRAIN_DATA_RX_FULL,
        )
        for ((name, pkt) in fixtures) {
            val expected = (pkt[pkt.size - 2].toInt() and 0xFF) or
                ((pkt[pkt.size - 1].toInt() and 0xFF) shl 8)
            val actual = Crc16.xmodem(pkt.copyOfRange(0, pkt.size - 2))
            assertEquals("CRC mismatch for $name", expected, actual)
        }
    }
}
