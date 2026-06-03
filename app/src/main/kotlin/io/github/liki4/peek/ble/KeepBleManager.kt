package io.github.liki4.peek.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import io.github.liki4.peek.protocol.Constants
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.data.Data

/**
 * Nordic [BleManager] subclass that owns the single bidirectional Keep
 * characteristic (`0xFF01` on service `0x00FF`).
 *
 * Handles:
 * - Service + char discovery, properties check
 * - MTU 185 negotiation
 * - Notify enable
 * - Write-without-response on outbound packets
 *
 * All incoming notification bytes are forwarded synchronously to [onIncoming]
 * — the higher-level [KeepBikeClient] is responsible for parsing.
 *
 * The lifecycle hooks (isRequiredServiceSupported / onServicesInvalidated /
 * initialize) are overridden directly on BleManager — this is the post-2.4
 * Nordic API; the older BleManagerGattCallback inner-class pattern is
 * deprecated.
 */
class KeepBleManager(
    context: Context,
    private val onIncoming: (ByteArray) -> Unit,
) : BleManager(context) {

    private var charFF01: BluetoothGattCharacteristic? = null

    override fun log(priority: Int, message: String) {
        Log.println(priority, TAG, message)
    }

    override fun getMinLogPriority(): Int = Log.INFO

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val svc = gatt.getService(Constants.KEEP_SERVICE) ?: return false
        val c = svc.getCharacteristic(Constants.KEEP_CHAR) ?: return false
        val required = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
            BluetoothGattCharacteristic.PROPERTY_NOTIFY
        if ((c.properties and required) != required) return false
        charFF01 = c
        return true
    }

    override fun onServicesInvalidated() {
        charFF01 = null
    }

    override fun initialize() {
        // Request MTU 185 (matches what the real Keep app negotiates)
        requestMtu(Constants.MTU).enqueue()

        // Wire up notification callback BEFORE enabling notifications,
        // so we don't miss the first push.
        setNotificationCallback(charFF01).with { _, data: Data ->
            data.value?.let { onIncoming(it) }
        }
        enableNotifications(charFF01).enqueue()
    }

    /** Write one raw Keep packet via write-without-response. */
    fun writeBytes(bytes: ByteArray) {
        val c = charFF01 ?: error("KeepBleManager: char not ready (service not discovered)")
        writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            .split()
            .enqueue()
    }

    companion object {
        private const val TAG = "KeepBleManager"
    }
}
