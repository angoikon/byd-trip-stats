package com.byd.tripstats.data.local

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.byd.tripstats.util.DiagLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The framework open helper, with one change: a database SQLite reports as corrupt is moved
 * aside instead of deleted.
 *
 * The stock `onCorruption` deletes the whole file the first time any query touches a damaged
 * page. On 2026-09-28 that turned one broken tree (the tail of `charging_data_points`, left by a
 * torn backup that had been restored) into the loss of six months of trips: the next charging
 * session wrote to that tree, the file vanished mid-transaction, and Room created an empty
 * database on the next start. Everything outside the broken tree was still readable.
 *
 * The app still starts over with an empty database — there is nothing safe to keep running on —
 * but the damaged file survives as [DAMAGED_MARKER]-named siblings for recovery, and
 * `LocalBackupManager.exportDamagedDatabases` copies it to Download/BydTripStats where a user
 * can reach it.
 */
class QuarantineOpenHelperFactory(
    private val context: Context,
    private val delegate: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory(),
) : SupportSQLiteOpenHelper.Factory {

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val inner = configuration.callback
        val callback = object : SupportSQLiteOpenHelper.Callback(inner.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = inner.onConfigure(db)
            override fun onCreate(db: SupportSQLiteDatabase) = inner.onCreate(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                inner.onUpgrade(db, oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                inner.onDowngrade(db, oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = inner.onOpen(db)
            override fun onCorruption(db: SupportSQLiteDatabase) = quarantine(db)
        }
        return delegate.create(
            SupportSQLiteOpenHelper.Configuration(
                configuration.context,
                configuration.name,
                callback,
                configuration.useNoBackupDirectory,
                configuration.allowDataLossOnRecovery,
            )
        )
    }

    private fun quarantine(db: SupportSQLiteDatabase) {
        val path = db.path
        runCatching { if (db.isOpen) db.close() }
        if (path == null || path == ":memory:") return

        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val moved = listOf("", "-wal", "-shm", "-journal").mapNotNull { suffix ->
            val src = File(path + suffix)
            if (!src.exists()) return@mapNotNull null
            val dst = File("$path$DAMAGED_MARKER$stamp$suffix")
            if (src.renameTo(dst)) dst.name else "${src.name} (rename failed)"
        }
        val msg = "database reported corrupt — moved aside instead of deleted: ${moved.joinToString()}"
        Log.e(TAG, msg)
        DiagLog.event(context, TAG, msg)
    }

    companion object {
        private const val TAG = "DbQuarantine"

        /** Inserted between the database name and the timestamp of a quarantined copy. */
        const val DAMAGED_MARKER = ".damaged-"
    }
}
