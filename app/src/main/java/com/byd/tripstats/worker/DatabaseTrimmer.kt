package com.byd.tripstats.worker

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import com.byd.tripstats.data.local.BydStatsDatabase
import com.byd.tripstats.sdk.DiLink5Platform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * User-initiated database trim.
 *
 * Phases:
 *   A — strip redundant fields from rawJson (every row).
 *   B — clear rawJson to '{}' for trip/charging points older than 45 days.
 *   C — downsample trip_data_points older than 60 days to 1 row/minute.
 *   D — delete charging_data_points belonging to AC sessions (peakKw < 25 kW)
 *       older than 45 days; DC sessions are preserved entirely.
 *   E — wal_checkpoint + VACUUM (best-effort).
 *
 * The user triggers this from the Local Backup screen; the result (timestamp +
 * summary) is persisted to SharedPreferences so the UI can show "Last trimmed
 * on YYYY-MM-DD" across sessions.
 */
object DatabaseTrimmer {

    sealed class State {
        object Idle : State()
        data class InProgress(val phase: String) : State()
        data class Success(
            val timestamp: Long,
            val summary: String,
            val restartRequired: Boolean = false,
        ) : State()
        data class Error(val message: String) : State()
    }

    data class LastRun(val timestamp: Long, val summary: String)

    private const val TAG               = "DatabaseTrimmer"
    private const val PREFS_NAME        = "bydstats_maintenance"
    private const val KEY_LAST_RUN_TS   = "trim_last_run_ts"
    private const val KEY_LAST_SUMMARY  = "trim_last_summary"
    private const val BATCH_SIZE        = 200

    private const val DAY_MS                  = 24L * 60 * 60 * 1000
    private const val RAWJSON_DROP_AFTER_DAYS = 45
    private const val DOWNSAMPLE_AFTER_DAYS   = 60
    private const val AC_PEAK_KW_THRESHOLD    = 25.0
    private const val DOWNSAMPLE_BUCKET_MS    = 60_000L

    private val json = Json { ignoreUnknownKeys = true }

    private val REDUNDANT_KEYS = setOf(
        "soc", "speed", "gear", "odometer", "engine_power", "total_discharge",
        "battery_12v_voltage", "battery_cell_voltage_max", "battery_cell_voltage_min",
        "soh", "battery_total_voltage", "engine_speed_front", "engine_speed_rear",
        "location_latitude", "location_longitude", "location_altitude",
        "electric_driving_range_km",
        "tyre_pressure_left_front_psi", "tyre_pressure_right_front_psi",
        "tyre_pressure_left_rear_psi", "tyre_pressure_right_rear_psi",
        "tyre_temperature_left_front_c", "tyre_temperature_right_front_c",
        "tyre_temperature_left_rear_c", "tyre_temperature_right_rear_c",
        "soc_panel",
    )

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun getLastRun(context: Context): LastRun? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ts = prefs.getLong(KEY_LAST_RUN_TS, 0L)
        val summary = prefs.getString(KEY_LAST_SUMMARY, null)
        return if (ts > 0L && summary != null) LastRun(ts, summary) else null
    }

    fun resetState() {
        _state.value = State.Idle
    }

    /**
     * Runs all phases sequentially. Caller must invoke from an IO coroutine.
     * Reentrancy guard: if already in progress, returns immediately.
     */
    suspend fun trim(context: Context) {
        if (_state.value is State.InProgress) {
            Log.w(TAG, "Trim already in progress — ignoring duplicate call")
            return
        }
        _state.value = State.InProgress("Preparing…")
        Log.i(TAG, "=== DB trim starting ===")

        try {
            val db = BydStatsDatabase.getDatabase(context)
            val sqLiteDb = db.openHelper.writableDatabase
            val now = System.currentTimeMillis()
            val cutoffRawJson = now - RAWJSON_DROP_AFTER_DAYS * DAY_MS
            val cutoffSample  = now - DOWNSAMPLE_AFTER_DAYS * DAY_MS

            _state.value = State.InProgress("Stripping redundant fields…")
            val tripRewritten = compactTable(sqLiteDb, "trip_data_points")
            val chgRewritten  = compactTable(sqLiteDb, "charging_data_points")
            Log.i(TAG, "Phase A — rewritten trip=$tripRewritten chg=$chgRewritten")

            _state.value = State.InProgress("Clearing old diagnostic data…")
            val tripCleared = clearOldRawJson(
                sqLiteDb, "trip_data_points", cutoffRawJson,
                "AND tripId NOT IN (SELECT id FROM trips WHERE isFavourite = 1)"
            )
            val chgCleared  = clearOldRawJson(
                sqLiteDb, "charging_data_points", cutoffRawJson,
                "AND sessionId NOT IN (SELECT id FROM charging_sessions WHERE isFavourite = 1)"
            )
            Log.i(TAG, "Phase B — rawJson cleared trip=$tripCleared chg=$chgCleared")

            _state.value = State.InProgress("Downsampling old trips…")
            val tripDownsampled = downsampleTripPoints(sqLiteDb, cutoffSample)
            Log.i(TAG, "Phase C — downsampled deleted=$tripDownsampled")

            _state.value = State.InProgress("Trimming AC charging history…")
            val chgAcDeleted = deleteOldAcChargingPoints(sqLiteDb, cutoffRawJson)
            Log.i(TAG, "Phase D — ac chg points deleted=$chgAcDeleted")

            // ── Phase E: VACUUM via raw SQLite after closing Room ───────────
            // Room holds the database open through its connection pool; VACUUM
            // requires exclusive access, so we must:
            //   1. Stop the telemetry service so it stops writing.
            //   2. Close Room.
            //   3. Open a raw SQLiteDatabase to run PRAGMA wal_checkpoint + VACUUM.
            //   4. Close the raw connection.
            //   5. Signal restartRequired so the UI restarts the process; Room
            //      will reopen cleanly on next launch.
            var vacuumOk = false
            var vacuumError: String? = null
            // DiLink-5: NEVER run the VACUUM path here. It closes Room and signals a
            // process self-restart, and an app-initiated kill + auto-relaunch races the
            // bydauto SDK classloader injection — which wedges the OEM com.byd.data.collect
            // service and boot-loops the head unit (ADAS/cluster fault; the 2.13.0 incident).
            // Phases A–D still trim the rows in place; we just don't reclaim the freed pages.
            val skipVacuum = DiLink5Platform.isDiLink5
            if (skipVacuum) {
                Log.i(TAG, "Phase E — VACUUM skipped on DiLink-5 (self-restart hazard); rows trimmed in place")
            } else {
                _state.value = State.InProgress("Stopping service & reclaiming space (VACUUM)…")
                try {
                    com.byd.tripstats.service.VehicleTelemetryService.stop(context)
                    // Give in-flight writes a moment to drain.
                    kotlinx.coroutines.delay(500)
                    BydStatsDatabase.closeDatabase()

                    val dbFile = context.getDatabasePath("byd_stats_database")
                    val raw = android.database.sqlite.SQLiteDatabase.openDatabase(
                        dbFile.path, null,
                        android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
                    )
                    try {
                        raw.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                        raw.execSQL("VACUUM")
                        vacuumOk = true
                        Log.i(TAG, "Phase E — VACUUM ok via raw SQLite")
                    } finally {
                        raw.close()
                    }
                } catch (ve: Exception) {
                    vacuumError = ve.message
                    Log.w(TAG, "VACUUM failed: ${ve.message}", ve)
                }
            }

            val favTrips    = countFavourites(sqLiteDb, "trips")
            val favSessions = countFavourites(sqLiteDb, "charging_sessions")

            val ts = System.currentTimeMillis()
            val summary = buildString {
                append("Stripped redundant fields from ${tripRewritten + chgRewritten} rows. ")
                append("Cleared diagnostic data for ${tripCleared + chgCleared} rows older than $RAWJSON_DROP_AFTER_DAYS days. ")
                append("Downsampled $tripDownsampled trip points older than $DOWNSAMPLE_AFTER_DAYS days. ")
                append("Deleted $chgAcDeleted AC charging points older than $RAWJSON_DROP_AFTER_DAYS days (DC sessions kept). ")
                if (favTrips > 0 || favSessions > 0) {
                    append("Skipped $favTrips favourited trip(s) and $favSessions favourited charging session(s) — kept at full detail. ")
                }
                append(
                    when {
                        vacuumOk   -> "Database vacuumed."
                        skipVacuum -> "Disk-reclaim (VACUUM) skipped on this device."
                        else       -> "VACUUM failed (${vacuumError ?: "unknown"})."
                    }
                )
            }

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putLong(KEY_LAST_RUN_TS, ts)
                .putString(KEY_LAST_SUMMARY, summary)
                .apply()

            _state.value = State.Success(ts, summary, restartRequired = vacuumOk)
            Log.i(TAG, "=== DB trim complete: $summary ===")
        } catch (e: Exception) {
            Log.e(TAG, "Trim failed", e)
            _state.value = State.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    // ── Phase A ──────────────────────────────────────────────────────────────
    private suspend fun compactTable(db: SupportSQLiteDatabase, table: String): Int {
        var lastId = 0L
        var rewritten = 0
        while (true) {
            val cursor = db.query(
                "SELECT id, rawJson FROM $table WHERE id > ? AND LENGTH(rawJson) > 2 ORDER BY id LIMIT $BATCH_SIZE",
                arrayOf(lastId.toString()),
            )
            if (!cursor.moveToFirst()) { cursor.close(); break }
            db.beginTransaction()
            try {
                do {
                    val id = cursor.getLong(0)
                    val rawJson = cursor.getString(1)
                    val stripped = stripRedundantKeys(rawJson)
                    if (stripped.length < rawJson.length) {
                        db.execSQL("UPDATE $table SET rawJson = ? WHERE id = ?", arrayOf(stripped, id))
                        rewritten++
                    }
                    lastId = id
                } while (cursor.moveToNext())
                db.setTransactionSuccessful()
            } finally {
                cursor.close()
                db.endTransaction()
            }
            yield()
        }
        return rewritten
    }

    private fun stripRedundantKeys(rawJson: String): String {
        if (rawJson.isBlank() || rawJson == "{}") return "{}"
        return runCatching {
            val obj = json.parseToJsonElement(rawJson).jsonObject
            JsonObject(obj.filterKeys { it !in REDUNDANT_KEYS }).toString()
        }.getOrDefault(rawJson)
    }

    // ── Phase B ──────────────────────────────────────────────────────────────
    // [favouriteExclusion] is an extra AND-clause excluding rows whose parent
    // trip / charging session is favourited, so their diagnostic rawJson is kept.
    private fun clearOldRawJson(
        db: SupportSQLiteDatabase,
        table: String,
        cutoff: Long,
        favouriteExclusion: String,
    ): Int {
        val countCursor = db.query(
            "SELECT COUNT(*) FROM $table WHERE timestamp < ? AND LENGTH(rawJson) > 2 $favouriteExclusion",
            arrayOf(cutoff.toString()),
        )
        val count = countCursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
        if (count == 0) return 0
        db.execSQL(
            "UPDATE $table SET rawJson = '{}' WHERE timestamp < ? AND LENGTH(rawJson) > 2 $favouriteExclusion",
            arrayOf<Any>(cutoff),
        )
        return count
    }

    // ── Phase C ──────────────────────────────────────────────────────────────
    // Favourited trips are excluded from the outer DELETE so their full point
    // density is preserved regardless of age.
    private fun downsampleTripPoints(db: SupportSQLiteDatabase, cutoff: Long): Int {
        val favClause = "AND tripId NOT IN (SELECT id FROM trips WHERE isFavourite = 1)"
        val countCursor = db.query(
            """
            SELECT COUNT(*) FROM trip_data_points
            WHERE timestamp < ?
              $favClause
              AND id NOT IN (
                SELECT MIN(id) FROM trip_data_points
                WHERE timestamp < ?
                GROUP BY tripId, (timestamp / $DOWNSAMPLE_BUCKET_MS)
              )
            """.trimIndent(),
            arrayOf(cutoff.toString(), cutoff.toString()),
        )
        val count = countCursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
        if (count == 0) return 0
        db.execSQL(
            """
            DELETE FROM trip_data_points
            WHERE timestamp < ?
              $favClause
              AND id NOT IN (
                SELECT MIN(id) FROM trip_data_points
                WHERE timestamp < ?
                GROUP BY tripId, (timestamp / $DOWNSAMPLE_BUCKET_MS)
              )
            """.trimIndent(),
            arrayOf<Any>(cutoff, cutoff),
        )
        return count
    }

    // Counts favourited rows in a parent table ("trips" / "charging_sessions"),
    // used purely for the user-facing summary note.
    private fun countFavourites(db: SupportSQLiteDatabase, table: String): Int {
        val cursor = db.query("SELECT COUNT(*) FROM $table WHERE isFavourite = 1")
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    // ── Phase D ──────────────────────────────────────────────────────────────
    private fun deleteOldAcChargingPoints(db: SupportSQLiteDatabase, cutoff: Long): Int {
        val countCursor = db.query(
            """
            SELECT COUNT(*) FROM charging_data_points
            WHERE sessionId IN (
                SELECT id FROM charging_sessions
                WHERE isActive = 0
                  AND isFavourite = 0
                  AND endTime IS NOT NULL
                  AND endTime < ?
                  AND peakKw > 0
                  AND peakKw < ?
            )
            """.trimIndent(),
            arrayOf(cutoff.toString(), AC_PEAK_KW_THRESHOLD.toString()),
        )
        val count = countCursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
        if (count == 0) return 0
        db.execSQL(
            """
            DELETE FROM charging_data_points
            WHERE sessionId IN (
                SELECT id FROM charging_sessions
                WHERE isActive = 0
                  AND isFavourite = 0
                  AND endTime IS NOT NULL
                  AND endTime < ?
                  AND peakKw > 0
                  AND peakKw < ?
            )
            """.trimIndent(),
            arrayOf<Any>(cutoff, AC_PEAK_KW_THRESHOLD),
        )
        return count
    }
}
