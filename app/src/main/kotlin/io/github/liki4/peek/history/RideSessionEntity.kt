package io.github.liki4.peek.history

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room row for one completed ride session. Written by [RideSessionRepository.save]
 * after [io.github.liki4.peek.fit.FitWriter.write] returns; read by HistoryScreen.
 *
 * The actual FIT file lives at [fitFilePath] under the app's `filesDir/ride_exports`
 * directory — kept on the filesystem rather than as a BLOB in SQLite so the
 * FileProvider share intent and the Strava upload path can both stream it.
 */
@Entity(tableName = "ride_session")
data class RideSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTimeUnixS: Long,
    val durationS: Int,
    val distanceM: Int?,
    val calorie: Int?,
    val avgWatt: Int?,
    val avgHrBpm: Int?,
    val maxHrBpm: Int?,
    val fitFilePath: String,
    /** Last upload attempt result. 0 = not attempted, 1 = success, 2 = failed. */
    val stravaStatus: Int = 0,
    /** Strava activity id once we have one (for opening the activity directly). */
    val stravaActivityId: Long? = null,
)
