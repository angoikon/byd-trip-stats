package com.byd.tripstats.data.backup

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import com.byd.tripstats.data.backup.LocalBackupManager.Companion.DATABASE_NAME
import com.byd.tripstats.data.local.BydStatsDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Consistent whole-file reads of the live database, while the app keeps recording.
 *
 * A backup copies the `.db` file byte by byte, and in WAL mode the only thing that ever writes
 * that file is a checkpoint — which Room runs by itself after any commit once the WAL has grown
 * past its threshold. A checkpoint landing in the middle of a copy leaves pages from two moments
 * in the archive: on 2026-09-27 a backup taken while charging (a row every ~8 s) came out with
 * `charging_data_points` pointing at pages past the end of the file. Nothing noticed until that
 * backup was restored and the next charging session wrote to the broken tree.
 *
 * [withFrozenFile] turns auto-checkpointing off on Room's write connection for the length of the
 * copy, so every new commit stays in the WAL and the main file cannot change underneath the
 * reader. Recording carries on normally; the WAL just grows for those seconds and is folded back
 * in once the copy is done.
 */
object DbSnapshot {

    private const val TAG = "DbSnapshot"

    /** SQLite's compiled-in default, used only if the current value can't be read back. */
    private const val DEFAULT_AUTOCHECKPOINT = 1000

    private val lock = Mutex()

    /**
     * Serialises everything that reads or rewrites the database file as a whole: backups,
     * restore, the weekly VACUUM and the manual trim. A second checkpoint (another backup's
     * flush, a VACUUM) running during a copy would tear it just as badly as an automatic one.
     */
    suspend fun <T> exclusive(block: suspend () -> T): T = lock.withLock { block() }

    /**
     * Runs [block] with the live database file ([File] argument) frozen and as up to date as a
     * checkpoint can make it. [block] must only read the file.
     */
    suspend fun <T> withFrozenFile(context: Context, block: suspend (File) -> T): T = exclusive {
        val db = BydStatsDatabase.getDatabase(context).openHelper.writableDatabase
        val previous = setAutoCheckpoint(db, 0)
        try {
            checkpoint(db)
            block(context.getDatabasePath(DATABASE_NAME))
        } finally {
            runCatching { setAutoCheckpoint(db, previous) }
                .onFailure { Log.w(TAG, "Could not restore wal_autocheckpoint: ${it.message}") }
        }
    }

    /**
     * Sets `wal_autocheckpoint` on Room's write connection and returns the previous value.
     *
     * The transaction is what pins this thread to that connection: outside one, Android may run
     * a query on one of the pool's read connections, and the setting is per connection. Only the
     * write connection ever commits, so it is the only one whose commits can trigger a checkpoint.
     */
    private fun setAutoCheckpoint(db: SupportSQLiteDatabase, pages: Int): Int {
        db.beginTransaction()
        try {
            val previous = db.query("PRAGMA wal_autocheckpoint").use { c ->
                if (c.moveToFirst()) c.getInt(0) else DEFAULT_AUTOCHECKPOINT
            }
            db.query("PRAGMA wal_autocheckpoint=$pages").use { it.moveToFirst() }
            db.setTransactionSuccessful()
            return previous
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Folds the WAL into the main file so the copy is current. A reader mid-query makes the
     * checkpoint report busy; that only means the file is a little behind, never torn — a
     * checkpoint stops at a commit boundary — so after a few tries the copy goes ahead anyway.
     */
    private suspend fun checkpoint(db: SupportSQLiteDatabase) {
        repeat(5) { attempt ->
            val busy = db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { c ->
                c.moveToFirst() && c.getInt(0) != 0
            }
            if (!busy) return
            Log.i(TAG, "Checkpoint busy (attempt ${attempt + 1}), retrying")
            delay(200)
        }
        Log.w(TAG, "Checkpoint still busy — copying the last checkpointed state")
    }
}
