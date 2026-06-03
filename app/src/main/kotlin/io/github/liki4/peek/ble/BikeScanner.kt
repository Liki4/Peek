package io.github.liki4.peek.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import io.github.liki4.peek.protocol.Constants
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * BluetoothLeScanner wrapper that emits `Keep_CC_*` devices as a Flow.
 *
 * The caller must hold the BLUETOOTH_SCAN runtime permission (API 31+). We
 * filter on local name prefix because the CC_23 advertises its name in the
 * scan response, not in a service UUID we can pre-filter on.
 */
class BikeScanner(context: Context) {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** Emits each newly-discovered (or RSSI-updated) bike. Caller cancels by collecting cancellation. */
    @SuppressLint("MissingPermission")
    fun scan(): Flow<DiscoveredBike> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
            ?: throw IllegalStateException("BluetoothLeScanner unavailable (BT off or no adapter)")

        val seen = mutableMapOf<String, Int>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = device.name
                    ?: result.scanRecord?.deviceName
                    ?: return
                if (!name.startsWith(Constants.NAME_PREFIX)) return
                val prev = seen[device.address]
                if (prev == result.rssi) return  // no change
                seen[device.address] = result.rssi
                trySend(DiscoveredBike(device.address, name, result.rssi))
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE scan failed: $errorCode"))
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, callback)
        awaitClose {
            runCatching { scanner.stopScan(callback) }
        }
    }
}
