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
 * Handles Telegram Bot API backup.
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
        private const val KEY_LAST_AUTO_BACKUP = "last_auto_backup"
        private const val KEY_SCHEDULE = "backup_schedule"
        private const val KEY_AUTO_ENABLED = "auto_backup_enabled"
        private const val BASE_URL = "https://api.telegram.org/bot"

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

    data class TelegramConfig(
        val token: String,
        val chatId: String,
        val botName: String
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

    val lastAutoBackup: String?
        get() = prefs.getString(KEY_LAST_AUTO_BACKUP, null)

    // ── Private loaders ───────────────────────────────────────────────────────

    private fun loadConfig(): TelegramConfig? {
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        val chatId = prefs.getString(KEY_CHAT_ID, null) ?: return null
        val botName = prefs.getString(KEY_BOT_NAME, "") ?: ""
        return TelegramConfig(token, chatId, botName)
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
            val botName = meJson.getJSONObject("result").getString("username")

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
                .apply()

            // Update StateFlow so UI reacts immediately
            _config.value = TelegramConfig(trimmedToken, chatId, botName)

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
            .remove(KEY_LAST_AUTO_BACKUP)
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