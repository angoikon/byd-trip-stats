package com.byd.tripstats.data.backup

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.byd.tripstats.data.backup.LocalBackupManager.Companion.DATABASE_NAME
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Periodic WorkManager task that sends a database backup to Telegram.
 *
 * Scheduled weekly by TelegramManager.scheduleWeeklyBackup() after the
 * user connects their private bot. Cancelled by TelegramManager.cancelWeeklyBackup()
 * when they disconnect.
 *
 * No dependency on MqttService or any foreground component — runs entirely
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

        val cfg = telegramManager.config
        if (cfg == null) {
            Log.w(TAG, "No Telegram config — skipping backup")
            return Result.failure()
        }

        return try {
            val dbFile = context.getDatabasePath(DATABASE_NAME)
            if (!dbFile.exists()) {
                Log.e(TAG, "Database file not found: ${dbFile.path}")
                return Result.retry()
            }

            // Flush WAL for a consistent snapshot
            flushWal(dbFile)

            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
            val fileName = "byd_stats_weekly_$timestamp.db"
            val tempFile = File(context.cacheDir, fileName)
            dbFile.copyTo(tempFile, overwrite = true)

            val caption = "BYD Trip Stats - Auto Backup\n$timestamp"
            telegramManager.sendFile(tempFile, caption)

            tempFile.delete()

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