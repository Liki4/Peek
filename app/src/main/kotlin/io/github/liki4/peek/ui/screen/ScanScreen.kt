package io.github.liki4.peek.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.liki4.peek.ble.BikeScanner
import io.github.liki4.peek.ble.DiscoveredBike
import io.github.liki4.peek.ble.DiscoveredHrBelt
import io.github.liki4.peek.hr.HrBeltClient
import io.github.liki4.peek.ride.RideRepository
import kotlinx.coroutines.launch

@Composable
fun ScanScreen(
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val repo = remember { RideRepository.get(ctx) }
    val scope = rememberCoroutineScope()

    val state by repo.state.collectAsState()
    val bikes = remember { mutableStateListOf<DiscoveredBike>() }
    val belts = remember { mutableStateListOf<DiscoveredHrBelt>() }

    LaunchedEffect(Unit) {
        // Stream both scans concurrently.
        scope.launch {
            BikeScanner(ctx).scan().collect { b ->
                val idx = bikes.indexOfFirst { it.address == b.address }
                if (idx >= 0) bikes[idx] = b else bikes += b
            }
        }
        scope.launch {
            HrBeltClient(ctx).scan().collect { h ->
                val idx = belts.indexOfFirst { it.address == h.address }
                if (idx >= 0) belts[idx] = h else belts += h
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text("Peek", style = MaterialTheme.typography.headlineMedium)
            Row {
                TextButton(onClick = onOpenHistory) { Text("历史") }
                TextButton(onClick = onOpenSettings) { Text("设置") }
            }
        }
        Spacer(Modifier.padding(4.dp))
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        SectionHeader("Bikes")
        if (bikes.isEmpty()) {
            Text("Scanning for Keep_CC_… devices",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(bikes, key = { it.address }) { bike ->
                    DeviceCard(name = bike.name, sub = "${bike.address}   ${bike.rssi} dBm") {
                        repo.connectBike(bike.address, bike.name)
                    }
                }
            }
        }

        SectionHeader("Heart rate straps")
        if (belts.isEmpty()) {
            Text("Scanning for BLE HR services (0x180D)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(belts, key = { it.address }) { belt ->
                    DeviceCard(name = belt.name, sub = "${belt.address}   ${belt.rssi} dBm") {
                        repo.connectHr(belt.address, belt.name)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Spacer(Modifier.padding(8.dp))
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun DeviceCard(name: String, sub: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(sub, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onClick) { Text("Pair") }
        }
    }
}
