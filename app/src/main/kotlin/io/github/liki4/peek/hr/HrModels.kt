package io.github.liki4.peek.hr

import java.util.UUID

object HrConstants {
    val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
}

/** One HR measurement notification, decoded per BLE HRP spec. */
data class HrSample(
    val heartRate: Int,
    val sensorContactSupported: Boolean,
    val sensorContactDetected: Boolean,
    val energyExpendedKj: Int?,
    /** RR intervals in seconds (each typically 0.5..1.2). May be empty if strap doesn't expose RR. */
    val rrIntervalsS: List<Float>,
)
