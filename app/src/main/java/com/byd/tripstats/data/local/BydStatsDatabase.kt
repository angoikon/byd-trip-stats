package com.byd.tripstats.data.local

import android.content.Context
import android.util.Log
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
    version = 2,
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

        // ── Migration template — copy this block for every future schema change ──
        // Baseline: version = 2. Next schema change → version = 3, Migration(2, 3)
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
                    DB_NAME
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