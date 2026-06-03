package io.github.liki4.peek.ride

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Hosts the active ride so the 1 Hz TrainData poll loop and HR notify pipeline
 * keep running with the screen off. Lifecycle: started when the user connects
 * to a bike, stopped when they disconnect or finish a session.
 *
 * Type is `connectedDevice` (matches manifest) — Android 14+ requires the type
 * to be declared both in the manifest and in `startForeground(..., type)`.
 */
class RideForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val initial = buildNotification("Connected")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, initial)
        }
        // Update notification text as ride state changes.
        val repo = RideRepository.get(this)
        observerJob = scope.launch {
            repo.state
                .map { ui -> notifText(ui) }
                .distinctUntilChanged()
                .collect { text ->
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(NOTIF_ID, buildNotification(text))
                }
        }
    }

    override fun onDestroy() {
        observerJob?.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Sticky so Android will try to restart us if killed during a ride.
        return START_STICKY
    }

    private fun notifText(ui: RideUiState): String {
        val bike = ui.bike?.name ?: "Peek"
        return when (ui.state) {
            RideState.IDLE          -> "$bike — idle"
            RideState.SCANNING      -> "$bike — scanning"
            RideState.CONNECTING    -> "$bike — connecting"
            RideState.CONNECTED     -> "$bike — ready"
            RideState.WAITING_KNOB  -> "$bike — press the knob to start"
            RideState.TRAINING      -> {
                val rpm = ui.live.rpm ?: 0
                val w = ui.live.watt ?: 0
                val km = (ui.live.distanceM ?: 0) / 1000f
                "Riding · $rpm rpm · $w W · %.2f km".format(km)
            }
            RideState.PAUSED        -> "$bike — paused"
            RideState.RECONNECTING  -> "$bike — reconnecting…"
            RideState.STOPPED       -> "$bike — session ended"
            RideState.EXPORTING     -> "Exporting ride…"
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Ride session", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Live ride status while connected to a Keep bike"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Peek")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    companion object {
        const val CHANNEL_ID = "peek_ride"
        const val NOTIF_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RideForegroundService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RideForegroundService::class.java))
        }
    }
}
