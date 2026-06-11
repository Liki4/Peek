package io.github.liki4.peek.ui

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import io.github.liki4.peek.ride.RideForegroundService
import io.github.liki4.peek.ride.RideRepository
import io.github.liki4.peek.ride.RideState
import io.github.liki4.peek.ui.screen.CalibrationScreen
import io.github.liki4.peek.ui.screen.ConnectScreen
import io.github.liki4.peek.ui.screen.HistoryScreen
import io.github.liki4.peek.ui.screen.RideScreen
import io.github.liki4.peek.ui.screen.ScanScreen
import io.github.liki4.peek.ui.screen.SessionScreen
import io.github.liki4.peek.ui.screen.SettingsScreen
import io.github.liki4.peek.ui.theme.PeekTheme

/**
 * Top-level app shell. State-driven screen selection — the current [RideState]
 * picks the visible screen; Settings is a modal overlay.
 *
 * Also manages the [RideForegroundService] lifecycle: starts it when a bike
 * connects, stops when disconnected.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PeekRoot() {
    val perms = rememberPeekPermissionsState()
    PeekTheme {
        // safeDrawingPadding handles status bar + nav bar + display cutouts.
        // Required since target SDK 35 forces edge-to-edge.
        Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            PermissionsGate(state = perms) {
                AppContent()
            }
        }
    }
}

@Composable
private fun AppContent() {
    val ctx = LocalContext.current
    val repo = remember { RideRepository.get(ctx) }
    val ui by repo.state.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showCalibration by remember { mutableStateOf(false) }

    // Foreground service is active whenever we hold a bike connection.
    LaunchedEffect(ui.bike?.address) {
        if (ui.bike != null) RideForegroundService.start(ctx)
        else RideForegroundService.stop(ctx)
    }

    // Keep the screen on whenever the user is mid-ride (any state where the
    // bike is actively recording). Releases on disconnect / session end so
    // we don't drain battery sitting on the summary screen.
    val keepScreenOn = ui.state in setOf(
        RideState.WAITING_KNOB,
        RideState.TRAINING,
        RideState.PAUSED,
        RideState.RECONNECTING,
    )
    val view = LocalView.current
    DisposableEffect(keepScreenOn) {
        val window = (view.context as? Activity)?.window
        if (keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    if (showCalibration) {
        BackHandler { showCalibration = false }
        CalibrationScreen(onDone = { showCalibration = false })
        return
    }

    if (showHistory) {
        BackHandler { showHistory = false }
        HistoryScreen(onBack = { showHistory = false })
        return
    }

    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsScreen(
            onBack = { showSettings = false },
            onOpenCalibration = {
                showSettings = false
                showCalibration = true
            },
        )
        return
    }

    when (ui.state) {
        RideState.IDLE,
        RideState.SCANNING        -> ScanScreen(
            onOpenSettings = { showSettings = true },
            onOpenHistory = { showHistory = true },
        )
        RideState.CONNECTING      -> ConnectScreen(ui)
        RideState.CONNECTED,
        RideState.WAITING_KNOB,
        RideState.TRAINING,
        RideState.PAUSED,
        RideState.RECONNECTING    -> RideScreen(
            ui = ui,
            repo = repo,
            onOpenSettings = { showSettings = true },
        )
        RideState.STOPPED,
        RideState.EXPORTING       -> SessionScreen(
            ui = ui,
            repo = repo,
            // Disconnect the bike — repo resets state to IDLE → back to ScanScreen.
            onReturnHome = { repo.disconnectBike() },
        )
    }
}
