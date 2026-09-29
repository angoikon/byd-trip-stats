package com.byd.tripstats.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.byd.tripstats.data.backup.DbSnapshot
import com.byd.tripstats.data.local.BydStatsDatabase
import java.util.concurrent.TimeUnit

/**
 * Runs once per week in the background to perform **lossless** database
 * compaction only:
 *
 *  1. Checkpoint the WAL file so the main .db is fully self-consistent
 *  2. VACUUM the database to reclaim space freed by deleted trips / manual trims
 *
 * Data-point thinning is deliberately NOT done here. Thinning permanently deletes
 * telemetry samples, so it is left entirely to the user via the manual "Trim
 * database" action (DatabaseTrimmer). This job never destroys data — it only
 * compacts what is already there.
 *
 * The worker is registered as a unique periodic job ("db_maintenance") by
 * BydStatsApplication on first launch. Using KEEP policy means a second
 * registration (e.g. after an app update) leaves the existing schedule intact.
 *
 * Why silent / no user interaction:
 *   Checkpoint + VACUUM are purely mechanical compaction steps — the user has no
 *   meaningful choice to make, and they never alter trip data.
 */
class DatabaseMaintenanceWorker(
    context: Context,
    params : WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting weekly database maintenance")
        return try {
            // Never alongside a backup: both steps rewrite the main file, which would tear a
            // copy that is being read from it.
            DbSnapshot.exclusive {
                val db = BydStatsDatabase.getDatabase(applicationContext)

                // ── Step 1: WAL checkpoint ────────────────────────────────────
                // Forces all WAL frames into the main database file so the subsequent
                // VACUUM operates on a fully consolidated file. Also ensures any backup
                // taken after maintenance is self-contained without the -wal sidecar.
                // TRUNCATE resets the WAL file to zero bytes after checkpointing,
                // reclaiming the disk space the WAL occupies between vacuums.
                db.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
                Log.i(TAG, "WAL checkpoint complete")

                // ── Step 2: VACUUM ────────────────────────────────────────────
                // SQLite does not reclaim freed pages automatically. After users delete
                // trips (or run a manual Trim), the file stays the same size until VACUUM
                // compacts it. On a 1-year database this can recover 20-40 MB.
                db.openHelper.writableDatabase.execSQL("VACUUM")
                Log.i(TAG, "VACUUM complete")
            }

            // NOTE: automatic data-point thinning was intentionally removed. Thinning
            // is irreversible and is now exclusively user-initiated via the manual
            // "Trim database" action (DatabaseTrimmer). This weekly job only performs
            // lossless compaction (checkpoint + VACUUM).

            Log.i(TAG, "Weekly maintenance finished successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Maintenance failed", e)
            // Retry once; if the second attempt also fails, give up until next month.
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG       = "DBMaintenanceWorker"
        private const val WORK_NAME = "db_maintenance"

        /**
         * Enqueues the monthly maintenance job.
         * Safe to call on every app launch — KEEP policy means only one instance
         * ever exists at a time, and an existing schedule is never reset.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DatabaseMaintenanceWorker>(
                repeatInterval          = 7,
                repeatIntervalTimeUnit  = TimeUnit.DAYS
            )
                .setConstraints(
                    Constraints.Builder()
                        // Don't require charging — the IVI has no separate charging
                        // state that maps cleanly to a car infotainment context.
                        // The job is lightweight enough to run any time the app is active.
                        .setRequiresBatteryNotLow(false)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )

            Log.i(TAG, "Weekly maintenance job scheduled (KEEP policy)")
        }
    }
}