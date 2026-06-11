package io.github.liki4.peek.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.liki4.peek.ftms.CalibrationRunner
import io.github.liki4.peek.ride.RideRepository
import kotlinx.coroutines.launch

/**
 * Calibration wizard screen. Triggered the first time the user enables the
 * FTMS toggle, or manually from Settings.
 *
 * Three phases driven by RideRepository.state.calibration:
 * - Idle / not started: show the intro card with an "开始" button.
 * - Settling / Measuring: show big level number + countdown + live numbers.
 * - Done / Failed: show summary; "完成" button returns to Settings.
 */
@Composable
fun CalibrationScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { RideRepository.get(ctx) }
    val ui by repo.state.collectAsState()
    val scope = rememberCoroutineScope()
    val progress = ui.calibration

    val totalLevels = CalibrationRunner.LEVELS_DEFAULT.size

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("FTMS 校准", style = MaterialTheme.typography.headlineSmall)

        when (progress) {
            is CalibrationRunner.Progress.Idle -> IntroCard(
                onStart = {
                    scope.launch { runCatching { repo.runCalibration() } }
                },
                onCancel = onDone,
            )
            is CalibrationRunner.Progress.Settling -> StepCard(
                stepIdx = progress.stepIdx, totalSteps = totalLevels,
                level = progress.level,
                phase = "档位切换中…",
                secondsLeft = progress.secondsLeft, totalSeconds = 5,
                liveRpm = ui.live.rpm, liveWatt = ui.live.watt,
                samplesCollected = 0,
            )
            is CalibrationRunner.Progress.Measuring -> StepCard(
                stepIdx = progress.stepIdx, totalSteps = totalLevels,
                level = progress.level,
                phase = "请变换踏频（慢/中/快）",
                secondsLeft = progress.secondsLeft, totalSeconds = 30,
                liveRpm = ui.live.rpm, liveWatt = ui.live.watt,
                samplesCollected = progress.samplesCollected,
                rpmMin = progress.rpmMin, rpmMax = progress.rpmMax,
            )
            is CalibrationRunner.Progress.Done -> DoneCard(
                measured = progress.measuredLevels,
                lowVariance = progress.lowVarianceLevels,
                onDone = {
                    repo.resetCalibrationUi()
                    onDone()
                },
            )
            is CalibrationRunner.Progress.Failed -> FailedCard(
                message = progress.message,
                onDone = {
                    repo.resetCalibrationUi()
                    onDone()
                },
            )
        }
    }
}

@Composable
private fun IntroCard(onStart: () -> Unit, onCancel: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("欢迎来到校准向导", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                "我们将用 ~4 分钟测量 6 个档位的踏频-功率关系（档位 2 / 5 / 8 / 11 / 14 / 17）。" +
                    "其余档位由相邻档位插值。\n\n" +
                    "建议每档内变换踏频（慢 / 中 / 快），让模型拟得更准。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, modifier = Modifier.weight(1f)) { Text("开始校准") }
                OutlinedButton(onClick = onCancel) { Text("跳过") }
            }
        }
    }
}

@Composable
private fun StepCard(
    stepIdx: Int, totalSteps: Int,
    level: Int,
    phase: String,
    secondsLeft: Int, totalSeconds: Int,
    liveRpm: Int?, liveWatt: Int?,
    samplesCollected: Int,
    rpmMin: Int? = null, rpmMax: Int? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("第 ${stepIdx + 1} 步 / $totalSteps · 档位 $level",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Text(phase, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            Box(
                modifier = Modifier.fillMaxWidth().height(120.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("${secondsLeft}s", fontSize = 72.sp, fontWeight = FontWeight.Bold)
            }
            LinearProgressIndicator(
                progress = { (totalSeconds - secondsLeft).coerceAtLeast(0) / totalSeconds.toFloat() },
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceAround,
            ) {
                MetricColumn("踏频", liveRpm?.toString() ?: "—", "rpm")
                MetricColumn("功率", liveWatt?.toString() ?: "—", "W")
                MetricColumn("样本数", samplesCollected.toString(), null)
            }

            if (rpmMin != null && rpmMax != null) {
                Text(
                    "本档踏频区间 $rpmMin – $rpmMax rpm" +
                        if (rpmMax - rpmMin < 5) "（建议变换更大）" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MetricColumn(label: String, value: String, unit: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            if (unit != null) Text(" $unit", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DoneCard(measured: List<Int>, lowVariance: List<Int>, onDone: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("校准完成", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text("已测量档位：${measured.joinToString()}",
                style = MaterialTheme.typography.bodyMedium)
            if (lowVariance.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "下列档位踏频变化偏小，下次校准时可以尝试更宽的踏频范围：${lowVariance.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("其余 ${18 - measured.size} 档由插值得到。ERG / SIM 已就绪。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("完成") }
        }
    }
}

@Composable
private fun FailedCard(message: String, onDone: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("校准失败", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("返回") }
        }
    }
}
