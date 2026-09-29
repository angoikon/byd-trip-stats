package com.byd.tripstats.data.backup

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import com.byd.tripstats.data.local.BydStatsDatabase
import com.byd.tripstats.data.local.QuarantineOpenHelperFactory
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.service.VehicleTelemetryService
import com.byd.tripstats.util.AppRestart
import com.byd.tripstats.util.BackupNaming
import com.byd.tripstats.util.DiagLog
import java.io.File
import java.io.FileInputStream

/**
 * Manages local database backup and restore operations.
 *
 * Backup  → saves a gzip-compressed .db.gz to TWO locations simultaneously:
 *     1. Download/BydTripStats/    (MediaStore, visible in file manager)
 *     2. files/db_backup/          (private app dir, accessible via ADB run-as)
 * Restore → two strategies, both accepting compressed (.db.gz) and plain (.db) backups:
 *   1. File picker (OpenDocument intent) — lets user navigate to any backup file
 *   2. Folder scan — lists backups from both Download and private db_backup/
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
        const val BACKUP_EXTENSION = BackupCodec.PLAIN_EXTENSION
        const val COMPRESSED_BACKUP_EXTENSION = BackupCodec.COMPRESSED_EXTENSION
        const val PRIVATE_BACKUP_DIR = "db_backup"
        const val PRIVATE_BACKUP_MAX = 5   // keep newest N backups in private dir
        const val SD_BACKUP_MAX      = 10  // keep newest N backups on the SD card
        const val SD_BACKUP_FOLDER   = "BydTripStats"  // fixed folder at the SD card root

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
        /** [restartRequired]: a restore failed after Room was closed for the swap. */
        data class Error(val message: String, val restartRequired: Boolean = false) : BackupState()
    }

    /** What a database holds, shown on both sides of the restore confirmation. */
    data class DbSummary(
        val trips: Int,
        val firstTrip: Long?,   // epoch ms
        val lastTrip: Long?,
        val chargingSessions: Int,
    )

    /**
     * A backup that has been decoded and checked, waiting for the user to confirm it against
     * what it would replace. Nothing has been touched yet: cancelling just deletes [file].
     */
    data class PendingRestore(
        val label: String,
        val backup: DbSummary,
        val current: DbSummary?,
        val settings: SettingsFile?,
        internal val file: File,
    ) {
        /** Restoring would leave fewer trips, or an older last trip, than there are now. */
        val losesData: Boolean
            get() = current != null && (
                backup.trips < current.trips ||
                    (current.lastTrip ?: 0L) > (backup.lastTrip ?: 0L)
                )
    }

    data class BackupFile(
        val name: String,
        val uri: Uri,            // content:// (MediaStore) or file:// (private dir)
        val sizeBytes: Long,
        val dateModified: Long,  // epoch ms
        val source: String = "" // "Downloads" or "Internal (ADB)"
    )

    /** A settings export ([SettingsBackup]) sitting next to the database backups. */
    data class SettingsFile(
        val name: String,
        val uri: Uri,
        val sizeBytes: Long,
        val dateModified: Long,
        val source: String = ""
    )

    private val _state = MutableStateFlow<BackupState>(BackupState.Idle)
    val state: StateFlow<BackupState> = _state.asStateFlow()

    private val _localBackups = MutableStateFlow<List<BackupFile>>(emptyList())
    val localBackups: StateFlow<List<BackupFile>> = _localBackups.asStateFlow()

    private val _settingsFiles = MutableStateFlow<List<SettingsFile>>(emptyList())
    val settingsFiles: StateFlow<List<SettingsFile>> = _settingsFiles.asStateFlow()

    private val _pendingRestore = MutableStateFlow<PendingRestore?>(null)
    val pendingRestore: StateFlow<PendingRestore?> = _pendingRestore.asStateFlow()

    // ── Backup ────────────────────────────────────────────────────────────────

    /**
     * Writes [archive] to Download/BydTripStats/[fileName] through MediaStore, so no
     * WRITE_EXTERNAL_STORAGE permission is needed (API 29+).
     */
    private fun saveArchiveToDownloads(archive: File, fileName: String) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, BACKUP_MIME_TYPE)
            put(MediaStore.Downloads.RELATIVE_PATH, "$BYD_DOWNLOAD_DIR/$BACKUP_SUBFOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("Could not create file in Download")

        resolver.openOutputStream(uri)?.use { out ->
            FileInputStream(archive).use { input -> input.copyTo(out) }
        } ?: throw Exception("Could not open output stream")

        // Mark complete — file becomes visible in file manager. Explicitly stamp SIZE/
        // DATE_MODIFIED here rather than relying on the platform to backfill them on
        // IS_PENDING clear — this BYD ROM's MediaProvider doesn't, leaving the row stuck
        // at its insert-time defaults (0 bytes / epoch date) forever.
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        values.put(MediaStore.Downloads.SIZE, archive.length())
        values.put(MediaStore.Downloads.DATE_MODIFIED, System.currentTimeMillis() / 1000L)
        resolver.update(uri, values, null, null)
    }

    /**
     * Saves the Room database to Download/BydTripStats/byd_stats_backup_DATE.db.gz
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

            // One timestamp for both artefacts: the settings file is paired back to this
            // database by that segment when the user restores (see [settingsFileFor]).
            val timestamp = BackupNaming.timestamp()
            val fileName  = BackupNaming.fileName(timestamp = timestamp, extension = COMPRESSED_BACKUP_EXTENSION)

            // Compress once into cache; both destinations copy the same archive.
            _state.value = BackupState.InProgress("Compressing database…")
            val archive = File(context.cacheDir, fileName)
            val archiveSize = try {
                DbSnapshot.withFrozenFile(context) { BackupCodec.compress(it, archive) }

                _state.value = BackupState.InProgress("Saving to Download…")
                saveArchiveToDownloads(archive, fileName)

                // Also write to private app dir for ADB access
                copyToPrivateBackup(archive, fileName)
                archive.length()
            } finally {
                archive.delete()
            }

            // Settings live outside the database, so a database-only backup can't put a
            // reinstalled app back the way it was. Written as a sibling file, never as
            // part of the .db — a new Room entity would mean a schema version bump.
            _state.value = BackupState.InProgress("Saving settings…")
            val settingsName = writeSettingsSidecar(timestamp, includeCredentialsSetting())

            val sizeMb = "%.1f".format(archiveSize / 1_048_576.0)
            _state.value = BackupState.Success(
                "Saved: $fileName ($sizeMb MB)\nDownload/$BACKUP_SUBFOLDER/ + internal storage" +
                    if (settingsName != null) "\nSettings: $settingsName" else ""
            )
            Log.i(TAG, "Backup saved: $fileName")

            // Refresh the local list
            scanLocalBackups()

        } catch (e: Exception) {
            Log.e(TAG, "Backup failed", e)
            _state.value = BackupState.Error("Backup failed: ${e.message}")
        }
    }

    // ── SD card backup (Pro) ────────────────────────────────────────────────
    // Fully automatic: backups go to a fixed "BydTripStats" folder at the root of the
    // removable SD card — no folder picker, nothing to configure. The folder lives at
    // the volume root (not the app-specific Android/data dir), so backups stay
    // user-visible and survive an app uninstall. Direct File access works because the
    // app holds WRITE_EXTERNAL_STORAGE with requestLegacyExternalStorage=true.

    /**
     * Root of the mounted removable SD card (e.g. /storage/6786-8D8D), or null if no card
     * is present. Tries three strategies because DiLink ROMs vary: some expose the card
     * only at /storage/<uuid> (not via getExternalFilesDirs, which is why a plain
     * getExternalFilesDirs check reports "no card" even with one inserted).
     */
    fun sdCardRoot(): File? {
        // 1) StorageManager — the reliable path: a removable, mounted volume → /storage/<uuid>.
        runCatching {
            val sm = context.getSystemService(StorageManager::class.java)
            sm?.storageVolumes?.forEach { vol ->
                if (vol.isRemovable && vol.state == Environment.MEDIA_MOUNTED) {
                    val uuid = vol.uuid
                    if (!uuid.isNullOrEmpty()) {
                        val dir = File("/storage/$uuid")
                        if (dir.isDirectory) return dir
                    }
                }
            }
        }.onFailure { Log.w(TAG, "SD detection (StorageManager) failed: ${it.message}") }

        // 2) getExternalFilesDirs — works on ROMs that expose the card's app-specific dir.
        runCatching {
            context.getExternalFilesDirs(null).filterNotNull().forEach { d ->
                if (Environment.isExternalStorageRemovable(d) &&
                    Environment.getExternalStorageState(d) == Environment.MEDIA_MOUNTED) {
                    val idx = d.absolutePath.indexOf("/Android/")
                    if (idx > 0) return File(d.absolutePath.substring(0, idx))
                }
            }
        }.onFailure { Log.w(TAG, "SD detection (getExternalFilesDirs) failed: ${it.message}") }

        // 3) Last resort: scan /storage for a UUID-style mounted volume (e.g. 6786-8D8D).
        runCatching {
            File("/storage").listFiles()?.forEach { vol ->
                if (vol.name.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) &&
                    vol.isDirectory &&
                    Environment.getExternalStorageState(vol) == Environment.MEDIA_MOUNTED) {
                    return vol
                }
            }
        }.onFailure { Log.w(TAG, "SD detection (/storage scan) failed: ${it.message}") }

        return null
    }

    /** The fixed SD-card backup folder (root/BydTripStats), or null if no card. */
    private fun sdBackupDir(): File? = sdCardRoot()?.let { File(it, SD_BACKUP_FOLDER) }

    /** True when a removable SD card is mounted. */
    fun isSdCardAvailable(): Boolean = sdCardRoot() != null

    /**
     * Saves the Room database to <SD root>/BydTripStats/. Pro-gated by the caller. Fully
     * automatic — creates the folder if needed, checks free space first, and keeps the
     * newest [SD_BACKUP_MAX]. SD backups appear in [scanLocalBackups] (source "SD card").
     */
    suspend fun backupDatabaseToSdCard() = withContext(Dispatchers.IO) {
        try {
            _state.value = BackupState.InProgress("Preparing database…")

            val dbFile = context.getDatabasePath(DATABASE_NAME)
            if (!dbFile.exists()) {
                _state.value = BackupState.Error("Database file not found: ${dbFile.path}")
                return@withContext
            }

            val dir = sdBackupDir()
            if (dir == null) {
                _state.value = BackupState.Error("No SD card detected. Insert a card and try again.")
                return@withContext
            }

            if (!dir.exists() && !dir.mkdirs()) {
                _state.value = BackupState.Error("Could not create the BydTripStats folder on the SD card.")
                return@withContext
            }

            val timestamp = BackupNaming.timestamp()
            val fileName  = BackupNaming.fileName(timestamp = timestamp, extension = COMPRESSED_BACKUP_EXTENSION)

            // Compress into cache first: only then is the size known that the card must hold
            // (sizing it by the raw database refused cards with ample room for the archive),
            // and a card that fills up mid-write never keeps a half-written backup.
            _state.value = BackupState.InProgress("Compressing database…")
            val archive = File(context.cacheDir, fileName)
            val dest = File(dir, fileName)
            try {
                DbSnapshot.withFrozenFile(context) { BackupCodec.compress(it, archive) }

                val needed = archive.length() + 5L * 1_048_576L
                val free = (sdCardRoot()?.usableSpace ?: 0L)
                if (free < needed) {
                    _state.value = BackupState.Error(
                        "Not enough space on the SD card: need %.0f MB, only %.0f MB free."
                            .format(needed / 1_048_576.0, free / 1_048_576.0)
                    )
                    return@withContext
                }

                _state.value = BackupState.InProgress("Saving to SD card…")
                try {
                    archive.copyTo(dest, overwrite = true)
                } catch (e: Exception) {
                    dest.delete()
                    throw e
                }
            } finally {
                archive.delete()
            }

            // Settings sibling, same as the Download backup.
            val settingsName = writeSettingsToSdCard(dir, timestamp)

            // Prune old SD backups — keep newest SD_BACKUP_MAX of each kind
            listOf<(File) -> Boolean>(
                { BackupCodec.isBackupName(it.name) },
                { it.extension == "json" },
            ).forEach { kind ->
                dir.listFiles { f -> f.isFile && kind(f) }
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(SD_BACKUP_MAX)
                    ?.forEach { it.delete() }
            }

            val sizeMb = "%.1f".format(dest.length() / 1_048_576.0)
            _state.value = BackupState.Success(
                "Saved to SD card: $SD_BACKUP_FOLDER/$fileName ($sizeMb MB)" +
                    if (settingsName != null) "\nSettings: $settingsName" else ""
            )
            Log.i(TAG, "SD card backup saved: ${dest.path}")

            scanLocalBackups()
        } catch (e: Exception) {
            Log.e(TAG, "SD card backup failed", e)
            _state.value = BackupState.Error("SD card backup failed: ${e.message}")
        }
    }

    /** Lists .db / .db.gz backups in <SD root>/BydTripStats/. */
    private fun scanSdCardBackups(): List<BackupFile> {
        return try {
            val dir = sdBackupDir() ?: return emptyList()
            if (!dir.exists()) return emptyList()
            dir.listFiles { f -> f.isFile && BackupCodec.isBackupName(f.name) }
                ?.sortedByDescending { it.lastModified() }
                ?.map { f ->
                    BackupFile(
                        name = f.name,
                        uri = Uri.fromFile(f),
                        sizeBytes = f.length(),
                        dateModified = f.lastModified(),
                        source = "SD card"
                    )
                } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "SD card backup scan failed: ${e.message}")
            emptyList()
        }
    }

    // ── Restore ───────────────────────────────────────────────────────────────
    //
    // Two steps with the user in between. [prepareRestore] decodes the backup into a temp file
    // and checks it — header, full structural check, schema version — without touching the live
    // database, then publishes a [PendingRestore] with what the backup holds next to what is
    // there now. Only [commitRestore] changes anything: it saves a copy of the current database,
    // applies the settings if asked, and swaps the file in.

    /**
     * Stage 1 for a local backup, a scanned backup or a file picked in the browser (.db or
     * .db.gz). [settings] is the settings file written alongside it, offered in the dialog.
     */
    suspend fun prepareRestore(uri: Uri, label: String, settings: SettingsFile? = null) =
        withContext(Dispatchers.IO) {
            try {
                _state.value = BackupState.InProgress("Reading backup file…")
                val input = if (uri.scheme == "file") {
                    FileInputStream(File(uri.path!!))
                } else {
                    context.contentResolver.openInputStream(uri) ?: throw Exception("Cannot read selected file")
                }
                stageRestore(input, label, settings)
            } catch (e: Exception) {
                Log.e(TAG, "Preparing restore failed", e)
                _state.value = BackupState.Error("Restore failed: ${e.message}")
            }
        }

    /** Drops a staged restore; the live database was never touched. */
    fun cancelRestore() {
        _pendingRestore.value?.file?.delete()
        _pendingRestore.value = null
        _state.value = BackupState.Idle
    }

    /**
     * Stage 2: the user has seen what the backup holds and confirmed. After success the app
     * process is restarted so Room reinitialises on the new file.
     */
    suspend fun commitRestore(restoreSettings: Boolean) = withContext(Dispatchers.IO) {
        val pending = _pendingRestore.value ?: return@withContext
        _pendingRestore.value = null
        try {
            // A copy of what is about to be replaced, so a wrong pick can be undone. Skipped
            // when there is nothing to keep — the usual restore onto a fresh install.
            val current = pending.current
            val safetyCopy = if (current != null && (current.trips > 0 || current.chargingSessions > 0)) {
                _state.value = BackupState.InProgress("Saving a copy of your current data…")
                saveBeforeRestoreCopy()
            } else null

            // Settings before the swap: a successful restore ends in a restart.
            var settingsNote = ""
            if (restoreSettings && pending.settings != null) {
                settingsNote = try {
                    val result = SettingsBackup.import(context, readSettingsText(pending.settings))
                    "\nSettings restored: ${result.sections.joinToString(", ")}."
                } catch (e: Exception) {
                    Log.e(TAG, "Settings restore failed during database restore", e)
                    "\nSettings could NOT be restored: ${e.message}"
                }
            }
            if (safetyCopy != null) settingsNote += "\nPrevious data saved as Download/$BACKUP_SUBFOLDER/$safetyCopy"

            _state.value = BackupState.InProgress("Restoring database…")
            doRestore(pending.file, settingsNote)
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed", e)
            pending.file.delete()
            _state.value = BackupState.Error("Restore failed: ${e.message}")
        }
    }

    /**
     * Compresses the live database — from a frozen snapshot, like any backup — into
     * Download/BydTripStats before a restore replaces it. Returns the file name.
     */
    private suspend fun saveBeforeRestoreCopy(): String {
        val fileName = BackupNaming.fileName(prefix = "byd_stats_before_restore", extension = COMPRESSED_BACKUP_EXTENSION)
        val archive = File(context.cacheDir, fileName)
        try {
            DbSnapshot.withFrozenFile(context) { BackupCodec.compress(it, archive) }
            saveArchiveToDownloads(archive, fileName)
        } finally {
            archive.delete()
        }
        Log.i(TAG, "Pre-restore copy saved: $fileName")
        return fileName
    }

    /**
     * Saves the live database — damaged, but still in use — to Download/BydTripStats for
     * recovery, when [DbHealthCheck] finds damage. Same `.sqlite.gz` naming as a database the
     * corruption handler moved aside, and the restore list doesn't offer it for the same reason.
     * Returns the file name.
     */
    suspend fun saveDamagedCopy(): String = withContext(Dispatchers.IO) {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US).format(java.util.Date())
        val name = "byd_stats_damaged_$stamp.sqlite.gz"
        val archive = File(context.cacheDir, name)
        try {
            DbSnapshot.withFrozenFile(context) { BackupCodec.compress(it, archive) }
            saveArchiveToDownloads(archive, name)
        } finally {
            archive.delete()
        }
        DiagLog.event(context, TAG, "damaged database copy saved: Download/$BACKUP_SUBFOLDER/$name")
        name
    }

    /**
     * Copies databases the corruption handler moved aside ([QuarantineOpenHelperFactory]) to
     * Download/BydTripStats, where they can be reached for recovery (`sqlite3 … .recover`) — the
     * app's own databases folder can't be read on a release build. Saved as `.sqlite.gz`, a name
     * the restore list doesn't match: a damaged file is for recovery, not for restoring as-is.
     */
    suspend fun exportDamagedDatabases() = withContext(Dispatchers.IO) {
        val dir = context.getDatabasePath(DATABASE_NAME).parentFile ?: return@withContext
        val marker = DATABASE_NAME + QuarantineOpenHelperFactory.DAMAGED_MARKER
        val damaged = dir.listFiles { f -> f.isFile && f.name.startsWith(marker) } ?: return@withContext
        // The shared-memory index is rebuilt from the WAL; it holds nothing worth keeping.
        damaged.filter { it.name.endsWith("-shm") }.forEach { it.delete() }
        damaged.filterNot { it.name.endsWith("-shm") }.forEach { f ->
            // byd_stats_database.damaged-<stamp>[-wal] → byd_stats_damaged_<stamp>.sqlite[-wal].gz
            val rest = f.name.removePrefix(marker)
            val suffix = listOf("-wal", "-journal").firstOrNull { rest.endsWith(it) } ?: ""
            val name = "byd_stats_damaged_${rest.removeSuffix(suffix)}.sqlite$suffix.gz"
            val archive = File(context.cacheDir, name)
            try {
                BackupCodec.compress(f, archive)
                saveArchiveToDownloads(archive, name)
                f.delete()
                DiagLog.event(context, TAG, "damaged database exported: Download/$BACKUP_SUBFOLDER/$name")
            } catch (e: Exception) {
                Log.w(TAG, "Could not export ${f.name}: ${e.message}")
            } finally {
                archive.delete()
            }
        }
    }

    // ── Scan local backups ────────────────────────────────────────────────────

    /**
     * Scans Download/BydTripStats/ for .db / .db.gz files using MediaStore.
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
                "AND (${MediaStore.Downloads.DISPLAY_NAME} LIKE ? " +
                "OR ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?)"
            val selectionArgs = arrayOf(
                "%$BACKUP_SUBFOLDER%",
                "%$BACKUP_EXTENSION",
                "%$COMPRESSED_BACKUP_EXTENSION"
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

            // Also scan the removable SD card (Pro backups land there).
            val sdResults = scanSdCardBackups()

            // Merge, deduplicate by name — sort newest first. Prefer whichever representation
            // reports the largest size rather than naively keeping the first (MediaStore) one:
            // on this ROM the MediaStore row can be stuck at 0 bytes / epoch date (see
            // backupDatabase()'s SIZE/DATE_MODIFIED stamp), while the private-dir/filesystem/SD
            // copies of the same file always carry the real File.length()/lastModified().
            val merged = (results + privateResults + filesystemResults + sdResults)
                .groupBy { it.name }
                .map { (_, group) -> group.maxByOrNull { it.sizeBytes } ?: group.first() }
                .sortedByDescending { it.dateModified }

            _localBackups.value = merged
            // Keep the settings list in step so a restore can offer the file that was
            // written alongside whichever backup the user picks.
            scanSettingsFiles()
            Log.i(TAG, "Found ${merged.size} backup(s) — MediaStore: ${results.size}, filesystem: ${filesystemResults.size}, internal: ${privateResults.size}, SD: ${sdResults.size}")
            if (merged.isEmpty()) {
                _state.value = BackupState.Error("No backups found. Run a backup first.")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Scan failed", e)
            _localBackups.value = emptyList()
        }
    }


    /**
     * Deletes ALL representations of a backup with the same filename and refreshes the list.
     * A single backup is often stored in multiple locations (MediaStore, private dir, filesystem),
     * so we collect every entry sharing the name before deleting to avoid a second-tap requirement.
     */
    suspend fun deleteBackup(backup: BackupFile) = withContext(Dispatchers.IO) {
        // Collect all entries with the same name across all sources before deleting any,
        // because scanLocalBackups() deduplicates by name in the visible list.
        val allRepresentations = buildAllRepresentationsFor(backup.name)
        for (entry in allRepresentations) {
            try {
                val deleted = if (entry.uri.scheme == "content") {
                    context.contentResolver.delete(entry.uri, null, null) > 0
                } else {
                    val path = entry.uri.path
                    if (path != null) deleteFilesystemFile(path, entry.name) else false
                }
                if (deleted) Log.i(TAG, "Deleted backup [${entry.source}]: ${entry.name}")
                else Log.w(TAG, "Delete returned false [${entry.source}]: ${entry.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete backup [${entry.source}]: ${entry.name}", e)
            }
        }
        scanLocalBackups()
    }

    /**
     * Returns every known representation (MediaStore, private dir, filesystem) for a given filename.
     */
    private fun buildAllRepresentationsFor(name: String): List<BackupFile> {
        val results = mutableListOf<BackupFile>()

        // MediaStore
        try {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE, MediaStore.Downloads.DATE_MODIFIED)
            val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
            resolver.query(collection, projection, selection, arrayOf(name),
                null)?.use { cursor ->
                val idCol   = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    results.add(BackupFile(
                        name         = cursor.getString(nameCol),
                        uri          = android.content.ContentUris.withAppendedId(collection, cursor.getLong(idCol)),
                        sizeBytes    = cursor.getLong(sizeCol),
                        dateModified = cursor.getLong(dateCol) * 1000L,
                        source       = "Downloads"
                    ))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore lookup for delete failed: ${e.message}")
        }

        // Private app dir
        try {
            val f = java.io.File(java.io.File(context.filesDir, PRIVATE_BACKUP_DIR), name)
            if (f.exists()) results.add(BackupFile(name = f.name, uri = android.net.Uri.fromFile(f),
                sizeBytes = f.length(), dateModified = f.lastModified(), source = "Internal (ADB)"))
        } catch (e: Exception) {
            Log.w(TAG, "Private dir lookup for delete failed: ${e.message}")
        }

        // Public Download/BydTripStats/ filesystem
        try {
            val base = Environment.getExternalStorageDirectory()
            listOf("$BYD_DOWNLOAD_DIR/$BACKUP_SUBFOLDER", "Downloads/$BACKUP_SUBFOLDER").forEach { rel ->
                val f = java.io.File(base, "$rel/$name")
                if (f.exists() && results.none { it.uri == android.net.Uri.fromFile(f) }) {
                    results.add(BackupFile(name = f.name, uri = android.net.Uri.fromFile(f),
                        sizeBytes = f.length(), dateModified = f.lastModified(), source = "Download (file)"))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Filesystem lookup for delete failed: ${e.message}")
        }

        // SD card BydTripStats/ folder
        try {
            sdBackupDir()?.let { dir ->
                val f = File(dir, name)
                if (f.exists()) results.add(BackupFile(name = f.name, uri = Uri.fromFile(f),
                    sizeBytes = f.length(), dateModified = f.lastModified(), source = "SD card"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "SD card lookup for delete failed: ${e.message}")
        }

        return results
    }

    /**
     * Deletes a file that lives in the public external storage (file:// URI) using a
     * four-step escalation strategy for Android 10 (requestLegacyExternalStorage):
     *
     *  1. File.delete() — works when the app has legacy write access.
     *  2. MediaStore lookup by DATA path — finds the record even after package UID changes.
     *  3. MediaStore lookup by DISPLAY_NAME — broader fallback.
     *  4. Insert the file into MediaStore to gain ownership, then delete via the new URI.
     *     This handles files that were never registered (created by old app versions that
     *     wrote directly to the filesystem without going through MediaStore).
     */
    private fun deleteFilesystemFile(path: String, name: String): Boolean {
        // Step 1: direct File.delete (works in legacy storage mode if WRITE permission active)
        if (java.io.File(path).delete()) {
            Log.i(TAG, "deleteFilesystemFile: File.delete() succeeded for $name")
            return true
        }

        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

        // Step 2: find existing MediaStore entry by exact file path
        try {
            resolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.MediaColumns.DATA} = ?",
                arrayOf(path), null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                    if (resolver.delete(uri, null, null) > 0) {
                        Log.i(TAG, "deleteFilesystemFile: MediaStore DATA delete succeeded for $name")
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "deleteFilesystemFile: DATA lookup failed for $name: ${e.message}")
        }

        // Step 3: find existing MediaStore entry by display name
        try {
            resolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf(name), null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                    if (resolver.delete(uri, null, null) > 0) {
                        Log.i(TAG, "deleteFilesystemFile: MediaStore DISPLAY_NAME delete succeeded for $name")
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "deleteFilesystemFile: DISPLAY_NAME lookup failed for $name: ${e.message}")
        }

        // Step 4: file has no MediaStore record — insert one (gains ownership) then delete.
        // This handles files created by older app versions that wrote directly to the filesystem.
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DATA, path)
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            }
            val insertedUri = resolver.insert(collection, values)
            if (insertedUri != null && resolver.delete(insertedUri, null, null) > 0) {
                Log.i(TAG, "deleteFilesystemFile: insert-then-delete succeeded for $name")
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "deleteFilesystemFile: insert-then-delete failed for $name: ${e.message}")
        }

        Log.w(TAG, "deleteFilesystemFile: all strategies failed for $name at $path")
        return false
    }

    /** Scans the private app db_backup/ folder. No permissions needed. */
    private fun scanPrivateBackups(): List<BackupFile> {
        return try {
            val dir = File(context.filesDir, PRIVATE_BACKUP_DIR)
            if (!dir.exists()) return emptyList()
            dir.listFiles { f -> f.isFile && BackupCodec.isBackupName(f.name) }
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

            dir.listFiles { f -> f.isFile && BackupCodec.isBackupName(f.name) }
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

    /** Copies a backup archive to files/db_backup/. Keeps newest PRIVATE_BACKUP_MAX files. */
    private fun copyToPrivateBackup(archive: File, fileName: String) {
        try {
            val dir = File(context.filesDir, PRIVATE_BACKUP_DIR)
            dir.mkdirs()

            val dest = File(dir, fileName)
            archive.copyTo(dest, overwrite = true)
            Log.i(TAG, "Private backup written: ${dest.path}")

            // Prune old backups — keep newest PRIVATE_BACKUP_MAX
            val files = dir.listFiles { f -> f.isFile && BackupCodec.isBackupName(f.name) }
                ?.sortedByDescending { it.lastModified() } ?: return
            files.drop(PRIVATE_BACKUP_MAX).forEach {
                it.delete()
                Log.i(TAG, "Pruned old private backup: ${it.name}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Private backup copy failed (non-fatal): ${e.message}")
        }
    }

    // ── Settings backup (the sibling of every .db) ────────────────────────────
    // The database backup carries trips and charging sessions; the settings file carries
    // everything around them (see [SettingsBackup]). They are written together and share a
    // timestamp, so a restore can offer the settings that belong to the database picked.

    fun settingsFileName(timestamp: String): String = BackupNaming.fileName(
        prefix    = SettingsBackup.FILE_PREFIX,
        timestamp = timestamp,
        extension = SettingsBackup.EXTENSION,
    )

    /** The user's "carry secrets in the settings file" choice; defaults to on. */
    suspend fun includeCredentialsSetting(): Boolean =
        runCatching { PreferencesManager(context).settingsBackupIncludeCredentials.first() }
            .getOrDefault(true)

    /** Exports settings on their own, without touching the database. */
    suspend fun backupSettings() = withContext(Dispatchers.IO) {
        _state.value = BackupState.InProgress("Saving settings…")
        val includeCredentials = includeCredentialsSetting()
        val name = writeSettingsSidecar(BackupNaming.timestamp(), includeCredentials)
        if (name == null) {
            _state.value = BackupState.Error("Could not save the settings file.")
        } else {
            scanSettingsFiles()
            _state.value = BackupState.Success(
                "Saved: $name\nDownload/$BACKUP_SUBFOLDER/ + internal storage\n" +
                    if (includeCredentials) "Credentials included."
                    else "Credentials excluded — tokens and passwords are not in this file."
            )
        }
    }

    /**
     * Writes the settings file to Download/ and the private dir. Never throws: a settings
     * failure must not fail the database backup, which is the part that cannot be
     * reproduced by hand.
     */
    private suspend fun writeSettingsSidecar(timestamp: String, includeCredentials: Boolean): String? =
        try {
            val json = SettingsBackup.export(context, includeCredentials)
            val name = settingsFileName(timestamp)
            // Private dir first: it needs no permission and no MediaStore, so the copy
            // that ADB can reach exists even if the Download write is refused. One
            // surviving copy is a success — only a total failure returns null.
            val privateOk = writeSettingsToPrivate(name, json)
            val downloadOk = runCatching { writeSettingsToDownloads(name, json) }
                .onFailure { Log.w(TAG, "Settings Download write failed: ${it.message}") }
                .isSuccess
            Log.i(TAG, "Settings file written: $name (credentials=$includeCredentials, " +
                "download=$downloadOk, private=$privateOk)")
            if (privateOk || downloadOk) name else null
        } catch (e: Exception) {
            Log.w(TAG, "Settings file write failed (non-fatal): ${e.message}")
            null
        }

    private suspend fun writeSettingsToSdCard(dir: File, timestamp: String): String? = try {
        val json = SettingsBackup.export(context, includeCredentialsSetting())
        val name = settingsFileName(timestamp)
        File(dir, name).writeText(json)
        name
    } catch (e: Exception) {
        Log.w(TAG, "SD settings write failed (non-fatal): ${e.message}")
        null
    }

    /** Also used by [TelegramBackupWorker] so scheduled backups carry settings too. */
    internal suspend fun sendSettingsToTelegram(telegramManager: TelegramManager, timestamp: String) {
        try {
            val includeCredentials = includeCredentialsSetting()
            val json = SettingsBackup.export(context, includeCredentials)
            val temp = File(context.cacheDir, settingsFileName(timestamp))
            temp.writeText(json)
            telegramManager.sendFile(
                temp,
                caption = "BYD Trip Stats settings — $timestamp" +
                    if (includeCredentials) "" else " (credentials excluded)"
            )
            temp.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Telegram settings send failed (non-fatal): ${e.message}")
        }
    }

    private fun writeSettingsToDownloads(name: String, json: String) {
        // MediaStore appends " (1)" rather than overwriting a duplicate display name, so
        // clear any same-named row first (two exports inside the same minute).
        runCatching { deleteDownloadsEntry(name) }

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, SettingsBackup.MIME_TYPE)
            put(MediaStore.Downloads.RELATIVE_PATH, "$BYD_DOWNLOAD_DIR/$BACKUP_SUBFOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("Could not create the settings file in Download")

        resolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            ?: throw Exception("Could not open the settings output stream")

        // Same SIZE/DATE_MODIFIED stamp the database backup needs — this ROM's
        // MediaProvider leaves both at their insert-time defaults otherwise.
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        values.put(MediaStore.Downloads.SIZE, json.toByteArray(Charsets.UTF_8).size.toLong())
        values.put(MediaStore.Downloads.DATE_MODIFIED, System.currentTimeMillis() / 1000L)
        resolver.update(uri, values, null, null)
    }

    private fun writeSettingsToPrivate(name: String, json: String): Boolean = try {
        val dir = File(context.filesDir, PRIVATE_BACKUP_DIR)
        dir.mkdirs()
        File(dir, name).writeText(json)
        dir.listFiles { f -> f.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(PRIVATE_BACKUP_MAX)
            ?.forEach { it.delete() }
        true
    } catch (e: Exception) {
        Log.w(TAG, "Private settings copy failed (non-fatal): ${e.message}")
        false
    }

    private fun deleteDownloadsEntry(name: String) {
        context.contentResolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.Downloads.DISPLAY_NAME} = ?",
            arrayOf(name)
        )
    }

    /**
     * Finds settings files in every place a backup can live. Filtered by the
     * `byd_stats_settings` prefix so it can't pick up `telegram_registry.json`, which
     * shares the folder.
     */
    suspend fun scanSettingsFiles() = withContext(Dispatchers.IO) {
        val results = mutableListOf<SettingsFile>()

        // Download/BydTripStats via MediaStore
        try {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.SIZE, MediaStore.Downloads.DATE_MODIFIED),
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                arrayOf("%$BACKUP_SUBFOLDER%", "${SettingsBackup.FILE_PREFIX}%${SettingsBackup.EXTENSION}"),
                "${MediaStore.Downloads.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idCol   = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    results.add(SettingsFile(
                        name         = cursor.getString(nameCol),
                        uri          = ContentUris.withAppendedId(collection, cursor.getLong(idCol)),
                        sizeBytes    = cursor.getLong(sizeCol),
                        dateModified = cursor.getLong(dateCol) * 1000L,
                        source       = "Downloads"
                    ))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Settings MediaStore scan failed: ${e.message}")
        }

        // Filesystem: private dir, the public Download folder (the path that still works
        // after a reinstall, when MediaStore ownership is gone), and the SD card.
        val dirs = buildList {
            add(File(context.filesDir, PRIVATE_BACKUP_DIR) to "Internal (ADB)")
            val base = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
            if (base != null) {
                add(File(base, "$BYD_DOWNLOAD_DIR/$BACKUP_SUBFOLDER") to "Download (file)")
                add(File(base, "Downloads/$BACKUP_SUBFOLDER") to "Download (file)")
            }
            sdBackupDir()?.let { add(it to "SD card") }
        }
        dirs.forEach { (dir, source) ->
            try {
                if (!dir.isDirectory) return@forEach
                dir.listFiles { f ->
                    f.isFile && f.name.startsWith(SettingsBackup.FILE_PREFIX) &&
                        f.name.endsWith(SettingsBackup.EXTENSION, ignoreCase = true)
                }?.forEach { f ->
                    results.add(SettingsFile(f.name, Uri.fromFile(f), f.length(), f.lastModified(), source))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Settings scan failed for ${dir.path}: ${e.message}")
            }
        }

        // Same dedupe rule as the database list: one entry per name, preferring the
        // representation that reports a real size (MediaStore rows can read 0 here).
        _settingsFiles.value = results
            .groupBy { it.name }
            .map { (_, group) -> group.maxByOrNull { it.sizeBytes } ?: group.first() }
            .sortedByDescending { it.dateModified }

        Log.i(TAG, "Found ${_settingsFiles.value.size} settings file(s)")
    }

    /** The settings file written in the same run as [dbName], if it is still around. */
    fun settingsFileFor(dbName: String): SettingsFile? {
        val timestamp = BackupNaming.timestampOf(dbName) ?: return null
        return _settingsFiles.value.firstOrNull { BackupNaming.timestampOf(it.name) == timestamp }
    }

    /**
     * Applies [file] to the running app. No process restart: every value is written
     * through its normal setter and is live immediately — which also keeps this off the
     * self-kill path that boot-loops DiLink-5 head units.
     */
    suspend fun restoreSettings(file: SettingsFile): SettingsBackup.ImportResult? =
        withContext(Dispatchers.IO) {
            try {
                _state.value = BackupState.InProgress("Restoring settings…")
                val result = SettingsBackup.import(context, readSettingsText(file))
                _state.value = BackupState.Success(settingsRestoredMessage(result))
                result
            } catch (e: Exception) {
                Log.e(TAG, "Settings restore failed", e)
                _state.value = BackupState.Error("Settings restore failed: ${e.message}")
                null
            }
        }

    private fun settingsRestoredMessage(result: SettingsBackup.ImportResult): String =
        if (result.sections.isEmpty()) "The settings file held nothing this version can apply."
        else "Settings restored: ${result.sections.joinToString(", ")}." +
            if (result.credentialsIncluded) ""
            else "\nThis file was saved without credentials — tokens and passwords are unchanged."

    private fun readSettingsText(file: SettingsFile): String {
        if (file.sizeBytes > SettingsBackup.MAX_FILE_BYTES) {
            throw Exception("That file is too large to be a settings backup.")
        }
        val text = if (file.uri.scheme == "file") {
            File(file.uri.path!!).readText()
        } else {
            context.contentResolver.openInputStream(file.uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: throw Exception("Could not read the settings file.")
        }
        if (!SettingsBackup.isSettingsFile(text)) {
            throw Exception("That file is not a BYD Trip Stats settings backup.")
        }
        return text
    }

    /** Deletes every representation of a settings file, then refreshes the list. */
    suspend fun deleteSettingsFile(file: SettingsFile) = withContext(Dispatchers.IO) {
        runCatching { deleteDownloadsEntry(file.name) }
            .onFailure { Log.w(TAG, "MediaStore delete failed for ${file.name}: ${it.message}") }
        val dirs = buildList {
            add(File(context.filesDir, PRIVATE_BACKUP_DIR))
            runCatching { Environment.getExternalStorageDirectory() }.getOrNull()?.let { base ->
                add(File(base, "$BYD_DOWNLOAD_DIR/$BACKUP_SUBFOLDER"))
                add(File(base, "Downloads/$BACKUP_SUBFOLDER"))
            }
            sdBackupDir()?.let { add(it) }
        }
        dirs.forEach { dir ->
            val f = File(dir, file.name)
            // Same escalation the database delete needs: after a reinstall the app no
            // longer owns the MediaStore row and a plain File.delete() is refused.
            if (f.exists() && !deleteFilesystemFile(f.path, f.name)) {
                Log.w(TAG, "Could not delete ${f.path}")
            }
        }
        scanSettingsFiles()
    }

    // ── Telegram backup ─────────────────────────────────────────────────

    /**
     * Compresses a frozen snapshot of the database into cache, then delegates to
     * TelegramManager.sendFile(). Progress and result are exposed via TelegramManager.state,
     * not BackupState.
     */
    suspend fun backupToTelegram() = withContext(Dispatchers.IO) {
        val telegramManager = TelegramManager.getInstance(context)
        try {
            val dbFile = context.getDatabasePath(DATABASE_NAME)
            if (!dbFile.exists()) throw Exception("Database file not found.")

            val timestamp = BackupNaming.timestamp()

            // Compress first: it's the archive, not the raw .db, that has to fit the cap.
            val fileName = BackupNaming.fileName(timestamp = timestamp, extension = COMPRESSED_BACKUP_EXTENSION)
            val tempFile = File(context.cacheDir, fileName)
            try {
                DbSnapshot.withFrozenFile(context) { BackupCodec.compress(it, tempFile) }

                // Settings only travel with a database that can actually be sent. Over Telegram's
                // cap, sendFile below refuses the backup and explains why — sending the settings file
                // anyway would leave a chat message that looks like a backup but holds no trips.
                // Settings go first so the database's result, success or cap error, owns the banner.
                if (tempFile.length() <= TelegramManager.TELEGRAM_MAX_FILE_SIZE_BYTES) {
                    sendSettingsToTelegram(telegramManager, timestamp)
                }

                telegramManager.sendFile(tempFile, caption = "BYD Trip Stats backup — $timestamp")
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Telegram backup prep failed", e)
        }
    }


    /**
     * Stage 1 for a Telegram backup: downloads it, then checks it like any other
     * ([prepareRestore]). Download progress is reported via TelegramManager.state; the rest
     * via LocalBackupManager.state, so the screen runs the same confirm-and-restart flow.
     */
    suspend fun prepareTelegramRestore(backup: TelegramManager.TelegramBackupFile) =
        withContext(Dispatchers.IO) {
            val telegramManager = TelegramManager.getInstance(context)
            try {
                val tempFile = telegramManager.downloadBackup(backup, context)
                    ?: return@withContext  // TelegramManager.state already has the error
                try {
                    stageRestore(tempFile.inputStream(), backup.fileName, settings = null)
                } finally {
                    tempFile.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Preparing Telegram restore failed", e)
                _state.value = BackupState.Error("Restore failed: ${e.message}")
            }
        }

    fun resetState() {
        _state.value = BackupState.Idle
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Step 1 of every restore: decodes [input] — gzip-compressed or plain — into a temp
     * file and checks it is a SQLite database. Done before touching Room, so a bad or
     * truncated backup never costs the live database. Returns null (temp removed) when
     * the file is not a usable backup. [input] is closed.
     */
    private fun decodeToRestoreTemp(input: java.io.InputStream): File? {
        val tempFile = File(context.cacheDir, "restore_temp.db")
        try {
            BackupCodec.decodeTo(input, tempFile)
        } catch (e: java.io.IOException) {
            tempFile.delete()
            // Only a corrupt or truncated gzip stream means "not a backup". A full disk or a
            // file-picker read failure propagates so the caller reports "Restore failed: …".
            if (e !is java.util.zip.ZipException && e !is java.io.EOFException) throw e
            Log.w(TAG, "Backup could not be decoded: ${e.message}")
            return null
        }
        if (!BackupCodec.isSQLiteFile(tempFile)) {
            tempFile.delete()
            return null
        }
        return tempFile
    }

    /**
     * Decodes [input], checks the result ([inspectBackup]) and publishes it as the
     * [pendingRestore] for the user to confirm. [input] is closed.
     */
    private suspend fun stageRestore(input: java.io.InputStream, label: String, settings: SettingsFile?) {
        _pendingRestore.value?.file?.delete()
        _pendingRestore.value = null

        val temp = decodeToRestoreTemp(input)
        if (temp == null) {
            _state.value = BackupState.Error("Selected file is not a valid database backup.")
            return
        }
        _state.value = BackupState.InProgress("Checking the backup…")
        val (summary, problem) = inspectBackup(temp)
        if (summary == null) {
            temp.delete()
            _state.value = BackupState.Error(problem ?: "Selected file is not a valid database backup.")
            return
        }
        _pendingRestore.value = PendingRestore(label, summary, currentSummary(), settings, temp)
        _state.value = BackupState.Idle
    }

    /**
     * The check the 16-byte header can't do: walks every b-tree (`quick_check`) so a backup
     * with a broken tree — e.g. one copied while a checkpoint was rewriting the file — is
     * refused here, instead of being installed and then failing on the first write that
     * touches the damage. Returns the backup's summary, or null and the reason to show.
     */
    private fun inspectBackup(file: File): Pair<DbSummary?, String?> {
        val db = try {
            // No-op error handler: the framework default deletes a file it finds corrupt.
            // WAL flag: leave the backup's journal mode as it is. No collators: no locale
            // table write. Nothing about the file changes by being checked.
            SQLiteDatabase.openDatabase(
                file.path, null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING or
                    SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                DatabaseErrorHandler { }
            )
        } catch (e: Exception) {
            return null to "This backup can't be opened: ${e.message}"
        }
        try {
            val problems = db.rawQuery("PRAGMA quick_check", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
            if (problems != listOf("ok")) {
                DiagLog.event(context, TAG, "restore refused — backup damaged: ${problems.take(3)}")
                return null to "This backup is damaged and can't be restored safely (${problems.firstOrNull()})."
            }
            val liveVersion = runCatching {
                BydStatsDatabase.getDatabase(context).openHelper.readableDatabase.version
            }.getOrDefault(Int.MAX_VALUE)
            if (db.version > liveVersion) {
                return null to "This backup was made by a newer version of the app " +
                    "(database v${db.version}, this app v$liveVersion). Update the app first."
            }
            return summarise { sql -> db.rawQuery(sql, null) } to null
        } catch (e: Exception) {
            return null to "This backup can't be read: ${e.message}"
        } finally {
            db.close()
            File(file.path + "-wal").delete()
            File(file.path + "-shm").delete()
        }
    }

    /** What the live database holds now, or null if it can't be read. */
    private fun currentSummary(): DbSummary? = runCatching {
        val db = BydStatsDatabase.getDatabase(context).openHelper.readableDatabase
        summarise { sql -> db.query(sql) }
    }.getOrNull()

    private fun summarise(query: (String) -> android.database.Cursor): DbSummary {
        fun hasTable(name: String) =
            query("SELECT 1 FROM sqlite_master WHERE type='table' AND name='$name'").use { it.moveToFirst() }
        fun android.database.Cursor.longOrNull(i: Int) = if (isNull(i)) null else getLong(i)

        if (!hasTable("trips")) throw IllegalStateException("not a BYD Trip Stats database")
        val trips = query("SELECT count(*), min(startTime), max(startTime) FROM trips").use { c ->
            c.moveToFirst()
            Triple(c.getInt(0), c.longOrNull(1), c.longOrNull(2))
        }
        // Old schemas (the first releases) had no charging tables yet.
        val sessions = if (hasTable("charging_sessions")) {
            query("SELECT count(*) FROM charging_sessions").use { c -> c.moveToFirst(); c.getInt(0) }
        } else 0
        return DbSummary(trips.first, trips.second, trips.third, sessions)
    }

    /**
     * Swaps [tempFile] — a checked backup — in as the live database. The process has to
     * restart afterwards whatever happens: Room is closed for good once this begins.
     */
    private suspend fun doRestore(tempFile: File, settingsNote: String) {
        val dbFile = context.getDatabasePath(DATABASE_NAME)

        // Stop the recorder first. The telemetry service writes through Room all the time — a
        // charging point every ~8 s, trip points every second — and a write that lands on the
        // closed connection pool crashes the app, until now possibly halfway through the copy.
        VehicleTelemetryService.stop(context)
        delay(500)

        val swapError = DbSnapshot.exclusive {
            BydStatsDatabase.closeForReplacement()
            runCatching {
                // Room checkpoints on close, so these are empty — but a WAL left beside the new
                // file would be replayed into it on the next open.
                val sidecars = listOf("-wal", "-shm", "-journal").map { File(dbFile.path + it) }
                sidecars.forEach { it.delete() }
                dbFile.parentFile?.mkdirs()
                // A rename is atomic: a crash leaves the old file or the new one, never a mix.
                // cache/ and databases/ share a filesystem; the copy is only a fallback.
                if (!tempFile.renameTo(dbFile)) {
                    val staged = File(dbFile.path + ".restoring")
                    try {
                        tempFile.copyTo(staged, overwrite = true)
                        if (!staged.renameTo(dbFile)) throw java.io.IOException("could not move the backup into place")
                    } finally {
                        staged.delete()
                    }
                }
                sidecars.forEach { it.delete() }
            }.exceptionOrNull()
        }
        tempFile.delete()

        if (swapError != null) {
            Log.e(TAG, "Restore swap failed", swapError)
            DiagLog.event(context, TAG, "restore failed before the swap completed: ${swapError.message}")
            _state.value = BackupState.Error(
                "Restore failed: ${swapError.message}. Your previous data is unchanged; the app will restart.",
                restartRequired = true
            )
            return
        }

        // The restored history may predate the one-shot repairs whose flags are already set.
        com.byd.tripstats.data.repository.TripRepository.rearmRepairsAfterRestore(context)
        DiagLog.event(context, TAG, "database restored")

        _state.value = BackupState.Success(
            "Database restored successfully.$settingsNote\n" +
                // Only DiLink-3 relaunches itself; promising it everywhere is how a
                // head unit that simply closed the app looks like a bug.
                if (AppRestart.canAutoRelaunch) "The app will close and reopen automatically."
                else "The app will now close — tap the notification, or the app icon, to reopen it.",
            restartRequired = true
        )
        Log.i(TAG, "Restore complete — process will restart")
    }
}