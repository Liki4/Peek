package io.github.liki4.peek.ble

import io.github.liki4.peek.protocol.DeviceInfo
import io.github.liki4.peek.protocol.parseDeviceInfo

/**
 * Orchestrates the connect-to-"已连接" sequence from PROTOCOL.md §7.
 *
 * Sends the minimum-viable handshake (identity + auth + observes + DeviceInfo).
 * Returns the parsed DeviceInfo on success.
 *
 * Skipped optional steps that CC_23 firmware rejects:
 * - `GET 0/3` (probe, no handler)
 * - `GET 106/13` (no handler — bike returns Not Found)
 * - `PUT 106/21 "1200"` / `"3600"` (CMD ERROR on CC_23)
 */
suspend fun runHandshake(
    client: KeepBikeClient,
    userId: String,
    deviceId: String,
    weightKg: Float,
    phoneId: String = "deadbeef00abcdef",
): Result<DeviceInfo> = runCatching {
    client.identity(phoneId) ?: error("identity timed out")
    client.observe("106/6") ?: error("observe 106/6 (first) timed out")
    client.auth(userId, deviceId, weightKg) ?: error("auth timed out")
    client.observe("106/4") ?: error("observe 106/4 timed out")
    client.observe("106/6") ?: error("observe 106/6 (second) timed out")
    client.observe("106/3") ?: error("observe 106/3 timed out")
    val info = client.getDeviceInfo() ?: error("getDeviceInfo timed out")
    parseDeviceInfo(info.payload)
}
