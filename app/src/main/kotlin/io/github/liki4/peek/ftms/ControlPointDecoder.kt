package io.github.liki4.peek.ftms

import io.github.liki4.peek.ftms.FtmsConstants.CpOp

/**
 * Decoder for the Fitness Machine Control Point (characteristic 0x2AD9).
 *
 * Clients write a 1-byte opcode followed by op-specific params; we reply via
 * an indicate with the standard 3-byte response (`0x80, req_op, result_code`).
 *
 * Only the opcodes Peek actually handles are decoded into typed [CpRequest]
 * variants; anything else surfaces as [CpRequest.Unsupported] so the bridge
 * can reply with `OP_NOT_SUPPORTED`.
 *
 * Units per FTMS GATT Spec Supplement:
 *   - 0x04 Set Target Resistance: sint16 × 0.1 (dimensionless)
 *   - 0x05 Set Target Power: sint16 watts
 *   - 0x11 Set Indoor Bike Simulation: sint16 wind (×0.001 m/s) | sint16 grade
 *     (×0.01 %) | uint8 Crr (×0.0001) | uint8 Cw (×0.01)
 */
object ControlPointDecoder {

    /** Decoded request. Use [respond] to build the corresponding indicate payload. */
    sealed class CpRequest {
        abstract val op: Int

        data object RequestControl : CpRequest() { override val op = CpOp.REQUEST_CONTROL }
        data object Reset          : CpRequest() { override val op = CpOp.RESET }

        /** [tenths] is the raw sint16 value; resistance level = tenths / 10. */
        data class SetTargetResistance(val tenths: Int) : CpRequest() {
            val level: Float get() = tenths / 10f
            override val op = CpOp.SET_TARGET_RESISTANCE
        }

        /** [watts] sint16 — negative values are spec-valid for some machines but nonsensical here. */
        data class SetTargetPower(val watts: Int) : CpRequest() {
            override val op = CpOp.SET_TARGET_POWER
        }

        /**
         * @param windMps wind speed (m/s) — positive = headwind
         * @param gradePercent road grade (% × 1.0; e.g. 5.5 = 5.5%)
         * @param crr rolling resistance coefficient (unitless)
         * @param cw aero drag coefficient × area × air density (kg/m)
         */
        data class SetIndoorBikeSim(
            val windMps: Float,
            val gradePercent: Float,
            val crr: Float,
            val cw: Float,
        ) : CpRequest() { override val op = CpOp.SET_INDOOR_BIKE_SIM }

        /** Op recognised but parameters malformed (wrong length). */
        data class Malformed(override val op: Int) : CpRequest()

        /** Op not handled — reply OP_NOT_SUPPORTED. */
        data class Unsupported(override val op: Int) : CpRequest()
    }

    fun decode(bytes: ByteArray): CpRequest {
        if (bytes.isEmpty()) return CpRequest.Unsupported(-1)
        val op = bytes[0].toInt() and 0xFF
        return when (op) {
            CpOp.REQUEST_CONTROL -> CpRequest.RequestControl
            CpOp.RESET           -> CpRequest.Reset
            CpOp.SET_TARGET_RESISTANCE -> {
                if (bytes.size < 3) CpRequest.Malformed(op)
                else CpRequest.SetTargetResistance(readInt16Le(bytes, 1))
            }
            CpOp.SET_TARGET_POWER -> {
                if (bytes.size < 3) CpRequest.Malformed(op)
                else CpRequest.SetTargetPower(readInt16Le(bytes, 1))
            }
            CpOp.SET_INDOOR_BIKE_SIM -> {
                if (bytes.size < 7) CpRequest.Malformed(op)
                else CpRequest.SetIndoorBikeSim(
                    windMps = readInt16Le(bytes, 1) * 0.001f,
                    gradePercent = readInt16Le(bytes, 3) * 0.01f,
                    crr = (bytes[5].toInt() and 0xFF) * 0.0001f,
                    cw = (bytes[6].toInt() and 0xFF) * 0.01f,
                )
            }
            else -> CpRequest.Unsupported(op)
        }
    }

    /** Build the 3-byte indicate response payload: `[0x80, req_op, result_code]`. */
    fun respond(reqOp: Int, resultCode: Int): ByteArray =
        byteArrayOf(CpOp.RESPONSE.toByte(), reqOp.toByte(), resultCode.toByte())

    private fun readInt16Le(bytes: ByteArray, offset: Int): Int {
        val lo = bytes[offset].toInt() and 0xFF
        val hi = bytes[offset + 1].toInt()
        return (hi shl 8) or lo
    }
}
