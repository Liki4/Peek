package io.github.liki4.peek.ftms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class PowerModelTest {

    /** Simulates the bike: at level L the rider produces `a(L)·rpm + b(L)` watts with noise. */
    private fun trueWatt(level: Int, rpm: Int, noise: Float = 0f): Int {
        // Hand-picked plausible spin-bike curve: harder levels = steeper rpm coefficient.
        val a = 0.6f + 0.18f * level
        val b = 5f + 2f * level
        return (a * rpm + b + noise).toInt()
    }

    private fun feedSweep(model: PowerModel, level: Int, rpmRange: IntRange, repeats: Int = 1, seed: Int = 42) {
        val rng = Random(seed + level)
        repeat(repeats) {
            for (rpm in rpmRange) {
                val noise = rng.nextFloat() * 6f - 3f
                model.feed(level, rpm, trueWatt(level, rpm, noise))
            }
        }
    }

    @Test
    fun `empty model is not ready and returns NaN`() {
        val m = PowerModel()
        assertFalse(m.isReady())
        assertTrue(m.predict(8, 80).isNaN())
        assertNull(m.pickLevel(targetWatt = 200, rpm = 80))
    }

    @Test
    fun `single calibrated level is not enough — still NaN for interpolation`() {
        val m = PowerModel()
        feedSweep(m, level = 8, rpmRange = 60..100)
        // Direct predict on the calibrated level works…
        val direct = m.predict(8, 80)
        assertTrue("direct predict should be finite, got $direct", direct.isFinite())
        // …but interpolation at other levels can't proceed with only one calibrated.
        assertTrue(m.predict(5, 80).isNaN())
        assertFalse(m.isReady())  // isReady requires ≥ 2
    }

    @Test
    fun `two calibrated levels enable interpolation across all`() {
        val m = PowerModel()
        feedSweep(m, level = 5, rpmRange = 60..100)
        feedSweep(m, level = 14, rpmRange = 60..100)
        assertTrue(m.isReady())
        // Interpolated level 9 should predict something close to the truth.
        val predicted = m.predict(9, 80)
        val truth = trueWatt(9, 80).toFloat()
        assertTrue("predicted=$predicted truth=$truth", abs(predicted - truth) < 30f)
    }

    @Test
    fun `pickLevel converges to the level closest to target`() {
        val m = PowerModel()
        // Calibrate the 6 wizard levels.
        for (L in listOf(2, 5, 8, 11, 14, 17)) {
            feedSweep(m, level = L, rpmRange = 60..100)
        }
        // Target 180 W at 80 rpm. Compute true watts per level and verify pick is within ±1 of argmin.
        val target = 180
        val rpm = 80
        val truth = (1..18).map { L -> L to trueWatt(L, rpm) }
        val truthBest = truth.minBy { abs(it.second - target) }.first
        val picked = m.pickLevel(target, rpm)!!
        assertTrue("picked=$picked truthBest=$truthBest", abs(picked - truthBest) <= 1)
    }

    @Test
    fun `model round-trips through blob`() {
        val m = PowerModel()
        feedSweep(m, level = 5, rpmRange = 60..100)
        feedSweep(m, level = 14, rpmRange = 60..100)
        val blob = m.toBlob()
        val restored = PowerModel.fromBlob(blob)
        // Direct comparison via predict — same blob, same predictions.
        for (rpm in listOf(60, 80, 100)) {
            for (L in 1..18) {
                val a = m.predict(L, rpm)
                val b = restored.predict(L, rpm)
                if (a.isNaN()) {
                    assertTrue(b.isNaN())
                } else {
                    assertEquals("L=$L rpm=$rpm", a, b, 0.001f)
                }
            }
        }
    }

    @Test
    fun `blob from null or empty returns empty model`() {
        assertFalse(PowerModel.fromBlob(null).isReady())
        assertFalse(PowerModel.fromBlob("").isReady())
        assertFalse(PowerModel.fromBlob("garbage").isReady())
    }

    @Test
    fun `extrapolation at the edges follows nearest slope`() {
        val m = PowerModel()
        feedSweep(m, level = 5, rpmRange = 60..100)
        feedSweep(m, level = 14, rpmRange = 60..100)
        // Level 1 should extrapolate below level 5 using the (5, 14) slope.
        val p1 = m.predict(1, 80)
        val p5 = m.predict(5, 80)
        val p14 = m.predict(14, 80)
        // Below = smaller watts, monotonic with our true function.
        assertTrue("p1=$p1 p5=$p5", p1.isFinite() && p1 < p5)
        // Level 18 extrapolation above.
        val p18 = m.predict(18, 80)
        assertTrue("p14=$p14 p18=$p18", p18.isFinite() && p18 > p14)
    }
}
