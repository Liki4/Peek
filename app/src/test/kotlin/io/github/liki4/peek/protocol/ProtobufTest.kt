package io.github.liki4.peek.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtobufTest {

    @Test
    fun `varint round-trips known values`() {
        // Protobuf varint reference values
        val cases = mapOf(
            0L to "00",
            1L to "01",
            127L to "7f",
            128L to "8001",
            300L to "ac02",
            1780126141L to "bda3ead006",  // matches AUTH_TX fixture's timestamp field
        )
        for ((value, hex) in cases) {
            val encoded = Protobuf.varint(value)
            assertEquals("encode $value", hex, encoded.joinToString("") { "%02x".format(it) })
        }
    }

    @Test
    fun `varintField encodes field+value correctly`() {
        // status=2 → field 1, wire type 0 → tag=0x08, then varint(2) = 0x02 → "0802"
        val bytes = Protobuf.varintField(1, 2)
        assertArrayEquals(byteArrayOf(0x08, 0x02), bytes)
    }

    @Test
    fun `stringField round-trips`() {
        val bytes = Protobuf.stringField(1, "CC_23")
        // tag = (1<<3)|2 = 0x0a, length = 5, "CC_23"
        assertArrayEquals(byteArrayOf(0x0A, 0x05, 'C'.code.toByte(), 'C'.code.toByte(), '_'.code.toByte(), '2'.code.toByte(), '3'.code.toByte()), bytes)
    }

    @Test
    fun `fixed32FloatField writes little-endian float`() {
        // 70.0f LE → 0x42 0x8c 0x00 0x00
        // tag = (3<<3)|5 = 0x1d
        val bytes = Protobuf.fixed32FloatField(3, 70.0f)
        assertArrayEquals(byteArrayOf(0x1D, 0x00, 0x00, 0x8C.toByte(), 0x42), bytes)
    }

    @Test
    fun `decode UserInfo payload matches captured fields`() {
        // Payload of AUTH_TX (after route "106/3" + 0xff separator):
        val payload = Fixtures.hex(
            "0a186465616462656566303030303030303030303030303030301210" +
            "646561646265656663616665313233341d00008c4220bda3ead006"
        )
        val decoded = Protobuf.decode(payload)
        assertEquals("deadbeef0000000000000000", decoded[1])
        assertEquals("deadbeefcafe1234", decoded[2])
        assertTrue("weight should be ~70.0", (decoded[3] as Float - 70.0f) < 0.001f)
        assertEquals(1780126141L, decoded[4])
    }

    @Test
    fun `decode TrainData full payload yields expected field map`() {
        // payload "08c6a3ead006 28 01 40 02 58 01" →
        //   field 1 (varint) = 1780126150 (start_time)
        //   field 5 (varint) = 1 (resistance)
        //   field 8 (varint) = 2 (field_8)
        //   field 11 (varint) = 1 (status = IDLE — pre-knob-press)
        val decoded = Protobuf.decode(Fixtures.TRAIN_DATA_FULL_PAYLOAD)
        assertEquals(1780126150L, decoded[1])
        assertEquals(1L, decoded[5])
        assertEquals(2L, decoded[8])
        assertEquals(1L, decoded[11])
    }
}
