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
    version = 1,
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

        // ── Migration template — copy this block for every future schema change ──
        // private val MIGRATION_X_Y = object : Migration(X, Y) {
        //     override fun migrate(database: SupportSQLiteDatabase) {
        //         // Example: new MQTT field becomes a first-class column
        //         // database.execSQL("ALTER TABLE trip_data_points ADD COLUMN newField REAL NOT NULL DEFAULT 0")
        //         //
        //         // Example: new MQTT field already in rawJson, promote to column
        //         // database.execSQL("ALTER TABLE trip_data_points ADD COLUMN tyrePressureFL REAL NOT NULL DEFAULT 0")
        //     }
        // }

        fun getDatabase(context: Context): BydStatsDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BydStatsDatabase::class.java,
                    "byd_stats_database"
                )
                    // ── DEV MODE ─────────────────────────────────────────────
                    // Safe to use until first production release. Remove this
                    // line and add .addMigrations(...) before shipping v1.
                    .fallbackToDestructiveMigration()
                    // ─────────────────────────────────────────────────────────
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}