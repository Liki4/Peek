package io.github.liki4.peek.ftms

import io.github.liki4.peek.ftms.FtmsConstants.IbdFlag
import io.github.liki4.peek.ride.RideUiState
import java.io.ByteArrayOutputStream

/**
 * Encodes Indoor Bike Data (FTMS characteristic 0x2AD2) packed payload.
 *
 * Field order is fixed by the spec — flags first (uint16 LE), then each
 * enabled field appended in flag-bit order. We always include Instantaneous
 * Speed (bit 0 stays 0 = include).
 *
 * Units (per the GATT Specification Supplement):
 * - Instantaneous Speed: uint16, resolution 0.01 km/h
 * - Instantaneous Cadence: uint16, resolution 0.5 rpm
 * - Total Distance: uint24, resolution 1 m
 * - Resistance Level: sint16, dimensionless (we send the literal 1..18)
 * - Instantaneous Power: sint16, resolution 1 W
 * - Heart Rate: uint8, resolution 1 bpm
 *
 * Fields whose source value is null are omitted (their flag bit cleared) —
 * subscribers should handle missing fields rather than us emitting zeros.
 */
object IndoorBikeDataEncoder {

    fun encode(live: RideUiState.LiveMetrics): ByteArray {
        var flags = 0
        // Speed is always present (bit 0 "More Data" stays 0 — we fit everything in one packet).
        if (live.rpm != null)        flags = flags or IbdFlag.INSTANT_CADENCE_PRES
        if (live.distanceM != null)  flags = flags or IbdFlag.TOTAL_DISTANCE_PRES
        if (live.resistance != null) flags = flags or IbdFlag.RESISTANCE_LEVEL_PRES
        if (live.watt != null)       flags = flags or IbdFlag.INSTANT_POWER_PRESENT
        if (live.hrBpm != null)      flags = flags or IbdFlag.HEART_RATE_PRESENT

        val out = ByteArrayOutputStream()
        writeUint16Le(out, flags)

        // Instantaneous Speed — always present. 0 when unknown, in 0.01 km/h.
        val speedHundredths = ((live.speedKmh ?: 0f) * 100f).toInt().coerceIn(0, 0xFFFF)
        writeUint16Le(out, speedHundredths)

        if (flags and IbdFlag.INSTANT_CADENCE_PRES != 0) {
            // 0.5 rpm units → multiply by 2.
            val cad = (live.rpm!! * 2).coerceIn(0, 0xFFFF)
            writeUint16Le(out, cad)
        }
        if (flags and IbdFlag.TOTAL_DISTANCE_PRES != 0) {
            writeUint24Le(out, live.distanceM!!.coerceIn(0, 0xFFFFFF))
        }
        if (flags and IbdFlag.RESISTANCE_LEVEL_PRES != 0) {
            writeInt16Le(out, live.resistance!!.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
        }
        if (flags and IbdFlag.INSTANT_POWER_PRESENT != 0) {
            writeInt16Le(out, live.watt!!.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
        }
        if (flags and IbdFlag.HEART_RATE_PRESENT != 0) {
            out.write(live.hrBpm!!.coerceIn(0, 255))
        }
        return out.toByteArray()
    }

    private fun writeUint16Le(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
    }

    private fun writeUint24Le(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
    }

    private fun writeInt16Le(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
    }
}
