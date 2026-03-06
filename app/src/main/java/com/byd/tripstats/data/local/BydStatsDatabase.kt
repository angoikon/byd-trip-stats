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
import com.byd.tripstats.data.local.dao.TripSegmentDao
import com.byd.tripstats.data.local.entity.TripDataPointEntity
import com.byd.tripstats.data.local.entity.TripEntity
import com.byd.tripstats.data.local.entity.TripStatsEntity
import com.byd.tripstats.data.local.entity.TripSegmentEntity
import android.os.Environment
import java.io.File
import java.io.IOException

@Database(
    entities = [
        TripEntity::class,
        TripDataPointEntity::class,
        TripStatsEntity::class,
        TripSegmentEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class BydStatsDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun tripDataPointDao(): TripDataPointDao
    abstract fun tripStatsDao(): TripStatsDao
    abstract fun tripSegmentDao(): TripSegmentDao

    companion object {
        private const val TAG = "BydStatsDatabase"
        private const val DB_NAME = "byd_stats_database"

        @Volatile
        private var INSTANCE: BydStatsDatabase? = null
        // }

        fun getDatabase(context: Context): BydStatsDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BydStatsDatabase::class.java,
                    DB_NAME
                )
                    // .fallbackToDestructiveMigration()
                    // .fallbackToDestructiveMigrationOnDowngrade()
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
        /**
         * Returns the backup directory in external Download/BydTripStats.
         * This location survives app uninstalls, unlike internal filesDir.
         */
        // ── Migration template — copy for every future schema change ─────────
        // val MIGRATION_X_Y = object : Migration(X, Y) {
        //     override fun migrate(database: SupportSQLiteDatabase) {
        //         database.execSQL("ALTER TABLE trip_data_points ADD COLUMN newField REAL NOT NULL DEFAULT 0")
        //     }
        // }
        // Add to getDatabase: .addMigrations(MIGRATION_X_Y)

        fun getBackupDir(): File =
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "BydTripStats"
            )

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
                // Save to Download/BydTripStats — survives uninstalls
                val backupDir = getBackupDir().also { it.mkdirs() }
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
            // Primary: Download/BydTripStats (persists across uninstalls)
            val externalDir = getBackupDir()
            // Legacy: internal filesDir/db_backups (pre-existing installs)
            val internalDir = File(context.filesDir, "db_backups")

            return listOf(externalDir, internalDir)
                .filter { it.exists() }
                .flatMap { dir ->
                    dir.listFiles()
                        ?.filter { it.name.endsWith(".db") }
                        .orEmpty()
                        .toList()
                }
                .distinctBy { it.name }
                .sortedByDescending { it.lastModified() }
        }
    }
}