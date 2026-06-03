package io.github.liki4.peek.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import io.github.liki4.peek.protocol.BodyBuilder
import io.github.liki4.peek.protocol.DeviceCommand
import io.github.liki4.peek.protocol.KeepFrame
import io.github.liki4.peek.protocol.KeepPacket
import io.github.liki4.peek.protocol.KirinMethod
import io.github.liki4.peek.protocol.KirinOpcode
import io.github.liki4.peek.protocol.TrainingStatus
import io.github.liki4.peek.protocol.buildCustomPayload
import io.github.liki4.peek.protocol.buildDeviceCommand
import io.github.liki4.peek.protocol.buildTrainAttributeSetResistance
import io.github.liki4.peek.protocol.buildTrainLogRequest
import io.github.liki4.peek.protocol.buildTrainingStatus
import io.github.liki4.peek.protocol.buildUserInfo
import io.github.liki4.peek.protocol.parsePacket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.android.ble.ConnectRequest
import no.nordicsemi.android.ble.observer.ConnectionObserver
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * High-level coroutine API mirroring `keep_protocol.py:KeepBikeClient`.
 *
 * - Owns one [KeepBleManager] + one [KeepPacket] builder.
 * - Tracks pending requests by `sub_seq`; matching responses resolve the
 *   corresponding [CompletableDeferred].
 * - Unsolicited bike pushes (OBSERVE 106/4 status, 106/6 TrainAttribute) are
 *   fanned out via [pushes] as a SharedFlow.
 *
 * Concurrency: a single GATT pipe means only one outbound write is in flight
 * at a time on the wire. Nordic's BleManager serializes writes via its own
 * queue, so callers can `send*` from any coroutine; correctness is preserved
 * by the sub_seq-keyed pending map.
 */
class KeepBikeClient(context: Context) {

    private val pushFlow = MutableSharedFlow<KeepFrame>(replay = 0, extraBufferCapacity = 64)
    val pushes: SharedFlow<KeepFrame> = pushFlow.asSharedFlow()

    /**
     * Disconnect events with their Nordic [ConnectionObserver] reason codes.
     * Subscribers (e.g. RideRepository) decide whether to reconnect based on
     * the reason — link loss/timeout = yes, user-initiated/peer-terminate = no.
     */
    private val disconnectFlow = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 8)
    val disconnects: SharedFlow<Int> = disconnectFlow.asSharedFlow()

    private val pending = ConcurrentHashMap<Int, CompletableDeferred<KeepFrame>>()
    private val builder = KeepPacket()
    private val manager = KeepBleManager(context) { bytes -> onIncoming(bytes) }

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    @Volatile var connectedAddress: String? = null
        private set

    @Volatile var lastError: Throwable? = null
        private set

    init {
        manager.setConnectionObserver(object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) {}
            override fun onDeviceConnected(device: BluetoothDevice) {}
            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
                failAllPending(IllegalStateException("connect failed: $reason"))
            }
            override fun onDeviceReady(device: BluetoothDevice) {
                connectedAddress = device.address
            }
            override fun onDeviceDisconnecting(device: BluetoothDevice) {}
            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                connectedAddress = null
                failAllPending(IllegalStateException("disconnected: $reason"))
                disconnectFlow.tryEmit(reason)
            }
        })
    }

    // ===== Connection lifecycle =====

    val isConnected: Boolean get() = connectedAddress != null

    /**
     * @param useAutoConnect first pair = false (immediate connect attempt with
     *     short timeout — user is staring at the screen waiting). Reconnects
     *     after link loss = true (Android queues the connect on its background
     *     scanner; happens whenever the device wakes up, but the first
     *     connection latency becomes unbounded).
     */
    suspend fun connect(
        address: String,
        timeoutMs: Int = 20_000,
        useAutoConnect: Boolean = false,
    ) {
        val device = btManager.adapter.getRemoteDevice(address)
        suspendCancellableCoroutine<Unit> { cont ->
            val req: ConnectRequest = manager.connect(device)
                .useAutoConnect(useAutoConnect)
                .timeout(timeoutMs.toLong())
                .retry(2, 200)
            req.done { cont.resume(Unit) }
                .fail { _, status -> cont.resumeWithException(IllegalStateException("connect: $status")) }
                .enqueue()
        }
    }

    suspend fun disconnect() {
        if (!isConnected) return
        suspendCancellableCoroutine<Unit> { cont ->
            manager.disconnect()
                .done { cont.resume(Unit) }
                .fail { _, _ -> cont.resume(Unit) }  // already disconnected is OK
                .enqueue()
        }
    }

    // ===== Notification routing =====

    private fun onIncoming(bytes: ByteArray) {
        val frame = parsePacket(bytes)
        // Resolve any pending request matching this sub_seq.
        pending.remove(frame.subSeq)?.takeIf { !it.isCompleted }?.complete(frame)
        // Always fan out to general subscribers (this is how UI sees OBSERVE pushes).
        pushFlow.tryEmit(frame)
    }

    private fun failAllPending(t: Throwable) {
        lastError = t
        val snapshot = pending.toMap()
        pending.clear()
        for ((_, def) in snapshot) {
            if (!def.isCompleted) def.completeExceptionally(t)
        }
    }

    // ===== Core send =====

    /**
     * Build, send, and (optionally) await a response correlated by sub_seq.
     *
     * Returns `null` on timeout or if `waitResponse=false`.
     */
    suspend fun sendRequest(
        method: Int,
        opcode: Int,
        body: ByteArray,
        waitResponse: Boolean = true,
        timeoutMs: Long = 3000,
    ): KeepFrame? {
        check(isConnected) { "not connected" }
        val (pkt, subSeq) = builder.build(method = method, opcode = opcode, body = body)
        val def = if (waitResponse) {
            val d = CompletableDeferred<KeepFrame>()
            pending[subSeq] = d
            d
        } else null

        manager.writeBytes(pkt)

        if (def == null) return null
        return try {
            withTimeoutOrNull(timeoutMs) { def.await() }
        } finally {
            if (def.isActive) {
                pending.remove(subSeq)
            }
        }
    }

    // ===== High-level convenience methods (mirror keep_protocol.py) =====

    suspend fun identity(phoneId: String = "deadbeef00abcdef") = sendRequest(
        method = KirinMethod.GET.v,
        opcode = KirinOpcode.IDENTITY.v,
        body = BodyBuilder.put("1/1", phoneId.toByteArray(Charsets.US_ASCII) + 0x00.toByte()),
    )

    suspend fun auth(userId: String, deviceId: String, weightKg: Float) = sendRequest(
        method = KirinMethod.PUT.v,
        opcode = KirinOpcode.NORMAL.v,
        body = BodyBuilder.put("106/3", buildUserInfo(userId, deviceId, weightKg)),
    )

    suspend fun getDeviceInfo() = sendRequest(
        method = KirinMethod.GET.v,
        opcode = KirinOpcode.NORMAL.v,
        body = BodyBuilder.get("106/1"),
    )

    suspend fun getConfig() = sendRequest(
        method = KirinMethod.GET.v,
        opcode = KirinOpcode.NORMAL.v,
        body = BodyBuilder.get("106/5"),
    )

    suspend fun queryStatus() = sendRequest(
        method = KirinMethod.GET.v,
        opcode = KirinOpcode.NORMAL.v,
        body = BodyBuilder.get("106/4"),
    )

    suspend fun setStatus(status: TrainingStatus) = sendRequest(
        method = KirinMethod.PUT.v,
        opcode = KirinOpcode.NORMAL.v,
        body = BodyBuilder.put("106/4", buildTrainingStatus(status)),
    )

    suspend fun queryData() = sendRequest(
        method = KirinMethod.GET.v,
        opcode = KirinOpcode.NORMAL.v,
        body = BodyBuilder.get("106/7"),
    )

    suspend fun setResistance(level: Int, grade: Int? = null) = sendRequest(
        method = KirinMethod.PUT.v,
        opcode = KirinOpcode.NORMAL.v,
        body = BodyBuilder.put("106/6", buildTrainAttributeSetResistance(level, grade)),
    )

    suspend fun observe(route: String) = sendRequest(
        method = KirinMethod.GET.v,
        opcode = KirinOpcode.OBSERVE.v,
        body = BodyBuilder.observe(route),
    )

    suspend fun unobserve(route: String) = sendRequest(
        method = KirinMethod.GET.v,
        opcode = KirinOpcode.OBSERVE.v,
        body = BodyBuilder.observe(route),  // UNOBSERVE shape not yet captured; reuse OBSERVE
    )

    suspend fun setAutopause(seconds: Int) = sendRequest(
        method = KirinMethod.PUT.v,
        opcode = KirinOpcode.SUBRES.v,
        body = BodyBuilder.put("106/21", buildCustomPayload(seconds.toString().toByteArray(Charsets.US_ASCII))),
    )

    suspend fun setAutostop(seconds: Int) = setAutopause(seconds)

    /** ⚠ Dangerous — only call if you've confirmed via UI/CLI gate. */
    suspend fun deviceCommand(cmd: DeviceCommand) = sendRequest(
        method = KirinMethod.PUT.v,
        opcode = KirinOpcode.NORMAL.v,
        body = BodyBuilder.put("106/10", buildDeviceCommand(cmd)),
    )

    suspend fun getLog(logType: Int, num: Int, pullIndex: Int = 0) = sendRequest(
        method = KirinMethod.GET.v,
        opcode = KirinOpcode.NORMAL.v,
        body = BodyBuilder.put("106/8", buildTrainLogRequest(logType, num, pullIndex)),
    )

    suspend fun getOldestLogSummary() = sendRequest(
        method = KirinMethod.GET.v,
        opcode = KirinOpcode.NORMAL.v,
        body = BodyBuilder.get("106/12"),
    )
}
