package io.github.liki4.peek.ui

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.rememberMultiplePermissionsState

/**
 * The Android 12+ runtime permissions Peek needs at scan/connect time.
 * Pre-Android 12 permissions (BLUETOOTH / BLUETOOTH_ADMIN / FINE_LOCATION) are
 * declared in the manifest with `android:maxSdkVersion="30"` and granted at
 * install time — no runtime prompt.
 */
private val REQUIRED_PERMISSIONS: List<String> = buildList {
    add(Manifest.permission.BLUETOOTH_SCAN)
    add(Manifest.permission.BLUETOOTH_CONNECT)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun rememberPeekPermissionsState(): MultiplePermissionsState =
    rememberMultiplePermissionsState(REQUIRED_PERMISSIONS)

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionsGate(
    state: MultiplePermissionsState,
    content: @Composable () -> Unit,
) {
    if (state.allPermissionsGranted) {
        content()
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Peek needs Bluetooth + notification permissions to talk to the bike and run rides in the background.",
                style = MaterialTheme.typography.bodyLarge,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))
            Button(onClick = { state.launchMultiplePermissionRequest() }) {
                Text("Grant permissions")
            }
        }
    }
}
