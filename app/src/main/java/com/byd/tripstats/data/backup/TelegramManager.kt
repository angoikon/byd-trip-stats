package com.byd.tripstats.data.backup

import android.content.Context
import android.util.Log
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
        private const val BASE_URL = "https://api.telegram.org/bot"

        @Volatile private var INSTANCE: TelegramManager? = null

        fun getInstance(context: Context): TelegramManager {
            return INSTANCE ?: synchronized(this) {
                TelegramManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

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

    val config: TelegramConfig?
        get() {
            val token = prefs.getString(KEY_TOKEN, null) ?: return null
            val chatId = prefs.getString(KEY_CHAT_ID, null) ?: return null
            val botName = prefs.getString(KEY_BOT_NAME, "") ?: ""
            return TelegramConfig(token, chatId, botName)
        }

    val isConfigured: Boolean get() = config != null

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

            _state.value = TelegramState.Success(
                "Connected to @$botName\nChat ID: $chatId"
            )
            Log.i(TAG, "Telegram configured: bot=@$botName chatId=$chatId")

        } catch (e: Exception) {
            Log.e(TAG, "Setup failed", e)
            _state.value = TelegramState.Error("Setup failed: ${e.message}")
        }
    }

    fun clearConfig() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_CHAT_ID).remove(KEY_BOT_NAME).apply()
        _state.value = TelegramState.Idle
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    /**
     * Sends [file] to the configured Telegram chat as a document.
     * Uses chunked multipart/form-data so large files don't need to be fully
     * buffered — Telegram supports up to 50 MB per document.
     */
    suspend fun sendFile(file: File, caption: String = "") = withContext(Dispatchers.IO) {
        try {
            val cfg = config ?: throw Exception("Telegram not configured.")
            _state.value = TelegramState.InProgress("Sending to Telegram…")

            val boundary = "----BydBackup${System.currentTimeMillis()}"
            val url = URL("$BASE_URL${cfg.token}/sendDocument")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setChunkedStreamingMode(4096)
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            DataOutputStream(conn.outputStream).use { out ->
                // chat_id field
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n")
                out.writeBytes("${cfg.chatId}\r\n")

                // caption field (optional)
                if (caption.isNotEmpty()) {
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"caption\"\r\n\r\n")
                    out.writeBytes("$caption\r\n")
                }

                // document field — stream directly from disk
                out.writeBytes("--$boundary\r\n")
                out.writeBytes(
                    "Content-Disposition: form-data; name=\"document\"; filename=\"${file.name}\"\r\n"
                )
                out.writeBytes("Content-Type: application/octet-stream\r\n\r\n")
                FileInputStream(file).use { fis -> fis.copyTo(out) }
                out.writeBytes("\r\n")

                out.writeBytes("--$boundary--\r\n")
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
                _state.value = TelegramState.Success(
                    "Sent to Telegram ✓\n${file.name}"
                )
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