package com.byd.tripstats.data.backup

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Handles Telegram private Bot API backup.
 *
 * SETUP (one-time, per user):
 *   1. Message @BotFather on Telegram → /newbot → copy the token
 *   2. Message your new bot once (so it can find your chat ID)
 *   3. Open Settings → Backup & Restore → Telegram section
 *   4. Paste token → tap "Validate & Save" → chat ID is fetched automatically
 *
 * BACKUP:
 *   Flushes WAL, reads the .db into memory, sends via multipart/form-data to
 *   https://api.telegram.org/bot{TOKEN}/sendDocument
 *   The file lands in your private Telegram chat, accessible from any device.
 *
 * RESTORE:
 *   Download the .db from your Telegram chat on any device, then use the
 *   file picker or ADB push to restore it.
 */
class TelegramManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "TelegramManager"
        private const val PREFS_NAME = "telegram_prefs"
        private const val KEY_TOKEN = "bot_token"
        private const val KEY_CHAT_ID = "chat_id"
        private const val KEY_BOT_NAME = "bot_name"
        private const val KEY_BOT_ID   = "bot_id"
        private const val KEY_LAST_AUTO_BACKUP = "last_auto_backup"
        private const val KEY_SCHEDULE = "backup_schedule"
        private const val KEY_AUTO_ENABLED  = "auto_backup_enabled"
        private const val KEY_SENT_FILES      = "sent_files"        // JSON array of sent backup metadata
        private const val REGISTRY_FILE_NAME  = "telegram_registry.json"  // survives uninstalls
        private const val BASE_URL      = "https://api.telegram.org/bot"
        private const val FILE_BASE_URL = "https://api.telegram.org/file/bot"

        @Volatile private var INSTANCE: TelegramManager? = null

        fun getInstance(context: Context): TelegramManager {
            return INSTANCE ?: synchronized(this) {
                TelegramManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // ── Schedule ──────────────────────────────────────────────────────────────

    enum class Schedule(val label: String, val days: Long) {
        DAILY("Daily", 1L),
        WEEKLY("Weekly", 7L),
        MONTHLY("Monthly", 30L)
    }

    // ── State ─────────────────────────────────────────────────────────────────

    sealed class TelegramState {
        object Idle : TelegramState()
        data class InProgress(val message: String) : TelegramState()
        data class Success(val message: String) : TelegramState()
        data class Error(val message: String) : TelegramState()
    }

    data class TelegramBackupFile(
        val fileId:   String,
        val fileName: String,
        val fileSize: Long,
        val date:     Long    // unix timestamp × 1000 → ms
    )

    data class TelegramConfig(
        val token: String,
        val chatId: String,
        val botName: String,
        val botId: Long
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<TelegramState>(TelegramState.Idle)
    val state: StateFlow<TelegramState> = _state.asStateFlow()

    // Config as StateFlow so UI reacts immediately to connect/disconnect
    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<TelegramConfig?> = _config.asStateFlow()

    // Schedule as StateFlow for reactive UI
    private val _schedule = MutableStateFlow(loadSchedule())
    val schedule: StateFlow<Schedule> = _schedule.asStateFlow()

    // Auto-backup toggle as StateFlow
    private val _autoEnabled = MutableStateFlow(prefs.getBoolean(KEY_AUTO_ENABLED, true))
    val autoEnabled: StateFlow<Boolean> = _autoEnabled.asStateFlow()

    private val _telegramBackups = MutableStateFlow<List<TelegramBackupFile>>(loadSentFiles())
    val telegramBackups: StateFlow<List<TelegramBackupFile>> = _telegramBackups.asStateFlow()

    val lastAutoBackup: String?
        get() = prefs.getString(KEY_LAST_AUTO_BACKUP, null)

    // ── Private loaders ───────────────────────────────────────────────────────

    private fun loadConfig(): TelegramConfig? {
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        val chatId = prefs.getString(KEY_CHAT_ID, null) ?: return null
        val botName = prefs.getString(KEY_BOT_NAME, "") ?: ""
        val botId   = prefs.getLong(KEY_BOT_ID, 0L)
        return TelegramConfig(token, chatId, botName, botId)
    }

    private fun loadSchedule(): Schedule {
        val name = prefs.getString(KEY_SCHEDULE, Schedule.WEEKLY.name) ?: Schedule.WEEKLY.name
        return Schedule.entries.firstOrNull { it.name == name } ?: Schedule.WEEKLY
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    /**
     * Validates the token via getMe, then fetches the chat ID from the most
     * recent message sent to the bot via getUpdates.
     *
     * The user must have sent at least one message to the bot before calling this.
     */
    suspend fun validateAndSave(token: String) = withContext(Dispatchers.IO) {
        try {
            _state.value = TelegramState.InProgress("Validating token…")

            val trimmedToken = token.trim()
            if (trimmedToken.isBlank()) {
                _state.value = TelegramState.Error("Token cannot be empty.")
                return@withContext
            }

            // Step 1: validate token via getMe
            val meJson = getRequest("$BASE_URL$trimmedToken/getMe")
            if (!meJson.getBoolean("ok")) {
                _state.value = TelegramState.Error("Invalid token — check and try again.")
                return@withContext
            }
            val botResult = meJson.getJSONObject("result")
            val botName   = botResult.getString("username")
            val botId     = botResult.getLong("id")

            // Step 2: fetch chat ID from updates
            _state.value = TelegramState.InProgress("Finding your chat ID…")
            val updatesJson = getRequest("$BASE_URL$trimmedToken/getUpdates")
            if (!updatesJson.getBoolean("ok")) {
                _state.value = TelegramState.Error("Could not fetch updates.")
                return@withContext
            }

            val updates = updatesJson.getJSONArray("result")
            if (updates.length() == 0) {
                _state.value = TelegramState.Error(
                    "No messages found.\nSend any message to @$botName on Telegram first, then try again."
                )
                return@withContext
            }

            // Take the most recent update's chat ID
            val lastUpdate = updates.getJSONObject(updates.length() - 1)
            val chatId = lastUpdate.getJSONObject("message")
                .getJSONObject("chat")
                .getLong("id")
                .toString()

            // Save
            prefs.edit()
                .putString(KEY_TOKEN, trimmedToken)
                .putString(KEY_CHAT_ID, chatId)
                .putString(KEY_BOT_NAME, botName)
                .putLong(KEY_BOT_ID, botId)
                .apply()

            // Update StateFlow so UI reacts immediately
            _config.value = TelegramConfig(trimmedToken, chatId, botName, botId)

            _state.value = TelegramState.Success("Connected to @$botName")
            Log.i(TAG, "Telegram configured: bot=@$botName chatId=$chatId")

            if (_autoEnabled.value) scheduleAutoBackup()

        } catch (e: Exception) {
            Log.e(TAG, "Setup failed", e)
            _state.value = TelegramState.Error("Setup failed: ${e.message}")
        }
    }

    fun clearConfig() {
        cancelAutoBackup()
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_CHAT_ID)
            .remove(KEY_BOT_NAME)
            .remove(KEY_BOT_ID)
            .remove(KEY_LAST_AUTO_BACKUP)
            .remove(KEY_SENT_FILES)
            .apply()
        // Update StateFlow so UI reacts immediately
        _config.value = null
        _state.value = TelegramState.Idle
    }

    // ── Schedule settings ─────────────────────────────────────────────────────

    fun setSchedule(newSchedule: Schedule) {
        prefs.edit().putString(KEY_SCHEDULE, newSchedule.name).apply()
        _schedule.value = newSchedule
        _state.value = TelegramState.Idle   // clear any stale banner
        if (_autoEnabled.value && _config.value != null) {
            scheduleAutoBackup()
        }
        Log.i(TAG, "Backup schedule set to ${newSchedule.label}")
    }

    fun setAutoEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_ENABLED, enabled).apply()
        _autoEnabled.value = enabled
        _state.value = TelegramState.Idle   // clear any stale error/success banner
        if (enabled && _config.value != null) {
            scheduleAutoBackup()
        } else {
            cancelAutoBackup()
        }
        Log.i(TAG, "Auto backup ${if (enabled) "enabled" else "disabled"}")
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    /**
     * Sends [file] to the configured Telegram chat as a document.
     * Uses chunked multipart/form-data so large files don't need to be fully
     * buffered — Telegram supports up to 50 MB per document.
     */
    suspend fun sendFile(file: File, caption: String = "") = withContext(Dispatchers.IO) {
        try {
            val cfg = _config.value ?: throw Exception("Telegram not configured.")
            _state.value = TelegramState.InProgress("Sending to Telegram…")

            val boundary = "----BydBackup${System.currentTimeMillis()}"
            val url = URL("$BASE_URL${cfg.token}/sendDocument")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setChunkedStreamingMode(4096)
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            // Use write(ByteArray) instead of writeBytes() — writeBytes() only writes
            // the low byte of each char (ISO-8859-1), which mangles any non-ASCII content
            // (emoji, en-dashes, etc.) and causes Telegram to reject with UTF-8 errors.
            fun DataOutputStream.utf8(s: String) = write(s.toByteArray(Charsets.UTF_8))

            DataOutputStream(conn.outputStream).use { out ->
                // chat_id field
                out.utf8("--$boundary\r\n")
                out.utf8("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n")
                out.utf8("${cfg.chatId}\r\n")

                // caption field (optional)
                if (caption.isNotEmpty()) {
                    out.utf8("--$boundary\r\n")
                    out.utf8("Content-Disposition: form-data; name=\"caption\"\r\n\r\n")
                    out.utf8("$caption\r\n")
                }

                // document field — stream directly from disk
                out.utf8("--$boundary\r\n")
                out.utf8(
                    "Content-Disposition: form-data; name=\"document\"; filename=\"${file.name}\"\r\n"
                )
                out.utf8("Content-Type: application/octet-stream\r\n\r\n")
                FileInputStream(file).use { fis -> fis.copyTo(out) }
                out.utf8("\r\n")
                out.utf8("--$boundary--\r\n")
            }

            val responseCode = conn.responseCode
            val responseBody = if (responseCode == 200) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            }
            conn.disconnect()

            val json = JSONObject(responseBody)
            if (json.getBoolean("ok")) {
                // Extract and persist the file_id so listTelegramBackups() can find it
                // without needing getUpdates (which only shows incoming messages)
                try {
                    val doc = json.getJSONObject("result").getJSONObject("document")
                    saveSentFile(TelegramBackupFile(
                        fileId   = doc.getString("file_id"),
                        fileName = doc.optString("file_name", file.name),
                        fileSize = doc.optLong("file_size", file.length()),
                        date     = System.currentTimeMillis()
                    ))
                } catch (e: Exception) {
                    Log.w(TAG, "Could not extract file_id from response: ${e.message}")
                }
                _state.value = TelegramState.Success("Sent to Telegram ✓\n${file.name}")
                Log.i(TAG, "Telegram send success: ${file.name}")
            } else {
                val desc = json.optString("description", "Unknown error")
                _state.value = TelegramState.Error("Telegram rejected the file: $desc")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Send failed", e)
            _state.value = TelegramState.Error("Send failed: ${e.message}")
        }
    }



    // ── Local sent-file registry ──────────────────────────────────────────────

    /** Persists metadata for every successfully sent backup so listTelegramBackups()
     *  can reconstruct the list without relying on getUpdates (which only shows
     *  messages the bot *received*, not messages it *sent*). */
    private fun saveSentFile(backup: TelegramBackupFile) {
        val existing = loadSentFiles().toMutableList()
        if (existing.none { it.fileId == backup.fileId }) {
            existing.add(0, backup)   // newest first
        }
        val jsonStr = buildRegistryJson(existing)
        // 1. SharedPreferences (fast access while app is installed)
        prefs.edit().putString(KEY_SENT_FILES, jsonStr).apply()
        // 2. Download/BydTripStats/telegram_registry.json (survives uninstalls)
        writeExternalRegistry(jsonStr)
        _telegramBackups.value = existing
    }

    private fun buildRegistryJson(backups: List<TelegramBackupFile>): String {
        val arr = org.json.JSONArray()
        backups.forEach { b ->
            arr.put(org.json.JSONObject().apply {
                put("fileId",   b.fileId)
                put("fileName", b.fileName)
                put("fileSize", b.fileSize)
                put("date",     b.date)
            })
        }
        return arr.toString()
    }

    private fun writeExternalRegistry(jsonStr: String) {
        try {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, REGISTRY_FILE_NAME)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/json")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                    "Download/BydTripStats")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI

            // Delete existing registry file first (MediaStore doesn't overwrite by display name)
            resolver.delete(collection,
                "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ? AND " +
                "${android.provider.MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf(REGISTRY_FILE_NAME, "%BydTripStats%")
            )

            val uri = resolver.insert(collection, values) ?: run {
                Log.w(TAG, "Could not create registry file in Download")
                return
            }
            resolver.openOutputStream(uri)?.use { it.write(jsonStr.toByteArray(Charsets.UTF_8)) }
            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Log.i(TAG, "External registry written: $REGISTRY_FILE_NAME")
        } catch (e: Exception) {
            Log.w(TAG, "External registry write failed (non-fatal): ${e.message}")
        }
    }

    private fun readExternalRegistry(): List<TelegramBackupFile> {
        // Try MediaStore first (fast path when ownership is intact)
        val fromMediaStore = readExternalRegistryViaMediaStore()
        if (fromMediaStore.isNotEmpty()) return fromMediaStore

        // Fallback: read the JSON file directly from disk.
        // This succeeds after a reinstall when MediaStore ownership is lost
        // but READ_EXTERNAL_STORAGE is granted.
        return readExternalRegistryFromFilesystem()
    }

    private fun readExternalRegistryViaMediaStore(): List<TelegramBackupFile> {
        return try {
            val resolver = context.contentResolver
            val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(android.provider.MediaStore.Downloads._ID)
            val cursor = resolver.query(
                collection, projection,
                "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ? AND " +
                "${android.provider.MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf(REGISTRY_FILE_NAME, "%BydTripStats%"),
                null
            )
            val uri = cursor?.use {
                if (it.moveToFirst()) {
                    val id = it.getLong(it.getColumnIndexOrThrow(android.provider.MediaStore.Downloads._ID))
                    android.content.ContentUris.withAppendedId(collection, id)
                } else null
            } ?: return emptyList()

            val jsonStr = resolver.openInputStream(uri)?.bufferedReader()?.readText()
                ?: return emptyList()
            parseRegistryJson(jsonStr)
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore registry read failed: ${e.message}")
            emptyList()
        }
    }

    private fun readExternalRegistryFromFilesystem(): List<TelegramBackupFile> {
        return try {
            val base = android.os.Environment.getExternalStorageDirectory()
            val file = listOf(
                java.io.File(base, "Download/BydTripStats/$REGISTRY_FILE_NAME"),
                java.io.File(base, "Downloads/BydTripStats/$REGISTRY_FILE_NAME")
            ).firstOrNull { it.exists() } ?: return emptyList()

            val jsonStr = file.readText(Charsets.UTF_8)
            val result = parseRegistryJson(jsonStr)
            Log.i(TAG, "Registry loaded from filesystem: ${result.size} entries")
            result
        } catch (e: Exception) {
            Log.w(TAG, "Filesystem registry read failed: ${e.message}")
            emptyList()
        }
    }

    private fun parseRegistryJson(jsonStr: String): List<TelegramBackupFile> {
        val arr = org.json.JSONArray(jsonStr)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            TelegramBackupFile(
                fileId   = o.getString("fileId"),
                fileName = o.getString("fileName"),
                fileSize = o.getLong("fileSize"),
                date     = o.getLong("date")
            )
        }
    }

    private fun loadSentFiles(): List<TelegramBackupFile> {
        // Merge SharedPreferences (fast) + external registry (survives uninstalls)
        val fromPrefs = try {
            val raw = prefs.getString(KEY_SENT_FILES, null) ?: ""
            if (raw.isBlank()) emptyList() else parseRegistryJson(raw)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse prefs registry: ${e.message}")
            emptyList()
        }
        val fromExternal = readExternalRegistry()

        // Merge, deduplicate by fileId, sort newest first
        val merged = (fromPrefs + fromExternal)
            .distinctBy { it.fileId }
            .sortedByDescending { it.date }

        // If external had entries that prefs didn't, persist back to prefs
        if (fromExternal.isNotEmpty() && merged.size > fromPrefs.size) {
            prefs.edit().putString(KEY_SENT_FILES, buildRegistryJson(merged)).apply()
            Log.i(TAG, "Restored ${merged.size - fromPrefs.size} backup(s) from external registry")
        }
        return merged
    }

    // ── Restore from Telegram ─────────────────────────────────────────────────

    /**
     * Scans the bot chat history for .db document messages.
     * Populates [telegramBackups] sorted newest-first.
     */
    /**
     * Merges the local sent-file registry with a getUpdates scan filtered to
     * messages sent BY the bot (from.id == botId). This means backups are
     * discoverable even after an uninstall or data reset, as long as the
     * Telegram chat history is intact.
     *
     * Newly discovered files are persisted back to the local registry.
     */
    /**
     * Loads the registry of sent backups, merging SharedPreferences with the
     * external telegram_registry.json in Download/BydTripStats (which survives
     * uninstalls). After reconnecting the bot, this will rediscover all previous
     * backups automatically.
     */
    fun listTelegramBackups() {
        val backups = loadSentFiles()
        _telegramBackups.value = backups
        _state.value = if (backups.isEmpty())
            TelegramState.Error("No backups found. Send a backup first.")
        else
            TelegramState.Idle
    }

        /**
     * Downloads [backup] to the app's cache dir and returns the local File.
     * The caller is responsible for deleting the temp file after restore.
     */
    suspend fun downloadBackup(backup: TelegramBackupFile, context: Context): File? =
        withContext(Dispatchers.IO) {
            try {
                val cfg = _config.value ?: throw Exception("Telegram not configured.")
                _state.value = TelegramState.InProgress("Downloading ${backup.fileName}…")

                // Step 1: resolve file path on Telegram servers
                val fileJson = getRequest("$BASE_URL${cfg.token}/getFile?file_id=${backup.fileId}")
                if (!fileJson.getBoolean("ok")) throw Exception("Could not resolve file path.")
                val filePath = fileJson.getJSONObject("result").getString("file_path")

                // Step 2: stream download
                val url      = URL("$FILE_BASE_URL${cfg.token}/$filePath")
                val tempFile = File(context.cacheDir, backup.fileName)
                val conn     = (url.openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout    = 60_000
                }
                conn.inputStream.use { input ->
                    tempFile.outputStream().use { out -> input.copyTo(out) }
                }
                conn.disconnect()

                _state.value = TelegramState.Success("Downloaded ${backup.fileName} ✓")
                tempFile

            } catch (e: Exception) {
                Log.e(TAG, "downloadBackup failed", e)
                _state.value = TelegramState.Error("Download failed: ${e.message}")
                null
            }
        }

    fun clearTelegramBackups() {
        _telegramBackups.value = emptyList()
    }

    // ── Auto backup scheduling ────────────────────────────────────────────────

    fun scheduleAutoBackup() {
        val days = _schedule.value.days
        val request = PeriodicWorkRequestBuilder<TelegramBackupWorker>(days, TimeUnit.DAYS)
            // Without an initial delay, WorkManager fires the worker immediately on first
            // enqueue (and on every UPDATE re-enqueue). Delaying by the full period means
            // the first run happens after one interval, matching user expectations.
            .setInitialDelay(days, TimeUnit.DAYS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            TelegramBackupWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        Log.i(TAG, "Auto backup scheduled every $days day(s), first run in $days day(s)")
    }

    fun cancelAutoBackup() {
        WorkManager.getInstance(context).cancelUniqueWork(TelegramBackupWorker.WORK_NAME)
        Log.i(TAG, "Auto backup cancelled")
    }

    /** Called by TelegramBackupWorker after a successful run to persist the timestamp. */
    fun recordAutoBackup(timestamp: String) {
        prefs.edit().putString(KEY_LAST_AUTO_BACKUP, timestamp).apply()
    }

    fun resetState() {
        _state.value = TelegramState.Idle
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun getRequest(urlString: String): JSONObject {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        val body = try {
            BufferedReader(InputStreamReader(conn.inputStream)).readText()
        } finally {
            conn.disconnect()
        }
        return JSONObject(body)
    }
}