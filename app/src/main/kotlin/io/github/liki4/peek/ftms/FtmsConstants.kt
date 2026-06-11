package io.github.liki4.peek.ftms

import java.util.UUID

/**
 * Bluetooth SIG assigned numbers for the Fitness Machine Service (0x1826),
 * the Indoor Bike Data characteristic and the Fitness Machine Control Point.
 *
 * UUIDs follow the 16-bit-base-128-bit pattern (`0000XXXX-0000-1000-8000-00805F9B34FB`).
 *
 * Reference: GATT Specification Supplement, Fitness Machine Service profile
 * (Bluetooth SIG, current as of 2023).
 */
object FtmsConstants {

    private fun bt(id: Int): UUID =
        UUID.fromString("0000${"%04x".format(id)}-0000-1000-8000-00805f9b34fb")

    // ===== Services =====
    val SERVICE_FITNESS_MACHINE: UUID = bt(0x1826)

    // ===== Characteristics =====
    /** Read. Bitfield of supported features (machine + target settings). */
    val CHAR_FITNESS_MACHINE_FEATURE: UUID = bt(0x2ACC)

    /** Notify. Packed Indoor Bike Data payload — flags + per-field values. */
    val CHAR_INDOOR_BIKE_DATA: UUID = bt(0x2AD2)

    /** Read. uint8 enum (idle / warmup / training / etc). */
    val CHAR_TRAINING_STATUS: UUID = bt(0x2AD3)

    /** Read. sint16 min, sint16 max, uint16 min-increment — for resistance level. */
    val CHAR_SUPPORTED_RESISTANCE_LEVEL_RANGE: UUID = bt(0x2AD6)

    /** Read. sint16 min, sint16 max, uint16 min-increment — for target power (watts). */
    val CHAR_SUPPORTED_POWER_RANGE: UUID = bt(0x2AD8)

    /** Write + Indicate. Client writes target requests, server responds via indicate. */
    val CHAR_FITNESS_MACHINE_CONTROL_POINT: UUID = bt(0x2AD9)

    /** Notify. Server-initiated state changes (control granted, started, paused …). */
    val CHAR_FITNESS_MACHINE_STATUS: UUID = bt(0x2ADA)

    // ===== Indoor Bike Data flags (uint16, LE) =====
    // Bit 0: "More Data" — when set, means data is split across multiple packets
    // and instantaneous speed is NOT present in this packet. We always fit
    // everything in one packet so bit 0 stays 0 (speed always present).
    object IbdFlag {
        const val MORE_DATA              = 1 shl 0
        const val AVG_SPEED_PRESENT      = 1 shl 1
        const val INSTANT_CADENCE_PRES   = 1 shl 2
        const val AVG_CADENCE_PRESENT    = 1 shl 3
        const val TOTAL_DISTANCE_PRES    = 1 shl 4
        const val RESISTANCE_LEVEL_PRES  = 1 shl 5
        const val INSTANT_POWER_PRESENT  = 1 shl 6
        const val AVG_POWER_PRESENT      = 1 shl 7
        const val EXPENDED_ENERGY_PRES   = 1 shl 8
        const val HEART_RATE_PRESENT     = 1 shl 9
        const val METABOLIC_EQUIV_PRES   = 1 shl 10
        const val ELAPSED_TIME_PRESENT   = 1 shl 11
        const val REMAINING_TIME_PRES    = 1 shl 12
    }

    // ===== Fitness Machine Feature bits (uint32 machine + uint32 target, in that order) =====
    // We claim only what we actually surface. Underclaiming is safe (some
    // clients gate features on these bits before sending requests).
    object MachineFeature {
        const val AVG_SPEED              = 1 shl 0
        const val CADENCE                = 1 shl 1
        const val TOTAL_DISTANCE         = 1 shl 2
        const val INCLINATION            = 1 shl 3
        const val ELEVATION_GAIN         = 1 shl 4
        const val PACE                   = 1 shl 5
        const val STEP_COUNT             = 1 shl 6
        const val RESISTANCE_LEVEL       = 1 shl 7
        const val STRIDE_COUNT           = 1 shl 8
        const val EXPENDED_ENERGY        = 1 shl 9
        const val HEART_RATE             = 1 shl 10
        const val METABOLIC_EQUIVALENT   = 1 shl 11
        const val ELAPSED_TIME           = 1 shl 12
        const val REMAINING_TIME         = 1 shl 13
        const val POWER_MEASUREMENT      = 1 shl 14
        const val FORCE_ON_BELT_PWR_OUT  = 1 shl 15
        const val USER_DATA_RETENTION    = 1 shl 16
    }

    object TargetFeature {
        const val SPEED                  = 1 shl 0
        const val INCLINATION            = 1 shl 1
        const val RESISTANCE             = 1 shl 2
        const val POWER                  = 1 shl 3
        const val HEART_RATE             = 1 shl 4
        const val TARGETED_EXP_ENERGY    = 1 shl 5
        const val TARGETED_STEPS         = 1 shl 6
        const val TARGETED_STRIDES       = 1 shl 7
        const val TARGETED_DISTANCE      = 1 shl 8
        const val TARGETED_TIME_IN_2_ZONES = 1 shl 9
        const val TARGETED_TIME_IN_3_ZONES = 1 shl 10
        const val TARGETED_TIME_IN_5_ZONES = 1 shl 11
        const val WHEEL_CIRCUMFERENCE    = 1 shl 12
        const val SPIN_DOWN_CONTROL      = 1 shl 13
        const val TARGETED_CADENCE       = 1 shl 14
        const val INDOOR_BIKE_SIMULATION = 1 shl 15  // grade / wind / Crr / Cw
    }

    // ===== Control Point opcodes =====
    object CpOp {
        const val REQUEST_CONTROL          = 0x00
        const val RESET                    = 0x01
        const val SET_TARGET_SPEED         = 0x02
        const val SET_TARGET_INCLINATION   = 0x03
        const val SET_TARGET_RESISTANCE    = 0x04
        const val SET_TARGET_POWER         = 0x05
        const val SET_TARGET_HEART_RATE    = 0x06
        const val START_RESUME             = 0x07
        const val STOP_PAUSE               = 0x08
        const val SET_TARGET_EXP_ENERGY    = 0x09
        const val SET_TARGET_STEPS         = 0x0A
        const val SET_TARGET_STRIDES       = 0x0B
        const val SET_TARGET_DISTANCE      = 0x0C
        const val SET_TARGET_TIME          = 0x0D
        const val SET_TARGET_TIME_2_ZONES  = 0x0E
        const val SET_TARGET_TIME_3_ZONES  = 0x0F
        const val SET_TARGET_TIME_5_ZONES  = 0x10
        const val SET_INDOOR_BIKE_SIM      = 0x11
        const val SET_WHEEL_CIRCUMFERENCE  = 0x12
        const val SPIN_DOWN_CONTROL        = 0x13
        const val SET_TARGETED_CADENCE     = 0x14

        /** Marker byte for a response indicate payload. Followed by req op + result code. */
        const val RESPONSE                 = 0x80
    }

    object CpResult {
        const val SUCCESS                = 0x01
        const val OP_NOT_SUPPORTED       = 0x02
        const val INVALID_PARAMETER      = 0x03
        const val OPERATION_FAILED       = 0x04
        const val CONTROL_NOT_PERMITTED  = 0x05
    }

    /** Resistance range advertised in 0x2AD6 — matches CC_23's 1..18 spring brake. */
    const val RESISTANCE_MIN = 1
    const val RESISTANCE_MAX = 18
    const val RESISTANCE_INCREMENT = 1

    /** Power range advertised in 0x2AD8 — wide enough for any rider on this bike. */
    const val POWER_MIN_W = 0
    const val POWER_MAX_W = 1500
    const val POWER_INCREMENT_W = 1
}
