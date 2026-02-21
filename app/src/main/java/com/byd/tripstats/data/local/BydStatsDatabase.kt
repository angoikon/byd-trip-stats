package com.byd.tripstats.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.byd.tripstats.data.local.dao.TripDao
import com.byd.tripstats.data.local.dao.TripDataPointDao
import com.byd.tripstats.data.local.dao.TripStatsDao
import com.byd.tripstats.data.local.entity.TripDataPointEntity
import com.byd.tripstats.data.local.entity.TripEntity
import com.byd.tripstats.data.local.entity.TripStatsEntity

@Database(
    entities = [
        TripEntity::class,
        TripDataPointEntity::class,
        TripStatsEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class BydStatsDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun tripDataPointDao(): TripDataPointDao
    abstract fun tripStatsDao(): TripStatsDao
    
    companion object {
        @Volatile
        private var INSTANCE: BydStatsDatabase? = null
        
        fun getDatabase(context: Context): BydStatsDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BydStatsDatabase::class.java,
                    "byd_stats_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
