package com.byd.tripstats.data.backup

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.byd.tripstats.MainActivity
import com.byd.tripstats.R
import com.byd.tripstats.data.backup.LocalBackupManager.Companion.DATABASE_NAME
import com.byd.tripstats.util.DiagLog
import com.byd.tripstats.util.ServiceIdleState
import java.io.File

/**
 * Checks the live database once, after the update that ships this check.
 *
 * Until then a restore installed whatever it was given after looking at 16 bytes, so a torn
 * backup could be restored and look fine: its damage only surfaces when the app next writes to
 * the broken tree, and at that point the database has to be moved aside and the app starts over
 * with an empty one (see [com.byd.tripstats.data.local.QuarantineOpenHelperFactory]). This finds
 * such damage while everything is still readable — saving a copy and telling the user — rather
 * than waiting for the write that hits it.
 */
object DbHealthCheck {

    private const val TAG = "DbHealthCheck"
    private const val PREFS = "db_health"
    private const val KEY_DONE = "integrity_check_revision"

    /** Raise to have every install check its database once more after the next update. */
    private const val CHECK_REVISION = 1

    private const val CHANNEL_ID = "db_health_channel"
    private const val NOTIFICATION_ID = 1003

    suspend fun runOnceAfterUpdate(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_DONE, 0) >= CHECK_REVISION) return
        // Deep Sleep stays dark; the next ordinary start runs it instead.
        if (ServiceIdleState.isStayingIdle(context)) return

        val dbFile = context.getDatabasePath(DATABASE_NAME)
        if (dbFile.exists()) {
            val started = SystemClock.elapsedRealtime()
            // Serialised with backups and restore, so the file can't be swapped mid-check.
            val problems = DbSnapshot.exclusive { quickCheck(dbFile) }
            val secs = (SystemClock.elapsedRealtime() - started) / 1000
            val sizeMb = dbFile.length() / 1_048_576
            if (problems == listOf("ok")) {
                DiagLog.event(context, TAG, "database check: ok (${sizeMb} MB, ${secs}s)")
            } else {
                DiagLog.event(context, TAG, "database check: DAMAGED (${sizeMb} MB, ${secs}s): ${problems.take(3)}")
                val copy = runCatching { LocalBackupManager.getInstance(context).saveDamagedCopy() }
                    .onFailure { DiagLog.event(context, TAG, "damaged-database copy failed: ${it.message}") }
                    .getOrNull()
                notifyDamage(context, copy)
            }
        }
        // Recorded once the check has run to the end: a start cut short (DiLink-5 closes the
        // app at every switch-off) simply checks again next time.
        prefs.edit().putInt(KEY_DONE, CHECK_REVISION).apply()
    }

    /**
     * `PRAGMA quick_check` over its own connection. The no-op error handler matters twice: the
     * framework default would delete the file on the first corrupt page, and Room's handler
     * would move the live database aside — this check only looks.
     */
    private fun quickCheck(dbFile: File): List<String> = try {
        val db = SQLiteDatabase.openDatabase(
            dbFile.path, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING or
                SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            DatabaseErrorHandler { }
        )
        try {
            db.rawQuery("PRAGMA quick_check", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
        } finally {
            db.close()
        }
    } catch (e: Exception) {
        // A page so broken the check itself can't get past it is damage too.
        listOf("check failed: ${e.message}")
    }

    private fun notifyDamage(context: Context, savedCopy: String?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Database damaged but POST_NOTIFICATIONS not granted — diag.log only")
            return
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val title = context.getString(R.string.db_damage_title)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, title, NotificationManager.IMPORTANCE_HIGH)
        )
        val body = buildString {
            append(context.getString(R.string.db_damage_text))
            if (savedCopy != null) append("\n\n").append(context.getString(R.string.db_damage_copy_saved, savedCopy))
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
