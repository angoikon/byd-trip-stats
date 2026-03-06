package com.byd.tripstats.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.byd.tripstats.data.local.BydStatsDatabase
import com.byd.tripstats.data.repository.TripRepository
import java.util.concurrent.TimeUnit

/**
 * Runs once per month in the background to:
 *
 *  1. Checkpoint the WAL file so the main .db is fully self-consistent
 *  2. VACUUM the database to reclaim space freed by deleted trips/points
 *  3. Thin raw data points for trips older than 3 months — keeps one point
 *     every 30 seconds instead of the full 1-10 second resolution
 *
 * The worker is registered as a unique periodic job ("db_maintenance") by
 * BydStatsApplication on first launch. Using KEEP policy means a second
 * registration (e.g. after an app update) leaves the existing schedule intact.
 *
 * Why silent / no user interaction:
 *   VACUUM is a purely mechanical compaction step — the user has no meaningful
 *   choice to make. Surfacing it as a UI option adds complexity for zero benefit.
 *   The thinning only removes raw point density on old trips; trip-level stats
 *   (distance, energy, efficiency) live in TripEntity/TripStatsEntity and are
 *   completely unaffected.
 */
class DatabaseMaintenanceWorker(
    context: Context,
    params : WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting monthly database maintenance")
        return try {
            val db = BydStatsDatabase.getDatabase(applicationContext)

            // ── Step 1: WAL checkpoint ────────────────────────────────────────
            // Forces all WAL frames into the main database file so the subsequent
            // VACUUM operates on a fully consolidated file. Also ensures any backup
            // taken after maintenance is self-contained without the -wal sidecar.
            db.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(FULL)")
            Log.i(TAG, "WAL checkpoint complete")

            // ── Step 2: VACUUM ────────────────────────────────────────────────
            // SQLite does not reclaim freed pages automatically. After users delete
            // trips, the file stays the same size until VACUUM compacts it.
            // On a 1-year database this can recover 20-40 MB.
            db.openHelper.writableDatabase.execSQL("VACUUM")
            Log.i(TAG, "VACUUM complete")

            // ── Step 3: Thin old data points ──────────────────────────────────
            // For trips older than 3 months, keep 1 point per 30 s instead of
            // the recorded 1-10 s density. Trip stats are unaffected.
            val repo = TripRepository.getInstance(applicationContext)
            repo.thinOldDataPoints(olderThanMonths = 3, keepEverySeconds = 30)

            Log.i(TAG, "Monthly maintenance finished successfully")
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
                repeatInterval          = 30,
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

            Log.i(TAG, "Monthly maintenance job scheduled (KEEP policy)")
        }
    }
}
