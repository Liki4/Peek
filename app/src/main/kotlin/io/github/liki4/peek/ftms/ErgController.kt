package io.github.liki4.peek.ftms

import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the current FTMS control mode — either ERG (`targetPower`) or SIM
 * (`simParams`) — and converts SIM into an effective target watts via the
 * standard outdoor-cycling physics model.
 *
 * Pure logic; no BLE or PowerModel coupling. The bridge owns one instance,
 * sets mode on Control Point writes, and calls [effectiveTargetWatt] every
 * tick to feed the level picker.
 *
 * Physics (Sim mode), per the FTMS GATT Specification Supplement:
 * ```
 * P = ½·Cw·v_apparent³ + Crr·m·g·v_ground + m·g·v_ground·(grade/100)
 * ```
 * where `v_apparent = v_ground + wind` (m/s; positive wind = headwind, per
 * the FTMS sign convention), `m` is rider mass (kg), and Cw is the lumped
 * `Cd·A·ρ` aero coefficient as supplied by the client.
 */
class ErgController {

    /** ERG target in watts, or null when not in ERG mode. */
    private val target = AtomicReference<Int?>(null)

    /** SIM parameters, or null when not in SIM mode. */
    private val sim = AtomicReference<SimParams?>(null)

    val currentTarget: Int? get() = target.get()
    val currentSim: SimParams? get() = sim.get()

    data class SimParams(
        val windMps: Float,
        val gradePercent: Float,
        val crr: Float,
        val cw: Float,
    )

    fun setErgTarget(watts: Int) {
        target.set(watts)
        sim.set(null)  // mutually exclusive — Zwift always picks one mode at a time
    }

    fun setSim(params: SimParams) {
        sim.set(params)
        target.set(null)
    }

    /** Called on Control Point Reset (0x01) and on bridge stop. */
    fun reset() {
        target.set(null)
        sim.set(null)
    }

    /**
     * Effective target watts for this tick.
     * - ERG mode: returns the literal target.
     * - SIM mode: evaluates the physics formula at the current speed.
     * - Neither: null.
     *
     * SIM at zero speed evaluates to ~0 watts — that's physically correct
     * (a stationary rider on flat ground needs no power). The bike will hold
     * whatever its lowest commandable level is until the rider gets moving.
     */
    fun effectiveTargetWatt(currentSpeedMps: Float, weightKg: Float): Int? {
        target.get()?.let { return it }
        val s = sim.get() ?: return null
        val g = 9.81f
        val v = currentSpeedMps.coerceAtLeast(0f)
        val vApparent = v + s.windMps
        val aero = 0.5f * s.cw * vApparent * vApparent * vApparent
        val rolling = s.crr * weightKg * g * v
        val grade = weightKg * g * v * (s.gradePercent / 100f)
        return (aero + rolling + grade).toInt().coerceAtLeast(0)
    }

    /** True iff either mode is active. UI uses this to show a "controlled by client" badge. */
    fun isActive(): Boolean = target.get() != null || sim.get() != null

    companion object {
        private const val SIM_FB_BASE = 5
        private const val SIM_FB_GRADE_UP = 1.5f
        private const val SIM_FB_GRADE_DOWN = 1.0f
        private const val SIM_FB_WIND = 0.05f

        /**
         * Direct grade→level mapping for SIM mode when PowerModel has no
         * calibration data. Lets Zwift route-riding work out of the box.
         */
        fun simFallbackLevel(params: SimParams): Int {
            val gradeEffect = if (params.gradePercent >= 0)
                params.gradePercent * SIM_FB_GRADE_UP
            else
                params.gradePercent * SIM_FB_GRADE_DOWN
            val windEffect = -params.windMps * SIM_FB_WIND
            return (SIM_FB_BASE + gradeEffect + windEffect)
                .toInt().coerceIn(1, 18)
        }
    }
}
