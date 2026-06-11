package io.github.liki4.peek.ride

import android.content.Context
import io.github.liki4.peek.protocol.TrainData
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-ride JSONL debug logger. When enabled, writes one JSON line per second
 * with all telemetry — raw TrainData, HR, speed model internals, ERG/SIM
 * state, bridge state, and resistance commands sent.
 *
 * Files live in `filesDir/ride_logs/` and persist across sessions.
 */
class RideLogger(context: Context) {

    private val logDir = File(context.filesDir, "ride_logs")
    private var writer: BufferedWriter? = null
    private var lineCount = 0
    var lastLogFile: File? = null
        private set

    fun open(startTimeUnixS: Long) {
        logDir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(Date(startTimeUnixS * 1000L))
        val file = File(logDir, "peek_debug_$stamp.jsonl")
        writer = BufferedWriter(FileWriter(file, false))
        lastLogFile = file
        lineCount = 0
    }

    data class TickContext(
        val unixS: Long,
        val rideSecond: Int,
        val td: TrainData?,
        val hrBpm: Int?,
        val speedKmh: Float?,
        val speedModel: String,
        val mPerRpmSec: Double?,
        val sumRpm: Long?,
        val ergMode: String?,
        val ergGrade: Float?,
        val ergTargetW: Int?,
        val ergPickedL: Int?,
        val ergFallback: Boolean,
        val bridgeState: String,
        val bridgeClient: String?,
        val cmdResistance: Int?,
        val cmdDedup: Boolean,
    )

    fun writeTick(ctx: TickContext) {
        val w = writer ?: return
        val root = JSONObject()
        root.put("t", ctx.unixS)
        root.put("s", ctx.rideSecond)

        if (ctx.td != null) {
            val td = JSONObject()
            ctx.td.rpm?.let { td.put("rpm", it) }
            ctx.td.watt?.let { td.put("watt", it) }
            ctx.td.resistance?.let { td.put("resistance", it) }
            ctx.td.distanceM?.let { td.put("distanceM", it) }
            ctx.td.durationS?.let { td.put("durationS", it) }
            ctx.td.calorie?.let { td.put("calorie", it) }
            ctx.td.status?.let { td.put("status", it) }
            root.put("td", td)
        }

        root.put("hr", ctx.hrBpm ?: JSONObject.NULL)

        val speed = JSONObject()
        speed.put("kmh", ctx.speedKmh ?: JSONObject.NULL)
        speed.put("model", ctx.speedModel)
        ctx.mPerRpmSec?.let { speed.put("mPerRpmSec", it) }
        ctx.sumRpm?.let { speed.put("sumRpm", it) }
        root.put("speed", speed)

        if (ctx.ergMode != null) {
            val erg = JSONObject()
            erg.put("mode", ctx.ergMode)
            erg.put("grade", ctx.ergGrade ?: JSONObject.NULL)
            erg.put("targetW", ctx.ergTargetW ?: JSONObject.NULL)
            erg.put("pickedL", ctx.ergPickedL ?: JSONObject.NULL)
            erg.put("fallback", ctx.ergFallback)
            root.put("erg", erg)
        }

        val bridge = JSONObject()
        bridge.put("state", ctx.bridgeState)
        bridge.put("client", ctx.bridgeClient ?: JSONObject.NULL)
        root.put("bridge", bridge)

        if (ctx.cmdResistance != null) {
            val cmd = JSONObject()
            cmd.put("resistance", ctx.cmdResistance)
            cmd.put("dedup", ctx.cmdDedup)
            root.put("cmd", cmd)
        }

        w.write(root.toString())
        w.newLine()
        lineCount++
        if (lineCount % 10 == 0) w.flush()
    }

    fun close() {
        writer?.flush()
        writer?.close()
        writer = null
    }

    val isOpen: Boolean get() = writer != null
}
