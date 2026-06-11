package io.github.liki4.peek.ftms

import io.github.liki4.peek.ftms.CalibrationRunner.CalibrationSample
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CalibrationRunnerTest {

    @Test
    fun `runner walks all six levels and feeds the model`() = runTest {
        val model = PowerModel()
        val samples = MutableSharedFlow<CalibrationSample>(replay = 0, extraBufferCapacity = 256)
        val commandedLevels = mutableListOf<Int>()

        val runner = CalibrationRunner(
            model = model,
            setLevel = { commandedLevels += it },
            samples = samples,
            settleSeconds = 1,
            measureSeconds = 15,  // ≥ PowerModel.MIN_SAMPLES per level for isReady()
        )

        // Run the wizard in the background…
        val job = launch { runner.run() }

        // …and pretend the bike is reporting varied rpm+watt at 1Hz throughout
        // the wizard. Each level: 1s settle + 15s measuring = 16s. 6 × 16 = 96s.
        val ticker = launch {
            repeat(110) { tick ->
                val rpm = 70 + (tick % 5) * 4    // varied so regression has signal
                val watt = 100 + (tick % 7) * 10
                samples.emit(CalibrationSample(rpm, watt))
                delay(1000)
            }
        }

        job.join()
        ticker.cancel()

        // Visited all six wizard levels in order.
        assertEquals(CalibrationRunner.LEVELS_DEFAULT, commandedLevels)

        // Each commanded level got samples — the model should be ready.
        assertTrue("model should be ready after wizard, got isReady=${model.isReady()}", model.isReady())

        // Final progress is Done.
        val final = runner.progress.value
        assertTrue("expected Done, got $final", final is CalibrationRunner.Progress.Done)
    }

    @Test
    fun `coasting samples (rpm too low) are not fed`() = runTest {
        val model = PowerModel()
        val samples = MutableSharedFlow<CalibrationSample>(replay = 0, extraBufferCapacity = 64)
        val runner = CalibrationRunner(
            model = model,
            setLevel = { },
            samples = samples,
            settleSeconds = 1,
            measureSeconds = 3,
            levels = listOf(8),
        )
        val job = launch { runner.run() }
        val ticker = launch {
            repeat(5) {
                samples.emit(CalibrationSample(rpm = 10, watt = 100)) // below MIN_RPM_FOR_FIT
                delay(1000)
            }
        }
        job.join()
        ticker.cancel()
        // No useful samples consumed.
        assertEquals(0, model.sampleCount(8))
    }

    @Test
    fun `low rpm variance is flagged`() = runTest {
        val model = PowerModel()
        val samples = MutableSharedFlow<CalibrationSample>(replay = 0, extraBufferCapacity = 64)
        val runner = CalibrationRunner(
            model = model,
            setLevel = { },
            samples = samples,
            settleSeconds = 1,
            measureSeconds = 4,
            levels = listOf(8),
        )
        val job = launch { runner.run() }
        val ticker = launch {
            repeat(6) {
                samples.emit(CalibrationSample(rpm = 80, watt = 150)) // constant rpm
                delay(1000)
            }
        }
        job.join()
        ticker.cancel()
        val final = runner.progress.value as CalibrationRunner.Progress.Done
        assertTrue("L=8 should be in lowVariance list: ${final.lowVarianceLevels}",
            8 in final.lowVarianceLevels)
    }
}
