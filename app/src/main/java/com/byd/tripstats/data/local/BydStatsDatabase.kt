package com.byd.tripstats.data.local

import android.content.Context
import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
import java.io.File
import java.io.IOException

@Database(
    entities = [
        TripEntity::class,
        TripDataPointEntity::class,
        TripStatsEntity::class
    ],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class BydStatsDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun tripDataPointDao(): TripDataPointDao
    abstract fun tripStatsDao(): TripStatsDao

    companion object {
        private const val TAG = "BydStatsDatabase"
        private const val DB_NAME = "byd_stats_database"

        @Volatile
        private var INSTANCE: BydStatsDatabase? = null

        // ── MIGRATION_2_3 ─────────────────────────────────────────────────────
        // Adds columns introduced after the v2 baseline:
        //   tyre pressures, battery health fields, rawJson escape hatch.
        // All use safe defaults so existing trip rows are unaffected.
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE trip_data_points ADD COLUMN tyrePressureLF REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE trip_data_points ADD COLUMN tyrePressureRF REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE trip_data_points ADD COLUMN tyrePressureLR REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE trip_data_points ADD COLUMN tyrePressureRR REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE trip_data_points ADD COLUMN soh INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE trip_data_points ADD COLUMN batteryTotalVoltage INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE trip_data_points ADD COLUMN battery12vVoltage REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE trip_data_points ADD COLUMN batteryCellVoltageMax REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE trip_data_points ADD COLUMN batteryCellVoltageMin REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE trip_data_points ADD COLUMN rawJson TEXT NOT NULL DEFAULT '{}'")
            }
        }


        // ── MIGRATION_3_4 ─────────────────────────────────────────────────────
        // Recreates trip_data_points with Room's exact schema (no explicit DEFAULT
        // metadata on new columns). Required because MIGRATION_2_3 used ALTER TABLE
        // with DEFAULT values, which SQLite stores as column metadata but Room
        // expects 'undefined'. Table recreation is the canonical fix.
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 1. Create new table with exact schema Room will generate
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS trip_data_points_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        tripId INTEGER NOT NULL,
                        timestamp INTEGER NOT NULL,
                        latitude REAL NOT NULL,
                        longitude REAL NOT NULL,
                        altitude REAL NOT NULL,
                        speed REAL NOT NULL,
                        power REAL NOT NULL,
                        soc REAL NOT NULL,
                        odometer REAL NOT NULL,
                        batteryTemp REAL NOT NULL,
                        totalDischarge REAL NOT NULL,
                        gear TEXT NOT NULL,
                        isRegenerating INTEGER NOT NULL,
                        engineSpeedFront INTEGER NOT NULL,
                        engineSpeedRear INTEGER NOT NULL,
                        electricDrivingRangeKm INTEGER NOT NULL,
                        tyrePressureLF REAL NOT NULL,
                        tyrePressureRF REAL NOT NULL,
                        tyrePressureLR REAL NOT NULL,
                        tyrePressureRR REAL NOT NULL,
                        soh INTEGER NOT NULL,
                        batteryTotalVoltage INTEGER NOT NULL,
                        battery12vVoltage REAL NOT NULL,
                        batteryCellVoltageMax REAL NOT NULL,
                        batteryCellVoltageMin REAL NOT NULL,
                        rawJson TEXT NOT NULL
                    )
                """.trimIndent())

                // 2. Copy all existing data; COALESCE supplies safe values for
                //    rows that predate the new columns (i.e. pre-v3 rows)
                database.execSQL("""
                    INSERT INTO trip_data_points_new
                    SELECT
                        id, tripId, timestamp, latitude, longitude, altitude,
                        speed, power, soc, odometer, batteryTemp, totalDischarge,
                        gear, isRegenerating, engineSpeedFront, engineSpeedRear,
                        0, -- electricDrivingRangeKm absent in pre-v3 backups; safe default
                        COALESCE(tyrePressureLF, 0.0),
                        COALESCE(tyrePressureRF, 0.0),
                        COALESCE(tyrePressureLR, 0.0),
                        COALESCE(tyrePressureRR, 0.0),
                        COALESCE(soh, 0),
                        COALESCE(batteryTotalVoltage, 0),
                        COALESCE(battery12vVoltage, 0.0),
                        COALESCE(batteryCellVoltageMax, 0.0),
                        COALESCE(batteryCellVoltageMin, 0.0),
                        COALESCE(rawJson, '{}')
                    FROM trip_data_points
                """.trimIndent())

                // 3. Swap tables
                database.execSQL("DROP TABLE trip_data_points")
                database.execSQL("ALTER TABLE trip_data_points_new RENAME TO trip_data_points")
            }
        }

        // ── Migration template — copy for every future schema change ─────────
        // private val MIGRATION_X_Y = object : Migration(X, Y) {
        //     override fun migrate(database: SupportSQLiteDatabase) {
        //         database.execSQL("ALTER TABLE trip_data_points ADD COLUMN newField REAL NOT NULL DEFAULT 0")
        //     }
        // }

        fun getDatabase(context: Context): BydStatsDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BydStatsDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    // fallbackToDestructiveMigration handles anything beyond explicit
                    // migrations during development. Remove before production release.
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }

        /**
         * Backs up the SQLite file to context.filesDir/db_backups/ before any
         * risky operation. Survives app updates; cleared only by "Clear data"
         * (not "Clear cache") in Android settings.
         *
         * Call this automatically before every migration (via a RoomDatabase
         * Callback or before .build()), and expose it from a Settings button
         * so users can create manual backups too.
         *
         * Returns the backup File on success, null on failure.
         */
        fun backupDatabase(context: Context): File? {
            // Close the instance so WAL is fully flushed before we copy the file.
            INSTANCE?.close()
            INSTANCE = null

            return try {
                val dbFile = context.getDatabasePath(DB_NAME)
                if (!dbFile.exists()) {
                    Log.w(TAG, "No database file to back up")
                    return null
                }
                val backupDir = File(context.filesDir, "db_backups").also { it.mkdirs() }
                val backupFile = File(backupDir, "${DB_NAME}_backup_${System.currentTimeMillis()}.db")
                dbFile.copyTo(backupFile, overwrite = true)
                Log.i(TAG, "Backed up to: ${backupFile.absolutePath}")
                backupFile
            } catch (e: IOException) {
                Log.e(TAG, "Backup failed", e)
                null
            }
        }

        /**
         * Wipes all trip data and recreates a clean database.
         * Equivalent to "Clear data" from Android settings, but accessible
         * from within the app's Settings screen.
         *
         * Always call backupDatabase() first if a safety net is desired.
         * The next call to getDatabase() will recreate the schema from scratch.
         */
        /**
         * Closes the open Room connection and clears the singleton.
         * The database file is left intact — call this before replacing the file
         * during a restore so Room isn't holding a handle to it.
         * The next getDatabase() call will reopen cleanly.
         */
        fun closeDatabase() {
            INSTANCE?.close()
            INSTANCE = null
            Log.i(TAG, "Database connection closed")
        }

        fun resetDatabase(context: Context) {
            INSTANCE?.close()
            INSTANCE = null
            context.deleteDatabase(DB_NAME)
            Log.i(TAG, "Database wiped — will be recreated on next getDatabase() call")
        }

        /**
         * Returns all available backups sorted newest-first.
         * Use this to populate a restore list in Settings if needed.
         */
        fun listBackups(context: Context): List<File> {
            val dir = File(context.filesDir, "db_backups")
            return if (dir.exists())
                dir.listFiles()
                    ?.filter { it.name.endsWith(".db") }
                    ?.sortedByDescending { it.lastModified() }
                    ?: emptyList()
            else emptyList()
        }
    }
}