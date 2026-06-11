package io.github.liki4.peek.hr

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import io.github.liki4.peek.ble.DiscoveredHrBelt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.android.ble.observer.ConnectionObserver
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Standard BLE HRP client, independent of the bike GATT.
 *
 * - [scan] emits HR straps advertising the standard 0x180D service UUID.
 * - [connect] establishes the GATT session and starts notification streaming.
 * - [samples] is a hot SharedFlow of decoded HR notifications.
 * - [batteryPct] reflects the most recently read battery percentage (or null).
 */
class HrBeltClient(context: Context) {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val sampleFlow = MutableSharedFlow<HrSample>(replay = 0, extraBufferCapacity = 32)
    val samples: SharedFlow<HrSample> = sampleFlow.asSharedFlow()

    private val batteryFlow = MutableStateFlow<Int?>(null)
    val batteryPct: StateFlow<Int?> = batteryFlow.asStateFlow()

    private val manager = HrBeltManager(context) { bytes ->
        parseHrMeasurement(bytes)?.let { sampleFlow.tryEmit(it) }
    }

    @Volatile var connectedAddress: String? = null
        private set

    init {
        manager.setConnectionObserver(object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) {}
            override fun onDeviceConnected(device: BluetoothDevice) {}
            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {}
            override fun onDeviceReady(device: BluetoothDevice) {
                connectedAddress = device.address
            }
            override fun onDeviceDisconnecting(device: BluetoothDevice) {}
            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                connectedAddress = null
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun scan(): Flow<DiscoveredHrBelt> = kotlinx.coroutines.flow.callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
            ?: throw IllegalStateException("BluetoothLeScanner unavailable")
        val seen = mutableMapOf<String, Int>()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                if (seen[device.address] == result.rssi) return
                seen[device.address] = result.rssi
                val name = device.name ?: result.scanRecord?.deviceName ?: "HR Belt"
                trySend(DiscoveredHrBelt(device.address, name, result.rssi))
            }
            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("HR scan failed: $errorCode"))
            }
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(HrConstants.HR_SERVICE))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(listOf(filter), settings, cb)
        awaitClose { runCatching { scanner.stopScan(cb) } }
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(address: String, timeoutMs: Int = 20_000) {
        val device = adapter?.getRemoteDevice(address)
            ?: throw IllegalStateException("Bluetooth adapter unavailable")
        suspendCancellableCoroutine<Unit> { cont ->
            manager.connect(device)
                .useAutoConnect(false)
                .timeout(timeoutMs.toLong())
                .retry(2, 200)
                .done { cont.resume(Unit) }
                .fail { _, status -> cont.resumeWithException(IllegalStateException("HR connect failed: $status")) }
                .enqueue()
        }
        // Attempt one battery read (best-effort, doesn't fail connect if missing).
        runCatching { readBatteryOnce() }
    }

    suspend fun disconnect() {
        if (connectedAddress == null) return
        suspendCancellableCoroutine<Unit> { cont ->
            manager.disconnect()
                .done { cont.resume(Unit) }
                .fail { _, _ -> cont.resume(Unit) }
                .enqueue()
        }
    }

    private suspend fun readBatteryOnce() {
        val def = CompletableDeferred<Unit>()
        manager.readBatteryLevel(
            onValue = { batteryFlow.value = it },
            done = { def.complete(Unit) },
        )
        withTimeoutOrNull(3000) { def.await() }
    }
}
