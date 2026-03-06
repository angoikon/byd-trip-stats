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
import com.byd.tripstats.data.local.BydStatsDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages local database backup and restore operations.
 *
 * Backup  → saves .db to TWO locations simultaneously:
 *     1. Download/BydTripStats/    (MediaStore, visible in file manager)
 *     2. files/db_backup/          (private app dir, accessible via ADB run-as)
 * Restore → two strategies:
 *   1. File picker (OpenDocument intent) — lets user navigate to any .db file
 *   2. Folder scan — lists .db files from both Download and private db_backup/
 *
 * DATABASE_NAME must match the string in Room.databaseBuilder() in BydStatsDatabase.kt
 */
class LocalBackupManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "LocalBackupManager"

        // ── CONFIGURE THIS ────────────────────────────────────────────────────
        const val DATABASE_NAME = "byd_stats_database"   // verify in BydStatsDatabase.kt
        // ─────────────────────────────────────────────────────────────────────

        const val BACKUP_SUBFOLDER   = "BydTripStats"
        // BYD DiLink names the folder "Download" (not "Downloads" as stock Android does)
        const val BYD_DOWNLOAD_DIR   = "Download"
        const val BACKUP_MIME_TYPE = "application/octet-stream"
        const val BACKUP_EXTENSION = ".db"
        const val PRIVATE_BACKUP_DIR = "db_backup"
        const val PRIVATE_BACKUP_MAX = 5   // keep newest N backups in private dir

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
        val uri: Uri,            // content:// (MediaStore) or file:// (private dir)
        val sizeBytes: Long,
        val dateModified: Long,  // epoch ms
        val source: String = "" // "Downloads" or "Internal (ADB)"
    )

    private val _state = MutableStateFlow<BackupState>(BackupState.Idle)
    val state: StateFlow<BackupState> = _state.asStateFlow()

    private val _localBackups = MutableStateFlow<List<BackupFile>>(emptyList())
    val localBackups: StateFlow<List<BackupFile>> = _localBackups.asStateFlow()

    // ── Backup ────────────────────────────────────────────────────────────────

    /**
     * Saves the Room database to Download/BydTripStats/byd_stats_backup_DATE.db
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

            _state.value = BackupState.InProgress("Saving to Download…")

            val values = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, BACKUP_MIME_TYPE)
                put(MediaStore.Downloads.RELATIVE_PATH,
                    "$BYD_DOWNLOAD_DIR/$BACKUP_SUBFOLDER")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("Could not create file in Download")

            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(dbFile).use { input -> input.copyTo(out) }
            } ?: throw Exception("Could not open output stream")

            // Mark complete — file becomes visible in file manager
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            // Also write to private app dir for ADB access
            copyToPrivateBackup(dbFile, fileName)

            val sizeMb = "%.1f".format(dbFile.length() / 1_048_576.0)
            _state.value = BackupState.Success(
                "Saved: $fileName ($sizeMb MB)\nDownload/$BACKUP_SUBFOLDER/ + internal storage"
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
     * Scans Download/BydTripStats/ for .db files using MediaStore.
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

            // Also scan private app dir (accessible via ADB)
            val privateResults = scanPrivateBackups()

            // Also scan the public Download/BydTripStats/ folder directly via the
            // filesystem — this works after a reinstall when MediaStore ownership
            // is lost and the cursor returns empty even though files still exist.
            val filesystemResults = scanDownloadFolderDirectly()

            // Merge, deduplicate by name, sort newest first
            val merged = (results + privateResults + filesystemResults)
                .distinctBy { it.name }
                .sortedByDescending { it.dateModified }

            _localBackups.value = merged
            Log.i(TAG, "Found ${merged.size} backup(s) — MediaStore: ${results.size}, filesystem: ${filesystemResults.size}, internal: ${privateResults.size}")
            if (merged.isEmpty()) {
                _state.value = BackupState.Error("No backups found. Run a backup first.")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Scan failed", e)
            _localBackups.value = emptyList()
        }
    }


    /** Scans the private app db_backup/ folder. No permissions needed. */
    private fun scanPrivateBackups(): List<BackupFile> {
        return try {
            val dir = File(context.filesDir, PRIVATE_BACKUP_DIR)
            if (!dir.exists()) return emptyList()
            dir.listFiles { f -> f.extension == "db" }
                ?.sortedByDescending { it.lastModified() }
                ?.map { f ->
                    BackupFile(
                        name = f.name,
                        uri = Uri.fromFile(f),
                        sizeBytes = f.length(),
                        dateModified = f.lastModified(),
                        source = "Internal (ADB)"
                    )
                } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Private backup scan failed: ${e.message}")
            emptyList()
        }
    }


    /**
     * Directly reads the public Download/BydTripStats/ folder via the filesystem.
     *
     * This is the fallback path that succeeds after a reinstall — when MediaStore
     * ownership is lost and the cursor returns empty — as long as
     * READ_EXTERNAL_STORAGE permission is granted.
     *
     * On BYD DiLink the public folder is "Download" (not "Downloads").
     */
    private fun scanDownloadFolderDirectly(): List<BackupFile> {
        return try {
            val base = Environment.getExternalStorageDirectory()
            // Try BYD path first, fall back to stock Android path
            val dir = listOf(
                java.io.File(base, "$BYD_DOWNLOAD_DIR/$BACKUP_SUBFOLDER"),
                java.io.File(base, "Downloads/$BACKUP_SUBFOLDER")
            ).firstOrNull { it.exists() && it.isDirectory } ?: return emptyList()

            dir.listFiles { f -> f.extension == "db" }
                ?.sortedByDescending { it.lastModified() }
                ?.map { f ->
                    BackupFile(
                        name         = f.name,
                        uri          = Uri.fromFile(f),
                        sizeBytes    = f.length(),
                        dateModified = f.lastModified(),
                        source       = "Download (file)"
                    )
                } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Direct filesystem scan failed: ${e.message}")
            emptyList()
        }
    }


    // ── Private app dir backup ───────────────────────────────────────────────

    /** Copies the database to files/db_backup/. Keeps newest PRIVATE_BACKUP_MAX files. */
    private fun copyToPrivateBackup(dbFile: File, fileName: String) {
        try {
            val dir = File(context.filesDir, PRIVATE_BACKUP_DIR)
            dir.mkdirs()

            val dest = File(dir, fileName)
            dbFile.copyTo(dest, overwrite = true)
            Log.i(TAG, "Private backup written: ${dest.path}")

            // Prune old backups — keep newest PRIVATE_BACKUP_MAX
            val files = dir.listFiles { f -> f.extension == "db" }
                ?.sortedByDescending { it.lastModified() } ?: return
            files.drop(PRIVATE_BACKUP_MAX).forEach {
                it.delete()
                Log.i(TAG, "Pruned old private backup: ${it.name}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Private backup copy failed (non-fatal): ${e.message}")
        }
    }

    // ── Telegram backup ─────────────────────────────────────────────────

    /**
     * Flushes WAL, copies db to cache, then delegates to TelegramManager.sendFile().
     * Progress and result are exposed via TelegramManager.state, not BackupState.
     */
    suspend fun backupToTelegram() = withContext(Dispatchers.IO) {
        val telegramManager = TelegramManager.getInstance(context)
        try {
            val dbFile = context.getDatabasePath(DATABASE_NAME)
            if (!dbFile.exists()) throw Exception("Database file not found.")

            flushWal(dbFile)

            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
            val fileName = "byd_stats_backup_$timestamp.db"
            val tempFile = File(context.cacheDir, fileName)
            dbFile.copyTo(tempFile, overwrite = true)

            telegramManager.sendFile(tempFile, caption = "BYD Trip Stats backup — $timestamp")

            tempFile.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Telegram backup prep failed", e)
        }
    }


    /**
     * Downloads [backup] from Telegram then restores it as the live database.
     * Progress is reported via TelegramManager.state; the final success/error
     * (with restartRequired) is reported via LocalBackupManager.state so the
     * screen can trigger the same auto-restart flow used by local restores.
     */
    suspend fun restoreFromTelegram(backup: TelegramManager.TelegramBackupFile) =
        withContext(Dispatchers.IO) {
            val telegramManager = TelegramManager.getInstance(context)
            try {
                val tempFile = telegramManager.downloadBackup(backup, context)
                    ?: return@withContext  // TelegramManager.state already has the error

                // Validate it's a real SQLite file before wiping the live DB
                val header = ByteArray(16)
                tempFile.inputStream().use { it.read(header) }
                if (!isSQLiteFile(header)) {
                    tempFile.delete()
                    _state.value = BackupState.Error("Downloaded file is not a valid database.")
                    return@withContext
                }

                _state.value = BackupState.InProgress("Restoring from Telegram backup…")
                doRestore(android.net.Uri.fromFile(tempFile), context.contentResolver)
                tempFile.delete()

            } catch (e: Exception) {
                Log.e(TAG, "restoreFromTelegram failed", e)
                _state.value = BackupState.Error("Restore failed: ${e.message}")
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

        // Step 1: Copy backup to a temp file first.
        // We do this before touching Room so we know the source read works.
        val tempFile = File(context.cacheDir, "restore_temp.db")
        val inputStream = if (uri.scheme == "file") {
            FileInputStream(File(uri.path!!))
        } else {
            resolver.openInputStream(uri) ?: throw Exception("Could not read backup stream")
        }
        inputStream.use { input ->
            FileOutputStream(tempFile).use { out -> input.copyTo(out) }
        }

        // Step 2: Close Room's connection BEFORE touching the database files.
        // Room auto-checkpoints WAL on close, so closing is sufficient — no need
        // to run a separate PRAGMA wal_checkpoint afterwards.
        BydStatsDatabase.closeDatabase()
        Log.i(TAG, "Room connection closed before restore")

        // Step 3: Delete WAL/SHM files now that Room has flushed and closed them.
        // If we deleted them while Room was open the process would crash on next access.
        walFile.delete()
        shmFile.delete()
        Log.i(TAG, "WAL/SHM files cleared")

        // Step 4: Replace the live database file with the backup.
        dbFile.parentFile?.mkdirs()
        tempFile.copyTo(dbFile, overwrite = true)
        tempFile.delete()

        _state.value = BackupState.Success(
            "Database restored successfully.\nThe app will close and reopen automatically.",
            restartRequired = true
        )
        Log.i(TAG, "Restore complete — process will restart")
    }

    /**
     * Flush SQLite WAL before a BACKUP (not restore — Room.close() handles that).
     * Opens a raw connection only when Room may still be running; safe for backup
     * since it is read-only from Room's perspective.
     */
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