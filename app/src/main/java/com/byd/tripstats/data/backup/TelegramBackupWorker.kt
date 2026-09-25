package com.byd.tripstats.data.backup

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.byd.tripstats.data.backup.LocalBackupManager.Companion.DATABASE_NAME
import com.byd.tripstats.util.BackupNaming
import java.io.File

/**
 * Periodic WorkManager task that sends a database backup to Telegram.
 *
 * Scheduled weekly by TelegramManager.scheduleWeeklyBackup() after the
 * user connects their private bot. Cancelled by TelegramManager.cancelWeeklyBackup()
 * when they disconnect.
 *
 * No dependency on the foreground telemetry service or any other foreground component — runs entirely
 * in the background, survives reboots via WorkManager's persistence.
 */
class TelegramBackupWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "TelegramBackupWorker"
        const val WORK_NAME = "telegram_weekly_backup"
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting weekly Telegram backup…")

        val telegramManager = TelegramManager.getInstance(context)

        return try {
            val dbFile = context.getDatabasePath(DATABASE_NAME)
            if (!dbFile.exists()) {
                Log.e(TAG, "Database file not found: ${dbFile.path}")
                return Result.retry()
            }

            val timestamp = BackupNaming.timestamp()

            // Flush WAL for a consistent snapshot
            flushWal(dbFile)

            // Compress locally first — it's the archive that has to fit Telegram's cap.
            val fileName = BackupNaming.fileName(
                prefix = "byd_stats_weekly",
                timestamp = timestamp,
                extension = BackupCodec.COMPRESSED_EXTENSION,
            )
            val tempFile = File(context.cacheDir, fileName)
            try {
                BackupCodec.compress(dbFile, tempFile)

                // Pre-check against Telegram's 50 MB cap. Without this, every periodic run
                // would stream the whole file to api.telegram.org before the server replies
                // with "Request Entity Too Large" — silently burning the user's data plan on
                // a daily/weekly cadence. We mark this as failure (not retry) so WorkManager
                // doesn't immediately re-try with the same oversized file; the next periodic
                // tick will check again.
                val archiveSize = tempFile.length()
                if (archiveSize > TelegramManager.TELEGRAM_MAX_FILE_SIZE_BYTES) {
                    Log.w(
                        TAG,
                        "Skipping auto-backup: compressed DB is ${archiveSize / (1024 * 1024)} MB, " +
                            "above Telegram's 50 MB limit."
                    )
                    return Result.failure()
                }

                // Settings ride along with the database — and ONLY with it. They are sent after
                // the size check above, not before it: a scheduled run that delivers a 2 KB
                // settings file while the database it belongs to never left the car looks like
                // the weekly backup arrived when it didn't. Over the cap, the run sends nothing
                // and settings are exported by hand from Backup & Restore instead.
                LocalBackupManager.getInstance(context).sendSettingsToTelegram(telegramManager, timestamp)

                val caption = "BYD Trip Stats - Auto Backup\n$timestamp"
                telegramManager.sendFile(tempFile, caption)
            } finally {
                tempFile.delete()
            }

            // Record timestamp of last successful auto-backup
            telegramManager.recordAutoBackup(timestamp)

            Log.i(TAG, "Weekly Telegram backup complete")
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Weekly Telegram backup failed", e)
            // Retry up to WorkManager's default limit (3 times with backoff)
            Result.retry()
        }
    }

    private fun flushWal(dbFile: File) {
        try {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                dbFile.path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
            )
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            db.close()
        } catch (e: Exception) {
            Log.w(TAG, "WAL flush warning (non-fatal): ${e.message}")
        }
    }
}
