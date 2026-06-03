package io.github.liki4.peek.fit

import io.github.liki4.peek.protocol.TrainData

/**
 * Streaming accumulator for ride samples.
 *
 * Wire sources (one ride session):
 *  1. 106/7 TrainData polls — cumulative {start_time, distance, duration,
 *     calorie, resistance, rpm, watt, status}. Polled at ~1 Hz. **This is
 *     the authoritative source for all per-second metrics.**
 *  2. 106/6 TrainAttribute pushes — event-driven on resistance change only,
 *     NOT a continuous stream. The `rpm` field on a TA push is a snapshot
 *     of the moment the resistance changed — using it as a fallback for
 *     rpm in [tick] freezes rpm at that snapshot for every subsequent
 *     second (the lastTa never expires). Therefore tick() reads from td
 *     only; TA is consumed elsewhere as a low-latency UI update for the
 *     resistance number.
 *  3. BLE HR strap — 1 Hz HrSample stream.
 *
 * Call sequence per second (driven by RideForegroundService):
 *  - feedTrainData(td)
 *  - feedHr(bpm) as samples arrive
 *  - tick()  → appends one entry to each per-second array
 *
 * Speed is NOT on the wire on CC_23 — it's derived from per-second distance
 * deltas in [build] (per `keep_fit.py:201-214`).
 */
class RideDataBuilder {

    var startTimeUnixS: Long = 0
        private set

    private val resistance = mutableListOf<Int?>()
    private val rpm = mutableListOf<Int?>()
    private val watt = mutableListOf<Int?>()
    private val distanceM = mutableListOf<Int?>()
    private val hrBpm = mutableListOf<Int?>()

    private var lastTd: TrainData? = null
    private var lastHrBpm: Int? = null
    private var totalCalorie: Int? = null

    // ===== Power-based speed model =====
    //
    // We model speed as v_t = (P_t / K)^(1/3), the standard cubic
    // power-vs-airspeed relation for a virtual flat-road model. The constant
    // K (W·s³/m³, lumped Cd·A·ρ + rolling) is calibrated per-ride so that
    // integrated speed matches the bike's reported cumulative distance:
    //
    //     Σ v_t · dt = D   with dt = 1s
    //   ⇒ K^(1/3) = Σ P_t^(1/3) / D
    //
    // Closed form, no iteration. Both numerator and denominator grow as the
    // ride proceeds, so K^(1/3) stabilizes after the first ~30 s. Until the
    // bike has reported ≥ 1 m of distance we fall back to the per-second
    // Δdistance × 3.6 estimate, which is noisy (1 m resolution = 3.6 km/h)
    // but well-defined from the very first sample.
    private var sumCbrtPower: Double = 0.0

    val secondsRecorded: Int get() = resistance.size
    val lastDistanceM: Int? get() = distanceM.lastOrNull()
    val lastCalorie: Int? get() = totalCalorie

    /**
     * Speed for the most recent second. Uses the power-based model once
     * cumulative distance > 0; falls back to Δdistance × 3.6 until then.
     */
    val lastSpeedKmh: Float? get() {
        val td = lastTd
        val p = td?.watt?.toInt() ?: return fallbackDeltaSpeed()
        val d = distanceM.lastOrNull { it != null } ?: return fallbackDeltaSpeed()
        if (d <= 0 || sumCbrtPower <= 0.0 || p <= 0) return fallbackDeltaSpeed()
        val kCbrt = sumCbrtPower / d
        return (Math.cbrt(p.toDouble()) / kCbrt * 3.6).toFloat()
    }

    private fun fallbackDeltaSpeed(): Float? {
        val n = distanceM.size
        if (n < 2) return null
        val cur = distanceM[n - 1] ?: return null
        val prev = distanceM[n - 2] ?: return null
        return (cur - prev).coerceAtLeast(0) * 3.6f
    }

    /** Average heart rate across all non-null HR samples in this ride. */
    val avgHr: Int? get() = hrBpm.filterNotNull().takeIf { it.isNotEmpty() }?.average()?.toInt()
    /** Peak HR sample seen in this ride. */
    val maxHr: Int? get() = hrBpm.filterNotNull().maxOrNull()
    /** Mean of all watt samples (overall ride average power). */
    val avgWatt: Int? get() = watt.filterNotNull().takeIf { it.isNotEmpty() }?.average()?.toInt()
    /** Mean of the last 3 watt samples — Strava/Garmin's 3-second smoothed power. */
    val watt3s: Int? get() {
        val tail = watt.takeLast(3).filterNotNull()
        return if (tail.isEmpty()) null else tail.average().toInt()
    }

    /**
     * Snapshot the per-second HR series as a fresh IntArray. Missing samples
     * (HR strap drop-outs or pre-strap seconds) are encoded as 0 — chart
     * renderers should skip zeros rather than drawing them as floor values.
     */
    fun hrSnapshot(): IntArray {
        val n = hrBpm.size
        val out = IntArray(n)
        for (i in 0 until n) out[i] = hrBpm[i] ?: 0
        return out
    }

    fun feedTrainData(td: TrainData) {
        if (startTimeUnixS == 0L) td.startTime?.let { startTimeUnixS = it }
        lastTd = td
        td.calorie?.let { totalCalorie = it.toInt() }
    }

    fun feedHr(bpm: Int) {
        lastHrBpm = bpm
    }

    /**
     * Append one second's worth of samples from the most recent TrainData
     * poll. Idempotent only across distinct calls — multiple invocations
     * within the same wall-clock second will all append.
     */
    fun tick() {
        val td = lastTd
        val p = td?.watt?.toInt()
        resistance += td?.resistance?.toInt()
        rpm += td?.rpm?.toInt()
        watt += p
        distanceM += td?.distanceM?.toInt()
        hrBpm += lastHrBpm
        if (p != null && p > 0) sumCbrtPower += Math.cbrt(p.toDouble())
    }

    /** Snapshot the accumulated state into an immutable [RideData]. */
    fun build(): RideData {
        val n = resistance.size
        if (n == 0) return RideData.EMPTY.copy(startTimeUnixS = startTimeUnixS)

        val totalD = distanceM.lastOrNull { it != null } ?: 0
        // Calibrate K from the whole ride: K^(1/3) = ΣP^(1/3) / D. Used to
        // emit a smooth speed for every record. If we never accumulated
        // meaningful power+distance (totalD == 0 or sumCbrtPower == 0), fall
        // back to Δdistance — same source the live tile used before this model.
        val kCbrt = if (totalD > 0 && sumCbrtPower > 0.0) sumCbrtPower / totalD else 0.0
        val speedKmh = ArrayList<Float?>(n)
        for (i in 0 until n) {
            val p = watt[i]
            if (kCbrt > 0.0 && p != null && p > 0) {
                speedKmh += (Math.cbrt(p.toDouble()) / kCbrt * 3.6).toFloat()
            } else {
                val cur = distanceM[i]
                val prev = if (i > 0) distanceM[i - 1] else null
                if (cur != null && prev != null) {
                    speedKmh += (cur - prev).coerceAtLeast(0) * 3.6f
                } else {
                    speedKmh += null
                }
            }
        }

        return RideData(
            startTimeUnixS = startTimeUnixS,
            durationS = n,
            resistance = resistance.toList(),
            rpm = rpm.toList(),
            watt = watt.toList(),
            speedKmh = speedKmh,
            hrBpm = hrBpm.toList(),
            totalDistanceM = totalD.takeIf { it > 0 },
            totalCalorie = totalCalorie,
        )
    }

    fun reset() {
        startTimeUnixS = 0
        resistance.clear(); rpm.clear(); watt.clear(); distanceM.clear(); hrBpm.clear()
        lastTd = null; lastHrBpm = null; totalCalorie = null
        sumCbrtPower = 0.0
    }
}
