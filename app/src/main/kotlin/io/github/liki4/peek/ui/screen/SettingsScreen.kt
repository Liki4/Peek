package io.github.liki4.peek.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.liki4.peek.ble.BikeScanner
import io.github.liki4.peek.ble.DiscoveredBike
import io.github.liki4.peek.ble.DiscoveredHrBelt
import io.github.liki4.peek.ftms.FtmsBridge
import io.github.liki4.peek.hr.HrBeltClient
import io.github.liki4.peek.ride.RideRepository
import io.github.liki4.peek.ride.RideState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Settings screen — entry point both from the (pre-connect) ScanScreen and
 * from the (post-connect) RideScreen, so the user can pair an HR strap after
 * already having connected the bike (or vice versa).
 *
 * Three sections:
 *  - Bike: connection status + disconnect / re-pair
 *  - HR strap: connection status + disconnect / pair
 *  - Profile: userId / weight / deviceId
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenCalibration: () -> Unit = {}) {
    val ctx = LocalContext.current
    val repo = remember { RideRepository.get(ctx) }
    val ui by repo.state.collectAsState()
    val scope = rememberCoroutineScope()

    var userId by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("70") }
    var maxHr by remember { mutableStateOf(io.github.liki4.peek.ride.Settings.DEFAULT_MAX_HR.toString()) }
    var deviceId by remember { mutableStateOf("") }
    var stravaClientId by remember { mutableStateOf("") }
    var stravaClientSecret by remember { mutableStateOf("") }
    var stravaRefreshToken by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        userId = repo.settings.userId.first() ?: ""
        weight = repo.settings.weightKg.first().toString()
        maxHr = repo.settings.maxHrBpm.first().toString()
        deviceId = repo.settings.ensureDeviceId()
        stravaClientId = repo.settings.stravaClientId.first().orEmpty()
        stravaClientSecret = repo.settings.stravaClientSecret.first().orEmpty()
        stravaRefreshToken = repo.settings.stravaRefreshToken.first().orEmpty()
    }
    val bridgeEnabled by repo.settings.bridgeEnabled.collectAsState(initial = false)
    val calibratedAt by repo.settings.calibratedAt.collectAsState(initial = null)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("设置", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("完成") }
        }
        Spacer(Modifier.height(8.dp))

        SectionTitle("动感单车")
        BikeDeviceCard(ui = ui, repo = repo)

        Spacer(Modifier.height(16.dp))
        SectionTitle("心率带")
        HrDeviceCard(ui = ui, repo = repo)

        Spacer(Modifier.height(16.dp))
        SectionTitle("个人信息")
        ProfileCard(
            userId = userId,
            onUserIdChange = { userId = it },
            weight = weight,
            onWeightChange = { weight = it },
            maxHr = maxHr,
            onMaxHrChange = { maxHr = it },
            deviceId = deviceId,
            onSave = {
                scope.launch {
                    repo.settings.setUserId(userId.trim())
                    weight.toFloatOrNull()?.let { repo.settings.setWeightKg(it) }
                    maxHr.toIntOrNull()
                        ?.takeIf { it in 100..230 }
                        ?.let { repo.settings.setMaxHrBpm(it) }
                }
            },
        )

        Spacer(Modifier.height(16.dp))
        SectionTitle("外部训练平台 (FTMS)")
        FtmsCard(
            bridgeState = ui.bridge,
            enabled = bridgeEnabled,
            onToggle = { enabled ->
                scope.launch {
                    repo.settings.setBridgeEnabled(enabled)
                    if (enabled && calibratedAt == null) onOpenCalibration()
                }
            },
            calibratedAt = calibratedAt,
            onRecalibrate = onOpenCalibration,
        )

        Spacer(Modifier.height(16.dp))
        SectionTitle("Strava 自动同步")
        StravaCard(
            clientId = stravaClientId,
            onClientIdChange = { stravaClientId = it },
            clientSecret = stravaClientSecret,
            onClientSecretChange = { stravaClientSecret = it },
            refreshToken = stravaRefreshToken,
            onRefreshTokenChange = { stravaRefreshToken = it },
            onSave = {
                scope.launch {
                    repo.settings.setStravaClientId(stravaClientId.trim())
                    repo.settings.setStravaClientSecret(stravaClientSecret.trim())
                    repo.settings.setStravaRefreshToken(stravaRefreshToken.trim())
                }
            },
        )

        Spacer(Modifier.height(16.dp))
        SectionTitle("调试")
        DebugCard(repo = repo, scope = scope)

        Spacer(Modifier.height(16.dp))
        Text(
            text = "动感单车不会校验 user ID — 只检查「会话有所有者」。" +
                "持久化一个真 ID 是为了将来接云端同步时保持一致。\n\n" +
                "Strava 凭据在 export_fit/ 项目里通过 OAuth flow 一次性 mint，" +
                "三个值粘进来后 Peek 会在每次结束骑行后自动用 refresh token 拿 access token 并上传 FIT。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DebugCard(repo: RideRepository, scope: kotlinx.coroutines.CoroutineScope) {
    val debugEnabled by repo.settings.debugLogEnabled.collectAsState(initial = false)
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("调试日志", style = MaterialTheme.typography.titleSmall)
                Text(
                    "开启后骑行时逐秒记录全部遥测数据（JSONL）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = debugEnabled,
                onCheckedChange = { enabled ->
                    scope.launch { repo.settings.setDebugLogEnabled(enabled) }
                },
            )
        }
    }
}

@Composable
private fun StravaCard(
    clientId: String,
    onClientIdChange: (String) -> Unit,
    clientSecret: String,
    onClientSecretChange: (String) -> Unit,
    refreshToken: String,
    onRefreshTokenChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            OutlinedTextField(
                value = clientId,
                onValueChange = onClientIdChange,
                label = { Text("client_id") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            OutlinedTextField(
                value = clientSecret,
                onValueChange = onClientSecretChange,
                label = { Text("client_secret") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            OutlinedTextField(
                value = refreshToken,
                onValueChange = onRefreshTokenChange,
                label = { Text("refresh_token") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            Button(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                onClick = onSave,
            ) { Text("保存 Strava 凭据") }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun FtmsCard(
    bridgeState: FtmsBridge.State,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    calibratedAt: Long?,
    onRecalibrate: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("把这台动感单车广播给 Zwift / Mywhoosh",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium)
                    Text(bridgeStatusLine(bridgeState),
                        style = MaterialTheme.typography.bodySmall,
                        color = bridgeStatusColor(bridgeState))
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            if (bridgeState is FtmsBridge.State.Unsupported) {
                Text(
                    "本设备的蓝牙芯片不支持 BLE peripheral 模式，无法作为 FTMS 服务端。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("功率-档位 校准",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium)
                    Text(
                        if (calibratedAt != null) "上次校准：${formatTimestamp(calibratedAt)}"
                        else "尚未校准 — ERG / SIM 模式需要校准后才能工作",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (calibratedAt != null) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedButton(onClick = onRecalibrate) {
                    Text(if (calibratedAt != null) "重新校准" else "立即校准")
                }
            }
        }
    }
}

private fun bridgeStatusLine(s: FtmsBridge.State): String = when (s) {
    is FtmsBridge.State.Disabled        -> "未启用"
    is FtmsBridge.State.Unsupported     -> "设备不支持"
    is FtmsBridge.State.Starting        -> "启动中…"
    is FtmsBridge.State.Advertising     -> "已广播 — 等待连接"
    is FtmsBridge.State.ClientConnected -> "已连接：${s.deviceName}"
    is FtmsBridge.State.Failed          -> "失败：${s.reason}"
}

@Composable
private fun bridgeStatusColor(s: FtmsBridge.State) = when (s) {
    is FtmsBridge.State.ClientConnected -> MaterialTheme.colorScheme.primary
    is FtmsBridge.State.Failed, is FtmsBridge.State.Unsupported -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun formatTimestamp(unixMs: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(unixMs))

@Composable
private fun BikeDeviceCard(
    ui: io.github.liki4.peek.ride.RideUiState,
    repo: RideRepository,
) {
    val ctx = LocalContext.current
    val bike = ui.bike

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            if (bike != null) {
                StatusRow(
                    label = bike.name,
                    sub = "${bike.address} · " + when (ui.state) {
                        RideState.RECONNECTING -> "正在重连…"
                        RideState.CONNECTING   -> "连接中…"
                        else                   -> "已连接"
                    },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    enabled = ui.state == RideState.CONNECTED,
                    onClick = { repo.disconnectBike() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("断开连接") }
            } else {
                Text("未连接", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                BikeScanList(
                    onPair = { addr, name -> repo.connectBike(addr, name) },
                )
            }
        }
    }
}

@Composable
private fun HrDeviceCard(
    ui: io.github.liki4.peek.ride.RideUiState,
    repo: RideRepository,
) {
    val belt = ui.hrBelt

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            if (belt != null) {
                StatusRow(
                    label = belt.name,
                    sub = belt.address +
                        (belt.batteryPct?.let { " · 电量 $it%" } ?: "") +
                        " · 已连接",
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { repo.disconnectHr() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("断开连接") }
            } else {
                Text("未连接", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                HrScanList(
                    onPair = { addr, name -> repo.connectHr(addr, name) },
                )
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, sub: String) {
    Column {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(sub, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BikeScanList(onPair: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val bikes = remember { mutableStateListOf<DiscoveredBike>() }
    val scope = rememberCoroutineScope()
    var scanJob: Job? by remember { mutableStateOf(null) }

    DisposableEffect(Unit) {
        scanJob = scope.launch {
            BikeScanner(ctx).scan().collect { b ->
                val idx = bikes.indexOfFirst { it.address == b.address }
                if (idx >= 0) bikes[idx] = b else bikes += b
            }
        }
        onDispose { scanJob?.cancel() }
    }

    if (bikes.isEmpty()) {
        Text("正在扫描 Keep_CC_… 设备",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        bikes.forEach { bike ->
            DiscoveredRow(name = bike.name, sub = "${bike.address}   ${bike.rssi} dBm") {
                onPair(bike.address, bike.name)
            }
        }
    }
}

@Composable
private fun HrScanList(onPair: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val belts = remember { mutableStateListOf<DiscoveredHrBelt>() }
    val scope = rememberCoroutineScope()
    var scanJob: Job? by remember { mutableStateOf(null) }

    DisposableEffect(Unit) {
        scanJob = scope.launch {
            HrBeltClient(ctx).scan().collect { h ->
                val idx = belts.indexOfFirst { it.address == h.address }
                if (idx >= 0) belts[idx] = h else belts += h
            }
        }
        onDispose { scanJob?.cancel() }
    }

    if (belts.isEmpty()) {
        Text("正在扫描 BLE 心率服务 (0x180D)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        belts.forEach { belt ->
            DiscoveredRow(name = belt.name, sub = "${belt.address}   ${belt.rssi} dBm") {
                onPair(belt.address, belt.name)
            }
        }
    }
}

@Composable
private fun DiscoveredRow(name: String, sub: String, onPair: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.padding(end = 8.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(onClick = onPair) { Text("配对") }
    }
}

@Composable
private fun ProfileCard(
    userId: String,
    onUserIdChange: (String) -> Unit,
    weight: String,
    onWeightChange: (String) -> Unit,
    maxHr: String,
    onMaxHrChange: (String) -> Unit,
    deviceId: String,
    onSave: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            OutlinedTextField(
                value = userId,
                onValueChange = onUserIdChange,
                label = { Text("Keep user ID (24 字符 ObjectId)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            OutlinedTextField(
                value = weight,
                onValueChange = onWeightChange,
                label = { Text("体重 (kg)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            OutlinedTextField(
                value = maxHr,
                onValueChange = onMaxHrChange,
                label = { Text("最大心率 (bpm)") },
                supportingText = { Text("用于心率区间划分。留空 = 默认 220-30 = 190。") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            OutlinedTextField(
                value = deviceId,
                onValueChange = { },
                label = { Text("设备 ID（自动生成）") },
                singleLine = true,
                readOnly = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            Button(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                onClick = onSave,
            ) { Text("保存") }
        }
    }
}
