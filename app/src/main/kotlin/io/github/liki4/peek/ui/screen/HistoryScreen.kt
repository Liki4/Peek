package io.github.liki4.peek.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.github.liki4.peek.history.RideSessionEntity
import io.github.liki4.peek.ride.RideRepository
import io.github.liki4.peek.ui.formatDuration
import io.github.liki4.peek.ui.shareFit
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-ride history list. Each row exposes:
 * - 上传到 Strava (opt-in; status pulled from Room + transient RideUiState)
 * - 分享 FIT (system share intent)
 * - 删除 (filesystem + Room row)
 */
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { RideRepository.get(ctx) }
    val scope = rememberCoroutineScope()

    val sessions by repo.rideDao.observeAll().collectAsState(initial = emptyList())
    val ui by repo.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("骑行记录", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("返回") }
        }
        Spacer(Modifier.height(8.dp))

        if (sessions.isEmpty()) {
            Text(
                "还没有保存的骑行记录。每次结束运动并导出 FIT 后会自动出现在这里。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(sessions, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        uploading = ui.uploadingSessionId == session.id,
                        error = ui.uploadErrors[session.id],
                        onUpload = { repo.uploadToStrava(session.id) },
                        onShare = { shareFit(ctx, File(session.fitFilePath)) },
                        onDelete = {
                            scope.launch {
                                runCatching { File(session.fitFilePath).delete() }
                                repo.rideDao.delete(session.id)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: RideSessionEntity,
    uploading: Boolean,
    error: String?,
    onUpload: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                formatDate(session.startTimeUnixS),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "时长 ${formatDuration(session.durationS)}" +
                    "  ·  距离 ${formatKm(session.distanceM)}" +
                    "  ·  ${session.calorie ?: 0} kcal",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "平均功率 ${session.avgWatt?.let { "$it W" } ?: "—"}" +
                    "  ·  平均心率 ${session.avgHrBpm?.let { "$it bpm" } ?: "—"}" +
                    "  ·  最高心率 ${session.maxHrBpm?.let { "$it bpm" } ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            StravaUploadCell(
                sessionId = session.id,
                stravaActivityId = session.stravaActivityId,
                uploading = uploading,
                error = error,
                onUpload = { onUpload() },
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Text("分享 FIT")
                }
                OutlinedButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

private fun formatDate(unixS: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(unixS * 1000))

private fun formatKm(m: Int?): String =
    m?.let { "%.2f km".format(it / 1000f) } ?: "—"
