package io.github.liki4.peek.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [RideSessionEntity::class], version = 1, exportSchema = false)
abstract class RideDatabase : RoomDatabase() {
    abstract fun rideSessionDao(): RideSessionDao

    companion object {
        @Volatile private var INSTANCE: RideDatabase? = null
        fun get(context: Context): RideDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RideDatabase::class.java,
                    "peek-history.db",
                ).build().also { INSTANCE = it }
            }
    }
}
