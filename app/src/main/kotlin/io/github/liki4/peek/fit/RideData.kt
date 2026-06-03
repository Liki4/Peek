package io.github.liki4.peek.fit

/**
 * Per-second arrays for one ride session, mirroring `keep_fit.py:RideData`
 * and Keep's cloud-side `KtPuncheurLogData` schema (resistance / power /
 * stepFrequency=rpm / speed per-second arrays + cumulative totals).
 *
 * Each List has length [durationS]; null entries mean "no data that second".
 */
data class RideData(
    val startTimeUnixS: Long,
    val durationS: Int,
    val resistance: List<Int?>,
    val rpm: List<Int?>,
    val watt: List<Int?>,
    val speedKmh: List<Float?>,
    val hrBpm: List<Int?>,
    val totalDistanceM: Int?,
    val totalCalorie: Int?,
) {
    fun endTimeUnixS(): Long = startTimeUnixS + durationS

    fun hasResistance() = resistance.any { it != null }
    fun hasRpm()        = rpm.any { it != null }
    fun hasWatt()       = watt.any { it != null }
    fun hasSpeed()      = speedKmh.any { it != null }
    fun hasHr()         = hrBpm.any { it != null }

    companion object {
        val EMPTY = RideData(
            startTimeUnixS = 0,
            durationS = 0,
            resistance = emptyList(),
            rpm = emptyList(),
            watt = emptyList(),
            speedKmh = emptyList(),
            hrBpm = emptyList(),
            totalDistanceM = null,
            totalCalorie = null,
        )
    }
}
