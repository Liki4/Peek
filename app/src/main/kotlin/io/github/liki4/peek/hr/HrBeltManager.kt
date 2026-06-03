package io.github.liki4.peek.hr

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.data.Data

/**
 * Standard BLE Heart Rate Profile client.
 *
 * Service `0x180D`, notify char `0x2A37` (Heart Rate Measurement).
 * Optionally reads Battery Level `0x2A19` if available on the strap.
 */
@Suppress("DEPRECATION")  // BleManagerGattCallback deprecated in newer Nordic; safe on 2.7.x.
class HrBeltManager(
    context: Context,
    private val onIncoming: (ByteArray) -> Unit,
) : BleManager(context) {

    private var hrChar: BluetoothGattCharacteristic? = null
    private var batteryChar: BluetoothGattCharacteristic? = null

    override fun log(priority: Int, message: String) {
        Log.println(priority, TAG, message)
    }

    override fun getMinLogPriority(): Int = Log.INFO

    override fun getGattCallback(): BleManagerGattCallback = GattCallback()

    private inner class GattCallback : BleManagerGattCallback() {
        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val svc = gatt.getService(HrConstants.HR_SERVICE) ?: return false
            hrChar = svc.getCharacteristic(HrConstants.HR_MEASUREMENT) ?: return false
            if ((hrChar!!.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) return false

            // Battery service is optional.
            batteryChar = gatt.getService(HrConstants.BATTERY_SERVICE)
                ?.getCharacteristic(HrConstants.BATTERY_LEVEL)
            return true
        }

        override fun onServicesInvalidated() {
            hrChar = null
            batteryChar = null
        }

        override fun initialize() {
            setNotificationCallback(hrChar).with { _, data: Data ->
                data.value?.let { onIncoming(it) }
            }
            enableNotifications(hrChar).enqueue()
        }
    }

    /**
     * Best-effort one-shot battery percentage read. Calls [onValue] with 0-100
     * on success, or [done] in either case. No-op (calls [done]) if the strap
     * doesn't expose the battery service.
     */
    fun readBatteryLevel(onValue: (Int) -> Unit, done: () -> Unit) {
        val c = batteryChar
        if (c == null) { done(); return }
        readCharacteristic(c)
            .with { _, data ->
                val v = data.value?.takeIf { it.isNotEmpty() }?.get(0)?.toInt()?.and(0xFF)
                if (v != null) onValue(v)
            }
            .done { done() }
            .fail { _, _ -> done() }
            .enqueue()
    }

    companion object {
        private const val TAG = "HrBeltManager"
    }
}
