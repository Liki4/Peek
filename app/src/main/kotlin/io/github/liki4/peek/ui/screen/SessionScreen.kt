package io.github.liki4.peek.ui.screen

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.liki4.peek.ride.RideRepository
import io.github.liki4.peek.ride.RideState
import io.github.liki4.peek.ride.RideUiState
import java.io.File

@Composable
fun SessionScreen(
    ui: RideUiState,
    repo: RideRepository,
    onReturnHome: () -> Unit,
) {
    val ctx = LocalContext.current
    val exportedFile = ui.lastFit

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Session summary", style = MaterialTheme.typography.headlineSmall)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                SummaryRow("Duration", formatDuration(ui.secondsRecorded))
                SummaryRow("Distance", "%.2f km".format((ui.live.distanceM ?: 0) / 1000f))
                SummaryRow("Calories", "${ui.live.calorieKcal ?: 0} kcal")
                SummaryRow("Max power", ui.live.watt?.let { "$it W" } ?: "—")
            }
        }

        if (exportedFile == null) {
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = ui.state != RideState.EXPORTING,
                onClick = { repo.exportFit() },
            ) {
                if (ui.state == RideState.EXPORTING) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(end = 8.dp))
                }
                Text("导出 FIT")
            }
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("已写入:", style = MaterialTheme.typography.labelSmall)
                    Text(exportedFile.name, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.padding(4.dp))
                    StravaStatusRow(state = ui.strava, onRetry = {
                        repo.uploadToStrava(exportedFile, sessionId = null)
                    })
                    Spacer(Modifier.padding(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = { shareFit(ctx, exportedFile) },
                        ) { Text("分享 FIT…") }
                        OutlinedButton(onClick = onReturnHome) { Text("完成") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StravaStatusRow(
    state: io.github.liki4.peek.ride.RideUiState.StravaState,
    onRetry: () -> Unit,
) {
    when (state) {
        is io.github.liki4.peek.ride.RideUiState.StravaState.Idle ->
            Text("等待上传 Strava…", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        is io.github.liki4.peek.ride.RideUiState.StravaState.NotConfigured ->
            Text("未配置 Strava 凭据 — 设置中填入 client_id/secret/refresh_token 即可自动同步。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        is io.github.liki4.peek.ride.RideUiState.StravaState.Uploading ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                CircularProgressIndicator(strokeWidth = 2.dp,
                    modifier = Modifier.padding(end = 8.dp).run { this })
                Text("正在上传 Strava…", style = MaterialTheme.typography.bodySmall)
            }
        is io.github.liki4.peek.ride.RideUiState.StravaState.Success ->
            Text("✓ 已同步 Strava (#${state.activityId})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
        is io.github.liki4.peek.ride.RideUiState.StravaState.Failed -> {
            Column {
                Text("Strava 上传失败：${state.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 4.dp)) {
                    Text("重试")
                }
            }
        }
    }
}

private fun shareFit(ctx: android.content.Context, file: File) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "Share FIT to…"))
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun formatDuration(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
