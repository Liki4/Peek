package io.github.liki4.peek.fit

import io.github.liki4.peek.protocol.TrainData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Behavioural tests for the per-ride accumulator + power-based speed model.
 *
 * Calibration property under test:
 *   v_t = (P_t / K)^(1/3)  with  K^(1/3) = Σ P_t^(1/3) / D
 *   ⇒ integrated speed (Σ v_t · dt) equals the bike's reported cumulative
 *     distance D, exactly (up to float rounding).
 */
class RideDataBuilderTest {

    private fun td(
        startTime: Long = 1_700_000_000L,
        rpm: Int = 80,
        watt: Int = 150,
        resistance: Int = 8,
        distanceM: Int,
        durationS: Int,
        calorie: Int = 0,
    ) = TrainData(
        startTime = startTime,
        rpm = rpm.toLong(),
        watt = watt.toLong(),
        resistance = resistance.toLong(),
        distanceM = distanceM.toLong(),
        durationS = durationS.toLong(),
        calorie = calorie.toLong(),
        status = null,
        field8 = null,
        raw = emptyMap(),
    )

    @Test
    fun `power-based speed integrates back to reported distance`() {
        // 60-second ride with power varying ±50% and distance accumulating
        // at the rate implied by v = (P/K_true)^(1/3) with K_true = 0.5.
        val builder = RideDataBuilder()
        val powers = IntArray(60) { i -> 100 + (i % 7) * 20 } // 100..220 W
        val kTrueCbrt = Math.cbrt(0.5)
        var d = 0.0
        for (i in 0 until 60) {
            val v = Math.cbrt(powers[i].toDouble()) / kTrueCbrt
            d += v
            builder.feedTrainData(td(distanceM = d.toInt(), durationS = i + 1, watt = powers[i]))
            builder.tick()
        }
        val ride = builder.build()
        val integrated = ride.speedKmh.filterNotNull().sumOf { (it / 3.6).toDouble() }
        // Cumulative distance the bike reported.
        val reported = ride.totalDistanceM!!.toDouble()
        // Allow 1 % drift — int-rounded distanceM loses a fraction per second.
        assertTrue(
            "integrated $integrated should track reported $reported within 1%",
            abs(integrated - reported) / reported < 0.01,
        )
    }

    @Test
    fun `lastSpeedKmh falls back to delta-distance before calibration is meaningful`() {
        val builder = RideDataBuilder()
        // First sample only — no power yet integrated into K, no prior distance.
        builder.feedTrainData(td(distanceM = 0, durationS = 1, watt = 150))
        builder.tick()
        // After only 1 tick lastSpeedKmh has no Δ available either → null.
        assertEquals(null, builder.lastSpeedKmh)

        // Second sample with Δd = 2 m. Power-model would also work here, but
        // we explicitly verify the model gives a non-null number.
        builder.feedTrainData(td(distanceM = 2, durationS = 2, watt = 150))
        builder.tick()
        val v = builder.lastSpeedKmh
        assertNotNull("lastSpeedKmh should be non-null once distance > 0", v)
        assertTrue("speed should be positive", v!! > 0f)
    }

    @Test
    fun `zero watt or zero distance falls back without NaN`() {
        val builder = RideDataBuilder()
        builder.feedTrainData(td(distanceM = 0, durationS = 1, watt = 0))
        builder.tick()
        val ride = builder.build()
        // Single-sample ride: speedKmh has one entry; with no Δ and no model
        // calibration it should be null, NOT NaN.
        assertEquals(1, ride.speedKmh.size)
        assertEquals(null, ride.speedKmh[0])
    }

    @Test
    fun `hr snapshot encodes missing samples as zero`() {
        val builder = RideDataBuilder()
        builder.feedTrainData(td(distanceM = 5, durationS = 1))
        builder.tick() // no HR fed yet → 0
        builder.feedHr(120)
        builder.feedTrainData(td(distanceM = 10, durationS = 2))
        builder.tick()
        val snap = builder.hrSnapshot()
        assertEquals(2, snap.size)
        assertEquals(0, snap[0])
        assertEquals(120, snap[1])
    }
}
