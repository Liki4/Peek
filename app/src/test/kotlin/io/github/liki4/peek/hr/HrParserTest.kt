package io.github.liki4.peek.hr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HrParserTest {

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        return ByteArray(clean.length / 2) {
            ((Character.digit(clean[it * 2], 16) shl 4) or Character.digit(clean[it * 2 + 1], 16)).toByte()
        }
    }

    @Test
    fun `uint8 HR with contact info`() {
        // flags=0x06 (bit1=contact detected, bit2=contact supported), HR=110
        val s = parseHrMeasurement(hex("066e"))!!
        assertEquals(110, s.heartRate)
        assertTrue(s.sensorContactSupported)
        assertTrue(s.sensorContactDetected)
        assertNull(s.energyExpendedKj)
        assertTrue(s.rrIntervalsS.isEmpty())
    }

    @Test
    fun `uint8 HR with RR interval`() {
        // flags=0x16 (uint8 + contact supported + RR present), HR=110, RR=979/1024 ≈ 0.956
        val s = parseHrMeasurement(hex("166ed303"))!!
        assertEquals(110, s.heartRate)
        assertEquals(1, s.rrIntervalsS.size)
        assertEquals(979f / 1024f, s.rrIntervalsS[0], 0.0001f)
    }

    @Test
    fun `uint16 HR format`() {
        // flags=0x01 (uint16 HR), HR=0x0096=150
        val s = parseHrMeasurement(hex("019600"))!!
        assertEquals(150, s.heartRate)
    }

    @Test
    fun `multiple RR intervals`() {
        // flags=0x10 (only RR present), HR=80, then two RRs: 980 and 1020 raw units
        val s = parseHrMeasurement(hex("1050d403fc03"))!!
        assertEquals(80, s.heartRate)
        assertEquals(2, s.rrIntervalsS.size)
        assertEquals(980f / 1024f, s.rrIntervalsS[0], 0.0001f)
        assertEquals(1020f / 1024f, s.rrIntervalsS[1], 0.0001f)
    }

    @Test
    fun `energy expended present`() {
        // flags=0x08 (energy expended), HR=75, EE=0x0100=256 kJ
        val s = parseHrMeasurement(hex("084b0001"))!!
        assertEquals(75, s.heartRate)
        assertEquals(256, s.energyExpendedKj)
    }

    @Test
    fun `empty input returns null`() {
        assertNull(parseHrMeasurement(ByteArray(0)))
    }
}
