package io.github.liki4.peek.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RideSessionDao {

    @Query("SELECT * FROM ride_session ORDER BY startTimeUnixS DESC")
    fun observeAll(): Flow<List<RideSessionEntity>>

    @Query("SELECT * FROM ride_session WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): RideSessionEntity?

    @Insert
    suspend fun insert(row: RideSessionEntity): Long

    @Update
    suspend fun update(row: RideSessionEntity)

    @Query("UPDATE ride_session SET stravaStatus = :status, stravaActivityId = :activityId WHERE id = :id")
    suspend fun setStravaStatus(id: Long, status: Int, activityId: Long?)

    @Query("DELETE FROM ride_session WHERE id = :id")
    suspend fun delete(id: Long)
}
