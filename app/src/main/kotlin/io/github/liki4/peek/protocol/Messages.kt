package io.github.liki4.peek.protocol

/**
 * High-level body builders + parsers for the Kirin Machine (serviceId 106)
 * messages. Field numbers + names are confirmed per PROTOCOL.md §5; see
 * `keep_protocol.py` for the Python reference.
 */

// ============================== BODY BUILDERS ==============================

/** `UserInfo` for `PUT 106/3` (auth). */
fun buildUserInfo(
    userId: String,
    deviceId: String,
    weightKg: Float,
    timestampS: Long = System.currentTimeMillis() / 1000,
): ByteArray =
    Protobuf.stringField(1, userId) +
        Protobuf.stringField(2, deviceId) +
        Protobuf.fixed32FloatField(3, weightKg) +
        Protobuf.varintField(4, timestampS)

/** `TrainingStatusMessage` for `PUT 106/4`. */
fun buildTrainingStatus(status: TrainingStatus): ByteArray =
    Protobuf.varintField(1, status.wire)

/** `TrainAttributeMessage` for `PUT 106/6` — app-side resistance control (1..18 on CC_23). */
fun buildTrainAttributeSetResistance(resistance: Int, grade: Int? = null): ByteArray {
    var out = Protobuf.varintField(1, resistance)
    if (grade != null) out += Protobuf.varintField(6, grade)
    return out
}

/** `CustomPaylodMessage` for `PUT 106/21` (AT-cmd channel — CC_23 returns CMD ERROR). */
fun buildCustomPayload(payload: ByteArray): ByteArray = Protobuf.bytesField(1, payload)

/** `DeviceCommandMessage` for `PUT 106/10`. */
fun buildDeviceCommand(cmd: DeviceCommand): ByteArray = Protobuf.varintField(1, cmd.v)

/** `TrainLogRequestMessage` for `GET 106/8` / `DELETE 106/8`. */
fun buildTrainLogRequest(logType: Int, num: Int, pullIndex: Int = 0): ByteArray =
    Protobuf.varintField(1, logType) +
        Protobuf.varintField(2, num) +
        Protobuf.varintField(3, pullIndex)

// ============================== PARSED MESSAGES ==============================

data class DeviceInfo(
    val model: String?,
    val hwId: String?,
    val statusBitfield: Long?,
    val hwVersion: String?,
    val fwVersion: String?,
    val totalDurationS: Long?,
    val totalDistanceM: Long?,
    val field9: Long?,
    val raw: Map<Int, Any>,
)

data class TrainData(
    val startTime: Long?,
    val distanceM: Long?,
    val durationS: Long?,
    val calorie: Long?,
    val resistance: Long?,
    val rpm: Long?,
    val watt: Long?,
    val field8: Long?,
    val status: Long?,
    val raw: Map<Int, Any>,
)

data class TrainAttribute(
    val resistance: Long?,
    val rpm: Long?,
    val changedByDevice: Long?,
    val speed: Long?,             // CC_23 firmware does NOT populate this
    val slope: Long?,
    val resistanceGrade: Long?,   // observed at field 8 on CC_23, not 6
    val raw: Map<Int, Any>,
)

data class TrainingStatusReply(
    val status: Long?,
    val statusName: String,
    val changedByDevice: Long?,
    val raw: Map<Int, Any>,
)

data class TrainLogSegment(
    val startTimeOffset: Long?,
    val resistance: Long?,
    val rpm: Long?,
    val watt: Long?,
    val spm: Long?,
    val pace: Long?,
    val speed: Long?,
    val slope: Long?,
)

data class TrainLogSummary(
    val userId: String?,
    val startTime: Long?,
    val distance: Long?,
    val calorie: Long?,
    val offline: Long?,
    val duration: Long?,
    val steps: Long?,
)

data class TrainLogResponse(
    val outer: Map<Int, Any>,
    val kind: Kind,
    val segments: List<TrainLogSegment>,
    val summary: TrainLogSummary?,
    val rawDataHex: String,
) {
    enum class Kind { EMPTY, SEGMENTS, SUMMARY, UNKNOWN }
}

// ============================== PARSERS ==============================

private fun Map<Int, Any>.long(k: Int): Long? = this[k] as? Long
private fun Map<Int, Any>.str(k: Int): String? = this[k] as? String
private fun Map<Int, Any>.bytes(k: Int): ByteArray? = this[k] as? ByteArray

fun parseDeviceInfo(payload: ByteArray): DeviceInfo {
    val p = Protobuf.decode(payload)
    return DeviceInfo(
        model = p.str(1),
        hwId = p.str(2),
        statusBitfield = p.long(3),
        hwVersion = p.str(4),
        fwVersion = p.str(5),
        totalDurationS = p.long(7),
        totalDistanceM = p.long(8),
        field9 = p.long(9),
        raw = p,
    )
}

fun parseTrainData(payload: ByteArray): TrainData {
    val p = Protobuf.decode(payload)
    return TrainData(
        startTime = p.long(1),
        distanceM = p.long(2),
        durationS = p.long(3),
        calorie = p.long(4),
        resistance = p.long(5),
        rpm = p.long(6),
        watt = p.long(7),
        field8 = p.long(8),
        status = p.long(11),
        raw = p,
    )
}

fun parseTrainAttribute(payload: ByteArray): TrainAttribute {
    val p = Protobuf.decode(payload)
    return TrainAttribute(
        resistance = p.long(1),
        rpm = p.long(2),
        changedByDevice = p.long(3),
        speed = p.long(4),
        slope = p.long(5),
        resistanceGrade = p.long(8),
        raw = p,
    )
}

fun parseTrainingStatus(payload: ByteArray): TrainingStatusReply {
    val p = Protobuf.decode(payload)
    val raw = p.long(1)
    val name = raw?.toInt()?.let { TrainingStatus.fromWire(it)?.name } ?: "UNKNOWN"
    return TrainingStatusReply(
        status = raw,
        statusName = name,
        changedByDevice = p.long(2),
        raw = p,
    )
}

/**
 * Parse `TrainLogResponseMessage` (resource 106/8 GET reply).
 *
 * Wire shape inferred from static RE (`zn1/a.java:399-432`):
 *   outer { logType, startTime, pullIndex, data:bytes }
 *   inner data = TrainLogSegmentListMessage  OR  TrainLogSummaryMessage
 *
 * Note: CC_23 firmware returns 0 bytes for 106/8 (not implemented) — this
 * parser is still here for completeness / future firmware revisions.
 */
fun parseTrainLogResponse(payload: ByteArray): TrainLogResponse {
    if (payload.isEmpty()) {
        return TrainLogResponse(emptyMap(), TrainLogResponse.Kind.EMPTY, emptyList(), null, "")
    }
    val outer = Protobuf.decode(payload)
    val outerView = outer.mapValues { (_, v) ->
        if (v is ByteArray) v.joinToString("") { "%02x".format(it) } else v
    }

    // Find embedded data bytes (field 4 most likely; try others)
    var data: ByteArray? = null
    for (fnum in intArrayOf(4, 3, 2, 5)) {
        val v = outer[fnum]
        if (v is ByteArray && v.size > 2) { data = v; break }
    }
    if (data == null) {
        return TrainLogResponse(outerView, TrainLogResponse.Kind.EMPTY, emptyList(), null, "")
    }

    val rawDataHex = data.joinToString("") { "%02x".format(it) }
    val inner = Protobuf.decode(data)

    // Segments path: walk bytes manually because Protobuf.decode keeps only the LAST
    // occurrence of repeated field 1 (TrainLogSegmentMessage entries).
    if (inner[1] is ByteArray) {
        val segBytesList = mutableListOf<ByteArray>()
        var pos = 0
        runCatching {
            while (pos < data.size) {
                val (tag, after) = Protobuf.readVarintAt(data, pos); pos = after
                val fnum = (tag ushr 3).toInt()
                val wtype = (tag and 7L).toInt()
                when (wtype) {
                    0 -> { val (_, p) = Protobuf.readVarintAt(data, pos); pos = p }
                    1 -> pos += 8
                    5 -> pos += 4
                    2 -> {
                        val (ln, p) = Protobuf.readVarintAt(data, pos); pos = p
                        val sub = data.copyOfRange(pos, pos + ln.toInt())
                        pos += ln.toInt()
                        if (fnum == 1) segBytesList += sub
                    }
                    else -> return@runCatching
                }
            }
        }

        if (segBytesList.isNotEmpty()) {
            val segs = segBytesList.map { sb ->
                val seg = Protobuf.decode(sb)
                TrainLogSegment(
                    startTimeOffset = seg.long(1),
                    resistance = seg.long(2),
                    rpm = seg.long(3),
                    watt = seg.long(4),
                    spm = seg.long(5),
                    pace = seg.long(6),
                    speed = seg.long(7),
                    slope = seg.long(8),
                )
            }
            return TrainLogResponse(outerView, TrainLogResponse.Kind.SEGMENTS, segs, null, rawDataHex)
        }
    }

    // Summary path: TrainLogSummaryMessage has userId (string) at field 1
    if (inner[1] is String) {
        val summary = TrainLogSummary(
            userId = inner.str(1),
            startTime = inner.long(2),
            distance = inner.long(3),
            calorie = inner.long(4),
            offline = inner.long(5),
            duration = inner.long(6),
            steps = inner.long(7),
        )
        return TrainLogResponse(outerView, TrainLogResponse.Kind.SUMMARY, emptyList(), summary, rawDataHex)
    }

    return TrainLogResponse(outerView, TrainLogResponse.Kind.UNKNOWN, emptyList(), null, rawDataHex)
}
