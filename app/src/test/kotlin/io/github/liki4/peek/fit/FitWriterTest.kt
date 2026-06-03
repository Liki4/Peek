package io.github.liki4.peek.fit

import com.garmin.fit.Decode
import com.garmin.fit.Event
import com.garmin.fit.EventMesg
import com.garmin.fit.EventMesgListener
import com.garmin.fit.EventType
import com.garmin.fit.FileIdMesg
import com.garmin.fit.FileIdMesgListener
import com.garmin.fit.LapMesg
import com.garmin.fit.LapMesgListener
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.RecordMesg
import com.garmin.fit.RecordMesgListener
import com.garmin.fit.SessionMesg
import com.garmin.fit.SessionMesgListener
import com.garmin.fit.Sport
import com.garmin.fit.SportMesg
import com.garmin.fit.SportMesgListener
import com.garmin.fit.SubSport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files

/**
 * Strava rejects malformed FIT files silently — verifying the writer end-to-end
 * by round-tripping with the Garmin SDK Decoder. We assert: sport markers are
 * CYCLING/VIRTUAL_ACTIVITY, all five required streams (cadence, power,
 * resistance, hr, distance) are populated on every Record, REAR_GEAR_CHANGE
 * events match the resistance transition count, and Session aggregates line up.
 */
class FitWriterTest {

    /**
     * Synthetic ride: 30 seconds, three resistance steps (5 → 8 → 8 → 6),
     * varying rpm/watt/HR each second, monotonically increasing distance.
     */
    private fun fixtureRide(): RideData {
        val n = 30
        val res = (0 until n).map { i -> when {
            i < 5 -> 5
            i < 20 -> 8
            else -> 6
        } }
        val rpm = (0 until n).map { i -> 60 + (i % 10) }
        val watt = (0 until n).map { i -> 100 + (i * 3) }
        val hr = (0 until n).map { i -> 120 + (i % 8) }
        // 1 m/sample increment so 1 m/s = 3.6 km/h. distanceM is cumulative.
        val distanceM = (0 until n).map { i -> i * 7 }   // 25.2 km/h
        // Speed in [build] is derived from distance, but here we feed it directly
        // mirroring what RideDataBuilder.build does.
        val speed = (0 until n).map { i ->
            if (i == 0) null
            else ((distanceM[i] - distanceM[i - 1]) * 3.6f)
        }

        return RideData(
            startTimeUnixS = 1735_000_000L,  // 2024-12-23-ish
            durationS = n,
            resistance = res,
            rpm = rpm,
            watt = watt,
            speedKmh = speed,
            hrBpm = hr,
            totalDistanceM = distanceM.last(),
            totalCalorie = 42,
        )
    }

    @Test
    fun `FIT round-trip preserves sport, all streams, and event counts`() {
        val ride = fixtureRide()
        val tmp = Files.createTempFile("peek_test_", ".fit").toFile()
        tmp.deleteOnExit()
        FitWriter.write(ride, tmp)

        // ---- decode ----
        val decoded = decodeFit(tmp)

        // ---- (a) Sport markers ----
        // SportMesg comes from the new top-level write.
        assertNotNull("SportMesg missing", decoded.sport)
        assertEquals(Sport.CYCLING, decoded.sport!!.sport)
        assertEquals(SubSport.VIRTUAL_ACTIVITY, decoded.sport!!.subSport)

        // Lap and Session both carry the same pair (Strava's primary check).
        assertEquals(Sport.CYCLING, decoded.session!!.sport)
        assertEquals(SubSport.VIRTUAL_ACTIVITY, decoded.session!!.subSport)
        assertEquals(Sport.CYCLING, decoded.lap!!.sport)
        assertEquals(SubSport.VIRTUAL_ACTIVITY, decoded.lap!!.subSport)

        // ---- (b) RecordMesg count == durationS ----
        assertEquals(ride.durationS, decoded.records.size)

        // ---- (c) all five required streams populated on every record ----
        decoded.records.forEachIndexed { i, r ->
            assertNotNull("record $i missing cadence", r.cadence)
            assertNotNull("record $i missing power",   r.power)
            assertNotNull("record $i missing resistance", r.resistance)
            assertNotNull("record $i missing heartRate", r.heartRate)
            assertNotNull("record $i missing distance", r.distance)
            // First record's distance is allowed to be 0 (no Δdistance yet).
            if (i > 0) assertTrue("distance must be non-decreasing at $i",
                r.distance!! >= decoded.records[i - 1].distance!!)
        }

        // ---- (d) REAR_GEAR_CHANGE events == # resistance transitions ----
        val transitions = ride.resistance
            .zipWithNext()
            .count { (a, b) -> a != b } + 1  // +1 for the very first sample
        val gearChanges = decoded.events.count {
            it.event == Event.REAR_GEAR_CHANGE && it.eventType == EventType.MARKER
        }
        assertEquals("REAR_GEAR_CHANGE event count mismatch", transitions, gearChanges)

        // ---- (e) Session aggregates line up ----
        val sess = decoded.session!!
        assertEquals(ride.durationS.toFloat(), sess.totalElapsedTime!!, 0.01f)
        assertEquals(ride.durationS.toFloat(), sess.totalTimerTime!!, 0.01f)
        // Average HR ~= mean of fixture HR
        val expectedAvgHr = ride.hrBpm.filterNotNull().average().toInt()
        assertEquals(expectedAvgHr.toShort(), sess.avgHeartRate)
        val expectedMaxHr = ride.hrBpm.filterNotNull().max()
        assertEquals(expectedMaxHr.toShort(), sess.maxHeartRate)
        // Average watt
        val expectedAvgWatt = ride.watt.filterNotNull().average().toInt()
        assertEquals(expectedAvgWatt, sess.avgPower)

        // ---- file header sanity ----
        assertNotNull(decoded.fileId)
    }

    @Test
    fun `FIT writer survives a ride with only resistance + rpm (no HR, no watt)`() {
        val n = 10
        val ride = RideData(
            startTimeUnixS = 1735_000_100L,
            durationS = n,
            resistance = List(n) { 7 },
            rpm = List(n) { 80 },
            watt = List(n) { null },   // no power meter
            speedKmh = List(n) { if (it == 0) null else 25f },
            hrBpm = List(n) { null },  // no HR strap
            totalDistanceM = 70,
            totalCalorie = 5,
        )
        val tmp = Files.createTempFile("peek_test_nohr_", ".fit").toFile()
        tmp.deleteOnExit()
        FitWriter.write(ride, tmp)

        val decoded = decodeFit(tmp)
        assertEquals(n, decoded.records.size)
        decoded.records.forEach { r ->
            assertNotNull(r.cadence)
            assertNotNull(r.resistance)
            assertNull(r.heartRate)
            assertNull(r.power)
        }
        // Single resistance value → exactly one REAR_GEAR_CHANGE.
        val gearChanges = decoded.events.count { it.event == Event.REAR_GEAR_CHANGE }
        assertEquals(1, gearChanges)
    }

    // ============================== decode helper ==============================

    private data class Decoded(
        val fileId: FileIdMesg?,
        val sport: SportMesg?,
        val records: List<RecordMesg>,
        val events: List<EventMesg>,
        val lap: LapMesg?,
        val session: SessionMesg?,
    )

    private fun decodeFit(file: File): Decoded {
        val records = mutableListOf<RecordMesg>()
        val events = mutableListOf<EventMesg>()
        var fileId: FileIdMesg? = null
        var sport: SportMesg? = null
        var lap: LapMesg? = null
        var session: SessionMesg? = null

        FileInputStream(file).use { input ->
            val decoder = Decode()
            val broadcaster = MesgBroadcaster(decoder)
            broadcaster.addListener(FileIdMesgListener { mesg -> fileId = mesg })
            broadcaster.addListener(SportMesgListener { mesg -> sport = mesg })
            broadcaster.addListener(RecordMesgListener { mesg -> records += mesg })
            broadcaster.addListener(EventMesgListener { mesg -> events += mesg })
            broadcaster.addListener(LapMesgListener { mesg -> lap = mesg })
            broadcaster.addListener(SessionMesgListener { mesg -> session = mesg })
            broadcaster.run(input)
        }

        return Decoded(fileId, sport, records, events, lap, session)
    }
}
