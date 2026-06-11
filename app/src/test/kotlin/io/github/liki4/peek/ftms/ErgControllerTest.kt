package io.github.liki4.peek.ftms

import io.github.liki4.peek.ftms.ErgController.SimParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ErgControllerTest {

    @Test
    fun `idle returns null`() {
        val c = ErgController()
        assertNull(c.effectiveTargetWatt(currentSpeedMps = 5f, weightKg = 70f))
        assertFalse(c.isActive())
    }

    @Test
    fun `ERG returns literal target`() {
        val c = ErgController()
        c.setErgTarget(220)
        assertEquals(220, c.effectiveTargetWatt(currentSpeedMps = 5f, weightKg = 70f))
        assertTrue(c.isActive())
    }

    @Test
    fun `setting ERG clears SIM and vice versa`() {
        val c = ErgController()
        c.setSim(SimParams(0f, 5f, 0.005f, 0.32f))
        c.setErgTarget(180)
        assertNull(c.currentSim)
        assertEquals(180, c.currentTarget)
        c.setSim(SimParams(0f, 0f, 0.005f, 0.32f))
        assertNull(c.currentTarget)
    }

    @Test
    fun `SIM flat ground no wind matches rolling resistance only`() {
        val c = ErgController()
        c.setSim(SimParams(windMps = 0f, gradePercent = 0f, crr = 0.005f, cw = 0.32f))
        // v = 8 m/s ≈ 29 km/h, m = 70 kg
        // rolling = 0.005 × 70 × 9.81 × 8 = 27.5 W
        // aero    = 0.5 × 0.32 × 8³ = 81.9 W
        // total   = 109.4 W → int 109
        val w = c.effectiveTargetWatt(currentSpeedMps = 8f, weightKg = 70f)!!
        assertTrue("expected ~109, got $w", abs(w - 109) <= 2)
    }

    @Test
    fun `SIM 5 percent grade adds gravity term`() {
        val c = ErgController()
        c.setSim(SimParams(windMps = 0f, gradePercent = 5f, crr = 0.005f, cw = 0.32f))
        // At v=5, m=75:
        // gravity = 75 × 9.81 × 5 × 0.05 = 183.9
        // rolling = 0.005 × 75 × 9.81 × 5 = 18.4
        // aero    = 0.5 × 0.32 × 125 = 20.0
        // total   = 222.3 → 222
        val w = c.effectiveTargetWatt(currentSpeedMps = 5f, weightKg = 75f)!!
        assertTrue("expected ~222, got $w", abs(w - 222) <= 3)
    }

    @Test
    fun `SIM at zero speed gives zero`() {
        val c = ErgController()
        c.setSim(SimParams(0f, 10f, 0.005f, 0.32f))
        assertEquals(0, c.effectiveTargetWatt(currentSpeedMps = 0f, weightKg = 70f))
    }

    @Test
    fun `headwind increases aero term`() {
        val c = ErgController()
        // Same ground speed, with vs without headwind
        c.setSim(SimParams(windMps = 0f, gradePercent = 0f, crr = 0f, cw = 0.32f))
        val noWind = c.effectiveTargetWatt(8f, 70f)!!
        c.setSim(SimParams(windMps = 5f, gradePercent = 0f, crr = 0f, cw = 0.32f))
        val headwind = c.effectiveTargetWatt(8f, 70f)!!
        assertTrue("headwind ($headwind) should exceed no-wind ($noWind)", headwind > noWind)
    }

    @Test
    fun `reset clears both modes`() {
        val c = ErgController()
        c.setErgTarget(200)
        c.reset()
        assertNull(c.currentTarget)
        assertNull(c.currentSim)
        assertFalse(c.isActive())
    }

    // ===== simFallbackLevel =====

    @Test
    fun `SIM fallback flat ground returns base level`() {
        val p = SimParams(windMps = 0f, gradePercent = 0f, crr = 0.005f, cw = 0.32f)
        assertEquals(5, ErgController.simFallbackLevel(p))
    }

    @Test
    fun `SIM fallback 5 pct uphill`() {
        val p = SimParams(windMps = 0f, gradePercent = 5f, crr = 0.005f, cw = 0.32f)
        // 5 + 5 × 1.5 = 12.5 → 12
        assertEquals(12, ErgController.simFallbackLevel(p))
    }

    @Test
    fun `SIM fallback steep downhill clamps to 1`() {
        val p = SimParams(windMps = 0f, gradePercent = -6f, crr = 0.005f, cw = 0.32f)
        // 5 + (-6) × 1.0 = -1 → clamped to 1
        assertEquals(1, ErgController.simFallbackLevel(p))
    }

    @Test
    fun `SIM fallback steep uphill clamps to 18`() {
        val p = SimParams(windMps = 0f, gradePercent = 12f, crr = 0.005f, cw = 0.32f)
        // 5 + 12 × 1.5 = 23 → clamped to 18
        assertEquals(18, ErgController.simFallbackLevel(p))
    }
}
