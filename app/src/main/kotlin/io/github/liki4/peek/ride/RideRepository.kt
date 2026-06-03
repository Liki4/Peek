package io.github.liki4.peek.ride

import android.content.Context
import io.github.liki4.peek.ble.KeepBikeClient
import io.github.liki4.peek.ble.runHandshake
import io.github.liki4.peek.fit.FitWriter
import io.github.liki4.peek.fit.RideDataBuilder
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

    private val _state = MutableStateFlow(RideUiState())
    val state: StateFlow<RideUiState> = _state.asStateFlow()

    private var pollJob: Job? = null

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
            // fail every tick. We'll restart it once we're back if we were polling.
            val wasPolling = pollJob != null
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
                // Restore prior ride state + the polling loop if we were mid-ride.
                _state.update {
                    it.copy(
                        state = priorState,
                        priorStateBeforeReconnect = null,
                        error = null,
                    )
                }
                if (wasPolling) startPolling()
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

    private fun handlePush(frame: KeepFrame) {
        when (frame.route) {
            "106/4" -> {
                val ts = parseTrainingStatus(frame.payload)
                // User pressed knob → bike pushes TRAINING.
                if (ts.status == TrainingStatus.TRAINING.wire.toLong() &&
                    _state.value.state == RideState.WAITING_KNOB
                ) {
                    _state.update { it.copy(state = RideState.TRAINING) }
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

    fun startRide() {
        scope.launch {
            builder.reset()
            _state.update { it.copy(
                live = RideUiState.LiveMetrics(),
                secondsRecorded = 0,
                lastFit = null,
            ) }
            runCatching { bikeClient.setStatus(TrainingStatus.TRAINING) }
            _state.update { it.copy(state = RideState.WAITING_KNOB) }
            startPolling()
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
            stopPolling()
            runCatching { bikeClient.setStatus(TrainingStatus.STOPPED) }
            runCatching { bikeClient.setStatus(TrainingStatus.IDLE) }
            _state.update { it.copy(state = RideState.STOPPED) }
        }
    }

    fun setResistance(level: Int) {
        require(level in 1..18) { "resistance out of range: $level" }
        scope.launch { runCatching { bikeClient.setResistance(level) } }
    }

    // ===== 1 Hz poll loop =====

    private fun startPolling() {
        stopPolling()
        pollJob = scope.launch {
            while (isActive) {
                runCatching {
                    val frame = bikeClient.queryData()
                    if (frame != null) {
                        val td = parseTrainData(frame.payload)
                        builder.feedTrainData(td)
                        builder.tick()
                        _state.update {
                            it.copy(
                                live = it.live.copy(
                                    rpm = td.rpm?.toInt() ?: it.live.rpm,
                                    watt = td.watt?.toInt() ?: it.live.watt,
                                    resistance = td.resistance?.toInt() ?: it.live.resistance,
                                    distanceM = td.distanceM?.toInt() ?: it.live.distanceM,
                                    calorieKcal = td.calorie?.toInt() ?: it.live.calorieKcal,
                                    speedKmh = builder.lastSpeedKmh ?: it.live.speedKmh,
                                    avgHrBpm = builder.avgHr ?: it.live.avgHrBpm,
                                    maxHrBpm = builder.maxHr ?: it.live.maxHrBpm,
                                    watt3s = builder.watt3s ?: it.live.watt3s,
                                    avgWatt = builder.avgWatt ?: it.live.avgWatt,
                                ),
                                secondsRecorded = builder.secondsRecorded,
                            )
                        }
                    }
                }
                delay(1000)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    // ===== FIT export =====

    /**
     * Build a FIT file from the accumulated ride asynchronously.
     * When done, [RideUiState.lastFit] is set so the UI can share it.
     */
    fun exportFit() {
        scope.launch {
            _state.update { it.copy(state = RideState.EXPORTING) }
            try {
                val ride = builder.build()
                val outDir = File(appContext.filesDir, "ride_exports").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(Date(ride.startTimeUnixS * 1000L))
                val out = File(outDir, "peek_ride_$stamp.fit")
                FitWriter.write(ride, out)

                // Persist a row in the history DB so the session survives the
                // next ride wiping `lastFit`. Best-effort — if Room write fails
                // the FIT is still on disk and the user can retrieve it manually.
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

                _state.update { it.copy(state = RideState.STOPPED, lastFit = out) }

                // Auto-upload to Strava if credentials are configured. Runs
                // sequentially after the FIT write so we don't compete for I/O.
                if (settings.hasStravaCredentials()) {
                    uploadToStrava(out, sessionId)
                } else {
                    _state.update { it.copy(strava = RideUiState.StravaState.NotConfigured) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(state = RideState.STOPPED, error = "Export: ${e.message}") }
            }
        }
    }

    /**
     * Upload [fitFile] to Strava using the refresh-token flow. Always non-suspend
     * — invoked internally from [exportFit] and (later) from a manual retry button.
     *
     * @param sessionId optional Room row id; if non-null we update its
     *     stravaStatus + stravaActivityId on success.
     */
    fun uploadToStrava(fitFile: File, sessionId: Long?) {
        scope.launch { runUploadToStrava(fitFile, sessionId) }
    }

    private suspend fun runUploadToStrava(fitFile: File, sessionId: Long?) {
        if (!settings.hasStravaCredentials()) {
            _state.update { it.copy(strava = RideUiState.StravaState.NotConfigured) }
            return
        }
        _state.update { it.copy(strava = RideUiState.StravaState.Uploading) }
        try {
            val clientId = settings.stravaClientId.first()!!
            val clientSecret = settings.stravaClientSecret.first()!!
            val refreshToken = settings.stravaRefreshToken.first()!!
            val token = strava.refresh(clientId, clientSecret, refreshToken)
            val result = strava.uploadFit(token.accessToken, fitFile)
            sessionId?.let {
                runCatching { rideDao.setStravaStatus(it, status = 1, activityId = result.activityId) }
            }
            _state.update { it.copy(strava = RideUiState.StravaState.Success(result.activityId)) }
        } catch (e: StravaException) {
            sessionId?.let {
                runCatching { rideDao.setStravaStatus(it, status = 2, activityId = null) }
            }
            _state.update { it.copy(strava = RideUiState.StravaState.Failed(e.message ?: "unknown")) }
        } catch (e: Exception) {
            sessionId?.let {
                runCatching { rideDao.setStravaStatus(it, status = 2, activityId = null) }
            }
            _state.update { it.copy(strava = RideUiState.StravaState.Failed(e.message ?: e::class.simpleName ?: "error")) }
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
