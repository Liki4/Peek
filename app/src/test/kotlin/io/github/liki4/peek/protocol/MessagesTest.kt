package io.github.liki4.peek.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessagesTest {

    @Test
    fun `parseDeviceInfo matches captured CC_23 values`() {
        val d = parseDeviceInfo(Fixtures.DEVICE_INFO_PAYLOAD)
        assertEquals("CC_23", d.model)
        assertEquals("C3S1XW4010200901", d.hwId)
        assertEquals(261L, d.statusBitfield)
        assertEquals("1.0", d.hwVersion)
        assertEquals("1.0.12", d.fwVersion)
        assertEquals(312843L, d.totalDurationS)
        assertEquals(1262471L, d.totalDistanceM)
        assertEquals(261L, d.field9)
    }

    @Test
    fun `parseTrainData full payload reads start_time + resistance + field8 + status`() {
        val td = parseTrainData(Fixtures.TRAIN_DATA_FULL_PAYLOAD)
        assertEquals(1780126150L, td.startTime)
        assertEquals(1L, td.resistance)
        assertEquals(2L, td.field8)
        assertEquals(1L, td.status)
        // Fields zero-suppressed in this snapshot:
        assertNull(td.distanceM)
        assertNull(td.durationS)
        assertNull(td.calorie)
        assertNull(td.rpm)
        assertNull(td.watt)
    }

    @Test
    fun `parseTrainData mid-ride payload extracts rpm and watt`() {
        // From TRAIN_DATA_RX_RPM14: payload "300e38044001"
        //   field 6 (rpm) = 14
        //   field 7 (watt) = 4
        //   field 8 = 1
        val payload = Fixtures.hex("300e38044001")
        val td = parseTrainData(payload)
        assertEquals(14L, td.rpm)
        assertEquals(4L, td.watt)
        assertEquals(1L, td.field8)
    }

    @Test
    fun `parseTrainingStatus decodes TRAINING with changedByDevice`() {
        val r = parseTrainingStatus(Fixtures.STATUS_PUSH_PAYLOAD)
        assertEquals(2L, r.status)
        assertEquals("TRAINING", r.statusName)
        assertEquals(1L, r.changedByDevice)
    }

    @Test
    fun `parseTrainLogResponse handles empty payload`() {
        val r = parseTrainLogResponse(ByteArray(0))
        assertEquals(TrainLogResponse.Kind.EMPTY, r.kind)
        assertEquals(0, r.segments.size)
        assertNull(r.summary)
    }

    @Test
    fun `buildUserInfo matches AUTH_TX payload`() {
        val payload = buildUserInfo(
            userId = "deadbeef0000000000000000",
            deviceId = "deadbeefcafe1234",
            weightKg = 70.0f,
            timestampS = 1780126141L,
        )
        // Captured wire payload for the 106/3 PUT:
        val expected = Fixtures.hex(
            "0a186465616462656566303030303030303030303030303030301210" +
            "646561646265656663616665313233341d00008c4220bda3ead006"
        )
        org.junit.Assert.assertArrayEquals(expected, payload)
    }

    @Test
    fun `buildTrainingStatus produces 0x0802 for TRAINING`() {
        org.junit.Assert.assertArrayEquals(
            byteArrayOf(0x08, 0x02),
            buildTrainingStatus(TrainingStatus.TRAINING),
        )
    }

    @Test
    fun `buildTrainAttributeSetResistance encodes level only when no grade`() {
        org.junit.Assert.assertArrayEquals(
            byteArrayOf(0x08, 0x0C),
            buildTrainAttributeSetResistance(12),
        )
    }
}
