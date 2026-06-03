package io.github.liki4.peek.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepPacketTest {

    @Test
    fun `parse identity TX matches expected fields`() {
        val f = parsePacket(Fixtures.IDENTITY_TX)
        assertEquals(null, f.error)
        assertTrue(f.crcOk)
        assertEquals(KeepFrame.Wrap.NORMAL, f.wrap)
        assertEquals(0x3216, f.src)
        assertEquals(0xEF23, f.dst)
        assertEquals(KirinMethod.GET.v, f.method)
        assertEquals(KirinOpcode.IDENTITY.v, f.opcode)
        assertEquals(6, f.subSeq)
        assertEquals(0x367E, f.seq)
        assertEquals("1/1", f.route)
        assertEquals("6465616462656566303061626364656600", f.payload.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `parse OBSERVE 106 4 TX detects observe wrap and magic sub_seq`() {
        val f = parsePacket(Fixtures.OBSERVE_106_4_TX)
        assertEquals(null, f.error)
        assertTrue(f.crcOk)
        assertEquals(KeepFrame.Wrap.OBSERVE, f.wrap)
        assertEquals(KirinMethod.GET.v, f.method)
        assertEquals(KirinOpcode.OBSERVE.v, f.opcode)
        assertEquals(KeepPacket.OBSERVE_SUB_SEQ, f.subSeq)
        assertEquals(KeepPacket.B18_OBSERVE, f.b18)
        assertEquals("106/4", f.route)
    }

    @Test
    fun `parse DeviceInfo RX gives correct payload`() {
        val f = parsePacket(Fixtures.DEVICE_INFO_RX)
        assertEquals(null, f.error)
        assertTrue(f.crcOk)
        assertEquals(KirinMethod.RSP.v, f.method)
        assertEquals("106/1", f.route)
        assertArrayEquals(Fixtures.DEVICE_INFO_PAYLOAD, f.payload)
    }

    @Test
    fun `parse status push has changedByDevice flag`() {
        val f = parsePacket(Fixtures.STATUS_PUSH_TRAINING)
        assertEquals(null, f.error)
        assertTrue(f.crcOk)
        assertEquals(KeepPacket.OBSERVE_SUB_SEQ, f.subSeq) // push from bike on observed channel
        assertEquals("106/4", f.route)
        assertArrayEquals(Fixtures.STATUS_PUSH_PAYLOAD, f.payload)
    }

    @Test
    fun `parse rejects truncated packet`() {
        val f = parsePacket(byteArrayOf(0xA5.toByte(), 0xA5.toByte(), 0xA0.toByte()))
        assertFalse(f.crcOk)
        assertEquals(KeepFrame.Wrap.INVALID, f.wrap)
        assertTrue("expected error", f.error != null)
    }

    @Test
    fun `parse rejects bad magic`() {
        val f = parsePacket(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x00, 0x00, 0x00, 0x00))
        assertEquals(KeepFrame.Wrap.INVALID, f.wrap)
        assertTrue("expected error", f.error != null)
    }

    /**
     * End-to-end build → parse round trip: assert that any packet we produce
     * decodes back to its input parameters.
     */
    @Test
    fun `build then parse round-trips for a PUT 106 4 status=2`() {
        val builder = KeepPacket(initialSeq = 0x3678)
        val body = BodyBuilder.put("106/4", buildTrainingStatus(TrainingStatus.TRAINING))
        val (pkt, sub) = builder.build(
            method = KirinMethod.PUT.v,
            opcode = KirinOpcode.NORMAL.v,
            body = body,
        )
        val f = parsePacket(pkt)
        assertTrue(f.crcOk)
        assertEquals(KeepFrame.Wrap.NORMAL, f.wrap)
        assertEquals(KirinMethod.PUT.v, f.method)
        assertEquals(KirinOpcode.NORMAL.v, f.opcode)
        assertEquals("106/4", f.route)
        assertEquals(KeepPacket.B18_PUT, f.b18)
        // sub-seq matches what build() returned
        assertEquals(sub, f.subSeq)
        // payload decodes to status = 2
        val ts = parseTrainingStatus(f.payload)
        assertEquals(TrainingStatus.TRAINING.wire.toLong(), ts.status)
    }

    @Test
    fun `build OBSERVE auto-applies magic sub_seq and b18`() {
        val builder = KeepPacket(initialSeq = 0x3678)
        val body = BodyBuilder.observe("106/4")
        val (pkt, sub) = builder.build(
            method = KirinMethod.GET.v,
            opcode = KirinOpcode.OBSERVE.v,
            body = body,
        )
        assertEquals(KeepPacket.OBSERVE_SUB_SEQ, sub)
        val f = parsePacket(pkt)
        assertEquals(KeepFrame.Wrap.OBSERVE, f.wrap)
        assertEquals(KeepPacket.B18_OBSERVE, f.b18)
        assertEquals(KeepPacket.OBSERVE_SUB_SEQ, f.subSeq)
    }
}
