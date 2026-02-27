package com.byd.tripstats.data.backup

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages local database backup and restore operations.
 *
 * Backup  → saves .db to public Downloads/BydTripStats/ via MediaStore (no permissions needed)
 * Restore → two strategies:
 *   1. File picker (OpenDocument intent) — lets user navigate to any .db file
 *   2. Folder scan — lists all .db files found in Downloads/BydTripStats/
 *      (fallback if the car's infotainment has no file manager registered)
 *
 * DATABASE_NAME must match the string in Room.databaseBuilder() in BydStatsDatabase.kt
 */
class LocalBackupManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "LocalBackupManager"

        // ── CONFIGURE THIS ────────────────────────────────────────────────────
        const val DATABASE_NAME = "byd_stats_database"   // verify in BydStatsDatabase.kt
        // ─────────────────────────────────────────────────────────────────────

        const val BACKUP_SUBFOLDER  = "BydTripStats"
        const val BACKUP_MIME_TYPE  = "application/octet-stream"
        const val BACKUP_EXTENSION  = ".db"

        @Volatile private var INSTANCE: LocalBackupManager? = null

        fun getInstance(context: Context): LocalBackupManager {
            return INSTANCE ?: synchronized(this) {
                LocalBackupManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    sealed class BackupState {
        object Idle : BackupState()
        data class InProgress(val message: String) : BackupState()
        data class Success(val message: String, val restartRequired: Boolean = false) : BackupState()
        data class Error(val message: String) : BackupState()
    }

    data class BackupFile(
        val name: String,
        val uri: Uri,           // content:// URI from MediaStore
        val sizeBytes: Long,
        val dateModified: Long  // epoch ms
    )

    private val _state = MutableStateFlow<BackupState>(BackupState.Idle)
    val state: StateFlow<BackupState> = _state.asStateFlow()

    private val _localBackups = MutableStateFlow<List<BackupFile>>(emptyList())
    val localBackups: StateFlow<List<BackupFile>> = _localBackups.asStateFlow()

    // ── Backup ────────────────────────────────────────────────────────────────

    /**
     * Saves the Room database to Downloads/BydTripStats/byd_stats_backup_DATE.db
     * Uses MediaStore so no WRITE_EXTERNAL_STORAGE permission is needed (API 29+).
     */
    suspend fun backupDatabase() = withContext(Dispatchers.IO) {
        try {
            _state.value = BackupState.InProgress("Preparing database…")

            val dbFile = context.getDatabasePath(DATABASE_NAME)
            if (!dbFile.exists()) {
                _state.value = BackupState.Error("Database file not found: ${dbFile.path}")
                return@withContext
            }

            // Flush WAL so the .db file is self-consistent
            _state.value = BackupState.InProgress("Flushing database…")
            flushWal(dbFile)

            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
            val fileName = "byd_stats_backup_$timestamp.db"

            _state.value = BackupState.InProgress("Saving to Downloads…")

            val values = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, BACKUP_MIME_TYPE)
                put(MediaStore.Downloads.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/$BACKUP_SUBFOLDER")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("Could not create file in Downloads")

            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(dbFile).use { input -> input.copyTo(out) }
            } ?: throw Exception("Could not open output stream")

            // Mark complete — file becomes visible in file manager
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            val sizeMb = "%.1f".format(dbFile.length() / 1_048_576.0)
            _state.value = BackupState.Success(
                "Saved: $fileName ($sizeMb MB)\nLocation: Downloads/$BACKUP_SUBFOLDER/"
            )
            Log.i(TAG, "Backup saved: $fileName")

            // Refresh the local list
            scanLocalBackups()

        } catch (e: Exception) {
            Log.e(TAG, "Backup failed", e)
            _state.value = BackupState.Error("Backup failed: ${e.message}")
        }
    }

    // ── Restore from URI (file picker) ────────────────────────────────────────

    /**
     * Restores the database from a URI returned by the system file picker.
     * After success the app process is killed so Room reinitialises cleanly.
     */
    suspend fun restoreFromUri(uri: Uri) = withContext(Dispatchers.IO) {
        try {
            _state.value = BackupState.InProgress("Reading backup file…")

            val resolver = context.contentResolver

            // Validate it looks like a SQLite file
            resolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(16)
                if (input.read(header) < 16 || !isSQLiteFile(header)) {
                    _state.value = BackupState.Error("Selected file is not a valid database backup.")
                    return@withContext
                }
            } ?: throw Exception("Cannot read selected file")

            _state.value = BackupState.InProgress("Restoring database…")
            doRestore(uri, resolver)

        } catch (e: Exception) {
            Log.e(TAG, "Restore from URI failed", e)
            _state.value = BackupState.Error("Restore failed: ${e.message}")
        }
    }

    /**
     * Restores from a BackupFile found by [scanLocalBackups].
     */
    suspend fun restoreFromBackupFile(backup: BackupFile) {
        restoreFromUri(backup.uri)
    }

    // ── Scan local backups ────────────────────────────────────────────────────

    /**
     * Scans Downloads/BydTripStats/ for .db files using MediaStore.
     * Populates [localBackups] sorted newest first.
     */
    suspend fun scanLocalBackups() = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATE_MODIFIED
            )

            val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? " +
                "AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf(
                "%$BACKUP_SUBFOLDER%",
                "%$BACKUP_EXTENSION"
            )

            val results = mutableListOf<BackupFile>()
            resolver.query(collection, projection, selection, selectionArgs,
                "${MediaStore.Downloads.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idCol   = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    val id   = cursor.getLong(idCol)
                    val uri  = android.content.ContentUris.withAppendedId(collection, id)
                    results.add(BackupFile(
                        name         = cursor.getString(nameCol),
                        uri          = uri,
                        sizeBytes    = cursor.getLong(sizeCol),
                        dateModified = cursor.getLong(dateCol) * 1000L  // seconds → ms
                    ))
                }
            }

            _localBackups.value = results
            Log.i(TAG, "Found ${results.size} local backup(s)")

        } catch (e: Exception) {
            Log.e(TAG, "Scan failed", e)
            _localBackups.value = emptyList()
        }
    }

    // ── SD card backup ────────────────────────────────────────────────────────

    /**
     * Backs up the database to the user-selected SD card folder via SdCardManager.
     * Requires the user to have already selected a folder (one-time SAF permission).
     */
    suspend fun backupDatabaseToSd() = withContext(Dispatchers.IO) {
        try {
            val sdManager = SdCardManager.getInstance(context)
            if (!sdManager.hasFolder) {
                _state.value = BackupState.Error(
                    "No SD card folder selected.\nTap 'Select SD Card Folder' first."
                )
                return@withContext
            }

            _state.value = BackupState.InProgress("Preparing database…")

            val dbFile = context.getDatabasePath(DATABASE_NAME)
            if (!dbFile.exists()) {
                _state.value = BackupState.Error("Database file not found: ${dbFile.path}")
                return@withContext
            }

            _state.value = BackupState.InProgress("Flushing database…")
            flushWal(dbFile)

            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
            val fileName = "byd_stats_backup_$timestamp.db"

            _state.value = BackupState.InProgress("Writing to SD card…")

            val uri = sdManager.writeFile(fileName, BACKUP_MIME_TYPE, dbFile.readBytes())

            if (uri != null) {
                val sizeMb = "%.1f".format(dbFile.length() / 1_048_576.0)
                _state.value = BackupState.Success(
                    "Saved to SD card: $fileName ($sizeMb MB)"
                )
                Log.i(TAG, "SD backup saved: $fileName")
            } else {
                _state.value = BackupState.Error(
                    "Failed to write to SD card.\nCheck the folder is still accessible."
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "SD backup failed", e)
            _state.value = BackupState.Error("SD backup failed: ${e.message}")
        }
    }

    fun resetState() {
        _state.value = BackupState.Idle
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun doRestore(uri: Uri, resolver: android.content.ContentResolver) {
        val dbFile  = context.getDatabasePath(DATABASE_NAME)
        val walFile = File(dbFile.path + "-wal")
        val shmFile = File(dbFile.path + "-shm")

        // Copy to temp first so we can validate before touching live DB
        val tempFile = File(context.cacheDir, "restore_temp.db")
        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { out -> input.copyTo(out) }
        } ?: throw Exception("Could not read backup stream")

        // Remove WAL files — they'd conflict with the restored DB
        walFile.delete()
        shmFile.delete()

        // Ensure parent directory exists
        dbFile.parentFile?.mkdirs()

        // Replace the live database
        tempFile.copyTo(dbFile, overwrite = true)
        tempFile.delete()

        _state.value = BackupState.Success(
            "Database restored successfully.\nThe app will now restart.",
            restartRequired = true
        )
        Log.i(TAG, "Restore complete, process will be killed")
    }

    /** Flush SQLite WAL to ensure the .db file is self-consistent before copying. */
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

    /** Check the first 16 bytes for the SQLite magic header string. */
    private fun isSQLiteFile(header: ByteArray): Boolean {
        val magic = "SQLite format 3\u0000"
        return header.size >= magic.length &&
            magic.indices.all { header[it] == magic[it].code.toByte() }
    }
}