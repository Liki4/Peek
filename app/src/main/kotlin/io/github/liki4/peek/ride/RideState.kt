package io.github.liki4.peek.ride

import io.github.liki4.peek.ftms.CalibrationRunner
import io.github.liki4.peek.ftms.FtmsBridge
import io.github.liki4.peek.protocol.DeviceInfo
import java.io.File

enum class RideState {
    /** Not connected to a bike. */
    IDLE,
    SCANNING,
    CONNECTING,

    /** Handshake complete; bike screen shows "已连接". Ready to start. */
    CONNECTED,

    /** App sent PUT 106/4 status=2; bike screen shows "press knob". */
    WAITING_KNOB,

    /** Bike pushed status=2 — actively training. */
    TRAINING,

    PAUSED,

    /**
     * BLE link dropped during a live session; auto-reconnect in flight.
     * Screen stays on the ride layout — when the link comes back we restore
     * the prior state (TRAINING/PAUSED/CONNECTED) via [RideUiState.priorStateBeforeReconnect].
     */
    RECONNECTING,

    /** Session ended. FIT not yet exported. */
    STOPPED,

    /** Writing FIT file. */
    EXPORTING,
}

data class RideUiState(
    val state: RideState = RideState.IDLE,
    val bike: ConnectedBike? = null,
    val hrBelt: ConnectedHr? = null,
    val live: LiveMetrics = LiveMetrics(),
    val deviceInfo: DeviceInfo? = null,
    val secondsRecorded: Int = 0,
    /** Set by [io.github.liki4.peek.ride.RideRepository.exportFit] when the FIT write completes. */
    val lastFit: File? = null,
    val error: String? = null,
    /** Snapshot of [state] before we transitioned to [RideState.RECONNECTING], so we can restore it on link recovery. */
    val priorStateBeforeReconnect: RideState? = null,
    /**
     * Room row id created by the most recent [io.github.liki4.peek.ride.RideRepository.exportFit].
     * SessionScreen uses this to drive the "upload to Strava" button against the row we just inserted.
     */
    val lastFitSessionId: Long? = null,
    val lastDebugLog: File? = null,
    /** Session id whose Strava upload is currently in flight, or null if none. */
    val uploadingSessionId: Long? = null,
    /**
     * Last upload error per session id, surfaced under the row. Cleared when a retry starts.
     * Persistent success is read from [io.github.liki4.peek.history.RideSessionEntity.stravaActivityId].
     */
    val uploadErrors: Map<Long, String> = emptyMap(),
    /** Mirror of [FtmsBridge.state] — drives the Settings indicator + RideScreen badge. */
    val bridge: FtmsBridge.State = FtmsBridge.State.Disabled,
    /** Mirror of [CalibrationRunner.progress] — drives CalibrationScreen UI. */
    val calibration: CalibrationRunner.Progress = CalibrationRunner.Progress.Idle,
) {

    data class ConnectedBike(val address: String, val name: String)
    data class ConnectedHr(val address: String, val name: String, val batteryPct: Int? = null)
    data class LiveMetrics(
        val rpm: Int? = null,
        val watt: Int? = null,
        val resistance: Int? = null,
        val speedKmh: Float? = null,
        val hrBpm: Int? = null,
        val distanceM: Int? = null,
        val calorieKcal: Int? = null,
        // ===== Aggregates over the current ride =====
        /** Mean of all HR samples seen in this ride. */
        val avgHrBpm: Int? = null,
        /** Peak HR sample seen in this ride. */
        val maxHrBpm: Int? = null,
        /** Mean of the last 3 watt samples (≈ 3s smoothed power). */
        val watt3s: Int? = null,
        /** Mean of all watt samples seen in this ride. */
        val avgWatt: Int? = null,
    )
}
