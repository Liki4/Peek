package io.github.liki4.peek.ftms

import io.github.liki4.peek.ride.RideUiState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden-bytes checks of the Indoor Bike Data (0x2AD2) encoder against
 * hand-computed FTMS frames. Subscribers (Zwift et al.) are strict about
 * field order, so we verify the literal byte sequence.
 *
 * Flag bits used:
 *   bit 2 = INSTANT_CADENCE_PRES  → 0x0004
 *   bit 4 = TOTAL_DISTANCE_PRES   → 0x0010
 *   bit 5 = RESISTANCE_LEVEL_PRES → 0x0020
 *   bit 6 = INSTANT_POWER_PRESENT → 0x0040
 *   bit 9 = HEART_RATE_PRESENT    → 0x0200
 */
class IndoorBikeDataEncoderTest {

    @Test
    fun `full payload with HR`() {
        // speed 25.50 km/h → 2550 (0xF6 0x09)
        // cadence 84 rpm → 168 (0xA8 0x00)
        // distance 1234 m → 0xD2 0x04 0x00
        // resistance 8 → 0x08 0x00
        // power 220 W → 0xDC 0x00
        // HR 152 bpm → 0x98
        // flags = bits 2|4|5|6|9 = 0x0274
        val live = RideUiState.LiveMetrics(
            rpm = 84, watt = 220, resistance = 8,
            speedKmh = 25.50f, hrBpm = 152,
            distanceM = 1234, calorieKcal = 0,
        )
        val expected = byteArrayOf(
            0x74.toByte(), 0x02,        // flags
            0xF6.toByte(), 0x09,        // speed 2550
            0xA8.toByte(), 0x00,        // cadence 168 = 84*2
            0xD2.toByte(), 0x04, 0x00,  // distance 1234 (uint24 LE)
            0x08, 0x00,                 // resistance 8
            0xDC.toByte(), 0x00,        // power 220
            0x98.toByte(),              // HR 152
        )
        val actual = IndoorBikeDataEncoder.encode(live)
        assertArrayEquals("payload mismatch", expected, actual)
    }

    @Test
    fun `omit HR when null`() {
        val live = RideUiState.LiveMetrics(
            rpm = 90, watt = 180, resistance = 6,
            speedKmh = 22f, hrBpm = null,
            distanceM = 5000, calorieKcal = 0,
        )
        val out = IndoorBikeDataEncoder.encode(live)
        // flags = 2|4|5|6 = 0x0074
        assertEquals(0x74.toByte(), out[0])
        assertEquals(0x00.toByte(), out[1])
        // No HR byte at end: total length = 2 + 2 + 2 + 3 + 2 + 2 = 13
        assertEquals(13, out.size)
    }

    @Test
    fun `omit cadence and power when both null`() {
        val live = RideUiState.LiveMetrics(
            rpm = null, watt = null, resistance = 1,
            speedKmh = 0f, hrBpm = null,
            distanceM = 0, calorieKcal = 0,
        )
        val out = IndoorBikeDataEncoder.encode(live)
        // flags = bits 4|5 = 0x0030
        assertEquals(0x30.toByte(), out[0])
        assertEquals(0x00.toByte(), out[1])
        // length = 2 flags + 2 speed + 3 distance + 2 resistance = 9
        assertEquals(9, out.size)
    }

    @Test
    fun `speed always present even when null`() {
        // Speed = null in source → encoded as 0. Bit 0 stays 0 (= include).
        val live = RideUiState.LiveMetrics(
            rpm = 100, watt = 100, resistance = 5,
            speedKmh = null, hrBpm = 130,
            distanceM = 100, calorieKcal = 0,
        )
        val out = IndoorBikeDataEncoder.encode(live)
        // flags bit 0 should be 0 → low byte mask 0xFE
        assertEquals(0, out[0].toInt() and 0x01)
        // Speed bytes are out[2..3] = 0x00 0x00
        assertEquals(0x00.toByte(), out[2])
        assertEquals(0x00.toByte(), out[3])
    }
}
