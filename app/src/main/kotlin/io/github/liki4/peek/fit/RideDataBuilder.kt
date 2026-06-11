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
    var lastHrBpm: Int? = null
        private set
    private var totalCalorie: Int? = null

    // ===== rpm-based speed model =====
    //
    // The CC_23 behaves like a single-gear bike on a variable-grade road: each
    // pedal revolution advances a fixed virtual distance, regardless of which
    // resistance level you're on. Higher resistance just costs more watts to
    // hit the same rpm — it doesn't make you faster.
    //
    // So speed is purely kinematic on cadence:
    //
    //     v_kmh = metersPerRpmSecond · rpm · 3.6
    //
    // where `metersPerRpmSecond = totalDistance / Σrpm` is the per-ride
    // calibration constant (closed form, one ride is enough). Until at least
    // ≥ 1 m of distance has been logged we fall back to the per-second
    // Δdistance × 3.6 estimate, which is noisy (1 m resolution = 3.6 km/h)
    // but well-defined from the very first sample.
    private var sumRpm: Long = 0L

    val secondsRecorded: Int get() = resistance.size
    val lastDistanceM: Int? get() = distanceM.lastOrNull()
    val lastCalorie: Int? get() = totalCalorie
    val debugSumRpm: Long get() = sumRpm
    val debugMPerRpmSec: Double? get() {
        val d = distanceM.lastOrNull { it != null } ?: return null
        return if (d > 0 && sumRpm > 0L) d.toDouble() / sumRpm else null
    }

    /**
     * Speed for the most recent second. Uses the rpm-based kinematic model
     * once cumulative distance > 0; falls back to Δdistance × 3.6 until then.
     */
    val lastSpeedKmh: Float? get() {
        val td = lastTd
        val r = td?.rpm?.toInt() ?: return fallbackDeltaSpeed()
        val d = distanceM.lastOrNull { it != null } ?: return fallbackDeltaSpeed()
        if (d <= 0 || sumRpm <= 0L || r <= 0) return fallbackDeltaSpeed()
        val metersPerRpmSec = d.toDouble() / sumRpm
        return (metersPerRpmSec * r * 3.6).toFloat()
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
        val r = td?.rpm?.toInt()
        resistance += td?.resistance?.toInt()
        rpm += r
        watt += td?.watt?.toInt()
        distanceM += td?.distanceM?.toInt()
        hrBpm += lastHrBpm
        if (r != null && r > 0) sumRpm += r
    }

    /** Snapshot the accumulated state into an immutable [RideData]. */
    fun build(): RideData {
        val n = resistance.size
        if (n == 0) return RideData.EMPTY.copy(startTimeUnixS = startTimeUnixS)

        val totalD = distanceM.lastOrNull { it != null } ?: 0
        // Calibrate metersPerRpmSec from the whole ride: D / Σrpm. Used to
        // emit a smooth kinematic speed for every record. If we never
        // accumulated meaningful rpm+distance (totalD == 0 or sumRpm == 0),
        // fall back to per-second Δdistance — same source the live tile uses
        // before calibration is meaningful.
        val mPerRpmSec = if (totalD > 0 && sumRpm > 0L) totalD.toDouble() / sumRpm else 0.0
        val speedKmh = ArrayList<Float?>(n)
        for (i in 0 until n) {
            val r = rpm[i]
            if (mPerRpmSec > 0.0 && r != null && r > 0) {
                speedKmh += (mPerRpmSec * r * 3.6).toFloat()
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
        sumRpm = 0L
    }
}
