package io.github.liki4.peek.ftms

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import io.github.liki4.peek.ftms.FtmsConstants.CpOp
import io.github.liki4.peek.ftms.FtmsConstants.CpResult
import io.github.liki4.peek.ftms.FtmsConstants.MachineFeature
import io.github.liki4.peek.ftms.FtmsConstants.TargetFeature
import io.github.liki4.peek.ride.RideUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * FTMS server-side bridge: re-broadcasts the Keep CC_23 as a standard FTMS
 * Indoor Bike so Zwift / Mywhoosh / TrainerRoad can connect through Peek.
 *
 * Responsibilities:
 * - Open a [BluetoothGattServer], register the FTMS service + characteristics
 * - Start advertising the FTMS service UUID via [BluetoothLeAdvertiser]
 * - Track per-client CCCD subscriptions
 * - On every [publish] call: notify subscribers of Indoor Bike Data (0x2AD2)
 * - On Control Point writes (0x2AD9): decode via [ControlPointDecoder], drive
 *   [ErgController] and (for direct resistance writes) call back into the bike
 *   via [onSetResistance]
 *
 * Lifecycle: [start] is idempotent — calling again on an already-running bridge
 * is a no-op. [stop] tears down both server and advertiser. Construct once at
 * RideRepository scope; start/stop on Settings toggle.
 */
class FtmsBridge(
    private val context: Context,
    private val ergCtrl: ErgController,
    /** Callback when an FTMS client writes Set Target Resistance (op 0x04). */
    private val onSetResistance: suspend (Int) -> Unit,
) {

    sealed class State {
        data object Disabled : State()
        data object Unsupported : State()
        data object Starting : State()
        data object Advertising : State()
        data class ClientConnected(val deviceName: String, val address: String) : State()
        data class Failed(val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Disabled)
    val state: StateFlow<State> = _state.asStateFlow()

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var indoorBikeChar: BluetoothGattCharacteristic? = null
    private var controlPointChar: BluetoothGattCharacteristic? = null
    private var statusChar: BluetoothGattCharacteristic? = null

    /** address → set of characteristic UUIDs the device has CCCD-subscribed to. */
    private val subscriptions = ConcurrentHashMap<String, MutableSet<UUID>>()

    /** Connected client addresses (post-MTU). */
    private val connectedDevices = ConcurrentHashMap.newKeySet<String>()

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "advertising started")
            _state.value = State.Advertising
        }
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "advertise failed: $errorCode")
            _state.value = State.Failed("advertise error $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    fun start(): Result<Unit> {
        if (_state.value !is State.Disabled && _state.value !is State.Failed) {
            return Result.success(Unit)  // idempotent
        }
        val adapter = btManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            _state.value = State.Failed("bluetooth not enabled")
            return Result.failure(IllegalStateException("bluetooth not enabled"))
        }
        val adv = adapter.bluetoothLeAdvertiser
        if (adv == null) {
            _state.value = State.Unsupported
            return Result.failure(IllegalStateException("device does not support BLE advertising"))
        }
        advertiser = adv

        _state.value = State.Starting
        // 1. Open GATT server + register service
        val srv = btManager.openGattServer(context, serverCallback)
            ?: run {
                _state.value = State.Failed("openGattServer returned null")
                return Result.failure(IllegalStateException("openGattServer returned null"))
            }
        server = srv
        srv.addService(buildFtmsService())

        // 2. Start advertising the FTMS service UUID. The connectable settings
        //    + service-UUID in payload are what Zwift's scanner keys on.
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(FtmsConstants.SERVICE_FITNESS_MACHINE))
            .build()
        adv.startAdvertising(settings, data, advertiseCallback)
        return Result.success(Unit)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stopAdvertising threw: ${e.message}")
        }
        try {
            server?.close()
        } catch (e: Exception) {
            Log.w(TAG, "server.close threw: ${e.message}")
        }
        advertiser = null
        server = null
        indoorBikeChar = null
        controlPointChar = null
        statusChar = null
        subscriptions.clear()
        connectedDevices.clear()
        ergCtrl.reset()
        _state.value = State.Disabled
    }

    /**
     * Push the latest live metrics to any subscribed FTMS client. Called once
     * per RideRepository poll tick. No-op if no client is subscribed.
     */
    @SuppressLint("MissingPermission")
    fun publish(live: RideUiState.LiveMetrics) {
        val char = indoorBikeChar ?: return
        val srv = server ?: return
        val payload = IndoorBikeDataEncoder.encode(live)
        for ((addr, chars) in subscriptions) {
            if (FtmsConstants.CHAR_INDOOR_BIKE_DATA !in chars) continue
            val device = btManager.adapter.getRemoteDevice(addr) ?: continue
            notifySubscriber(srv, device, char, payload, confirm = false)
        }
    }

    // ===== GATT server callback =====
    private val serverCallback = object : BluetoothGattServerCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val addr = device.address
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevices += addr
                    subscriptions.computeIfAbsent(addr) { mutableSetOf() }
                    val name = runCatching { device.name }.getOrNull() ?: addr
                    _state.value = State.ClientConnected(name, addr)
                    Log.i(TAG, "client connected: $name ($addr)")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedDevices -= addr
                    subscriptions -= addr
                    // ERG/SIM was requested by this client — clear so the bike
                    // doesn't keep tracking a target nobody is feeding any more.
                    ergCtrl.reset()
                    _state.value = if (connectedDevices.isEmpty()) State.Advertising
                                   else _state.value
                    Log.i(TAG, "client disconnected: $addr")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = when (characteristic.uuid) {
                FtmsConstants.CHAR_FITNESS_MACHINE_FEATURE -> featureBytes()
                FtmsConstants.CHAR_SUPPORTED_RESISTANCE_LEVEL_RANGE -> resistanceRangeBytes()
                FtmsConstants.CHAR_SUPPORTED_POWER_RANGE -> powerRangeBytes()
                FtmsConstants.CHAR_TRAINING_STATUS -> byteArrayOf(0x00, 0x01) // flags=0, status=other
                else -> ByteArray(0)
            }
            val sliced = if (offset >= value.size) ByteArray(0) else value.copyOfRange(offset, value.size)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, sliced)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray,
        ) {
            if (characteristic.uuid != FtmsConstants.CHAR_FITNESS_MACHINE_CONTROL_POINT) {
                if (responseNeeded) {
                    server?.sendResponse(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null)
                }
                return
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
            handleControlPointWrite(device, value)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            // Respond with the current CCCD value for this client.
            val sub = subscriptions[device.address]
            val charUuid = descriptor.characteristic.uuid
            val isNotifying = sub?.contains(charUuid) == true
            val value = if (isNotifying) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray,
        ) {
            // CCCD write: enable/disable notify/indicate for the parent characteristic.
            val parentUuid = descriptor.characteristic.uuid
            val sub = subscriptions.computeIfAbsent(device.address) { mutableSetOf() }
            when {
                value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) -> {
                    sub += parentUuid
                }
                value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) -> {
                    sub -= parentUuid
                }
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleControlPointWrite(device: BluetoothDevice, value: ByteArray) {
        val req = ControlPointDecoder.decode(value)
        val (reqOp, resultCode) = when (req) {
            is ControlPointDecoder.CpRequest.RequestControl -> req.op to CpResult.SUCCESS
            is ControlPointDecoder.CpRequest.Reset -> {
                ergCtrl.reset(); req.op to CpResult.SUCCESS
            }
            is ControlPointDecoder.CpRequest.SetTargetResistance -> {
                val level = req.level.toInt().coerceIn(FtmsConstants.RESISTANCE_MIN, FtmsConstants.RESISTANCE_MAX)
                scope.launch { runCatching { onSetResistance(level) } }
                req.op to CpResult.SUCCESS
            }
            is ControlPointDecoder.CpRequest.SetTargetPower -> {
                ergCtrl.setErgTarget(req.watts)
                req.op to CpResult.SUCCESS
            }
            is ControlPointDecoder.CpRequest.SetIndoorBikeSim -> {
                ergCtrl.setSim(ErgController.SimParams(
                    windMps = req.windMps,
                    gradePercent = req.gradePercent,
                    crr = req.crr,
                    cw = req.cw,
                ))
                req.op to CpResult.SUCCESS
            }
            is ControlPointDecoder.CpRequest.Malformed   -> req.op to CpResult.INVALID_PARAMETER
            is ControlPointDecoder.CpRequest.Unsupported -> req.op to CpResult.OP_NOT_SUPPORTED
        }
        // Send indicate response on the same characteristic.
        val cp = controlPointChar ?: return
        val srv = server ?: return
        notifySubscriber(srv, device, cp, ControlPointDecoder.respond(reqOp, resultCode), confirm = true)
    }

    /**
     * Wrap notifyCharacteristicChanged across the API-33 deprecation seam.
     * Older devices use the deprecated mutable-value form; API 33+ uses the
     * value-as-parameter form which is type-safe and avoids the deprecation
     * warning. Both forms ultimately do the same on-wire work.
     */
    @SuppressLint("MissingPermission")
    private fun notifySubscriber(
        srv: BluetoothGattServer,
        device: BluetoothDevice,
        char: BluetoothGattCharacteristic,
        value: ByteArray,
        confirm: Boolean,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            srv.notifyCharacteristicChanged(device, char, confirm, value)
        } else {
            @Suppress("DEPRECATION")
            char.value = value
            @Suppress("DEPRECATION")
            srv.notifyCharacteristicChanged(device, char, confirm)
        }
    }

    // ===== Service / characteristic / value builders =====

    private fun buildFtmsService(): BluetoothGattService {
        val service = BluetoothGattService(
            FtmsConstants.SERVICE_FITNESS_MACHINE,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )

        // 0x2ACC Feature — read
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                FtmsConstants.CHAR_FITNESS_MACHINE_FEATURE,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            )
        )

        // 0x2AD2 Indoor Bike Data — notify (no CCCD on creation; we add one).
        indoorBikeChar = BluetoothGattCharacteristic(
            FtmsConstants.CHAR_INDOOR_BIKE_DATA,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0,
        ).also {
            it.addDescriptor(buildCccd())
            service.addCharacteristic(it)
        }

        // 0x2AD3 Training Status — read (we don't notify status changes in v1)
        statusChar = BluetoothGattCharacteristic(
            FtmsConstants.CHAR_TRAINING_STATUS,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).also { service.addCharacteristic(it) }

        // 0x2AD6 Supported Resistance Level Range — read
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                FtmsConstants.CHAR_SUPPORTED_RESISTANCE_LEVEL_RANGE,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            )
        )

        // 0x2AD8 Supported Power Range — read
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                FtmsConstants.CHAR_SUPPORTED_POWER_RANGE,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            )
        )

        // 0x2AD9 Control Point — write + indicate
        controlPointChar = BluetoothGattCharacteristic(
            FtmsConstants.CHAR_FITNESS_MACHINE_CONTROL_POINT,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        ).also {
            it.addDescriptor(buildCccd())
            service.addCharacteristic(it)
        }
        return service
    }

    private fun buildCccd(): BluetoothGattDescriptor =
        BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )

    /** 8 bytes: machine features (uint32 LE) + target features (uint32 LE). */
    private fun featureBytes(): ByteArray {
        val machine = (MachineFeature.CADENCE
            or MachineFeature.TOTAL_DISTANCE
            or MachineFeature.RESISTANCE_LEVEL
            or MachineFeature.POWER_MEASUREMENT
            or MachineFeature.HEART_RATE)
        val target = (TargetFeature.RESISTANCE
            or TargetFeature.POWER
            or TargetFeature.INDOOR_BIKE_SIMULATION)
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(machine).putInt(target).array()
    }

    /** 6 bytes: sint16 min, sint16 max, uint16 increment. */
    private fun resistanceRangeBytes(): ByteArray =
        ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(FtmsConstants.RESISTANCE_MIN.toShort())
            .putShort(FtmsConstants.RESISTANCE_MAX.toShort())
            .putShort(FtmsConstants.RESISTANCE_INCREMENT.toShort())
            .array()

    private fun powerRangeBytes(): ByteArray =
        ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(FtmsConstants.POWER_MIN_W.toShort())
            .putShort(FtmsConstants.POWER_MAX_W.toShort())
            .putShort(FtmsConstants.POWER_INCREMENT_W.toShort())
            .array()

    companion object {
        private const val TAG = "FtmsBridge"
        /** Bluetooth SIG Client Characteristic Configuration Descriptor UUID. */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
