package com.byd.tripstats.data.backup

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.byd.tripstats.data.backup.LocalBackupManager.Companion.DATABASE_NAME
import com.byd.tripstats.util.BackupNaming
import com.byd.tripstats.util.DiagLog
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.Locale

/**
 * Periodic WorkManager task that sends a database backup to Telegram.
 *
 * Scheduled weekly by TelegramManager.scheduleWeeklyBackup() after the
 * user connects their private bot. Cancelled by TelegramManager.cancelWeeklyBackup()
 * when they disconnect.
 *
 * No dependency on the foreground telemetry service or any other foreground component — runs entirely
 * in the background, survives reboots via WorkManager's persistence.
 *
 * Every run leaves a "scheduled backup:" line in diag.log. Release builds strip Log, and nothing
 * else records a run: an archive over Telegram's cap went unsent for weeks with no trace.
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

        val dbFile = context.getDatabasePath(DATABASE_NAME)
        if (!dbFile.exists()) {
            Log.e(TAG, "Database file not found: ${dbFile.path}")
            diag("database file not found")
            return retryOrGiveUp()
        }
        diag("start (attempt ${runAttemptCount + 1}, database ${mb(dbFile.length())})")

        // An archive over the cap is thrown away after ~25 s of a full core, and the database only
        // grows between runs. While the last oversized result, scaled to today's file, is still
        // over, report it without compressing again; a trim or delete that brings the estimate
        // under the cap gets a real attempt.
        telegramManager.lastOversizedAutoBackup()?.let { (lastDbBytes, lastArchiveBytes) ->
            val dbBytes = dbFile.length()
            val estimate = scheduledBackupEstimate(dbBytes, lastDbBytes, lastArchiveBytes)
            if (estimate > TelegramManager.TELEGRAM_MAX_FILE_SIZE_BYTES) {
                val told = telegramManager.sendMessage(scheduledBackupOversizeMessage(estimate, estimated = true))
                diag(
                    "skipped without compressing — the last archive was ${mb(lastArchiveBytes)} from a " +
                        "${mb(lastDbBytes)} database, so ${mb(dbBytes)} makes about ${mb(estimate)}, " +
                        "over the limit; chat told=$told"
                )
                return Result.failure()
            }
        }

        val timestamp = BackupNaming.timestamp()

        // Compress locally first — it's the archive that has to fit Telegram's cap.
        val fileName = BackupNaming.fileName(
            prefix = "byd_stats_weekly",
            timestamp = timestamp,
            extension = BackupCodec.COMPRESSED_EXTENSION,
        )
        val tempFile = File(context.cacheDir, fileName)
        val startedMs = SystemClock.elapsedRealtime()
        fun took() = "${(SystemClock.elapsedRealtime() - startedMs) / 1000} s"
        try {
            // From a frozen snapshot: this runs whenever WorkManager likes, often mid-drive
            // or mid-charge, when the file is being written every few seconds. Android stops the
            // worker when the car leaves Wi-Fi or the app is closing; the compression stops with
            // it instead of finishing an archive that will never be sent.
            var dbBytes = 0L
            DbSnapshot.withFrozenFile(context) {
                dbBytes = it.length()
                BackupCodec.compress(it, tempFile) { isStopped }
            }

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
                telegramManager.recordOversizedAutoBackup(dbBytes, archiveSize)
                // The chat is where the user expects the backup, so that is where the skip is told.
                val told = telegramManager.sendMessage(scheduledBackupOversizeMessage(archiveSize, estimated = false))
                diag(
                    "archive ${mb(archiveSize)} from a ${mb(dbBytes)} database (compressed in ${took()}) " +
                        "is over the limit — not sent; chat told=$told"
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
            if (!telegramManager.sendFile(tempFile, caption)) {
                // Not a retry: that would compress again and, without the Wi-Fi-only setting,
                // upload up to 50 MB of mobile data again. The next periodic run tries.
                diag("upload of ${mb(archiveSize)} failed — ${failureReason(telegramManager)}")
                return Result.failure()
            }

            telegramManager.clearOversizedAutoBackup()
            // Record timestamp of last successful auto-backup
            telegramManager.recordAutoBackup(timestamp)
            diag("sent ${mb(archiveSize)} (compressed in ${took()})")

            Log.i(TAG, "Weekly Telegram backup complete")
            return Result.success()

        } catch (e: CancellationException) {
            diag("stopped by Android after ${took()} (left Wi-Fi, or the app is closing) — WorkManager runs it again")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Weekly Telegram backup failed", e)
            diag("failed: ${e.javaClass.simpleName}: ${e.message}")
            return retryOrGiveUp()
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Two retries with backoff, then the next periodic run. WorkManager sets no limit of its own,
     * and every attempt compresses the whole database.
     */
    private fun retryOrGiveUp(): Result = if (runAttemptCount < 2) Result.retry() else Result.failure()

    /** What sendFile left in the banner, minus the bot token an HTTP error's URL can carry. */
    private fun failureReason(telegram: TelegramManager): String {
        val message = (telegram.state.value as? TelegramManager.TelegramState.Error)?.message ?: "no reason given"
        val token = telegram.config.value?.token
        return if (token.isNullOrEmpty()) message else message.replace(token, "<token>")
    }

    private fun diag(message: String) = DiagLog.event(context, TAG, "scheduled backup: $message")
}

/**
 * The archive a [dbBytes] database would make now, scaled from the last scheduled backup that was
 * too big: [lastDbBytes] of database then compressed to [lastArchiveBytes].
 */
internal fun scheduledBackupEstimate(dbBytes: Long, lastDbBytes: Long, lastArchiveBytes: Long): Long =
    (dbBytes.toDouble() * lastArchiveBytes / lastDbBytes).toLong()

/** The chat message for a scheduled backup too big to send ([archiveBytes]), in Telegram HTML. */
internal fun scheduledBackupOversizeMessage(archiveBytes: Long, estimated: Boolean): String {
    val size = (if (estimated) "about " else "") + mb(archiveBytes)
    val limitMb = TelegramManager.TELEGRAM_MAX_FILE_SIZE_BYTES / (1024 * 1024)
    return "⚠️ <b>Scheduled backup not sent</b>\n" +
        "The compressed database is $size, over the $limitMb MB a bot can send to Telegram, " +
        "so this backup was skipped.\n\n" +
        "To get backups here again, trim the database or delete old trips in " +
        "Settings → App → Backup &amp; Restore. Backup Now there still saves a copy on the car."
}

/** Megabytes as Telegram and the cap count them (1024²), one decimal. */
private fun mb(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
