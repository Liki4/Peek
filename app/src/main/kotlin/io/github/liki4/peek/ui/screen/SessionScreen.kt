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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.height
import io.github.liki4.peek.ride.RideRepository
import io.github.liki4.peek.ride.RideUiState
import io.github.liki4.peek.ride.Settings as PeekSettings
import io.github.liki4.peek.ui.component.HrChart
import java.io.File

@Composable
fun SessionScreen(
    ui: RideUiState,
    repo: RideRepository,
    onReturnHome: () -> Unit,
) {
    val ctx = LocalContext.current
    val exportedFile = ui.lastFit
    val sessionId = ui.lastFitSessionId
    // Observe the freshly-inserted row so we can show its persisted Strava status.
    val sessions by repo.rideDao.observeAll().collectAsState(initial = emptyList())
    val session = sessions.firstOrNull { it.id == sessionId }
    val hrSeries by repo.hrSeries.collectAsState()
    val maxHr by repo.settings.maxHrBpm.collectAsState(initial = PeekSettings.DEFAULT_MAX_HR)

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

        if (hrSeries.any { it > 0 }) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(
                        "心率曲线",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.padding(top = 4.dp))
                    HrChart(
                        samples = hrSeries,
                        maxHr = maxHr,
                        windowSeconds = null, // full ride
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                    )
                }
            }
        }

        if (exportedFile != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("已写入:", style = MaterialTheme.typography.labelSmall)
                    Text(exportedFile.name, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.padding(4.dp))
                    StravaUploadCell(
                        sessionId = sessionId,
                        stravaActivityId = session?.stravaActivityId,
                        uploading = sessionId != null && ui.uploadingSessionId == sessionId,
                        error = sessionId?.let { ui.uploadErrors[it] },
                        onUpload = { sid -> repo.uploadToStrava(sid) },
                    )
                    Spacer(Modifier.padding(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { shareFit(ctx, exportedFile) },
                        ) { Text("分享 FIT…") }
                    }
                }
            }
        }

        if (ui.lastDebugLog != null) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { shareFit(ctx, ui.lastDebugLog!!) },
            ) { Text("分享调试日志") }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onReturnHome,
        ) { Text("完成") }
    }
}

/**
 * Reusable "upload to Strava" cell: status text + button. Used by SessionScreen
 * after a fresh export, and by HistoryScreen for each past row.
 */
@Composable
fun StravaUploadCell(
    sessionId: Long?,
    stravaActivityId: Long?,
    uploading: Boolean,
    error: String?,
    onUpload: (Long) -> Unit,
) {
    Column {
        when {
            stravaActivityId != null -> Text(
                "✓ 已同步 Strava (#$stravaActivityId)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            uploading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(end = 8.dp))
                Text("正在上传 Strava…", style = MaterialTheme.typography.bodySmall)
            }
            error != null -> Text(
                "Strava 上传失败：$error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            else -> Text(
                "本地保存。需要时可上传到 Strava。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (stravaActivityId == null) {
            OutlinedButton(
                onClick = { sessionId?.let(onUpload) },
                enabled = !uploading && sessionId != null,
                modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
            ) {
                Text(if (error != null) "重试上传 Strava" else "上传到 Strava")
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
