package io.github.liki4.peek.ftms

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the 6-level calibration wizard: for each chosen level L, sets the
 * bike to L (via the injected [setLevel]), waits [settleSeconds] for the
 * brake motor to finish moving, then collects [measureSeconds] of (rpm, watt)
 * samples from [samples], feeding them into [model].
 *
 * UI subscribes to [progress] to render the wizard. The class is pure
 * coroutines + lambdas; the test injects a synthetic samples flow and a
 * no-op setLevel.
 */
class CalibrationRunner(
    private val model: PowerModel,
    private val setLevel: suspend (Int) -> Unit,
    private val samples: Flow<CalibrationSample>,
    private val settleSeconds: Int = 5,
    private val measureSeconds: Int = 30,
    private val levels: List<Int> = LEVELS_DEFAULT,
) {

    data class CalibrationSample(val rpm: Int, val watt: Int)

    sealed class Progress {
        data object Idle : Progress()
        data class Settling(val stepIdx: Int, val level: Int, val secondsLeft: Int) : Progress()
        data class Measuring(
            val stepIdx: Int,
            val level: Int,
            val secondsLeft: Int,
            val samplesCollected: Int,
            val rpmMin: Int?,
            val rpmMax: Int?,
        ) : Progress()
        data class Done(val measuredLevels: List<Int>, val lowVarianceLevels: List<Int>) : Progress()
        data class Failed(val message: String) : Progress()
    }

    private val _progress = MutableStateFlow<Progress>(Progress.Idle)
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /** Run the full sequence. Cancelable; on cancellation the bridge should reset the bike. */
    suspend fun run() = coroutineScope {
        val measured = mutableListOf<Int>()
        val lowVariance = mutableListOf<Int>()
        try {
            for ((idx, level) in levels.withIndex()) {
                setLevel(level)

                // Settle phase: countdown without sampling — the brake is moving.
                for (s in settleSeconds downTo 1) {
                    _progress.value = Progress.Settling(idx, level, s)
                    delay(1000)
                }

                // Measure phase: countdown + sample collection in parallel.
                var samplesCollected = 0
                var rpmMin: Int? = null
                var rpmMax: Int? = null

                fun emit(secondsLeft: Int) {
                    _progress.value = Progress.Measuring(
                        stepIdx = idx,
                        level = level,
                        secondsLeft = secondsLeft,
                        samplesCollected = samplesCollected,
                        rpmMin = rpmMin,
                        rpmMax = rpmMax,
                    )
                }
                emit(measureSeconds)

                val collector = launch {
                    samples.collect { sample ->
                        if (sample.rpm > MIN_RPM_FOR_FIT && sample.watt > 0) {
                            model.feed(level, sample.rpm, sample.watt)
                            samplesCollected++
                            rpmMin = rpmMin?.coerceAtMost(sample.rpm) ?: sample.rpm
                            rpmMax = rpmMax?.coerceAtLeast(sample.rpm) ?: sample.rpm
                        }
                    }
                }
                try {
                    for (s in (measureSeconds - 1) downTo 0) {
                        delay(1000)
                        emit(s)
                    }
                } finally {
                    collector.cancelAndJoin()
                }

                measured += level
                if ((rpmMin != null) && (rpmMax!! - rpmMin!! < MIN_RPM_VARIANCE)) {
                    lowVariance += level
                }
            }
            _progress.value = Progress.Done(measured, lowVariance)
        } catch (e: Exception) {
            _progress.value = Progress.Failed(e.message ?: e::class.simpleName ?: "calibration error")
            throw e
        }
    }

    companion object {
        /** 6 wizard levels, evenly spaced across 1..18, covering low/mid/high. */
        val LEVELS_DEFAULT = listOf(2, 5, 8, 11, 14, 17)

        /** Below this rpm, samples are noise — treat as coasting. */
        const val MIN_RPM_FOR_FIT = 20

        /** Soft threshold for "rider didn't vary cadence enough" warning. */
        const val MIN_RPM_VARIANCE = 5
    }
}
