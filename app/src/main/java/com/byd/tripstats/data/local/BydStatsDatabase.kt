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
import com.byd.tripstats.data.local.dao.ChargingSessionDao
import com.byd.tripstats.data.local.dao.TagDao
import com.byd.tripstats.data.local.entity.TripDataPointEntity
import com.byd.tripstats.data.local.entity.TripEntity
import com.byd.tripstats.data.local.entity.TripStatsEntity
import com.byd.tripstats.data.local.entity.TripSegmentEntity
import com.byd.tripstats.data.local.entity.ChargingSessionEntity
import com.byd.tripstats.data.local.entity.ChargingDataPointEntity
import com.byd.tripstats.data.local.entity.TagEntity
import com.byd.tripstats.data.local.entity.TripTagCrossRef
import com.byd.tripstats.util.BackupNaming
import android.os.Environment
import java.io.File
import java.io.IOException

@Database(
    entities = [
        TripEntity::class,
        TripDataPointEntity::class,
        TripStatsEntity::class,
        TripSegmentEntity::class,
        ChargingSessionEntity::class,
        ChargingDataPointEntity::class,
        TagEntity::class,
        TripTagCrossRef::class
    ],
    version = 13,
    // Schemas are exported to app/schemas/ and committed — see the ksp { } block in
    // app/build.gradle.kts for why.
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class BydStatsDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun tripDataPointDao(): TripDataPointDao
    abstract fun tripStatsDao(): TripStatsDao
    abstract fun tripSegmentDao(): TripSegmentDao
    abstract fun chargingSessionDao(): ChargingSessionDao
    abstract fun tagDao(): TagDao

    companion object {
        private const val TAG = "BydStatsDatabase"
        private const val DB_NAME = "byd_stats_database"

        @Volatile
        private var INSTANCE: BydStatsDatabase? = null
        // }

        fun getDatabase(context: Context): BydStatsDatabase {
            return INSTANCE ?: synchronized(this) {
                val appCtx = context.applicationContext
                // Safety guard: if running under the test package, refuse to open
                // the real on-disk database. Tests must inject an in-memory DB via
                // reflection before calling getDatabase(). If they forget, we crash
                // loudly here rather than silently corrupting production data.
                check(appCtx.packageName != "com.byd.tripstats.test") {
                    "getDatabase() called from test package without injecting " +
                    "an in-memory DB first. Set BydStatsDatabase.INSTANCE before " +
                    "calling getDatabase() in your test setUp()."
                }
                val instance = Room.databaseBuilder(
                    appCtx,
                    BydStatsDatabase::class.java,
                    DB_NAME
                )
                    // .fallbackToDestructiveMigration()
                    // .fallbackToDestructiveMigrationOnDowngrade()
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                        MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                        MIGRATION_11_12, MIGRATION_12_13
                    )
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

        // ── Migration 1 → 2 ──────────────────────────────────────────────────
        // Adds:
        //   • 5 new columns to trip_data_points (tyre temps + socPanel)
        //   • charging_sessions table
        //   • charging_data_points table
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // New trip_data_points columns
                db.execSQL("ALTER TABLE trip_data_points ADD COLUMN socPanel INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE trip_data_points ADD COLUMN tyreTempLF INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE trip_data_points ADD COLUMN tyreTempRF INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE trip_data_points ADD COLUMN tyreTempLR INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE trip_data_points ADD COLUMN tyreTempRR INTEGER NOT NULL DEFAULT 0")

                // Charging sessions table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS charging_sessions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        startTime INTEGER NOT NULL,
                        endTime INTEGER,
                        socStart REAL NOT NULL,
                        socEnd REAL,
                        kwhAdded REAL,
                        peakKw REAL NOT NULL DEFAULT 0,
                        avgKw REAL NOT NULL DEFAULT 0,
                        batteryTempStart REAL NOT NULL DEFAULT 0,
                        batteryTempEnd REAL,
                        voltageStart INTEGER NOT NULL DEFAULT 0,
                        voltageEnd INTEGER,
                        batteryKwh REAL NOT NULL DEFAULT 0,
                        carConfigId TEXT NOT NULL DEFAULT '',
                        isActive INTEGER NOT NULL DEFAULT 1
                    )
                """.trimIndent())

                // Charging data points table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS charging_data_points (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sessionId INTEGER NOT NULL,
                        timestamp INTEGER NOT NULL,
                        soc REAL NOT NULL,
                        socPanel INTEGER NOT NULL DEFAULT 0,
                        chargingPower REAL NOT NULL,
                        batteryTotalVoltage INTEGER NOT NULL DEFAULT 0,
                        battery12vVoltage REAL NOT NULL DEFAULT 0,
                        batteryTempAvg REAL NOT NULL DEFAULT 0,
                        batteryCellTempMin INTEGER NOT NULL DEFAULT 0,
                        batteryCellTempMax INTEGER NOT NULL DEFAULT 0,
                        batteryCellVoltageMin REAL NOT NULL DEFAULT 0,
                        batteryCellVoltageMax REAL NOT NULL DEFAULT 0,
                        FOREIGN KEY(sessionId) REFERENCES charging_sessions(id) ON DELETE CASCADE
                    )
                """.trimIndent())

                db.execSQL("CREATE INDEX IF NOT EXISTS index_charging_data_points_sessionId ON charging_data_points(sessionId)")
            }
        }

        // ── Migration 2 → 3 ──────────────────────────────────────────────────
        // Adds raw JSON payload capture to charging_data_points.
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE charging_data_points ADD COLUMN rawJson TEXT NOT NULL DEFAULT '{}' ")
            }
        }

        // ── Migration 3 → 4 ──────────────────────────────────────────────────
        // Adds offStateDurationMs to trips — the cumulative wallclock time spent
        // with the trip open but the car off (between-segment gaps + trailing
        // engine-off-resume timeout). Used by TripEntity.duration to expose
        // active driving time. Legacy rows default to 0; a one-shot backfill in
        // TripRepository.backfillOffStateDuration computes the correct value for
        // existing trips on first launch after the upgrade.
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN offStateDurationMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        // ── Migration 4 → 5 ──────────────────────────────────────────────────
        // Adds panel-SoC columns to trips so the trip history / detail screens
        // can show the instrument-panel SoC when the user has selected that
        // source (useful on PHEVs where BMS SoC is typically 0).
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN startSocPanel REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE trips ADD COLUMN endSocPanel REAL")
            }
        }

        // ── Migration 5 → 6 ──────────────────────────────────────────────────
        // Adds panel-SoC columns to charging_sessions so the charging history
        // and detail screens respect the user's SoC source preference (Panel /
        // BMS), matching the behaviour added to trip history in migration 4→5.
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE charging_sessions ADD COLUMN socStartPanel REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE charging_sessions ADD COLUMN socEndPanel REAL")
            }
        }

        // ── Migration 6 → 7 ──────────────────────────────────────────────────
        // Adds isFavourite to trips and charging_sessions. Favourited rows are
        // exempt from automatic point-thinning and the manual DatabaseTrimmer,
        // preserving their full data-point density and rawJson diagnostics.
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN isFavourite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE charging_sessions ADD COLUMN isFavourite INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v8 (2.9.1): precise SoH (statistic-feature float) promoted from rawJson to its own
        // column so the degradation chart/report keep the decimal the dashboard shows. Old rows
        // stay 0 and fall back to the integer `soh` in getAvgSohPerTrip.
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trip_data_points ADD COLUMN sohPrecise REAL NOT NULL DEFAULT 0")
            }
        }

        // v9 (2.10.0): trip tagging. Adds a `tags` table (reusable coloured labels)
        // and a `trip_tags` many-to-many link table. Column shapes / index names
        // match what Room generates for TagEntity and TripTagCrossRef so the runtime
        // schema-identity check passes. No data is touched on existing rows.
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tags` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`colorIndex` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_name` ON `tags` (`name`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `trip_tags` (" +
                        "`tripId` INTEGER NOT NULL, " +
                        "`tagId` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`tripId`, `tagId`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_tags_tagId` ON `trip_tags` (`tagId`)")
            }
        }

        // v10: persist the BMS remaining-EV-energy reading (battery_remain_power_ev) per
        // data point so restoreTripState can reproduce the live EV-range projection exactly
        // for PHEVs instead of falling back to capacity × SoC (which overstates EV energy
        // near the charge-sustaining floor). Nullable — old rows and BEV/non-reporting
        // firmwares stay NULL and transparently fall back to the SoC product, as before.
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trip_data_points ADD COLUMN batteryRemainPowerEV REAL")
            }
        }

        // v11: cost & distance-between-charges.
        //   • charging_sessions.startOdometer — odometer (km) at plug-in, the anchor for
        //     "distance driven since the previous charge". Nullable; legacy rows stay NULL
        //     and the UI derives the value from the nearest trip's odometer.
        //   • charging_sessions.pricePerKwh — user-set price for that charge (currency/kWh);
        //     NULL = use the global tariff, 0.0 = free.
        // Both nullable — no DEFAULT — so existing rows transparently fall back to the tariff.
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE charging_sessions ADD COLUMN startOdometer REAL")
                db.execSQL("ALTER TABLE charging_sessions ADD COLUMN pricePerKwh REAL")
            }
        }

        // v12: drops trips.pricePerKwhOverride, which existed only on pre-release v11
        // dev databases (trip rates derive purely from charges + tariff now — see
        // CostAttribution). The version bump is what actually matters: removing an
        // entity field changes Room's schema identity hash, and without a bump Room
        // finds version 11 == 11, runs no migration, and fails the identity check with
        // "Room cannot verify the data integrity" on any pre-existing v11 database.
        //
        // Conditional because pre-release v11 DBs exist in both shapes (some were
        // hand-patched with ALTER TABLE DROP COLUMN before this migration was written).
        // Rebuild rather than DROP COLUMN: the head units run Android 10 / SQLite 3.22,
        // and DROP COLUMN needs 3.35+. `trips` holds one row per trip — the bulk of the
        // DB is trip_data_points — so the copy is cheap even on a 600 MB database.
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val hasColumn = db.query("PRAGMA table_info(`trips`)").use { c ->
                    val nameIdx = c.getColumnIndex("name")
                    generateSequence { if (c.moveToNext()) c.getString(nameIdx) else null }
                        .any { it == "pricePerKwhOverride" }
                }
                if (!hasColumn) return

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `trips_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`startTime` INTEGER NOT NULL, `endTime` INTEGER, " +
                        "`startOdometer` REAL NOT NULL, `endOdometer` REAL, " +
                        "`startSoc` REAL NOT NULL, `endSoc` REAL, " +
                        "`startSocPanel` REAL NOT NULL, `endSocPanel` REAL, " +
                        "`startTotalDischarge` REAL NOT NULL, `endTotalDischarge` REAL, " +
                        "`isActive` INTEGER NOT NULL, `isManual` INTEGER NOT NULL, " +
                        "`maxSpeed` REAL NOT NULL, `maxPower` REAL NOT NULL, " +
                        "`maxRegenPower` REAL NOT NULL, `avgBatteryTemp` REAL NOT NULL, " +
                        "`minSoc` REAL NOT NULL, `maxBatteryCellTemp` INTEGER NOT NULL, " +
                        "`minBatteryCellTemp` INTEGER NOT NULL, " +
                        "`offStateDurationMs` INTEGER NOT NULL, `isFavourite` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO `trips_new` SELECT `id`, `startTime`, `endTime`, " +
                        "`startOdometer`, `endOdometer`, `startSoc`, `endSoc`, " +
                        "`startSocPanel`, `endSocPanel`, `startTotalDischarge`, " +
                        "`endTotalDischarge`, `isActive`, `isManual`, `maxSpeed`, " +
                        "`maxPower`, `maxRegenPower`, `avgBatteryTemp`, `minSoc`, " +
                        "`maxBatteryCellTemp`, `minBatteryCellTemp`, " +
                        "`offStateDurationMs`, `isFavourite` FROM `trips`"
                )
                db.execSQL("DROP TABLE `trips`")
                db.execSQL("ALTER TABLE `trips_new` RENAME TO `trips`")
            }
        }

        /** Column names of [table], for migrations that must tolerate divergent schemas. */
        private fun SupportSQLiteDatabase.columnsOf(table: String): Set<String> =
            query("PRAGMA table_info(`$table`)").use { c ->
                val nameIdx = c.getColumnIndex("name")
                generateSequence { if (c.moveToNext()) c.getString(nameIdx) else null }.toSet()
            }

        // v13: reconciles pre-release v12 databases that are missing charging_sessions
        // columns. `startOdometer` and `pricePerKwh` were both added to MIGRATION_10_11
        // in place, during the 2.15.0 beta — so a device that ran 10→11 on an earlier
        // beta got only the columns that existed at the time, then 11→12 (which only
        // touches `trips`, and early-returns) left it on version 12 with an incomplete
        // charging_sessions. Room then sees 12 == 12, runs no migration, and every
        // ChargingSessionDao query dies with "Room cannot verify the data integrity"
        // — an unrecoverable crash loop, since nothing re-runs for the same version.
        //
        // The bump to 13 is what makes this reachable at all. Adding the columns to
        // MIGRATION_10_11 (again) or correcting v12 in place cannot help a database
        // that already reports version 12.
        //
        // Entities are unchanged, so 13's identity hash equals 12's: databases already
        // on a correct v12 migrate as a no-op, divergent ones converge. Column-by-column
        // rather than blanket ALTERs because a correct v12 must not fail on "duplicate
        // column name" — and unlike DROP COLUMN, ADD COLUMN works on SQLite 3.22.
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val existing = db.columnsOf("charging_sessions")
                // REAL/nullable matches the entity: `startOdometer: Double?`, `pricePerKwh: Double?`.
                mapOf(
                    "startOdometer" to "REAL",
                    "pricePerKwh" to "REAL",
                ).forEach { (column, type) ->
                    if (column !in existing) {
                        db.execSQL("ALTER TABLE `charging_sessions` ADD COLUMN `$column` $type")
                        Log.i(TAG, "MIGRATION_12_13: added missing charging_sessions.$column")
                    }
                }
            }
        }

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
                val backupFile = File(backupDir, BackupNaming.fileName(prefix = "${DB_NAME}_backup"))
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