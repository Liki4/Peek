package io.github.liki4.peek.fit

import com.garmin.fit.ActivityMesg
import com.garmin.fit.DateTime
import com.garmin.fit.Event
import com.garmin.fit.EventMesg
import com.garmin.fit.EventType
import com.garmin.fit.File as FitFileType
import com.garmin.fit.FileEncoder
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Fit
import com.garmin.fit.Intensity
import com.garmin.fit.LapMesg
import com.garmin.fit.LapTrigger
import com.garmin.fit.Manufacturer
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.Sport
import com.garmin.fit.SportMesg
import com.garmin.fit.SubSport
import java.io.File
import java.util.Date

/**
 * Write a Garmin FIT activity file from a completed [RideData].
 *
 * Output is a Strava-compatible Virtual Ride: `Sport.CYCLING` +
 * `SubSport.VIRTUAL_ACTIVITY`. Resistance transitions are emitted as
 * `Event.REAR_GEAR_CHANGE` events so Strava renders a step chart for
 * resistance level — same convention as `keep_fit.py:299-313`.
 */
object FitWriter {

    fun write(ride: RideData, outFile: File): File {
        outFile.parentFile?.mkdirs()

        val startDt = DateTime(Date(ride.startTimeUnixS * 1000L))
        val endDt = DateTime(Date(ride.endTimeUnixS() * 1000L))

        val encoder = FileEncoder(outFile, Fit.ProtocolVersion.V2_0)
        try {
            // ---- FILE_ID ----
            encoder.write(FileIdMesg().apply {
                type = FitFileType.ACTIVITY
                manufacturer = Manufacturer.DEVELOPMENT
                product = 0
                timeCreated = startDt
                serialNumber = (ride.startTimeUnixS and 0xFFFFFFFFL)
            })

            // ---- SPORT (top-level marker; some FIT consumers only inspect SportMesg) ----
            encoder.write(SportMesg().apply {
                sport = Sport.CYCLING
                subSport = SubSport.VIRTUAL_ACTIVITY
            })

            // ---- TIMER START ----
            encoder.write(EventMesg().apply {
                event = Event.TIMER
                eventType = EventType.START
                timestamp = startDt
            })

            // ---- per-second RECORDs + cumulative distance ----
            var cumDistM = 0.0
            val cumDistByIdx = DoubleArray(ride.durationS)
            for (i in 0 until ride.durationS) {
                val sp = ride.speedKmh.getOrNull(i)
                if (sp != null) cumDistM += sp / 3.6  // km/h → m/s == m added per 1s tick
                cumDistByIdx[i] = cumDistM

                val rec = RecordMesg().apply {
                    timestamp = DateTime(Date((ride.startTimeUnixS + i) * 1000L))
                    ride.hrBpm.getOrNull(i)?.let { heartRate = it.toShort() }
                    ride.watt.getOrNull(i)?.let { power = it }
                    ride.resistance.getOrNull(i)?.let { resistance = it.toShort() }
                    ride.rpm.getOrNull(i)?.let { cadence = it.toShort() }
                    sp?.let { speed = it / 3.6f }   // FIT expects m/s
                    distance = cumDistM.toFloat()
                }
                encoder.write(rec)
            }

            // ---- REAR_GEAR_CHANGE events on each resistance transition ----
            if (ride.hasResistance()) {
                var prev: Int? = null
                for (i in 0 until ride.durationS) {
                    val r = ride.resistance.getOrNull(i) ?: continue
                    if (r != prev) {
                        encoder.write(EventMesg().apply {
                            timestamp = DateTime(Date((ride.startTimeUnixS + i) * 1000L))
                            event = Event.REAR_GEAR_CHANGE
                            eventType = EventType.MARKER
                            rearGear = (r + 1).toShort()
                            rearGearNum = r.toShort()
                        })
                        prev = r
                    }
                }
            }

            // ---- TIMER STOP ----
            encoder.write(EventMesg().apply {
                event = Event.TIMER
                eventType = EventType.STOP_ALL
                timestamp = endDt
            })

            // ---- aggregates ----
            fun avgMax(arr: List<Int?>): Pair<Float, Int>? {
                val vals = arr.filterNotNull()
                if (vals.isEmpty()) return null
                return vals.average().toFloat() to vals.max()
            }

            fun avgMaxF(arr: List<Float?>): Pair<Float, Float>? {
                val vals = arr.filterNotNull()
                if (vals.isEmpty()) return null
                return vals.average().toFloat() to vals.max()
            }

            val finalDistance = ride.totalDistanceM?.toFloat() ?: cumDistM.toFloat()
            val totalCal = ride.totalCalorie ?: 0
            val hrAgg = avgMax(ride.hrBpm)
            val rpmAgg = avgMax(ride.rpm)
            val wattAgg = avgMax(ride.watt)
            val speedAgg = avgMaxF(ride.speedKmh)

            // ---- LAP ----
            encoder.write(LapMesg().apply {
                timestamp = endDt
                startTime = startDt
                totalElapsedTime = ride.durationS.toFloat()
                totalTimerTime = ride.durationS.toFloat()
                totalDistance = finalDistance
                totalCalories = totalCal
                hrAgg?.let { (avg, mx) -> avgHeartRate = avg.toInt().toShort(); maxHeartRate = mx.toShort() }
                wattAgg?.let { (avg, mx) -> avgPower = avg.toInt(); maxPower = mx }
                rpmAgg?.let { (avg, mx) -> avgCadence = avg.toInt().toShort(); maxCadence = mx.toShort() }
                speedAgg?.let { (avg, mx) -> avgSpeed = avg / 3.6f; maxSpeed = mx / 3.6f }
                event = Event.LAP
                eventType = EventType.STOP
                lapTrigger = LapTrigger.SESSION_END
                sport = Sport.CYCLING
                subSport = SubSport.VIRTUAL_ACTIVITY
                intensity = Intensity.ACTIVE
            })

            // ---- SESSION ----
            encoder.write(SessionMesg().apply {
                timestamp = endDt
                startTime = startDt
                totalElapsedTime = ride.durationS.toFloat()
                totalTimerTime = ride.durationS.toFloat()
                totalDistance = finalDistance
                totalCalories = totalCal
                hrAgg?.let { (avg, mx) -> avgHeartRate = avg.toInt().toShort(); maxHeartRate = mx.toShort() }
                wattAgg?.let { (avg, mx) -> avgPower = avg.toInt(); maxPower = mx }
                rpmAgg?.let { (avg, mx) -> avgCadence = avg.toInt().toShort(); maxCadence = mx.toShort() }
                speedAgg?.let { (avg, mx) -> avgSpeed = avg / 3.6f; maxSpeed = mx / 3.6f }
                sport = Sport.CYCLING
                subSport = SubSport.VIRTUAL_ACTIVITY
                firstLapIndex = 0
                numLaps = 1
                event = Event.SESSION
                eventType = EventType.STOP
            })

            // ---- ACTIVITY ----
            encoder.write(ActivityMesg().apply {
                timestamp = endDt
                totalTimerTime = ride.durationS.toFloat()
                numSessions = 1
                type = com.garmin.fit.Activity.MANUAL
                event = Event.ACTIVITY
                eventType = EventType.STOP
            })
        } finally {
            encoder.close()
        }
        return outFile
    }
}
