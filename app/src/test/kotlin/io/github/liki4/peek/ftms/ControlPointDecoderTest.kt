package io.github.liki4.peek.ftms

import io.github.liki4.peek.ftms.ControlPointDecoder.CpRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlPointDecoderTest {

    @Test
    fun `decode request control`() {
        val r = ControlPointDecoder.decode(byteArrayOf(0x00))
        assertTrue(r is CpRequest.RequestControl)
    }

    @Test
    fun `decode reset`() {
        val r = ControlPointDecoder.decode(byteArrayOf(0x01))
        assertTrue(r is CpRequest.Reset)
    }

    @Test
    fun `decode set target resistance`() {
        // sint16 LE = 80 means level 8.0
        val r = ControlPointDecoder.decode(byteArrayOf(0x04, 0x50, 0x00)) as CpRequest.SetTargetResistance
        assertEquals(80, r.tenths)
        assertEquals(8.0f, r.level, 0.001f)
    }

    @Test
    fun `decode set target power`() {
        // sint16 LE 220 = 0xDC 0x00
        val r = ControlPointDecoder.decode(byteArrayOf(0x05, 0xDC.toByte(), 0x00)) as CpRequest.SetTargetPower
        assertEquals(220, r.watts)
    }

    @Test
    fun `decode negative target power`() {
        // sint16 LE -50 = 0xCE 0xFF
        val r = ControlPointDecoder.decode(byteArrayOf(0x05, 0xCE.toByte(), 0xFF.toByte())) as CpRequest.SetTargetPower
        assertEquals(-50, r.watts)
    }

    @Test
    fun `decode indoor bike sim`() {
        // wind = 1500 × 0.001 = 1.5 m/s     → 0xDC 0x05
        // grade = 550 × 0.01 = 5.5 %        → 0x26 0x02
        // crr = 50 × 0.0001 = 0.005        → 0x32
        // cw = 32 × 0.01 = 0.32            → 0x20
        val bytes = byteArrayOf(
            0x11,
            0xDC.toByte(), 0x05,
            0x26.toByte(), 0x02,
            0x32,
            0x20,
        )
        val r = ControlPointDecoder.decode(bytes) as CpRequest.SetIndoorBikeSim
        assertEquals(1.5f, r.windMps, 0.001f)
        assertEquals(5.5f, r.gradePercent, 0.001f)
        assertEquals(0.005f, r.crr, 0.0001f)
        assertEquals(0.32f, r.cw, 0.001f)
    }

    @Test
    fun `decode unknown opcode`() {
        val r = ControlPointDecoder.decode(byteArrayOf(0x07))
        assertTrue(r is CpRequest.Unsupported && r.op == 0x07)
    }

    @Test
    fun `malformed param length`() {
        val r = ControlPointDecoder.decode(byteArrayOf(0x05, 0x10)) // 1-byte param, need 2
        assertTrue(r is CpRequest.Malformed && r.op == 0x05)
    }

    @Test
    fun `respond builds three-byte indicate`() {
        val out = ControlPointDecoder.respond(reqOp = 0x05, resultCode = 0x01)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x05, 0x01), out)
    }
}
