package io.github.liki4.peek.ride

import android.content.Context
import io.github.liki4.peek.ble.KeepBikeClient
import io.github.liki4.peek.ble.runHandshake
import io.github.liki4.peek.fit.FitWriter
import io.github.liki4.peek.fit.RideDataBuilder
import io.github.liki4.peek.ftms.CalibrationRunner
import io.github.liki4.peek.ftms.ErgController
import io.github.liki4.peek.ftms.FtmsBridge
import io.github.liki4.peek.ftms.PowerModel
import io.github.liki4.peek.history.RideDatabase
import io.github.liki4.peek.history.RideSessionEntity
import io.github.liki4.peek.hr.HrBeltClient
import io.github.liki4.peek.strava.StravaClient
import io.github.liki4.peek.strava.StravaException
import io.github.liki4.peek.protocol.KeepFrame
import io.github.liki4.peek.protocol.TrainingStatus
import io.github.liki4.peek.protocol.parseTrainAttribute
import io.github.liki4.peek.protocol.parseTrainData
import io.github.liki4.peek.protocol.parseTrainingStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Process-wide singleton that owns the BLE bike client, HR strap client, and
 * the per-second ride accumulator. Exposes a single [state] StateFlow that
 * the UI + foreground service both observe.
 *
 * Lifecycle: created lazily on first [get]; never destroyed. Reconstructing
 * BLE clients across screen rotations would drop the GATT session.
 */
class RideRepository private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settings = Settings(appContext)
    val rideDao = RideDatabase.get(appContext).rideSessionDao()
    private val bikeClient = KeepBikeClient(appContext)
    private val hrClient = HrBeltClient(appContext)
    private val builder = RideDataBuilder()
    private val strava = StravaClient()
    private val debugLogger = RideLogger(appContext)

    private val _state = MutableStateFlow(RideUiState())
    val state: StateFlow<RideUiState> = _state.asStateFlow()

    /**
     * Per-second HR series snapshot, published after each tick. 0 means
     * "no sample at this second" — chart consumers should skip zeros.
     * Held separately from [state] so list-typed UI subscribers don't churn
     * the whole RideUiState whenever a single value lands.
     */
    private val _hrSeries = MutableStateFlow(IntArray(0))
    val hrSeries: StateFlow<IntArray> = _hrSeries.asStateFlow()

    private var pollJob: Job? = null

    // The CC_23 runs a ~4-5 s countdown after the knob is pressed before it
    // starts logging.  The original Keep app shows a 3-2-1-GO overlay for the
    // same duration.  We mirror that with a fixed 4-second wall-clock delay
    // after receiving the TRAINING push — no dependency on pedaling.
    // Displayed time still comes from the bike's own `durationS` (see poll
    // loop), so sub-second start misalignment doesn't accumulate.
    @Volatile private var countdownUntilNanos = 0L

    // ===== FTMS bridge state =====
    //
    // PowerModel is loaded lazily from settings.powerModelBlob on first ride
    // tick (we don't block construction on DataStore I/O). When the user runs
    // the calibration wizard or finishes a ride that fed new samples,
    // [persistPowerModel] writes the blob back.
    private val powerModel = PowerModel()
    @Volatile private var powerModelLoaded = false
    @Volatile private var lastCommandedLevel: Int? = null
    private val ergCtrl = ErgController()
    /**
     * Raw 1Hz (rpm, watt) samples for the calibration wizard. We always emit
     * on every TrainData tick during a connected session; the runner ignores
     * out-of-window samples.
     */
    private val _calibrationSamples = MutableSharedFlow<CalibrationRunner.CalibrationSample>(
        replay = 0, extraBufferCapacity = 8,
    )
    val calibrationSamples: SharedFlow<CalibrationRunner.CalibrationSample> = _calibrationSamples.asSharedFlow()
    val ftmsBridge = FtmsBridge(
        context = appContext,
        ergCtrl = ergCtrl,
        onSetResistance = { level -> runCatching { bikeClient.setResistance(level) } },
    )

    init {
        scope.launch {
            bikeClient.pushes.collect { handlePush(it) }
        }
        scope.launch {
            bikeClient.disconnects.collect { reason -> handleDisconnect(reason) }
        }
        scope.launch {
            hrClient.samples.collect { sample ->
                builder.feedHr(sample.heartRate)
                _state.update { it.copy(live = it.live.copy(hrBpm = sample.heartRate)) }
            }
        }
        scope.launch {
            hrClient.batteryPct.collect { pct ->
                _state.update { ui ->
                    val belt = ui.hrBelt ?: return@update ui
                    ui.copy(hrBelt = belt.copy(batteryPct = pct))
                }
            }
        }
        // Mirror FtmsBridge state into RideUiState so UI can show the toggle status.
        scope.launch {
            ftmsBridge.state.collect { s -> _state.update { it.copy(bridge = s) } }
        }
        // Observe the settings toggle: start/stop the bridge accordingly. The
        // toggle is the only user surface for enabling FTMS; flipping it OFF
        // mid-session immediately tears the bridge down.
        scope.launch {
            settings.bridgeEnabled.collect { enabled ->
                if (enabled) ftmsBridge.start()
                else ftmsBridge.stop()
            }
        }
    }

    // ===== PowerModel persistence =====

    /** Lazy load on first need (rather than blocking RideRepository construction on DataStore). */
    private suspend fun ensurePowerModelLoaded() {
        if (powerModelLoaded) return
        val blob = settings.powerModelBlob.first()
        if (!blob.isNullOrBlank()) {
            powerModel.replaceWith(PowerModel.fromBlob(blob))
        }
        powerModelLoaded = true
    }

    private suspend fun persistPowerModel() {
        runCatching { settings.setPowerModelBlob(powerModel.toBlob()) }
    }

    // ===== Auto-reconnect on link loss =====

    /**
     * Nordic ConnectionObserver reason codes. We only auto-reconnect on
     * involuntary drops (link loss, timeout, unknown) — not on user-initiated
     * disconnects (we already cleared `bike` ourselves) or peer-terminated.
     */
    private fun shouldAutoReconnect(reason: Int): Boolean {
        // 1 = TERMINATE_LOCAL_HOST, 2 = TERMINATE_PEER_USER, 5 = CANCELLED → don't retry.
        // 3 = LINK_LOSS, 6 = TIMEOUT, -1 = UNKNOWN → retry.
        return reason !in setOf(1, 2, 5)
    }

    private fun handleDisconnect(reason: Int) {
        val cur = _state.value
        // If we don't have a bike record any more, the user clicked Disconnect —
        // disconnectBike() already cleared bike + state. Nothing to do.
        val bike = cur.bike ?: return
        if (cur.state == RideState.RECONNECTING) return  // already in flight
        if (!shouldAutoReconnect(reason)) return

        val priorState = cur.state
        _state.update {
            it.copy(state = RideState.RECONNECTING, priorStateBeforeReconnect = priorState)
        }
        scope.launch {
            // Pause the poll loop while we're disconnected — queryData() would
            // fail every tick. We always restart it once we're back; polling is
            // unconditional whenever the bike is connected.
            stopPolling()
            try {
                // useAutoConnect = true → OS scanner re-attaches whenever the
                // bike re-advertises. No timeout (well, a long one), but that's
                // fine — we want it to keep trying.
                bikeClient.connect(bike.address, timeoutMs = 60_000, useAutoConnect = true)
                // Re-run the auth handshake; bike doesn't preserve session across drops.
                val userId = settings.userId.first() ?: "deadbeef0000000000000000"
                val deviceId = settings.ensureDeviceId()
                val weight = settings.weightKg.first()
                runHandshake(bikeClient, userId, deviceId, weight).getOrThrow()
                _state.update {
                    it.copy(
                        state = priorState,
                        priorStateBeforeReconnect = null,
                        error = null,
                    )
                }
                startPolling()
            } catch (e: Exception) {
                // Reconnect itself failed — drop back to IDLE so user can manually rescan.
                runCatching { bikeClient.disconnect() }
                _state.update {
                    it.copy(
                        state = RideState.IDLE,
                        bike = null,
                        deviceInfo = null,
                        priorStateBeforeReconnect = null,
                        error = "Reconnect failed: ${e.message}",
                    )
                }
            }
        }
    }

    // ===== Push routing =====

    private suspend fun handlePush(frame: KeepFrame) {
        when (frame.route) {
            "106/4" -> {
                val ts = parseTrainingStatus(frame.payload)
                // changedByDevice=1 → the user pressed the physical knob.
                // This is the bike telling us "I'm training now" regardless of
                // whether the app sent a start command first. Handle it from any
                // pre-ride state:
                //   WAITING_KNOB → app already set up builder; just flip state.
                //   CONNECTED    → user pressed knob directly; set up recording.
                if (ts.status == TrainingStatus.TRAINING.wire.toLong() &&
                    ts.changedByDevice == 1L &&
                    _state.value.state != RideState.TRAINING
                ) {
                    when (_state.value.state) {
                        RideState.WAITING_KNOB -> {
                            _state.update { it.copy(state = RideState.TRAINING) }
                        }
                        RideState.CONNECTED -> {
                            beginRecording()
                            _state.update { it.copy(state = RideState.TRAINING) }
                        }
                        RideState.PAUSED -> {
                            _state.update { it.copy(state = RideState.TRAINING) }
                        }
                        else -> {} // RECONNECTING / STOPPED — irrelevant
                    }
                }
                // Bike pushes PAUSED when the user presses the knob during
                // training — honor it the same way we handle the UI pause button.
                if (ts.status == TrainingStatus.PAUSED.wire.toLong() &&
                    ts.changedByDevice == 1L &&
                    _state.value.state == RideState.TRAINING
                ) {
                    _state.update { it.copy(state = RideState.PAUSED) }
                }
                // Bike pushes STOPPED when the user long-presses the knob to end
                // the session — run the full stop flow (persist power model,
                // close debug log, auto-export FIT). The user lands on
                // SessionScreen with the "Upload to Strava" button.
                if (ts.status == TrainingStatus.STOPPED.wire.toLong() &&
                    ts.changedByDevice == 1L &&
                    _state.value.state in setOf(RideState.TRAINING, RideState.PAUSED)
                ) {
                    endRecording()
                }
            }
            "106/6" -> {
                // TrainAttribute is event-driven (resistance change only). rpm
                // here is a snapshot from that moment — using it as a live rpm
                // value freezes the displayed rpm. Pull only the resistance,
                // which IS the authoritative state on every push. rpm is
                // refreshed by the 1 Hz TrainData poll loop.
                val ta = parseTrainAttribute(frame.payload)
                _state.update {
                    it.copy(
                        live = it.live.copy(
                            resistance = ta.resistance?.toInt() ?: it.live.resistance,
                        )
                    )
                }
            }
        }
    }

    // ===== Connection / handshake =====
    //
    // All trigger methods below are non-suspend by design — they launch work on
    // the long-lived repository [scope], not on the caller's composition scope.
    // Compose recompositions during a state transition (e.g. SCANNING → CONNECTING
    // switches screens) would otherwise cancel a UI-launched suspend mid-handshake
    // with "The coroutine scope left the composition".

    fun connectBike(address: String, name: String) {
        scope.launch {
            _state.update { it.copy(state = RideState.CONNECTING, error = null) }
            try {
                bikeClient.connect(address)
                val userId = settings.userId.first() ?: "deadbeef0000000000000000"
                val deviceId = settings.ensureDeviceId()
                val weight = settings.weightKg.first()
                val info = runHandshake(bikeClient, userId, deviceId, weight).getOrThrow()
                settings.setLastBikeAddr(address)
                _state.update {
                    it.copy(
                        state = RideState.CONNECTED,
                        bike = RideUiState.ConnectedBike(address, name),
                        deviceInfo = info,
                    )
                }
                // Begin polling as soon as we're connected — calibration wizard,
                // pre-ride live tile, and FTMS bridge all need 1Hz TrainData
                // before the user presses Start. Recording (builder.tick) is
                // gated separately on RideState.TRAINING in startPolling().
                startPolling()
            } catch (e: Exception) {
                _state.update { it.copy(state = RideState.IDLE, error = "Connect: ${e.message}") }
                runCatching { bikeClient.disconnect() }
            }
        }
    }

    fun disconnectBike() {
        scope.launch {
            stopPolling()
            runCatching { bikeClient.disconnect() }
            _state.update { it.copy(state = RideState.IDLE, bike = null, deviceInfo = null) }
        }
    }

    fun connectHr(address: String, name: String) {
        scope.launch {
            try {
                hrClient.connect(address)
                settings.setLastHrAddr(address)
                _state.update { it.copy(hrBelt = RideUiState.ConnectedHr(address, name)) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "HR: ${e.message}") }
            }
        }
    }

    fun disconnectHr() {
        scope.launch {
            runCatching { hrClient.disconnect() }
            _state.update { it.copy(hrBelt = null) }
        }
    }

    // ===== Ride control =====

    /** Shared setup for both app-initiated and bike-initiated ride starts. */
    private suspend fun beginRecording() {
        builder.reset()
        // 4-second wall-clock countdown — mirrors the Keep app's 3-2-1-GO
        // overlay. The bike has its own internal countdown of ~4-5 s; after
        // both expire, the bike's `durationS` field is the authoritative
        // elapsed time (used for UI display in the poll loop).
        countdownUntilNanos = System.nanoTime() + 4_000_000_000L
        _hrSeries.value = IntArray(0)
        _state.update { it.copy(
            live = RideUiState.LiveMetrics(),
            secondsRecorded = 0,
            lastFit = null,
            lastFitSessionId = null,
            lastDebugLog = null,
        ) }
        if (settings.debugLogEnabled.first()) {
            debugLogger.open(System.currentTimeMillis() / 1000L)
        }
    }

    fun startRide() {
        scope.launch {
            beginRecording()
            runCatching { bikeClient.setStatus(TrainingStatus.TRAINING) }
            _state.update { it.copy(state = RideState.WAITING_KNOB) }
        }
    }

    fun pauseRide() {
        scope.launch {
            runCatching { bikeClient.setStatus(TrainingStatus.PAUSED) }
            _state.update { it.copy(state = RideState.PAUSED) }
        }
    }

    fun stopRide() {
        scope.launch {
            runCatching { bikeClient.setStatus(TrainingStatus.STOPPED) }
            runCatching { bikeClient.setStatus(TrainingStatus.IDLE) }
            endRecording()
        }
    }

    /**
     * Shared end-of-ride logic: persist power model, close debug log,
     * auto-export FIT → Room insert, and transition to STOPPED state.
     * Called both when the app stops the ride and when the bike long-presses
     * the knob to end the session independently.
     */
    private suspend fun endRecording() {
        persistPowerModel()
        debugLogger.close()
        autoExportFit()
        _state.update {
            it.copy(
                state = RideState.STOPPED,
                lastDebugLog = debugLogger.lastLogFile,
            )
        }
    }

    /**
     * Run the FTMS calibration wizard. Suspends until done (or aborts on
     * exception). Progress is mirrored into [RideUiState.calibration] for the
     * UI to render. Persists PowerModel + calibration timestamp on success.
     *
     * Caller is responsible for ensuring the bike is connected and the user
     * isn't mid-ride. Wizard explicitly drives `setResistance` on the bike.
     */
    suspend fun runCalibration() {
        ensurePowerModelLoaded()
        val runner = CalibrationRunner(
            model = powerModel,
            setLevel = { L -> runCatching { bikeClient.setResistance(L) } },
            samples = calibrationSamples,
        )
        val mirror = scope.launch {
            runner.progress.collect { p -> _state.update { it.copy(calibration = p) } }
        }
        try {
            runner.run()
            persistPowerModel()
            runCatching { settings.setCalibratedAt(System.currentTimeMillis()) }
        } finally {
            mirror.cancel()
            // Leave the terminal Done/Failed state in UI for a beat so the user
            // sees the outcome; consumer can call resetCalibrationUi() later.
        }
    }

    fun resetCalibrationUi() {
        _state.update { it.copy(calibration = CalibrationRunner.Progress.Idle) }
    }

    fun setResistance(level: Int) {
        require(level in 1..18) { "resistance out of range: $level" }
        scope.launch { runCatching { bikeClient.setResistance(level) } }
    }

    // ===== 1 Hz poll loop =====

    private fun startPolling() {
        stopPolling()
        pollJob = scope.launch {
            ensurePowerModelLoaded()
            // Wall-clock anchored ticker: each iteration sleeps until the next
            // 1-second boundary from startNanos, so queryData latency doesn't
            // accumulate drift over a long ride.
            val startNanos = System.nanoTime()
            var iteration = 0L
            while (isActive) {
                runCatching {
                    val frame = bikeClient.queryData()
                    if (frame != null) {
                        val td = parseTrainData(frame.payload)
                        val recording = _state.value.state == RideState.TRAINING

                        // Always-update live tile so the UI feels alive even
                        // outside TRAINING (CONNECTED, WAITING_KNOB, PAUSED,
                        // STOPPED). Distance is bike-reported cumulative so it
                        // makes sense regardless of recording state.
                        _state.update {
                            it.copy(
                                live = it.live.copy(
                                    rpm = td.rpm?.toInt() ?: it.live.rpm,
                                    watt = td.watt?.toInt() ?: it.live.watt,
                                    resistance = td.resistance?.toInt() ?: it.live.resistance,
                                    distanceM = td.distanceM?.toInt() ?: it.live.distanceM,
                                    calorieKcal = td.calorie?.toInt() ?: it.live.calorieKcal,
                                ),
                            )
                        }

                        // Recording (per-second arrays + aggregates + secondsRecorded)
                        // is gated on TRAINING. WAITING_KNOB / PAUSED don't append.
                        // A fixed 4-second countdown runs first so the app's
                        // first tick lands close to the bike's own countdown end.
                        if (recording) {
                            val inCountdown = System.nanoTime() < countdownUntilNanos
                            if (!inCountdown) {
                                builder.feedTrainData(td)
                                builder.tick()
                                _hrSeries.value = builder.hrSnapshot()
                                _state.update {
                                    it.copy(
                                        live = it.live.copy(
                                            speedKmh = builder.lastSpeedKmh ?: it.live.speedKmh,
                                            avgHrBpm = builder.avgHr ?: it.live.avgHrBpm,
                                            maxHrBpm = builder.maxHr ?: it.live.maxHrBpm,
                                            watt3s = builder.watt3s ?: it.live.watt3s,
                                            avgWatt = builder.avgWatt ?: it.live.avgWatt,
                                        ),
                                        // Use the bike's own durationS as the
                                        // displayed time so the app's clock stays
                                        // in sync with the bike's display even
                                        // if our poll loop occasionally skips a
                                        // tick (BLE congestion, reconnect, etc.).
                                        secondsRecorded = td.durationS?.toInt()
                                            ?: builder.secondsRecorded,
                                    )
                                }
                            }
                        }

                        // ===== FTMS bridge tick (always-on) =====
                        val live = _state.value.live
                        val L = live.resistance
                        val rpm = live.rpm ?: 0
                        val watt = live.watt ?: 0
                        if (recording && L != null && L in 1..PowerModel.NUM_LEVELS &&
                            rpm > CalibrationRunner.MIN_RPM_FOR_FIT && watt > 0) {
                            powerModel.feed(L, rpm, watt)
                        }

                        ftmsBridge.publish(live)

                        val levelBefore = lastCommandedLevel
                        runErgIfActive(live)

                        _calibrationSamples.tryEmit(
                            CalibrationRunner.CalibrationSample(rpm = rpm, watt = watt),
                        )

                        // ===== Debug logger (only during recording) =====
                        if (recording && debugLogger.isOpen) {
                            val sim = ergCtrl.currentSim
                            val bridgeState = ftmsBridge.state.value
                            debugLogger.writeTick(RideLogger.TickContext(
                                unixS = System.currentTimeMillis() / 1000L,
                                rideSecond = builder.secondsRecorded,
                                td = td,
                                hrBpm = builder.lastHrBpm,
                                speedKmh = builder.lastSpeedKmh,
                                speedModel = if (builder.debugMPerRpmSec != null) "rpm" else "delta",
                                mPerRpmSec = builder.debugMPerRpmSec,
                                sumRpm = builder.debugSumRpm,
                                ergMode = when {
                                    ergCtrl.currentTarget != null -> "erg"
                                    sim != null -> "sim"
                                    else -> null
                                },
                                ergGrade = sim?.gradePercent,
                                ergTargetW = ergCtrl.currentTarget,
                                ergPickedL = lastCommandedLevel,
                                ergFallback = sim != null && !powerModel.isReady(),
                                bridgeState = bridgeState::class.simpleName ?: "Unknown",
                                bridgeClient = (bridgeState as? FtmsBridge.State.ClientConnected)?.deviceName,
                                cmdResistance = if (lastCommandedLevel != levelBefore) lastCommandedLevel else null,
                                cmdDedup = lastCommandedLevel == levelBefore && ergCtrl.isActive(),
                            ))
                        }
                    }
                }
                iteration++
                val nextNanos = startNanos + iteration * 1_000_000_000L
                val sleepMs = (nextNanos - System.nanoTime()) / 1_000_000
                if (sleepMs > 0) delay(sleepMs) else delay(1)
            }
        }
    }

    /**
     * If [ergCtrl] is active, pick the resistance level and write it to the
     * bike — skipping if it matches the last level we already commanded.
     *
     * SIM mode works even without a calibrated [powerModel]: falls back to a
     * direct grade→level linear mapping so Zwift route-riding is usable from
     * the first ride. ERG mode still requires calibration.
     */
    private suspend fun runErgIfActive(live: RideUiState.LiveMetrics) {
        if (!ergCtrl.isActive()) return

        if (ergCtrl.currentSim != null && !powerModel.isReady()) {
            val level = ErgController.simFallbackLevel(ergCtrl.currentSim!!)
            if (level == lastCommandedLevel) return
            lastCommandedLevel = level
            runCatching { bikeClient.setResistance(level) }
            return
        }

        if (!powerModel.isReady()) return  // ERG needs calibration
        val rpm = live.rpm ?: return
        if (rpm < CalibrationRunner.MIN_RPM_FOR_FIT) return
        val weight = settings.weightKg.first()
        val speedMps = (live.speedKmh ?: 0f) / 3.6f
        val targetW = ergCtrl.effectiveTargetWatt(speedMps, weight) ?: return
        val pickedL = powerModel.pickLevel(targetW, rpm) ?: return
        if (pickedL == lastCommandedLevel) return
        lastCommandedLevel = pickedL
        runCatching { bikeClient.setResistance(pickedL) }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    // ===== FIT export =====

    /**
     * Auto-export FIT + persist to history DB. Called from [stopRide].
     * Skips silently if no data was recorded (secondsRecorded == 0).
     */
    private suspend fun autoExportFit() {
        if (builder.secondsRecorded == 0) return
        try {
            val ride = builder.build()
            val outDir = File(appContext.filesDir, "ride_exports").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(Date(ride.startTimeUnixS * 1000L))
            val out = File(outDir, "peek_ride_$stamp.fit")
            FitWriter.write(ride, out)

            val sessionId = runCatching {
                rideDao.insert(
                    RideSessionEntity(
                        startTimeUnixS = ride.startTimeUnixS,
                        durationS = ride.durationS,
                        distanceM = ride.totalDistanceM,
                        calorie = ride.totalCalorie,
                        avgWatt = builder.avgWatt,
                        avgHrBpm = builder.avgHr,
                        maxHrBpm = builder.maxHr,
                        fitFilePath = out.absolutePath,
                    )
                )
            }.getOrNull()

            _state.update {
                it.copy(lastFit = out, lastFitSessionId = sessionId)
            }
        } catch (e: Exception) {
            _state.update { it.copy(error = "Export: ${e.message}") }
        }
    }

    /**
     * Upload the FIT file backing [sessionId] (looked up in Room) to Strava using
     * the refresh-token flow. Non-suspend; runs on the long-lived repo scope.
     *
     * UI surfaces progress via [RideUiState.uploadingSessionId] (in-flight) and
     * [RideUiState.uploadErrors] (per-session error message). Persistent success
     * lives in Room ([RideSessionEntity.stravaActivityId]).
     */
    fun uploadToStrava(sessionId: Long) {
        scope.launch { runUploadToStrava(sessionId) }
    }

    private suspend fun runUploadToStrava(sessionId: Long) {
        val row = rideDao.byId(sessionId)
        if (row == null) {
            _state.update { it.copy(uploadErrors = it.uploadErrors + (sessionId to "找不到这次骑行记录")) }
            return
        }
        val fitFile = File(row.fitFilePath)
        if (!fitFile.exists()) {
            _state.update { it.copy(uploadErrors = it.uploadErrors + (sessionId to "FIT 文件已被删除：${fitFile.name}")) }
            return
        }
        if (!settings.hasStravaCredentials()) {
            _state.update {
                it.copy(uploadErrors = it.uploadErrors + (sessionId to "请先在设置里填入 Strava client_id/secret/refresh_token"))
            }
            return
        }
        _state.update {
            it.copy(
                uploadingSessionId = sessionId,
                uploadErrors = it.uploadErrors - sessionId,
            )
        }
        try {
            val clientId = settings.stravaClientId.first()!!
            val clientSecret = settings.stravaClientSecret.first()!!
            val refreshToken = settings.stravaRefreshToken.first()!!
            val token = strava.refresh(clientId, clientSecret, refreshToken)
            val result = strava.uploadFit(token.accessToken, fitFile)
            runCatching { rideDao.setStravaStatus(sessionId, status = 1, activityId = result.activityId) }
            _state.update { it.copy(uploadingSessionId = null) }
        } catch (e: Exception) {
            val msg = (e as? StravaException)?.message
                ?: e.message
                ?: e::class.simpleName
                ?: "error"
            runCatching { rideDao.setStravaStatus(sessionId, status = 2, activityId = null) }
            _state.update {
                it.copy(
                    uploadingSessionId = null,
                    uploadErrors = it.uploadErrors + (sessionId to msg),
                )
            }
        }
    }

    fun isRideActive(): Boolean = _state.value.state in
        setOf(RideState.WAITING_KNOB, RideState.TRAINING, RideState.PAUSED)

    companion object {
        @Volatile private var INSTANCE: RideRepository? = null
        fun get(context: Context): RideRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: RideRepository(context.applicationContext).also { INSTANCE = it }
            }
    }
}
