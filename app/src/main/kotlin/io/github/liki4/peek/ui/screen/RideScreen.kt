package io.github.liki4.peek.ui.screen

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.liki4.peek.ftms.FtmsBridge
import io.github.liki4.peek.ride.RideRepository
import io.github.liki4.peek.ride.RideState
import io.github.liki4.peek.ride.RideUiState
import io.github.liki4.peek.ride.Settings as PeekSettings
import io.github.liki4.peek.ui.component.HrChart
import io.github.liki4.peek.ui.formatDuration

/**
 * Live ride screen — iGPSPORT-inspired layout.
 *
 * Visual hierarchy: time → distance → heart rate (with zone bar) → cadence /
 * speed / 3s power / avg power → resistance bar → control buttons.
 *
 * Two layouts driven by [LocalConfiguration.orientation]: portrait stacks
 * everything vertically; landscape splits the metric grid into two columns
 * with the resistance + control bar still spanning the full width at the
 * bottom. Activity has `configChanges` declared so rotation does NOT recreate
 * the activity (would tear down the BLE foreground service binding).
 */
@Composable
fun RideScreen(
    ui: RideUiState,
    repo: RideRepository,
    onOpenSettings: () -> Unit = {},
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val maxHr by repo.settings.maxHrBpm.collectAsState(initial = PeekSettings.DEFAULT_MAX_HR)
    val hrSeries by repo.hrSeries.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        StatusHeader(ui = ui, onOpenSettings = onOpenSettings)
        Spacer(Modifier.height(8.dp))

        if (isLandscape) {
            LandscapeBody(ui = ui, maxHr = maxHr, hrSeries = hrSeries, modifier = Modifier.weight(1f))
        } else {
            PortraitBody(ui = ui, maxHr = maxHr, hrSeries = hrSeries, modifier = Modifier.weight(1f))
        }

        Spacer(Modifier.height(8.dp))
        ResistanceFooter(
            currentLevel = ui.live.resistance,
            onSet = { repo.setResistance(it) },
        )
        Spacer(Modifier.height(8.dp))
        ControlBar(ui = ui, repo = repo)
    }
}

// ============================== HEADER ==============================

@Composable
private fun StatusHeader(ui: RideUiState, onOpenSettings: () -> Unit) {
    val (text, color) = when (ui.state) {
        RideState.CONNECTED     -> "已连接 · 点击 Start 开始" to MaterialTheme.colorScheme.primary
        RideState.WAITING_KNOB  -> "请按动感单车上的旋钮开始" to MaterialTheme.colorScheme.tertiary
        RideState.TRAINING      -> "骑行中…" to MaterialTheme.colorScheme.primary
        RideState.PAUSED        -> "已暂停" to MaterialTheme.colorScheme.secondary
        RideState.RECONNECTING  -> "正在重连…" to MaterialTheme.colorScheme.error
        else                    -> ui.state.name to MaterialTheme.colorScheme.outline
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text, color = color, style = MaterialTheme.typography.titleSmall)
            ui.bike?.name?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val client = ui.bridge as? FtmsBridge.State.ClientConnected
        if (client != null) {
            Text(
                "▲ ${client.deviceName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        if (ui.state == RideState.CONNECTED) {
            TextButton(onClick = onOpenSettings) { Text("设置") }
        }
    }
}

// ============================== PORTRAIT ==============================

@Composable
private fun PortraitBody(ui: RideUiState, maxHr: Int, hrSeries: IntArray, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        TimeRow(ui.secondsRecorded)
        ThinDivider()
        DistanceRow(ui.live.distanceM)
        ThinDivider()
        HeartRateBlock(ui = ui, maxHr = maxHr, hrSeries = hrSeries, modifier = Modifier.weight(1.4f))
        ThinDivider()
        Row(modifier = Modifier.fillMaxWidth().weight(0.7f)) {
            HalfTile("踏频", ui.live.rpm?.toString(), null, modifier = Modifier.weight(1f))
            VerticalThinDivider()
            HalfTile("速度", ui.live.speedKmh?.let { "%.1f".format(it) }, null, modifier = Modifier.weight(1f))
        }
        ThinDivider()
        Row(modifier = Modifier.fillMaxWidth().weight(0.7f)) {
            HalfTile("3s 功率", ui.live.watt3s?.toString() ?: ui.live.watt?.toString(), null, modifier = Modifier.weight(1f))
            VerticalThinDivider()
            HalfTile("平均功率", ui.live.avgWatt?.toString(), null, modifier = Modifier.weight(1f))
        }
    }
}

// ============================== LANDSCAPE ==============================

@Composable
private fun LandscapeBody(ui: RideUiState, maxHr: Int, hrSeries: IntArray, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth()) {
        // Left column: time, distance, HR
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            TimeRow(ui.secondsRecorded)
            ThinDivider()
            DistanceRow(ui.live.distanceM)
            ThinDivider()
            HeartRateBlock(ui = ui, maxHr = maxHr, hrSeries = hrSeries, modifier = Modifier.weight(1.5f))
        }
        VerticalThinDivider()
        // Right column: 2x2 metric grid
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                HalfTile("踏频", ui.live.rpm?.toString(), null, modifier = Modifier.weight(1f))
                VerticalThinDivider()
                HalfTile("速度", ui.live.speedKmh?.let { "%.1f".format(it) }, null, modifier = Modifier.weight(1f))
            }
            ThinDivider()
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                HalfTile("3s 功率", ui.live.watt3s?.toString() ?: ui.live.watt?.toString(), null, modifier = Modifier.weight(1f))
                VerticalThinDivider()
                HalfTile("平均功率", ui.live.avgWatt?.toString(), null, modifier = Modifier.weight(1f))
            }
        }
    }
}

// ============================== ROWS ==============================

@Composable
private fun TimeRow(seconds: Int) {
    BigStat(label = "记录时间", value = formatDuration(seconds), unit = null)
}

@Composable
private fun DistanceRow(distanceM: Int?) {
    val km = distanceM?.let { "%.2f".format(it / 1000f) } ?: "—"
    BigStat(label = "距离", value = km, unit = "km")
}

@Composable
private fun HeartRateBlock(
    ui: RideUiState,
    maxHr: Int,
    hrSeries: IntArray,
    modifier: Modifier = Modifier,
) {
    val live = ui.live
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "❤",
                    fontSize = 28.sp,
                    color = HrColor,
                    modifier = Modifier.padding(end = 4.dp, bottom = 4.dp),
                )
                Text(
                    text = live.hrBpm?.toString() ?: "—",
                    fontSize = 44.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = HrColor,
                )
                Text(
                    text = "bpm",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "AVG: ${live.avgHrBpm?.toString() ?: "—"}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "MAX: ${live.maxHrBpm?.toString() ?: "—"}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        HrChart(
            samples = hrSeries,
            maxHr = maxHr,
            // Rolling 60-second window: fixed-width x-axis so the chart
            // doesn't stretch a handful of points across the screen. Data
            // anchors to the right; left side stays blank until the window
            // is full.
            windowSeconds = 60,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Spacer(Modifier.height(4.dp))
        HrZoneBar(
            currentHr = live.hrBpm,
            maxHr = maxHr,
            modifier = Modifier.fillMaxWidth().height(8.dp),
        )
    }
}

/**
 * Heart-rate zone strip — 5 colored segments (Z1 grey, Z2 blue, Z3 green,
 * Z4 orange, Z5 red). The current zone segment is full opacity; others are
 * dimmed. [maxHr] comes from Settings (default 220-30=190).
 */
@Composable
private fun HrZoneBar(currentHr: Int?, maxHr: Int, modifier: Modifier = Modifier) {
    val zoneBoundaries = listOf(
        0.50f to 0.60f, // Z1
        0.60f to 0.70f, // Z2
        0.70f to 0.80f, // Z3
        0.80f to 0.90f, // Z4
        0.90f to 1.00f, // Z5
    )
    val zoneColors = listOf(
        Color(0xFFB0B0B0),
        Color(0xFF4DA6FF),
        Color(0xFF40C26C),
        Color(0xFFFF8A24),
        Color(0xFFE53935),
    )
    val activeIdx = currentHr?.let { hr ->
        val pct = hr / maxHr.toFloat()
        zoneBoundaries.indexOfFirst { (lo, hi) -> pct in lo..hi }
            .takeIf { it >= 0 }
            ?: if ((hr / maxHr.toFloat()) >= 1f) zoneBoundaries.lastIndex else null
    }
    Row(modifier = modifier.clip(RoundedCornerShape(4.dp))) {
        zoneColors.forEachIndexed { idx, c ->
            val color = if (activeIdx == null || idx == activeIdx) c else c.copy(alpha = 0.35f)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(color),
            )
        }
    }
}

@Composable
private fun BigStat(label: String, value: String, unit: String?) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                fontSize = 56.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (unit != null) {
                Text(
                    text = unit,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun HalfTile(label: String, value: String?, unit: String?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxHeight().padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value ?: "—",
            fontSize = 38.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (unit != null) {
            Text(unit, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ThinDivider() {
    HorizontalDivider(
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun VerticalThinDivider() {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

// ============================== FOOTER ==============================

@Composable
private fun ResistanceFooter(currentLevel: Int?, onSet: (Int) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { currentLevel?.let { onSet((it - 1).coerceAtLeast(1)) } },
                enabled = currentLevel != null && currentLevel > 1,
            ) { Text("−", fontSize = 20.sp) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "阻力",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${currentLevel?.toString() ?: "—"} / 18",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            OutlinedButton(
                onClick = { currentLevel?.let { onSet((it + 1).coerceAtMost(18)) } },
                enabled = currentLevel != null && currentLevel < 18,
            ) { Text("+", fontSize = 20.sp) }
        }
    }
}

@Composable
private fun ControlBar(ui: RideUiState, repo: RideRepository) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        when (ui.state) {
            RideState.CONNECTED -> {
                Button(
                    onClick = { repo.startRide() },
                    modifier = Modifier.weight(1f),
                ) { Text("开始") }
            }
            RideState.WAITING_KNOB, RideState.TRAINING -> {
                FilledTonalButton(
                    onClick = { repo.pauseRide() },
                    modifier = Modifier.weight(1f).padding(end = 4.dp),
                ) { Text("暂停") }
                Button(
                    onClick = { repo.stopRide() },
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                ) { Text("停止") }
            }
            RideState.PAUSED -> {
                Button(
                    onClick = { repo.resumeRide() },
                    modifier = Modifier.weight(1f).padding(end = 4.dp),
                ) { Text("继续") }
                FilledTonalButton(
                    onClick = { repo.stopRide() },
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                ) { Text("停止") }
            }
            RideState.RECONNECTING -> {
                FilledTonalButton(
                    onClick = { },
                    enabled = false,
                    modifier = Modifier.weight(1f),
                ) { Text("等待重连…") }
            }
            else -> Unit
        }
    }
}

private val HrColor = Color(0xFFE53935)
