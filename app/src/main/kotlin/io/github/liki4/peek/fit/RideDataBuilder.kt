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

    val secondsRecorded: Int get() = resistance.size
    val lastDistanceM: Int? get() = distanceM.lastOrNull()
    val lastCalorie: Int? get() = totalCalorie

    /**
     * Speed for the most recent second, derived from the last two non-null
     * distanceM samples (Δm/s × 3.6). Null until at least two distance
     * samples have arrived.
     */
    val lastSpeedKmh: Float? get() {
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
        resistance += td?.resistance?.toInt()
        rpm += td?.rpm?.toInt()
        watt += td?.watt?.toInt()
        distanceM += td?.distanceM?.toInt()
        hrBpm += lastHrBpm
    }

    /** Snapshot the accumulated state into an immutable [RideData]. */
    fun build(): RideData {
        val n = resistance.size
        if (n == 0) return RideData.EMPTY.copy(startTimeUnixS = startTimeUnixS)

        // Derive speed from per-second distance deltas.
        val speedKmh = ArrayList<Float?>(n)
        for (i in 0 until n) {
            if (i == 0) { speedKmh += null; continue }
            val cur = distanceM[i]
            val prev = distanceM[i - 1]
            if (cur == null || prev == null) {
                speedKmh += null
            } else {
                val dMeters = (cur - prev).coerceAtLeast(0)
                speedKmh += dMeters * 3.6f
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
            totalDistanceM = distanceM.lastOrNull { it != null },
            totalCalorie = totalCalorie,
        )
    }

    fun reset() {
        startTimeUnixS = 0
        resistance.clear(); rpm.clear(); watt.clear(); distanceM.clear(); hrBpm.clear()
        lastTd = null; lastHrBpm = null; totalCalorie = null
    }
}
