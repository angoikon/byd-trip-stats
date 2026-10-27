package com.byd.tripstats.connections

import android.content.Context

data class AbrpConnectionConfig(
    val enabled: Boolean = false,
    val userToken: String = "",
    val apiKey: String = "",
    val uploadIntervalSeconds: Int = 30,
    val lastStatus: String = "Not configured",
    val lastUploadAtMs: Long = 0L,
)

object AbrpConnectionStore {
    private const val PREFS_NAME = "abrp_connections"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_USER_TOKEN = "user_token"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_UPLOAD_INTERVAL = "upload_interval_seconds"
    private const val KEY_LAST_STATUS = "last_status"
    private const val KEY_LAST_UPLOAD_AT_MS = "last_upload_at_ms"

    const val DEFAULT_PUBLIC_API_KEY = "f5f2bc68-b7de-4c5a-8318-59219335370d"
    private const val DEFAULT_INTERVAL_SECONDS = 30

    fun load(context: Context): AbrpConnectionConfig {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedApiKey = prefs.getString(KEY_API_KEY, DEFAULT_PUBLIC_API_KEY)?.trim().orEmpty()
        return AbrpConnectionConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            userToken = prefs.getString(KEY_USER_TOKEN, "") ?: "",
            apiKey = storedApiKey.ifBlank { DEFAULT_PUBLIC_API_KEY },
            uploadIntervalSeconds = prefs.getInt(KEY_UPLOAD_INTERVAL, DEFAULT_INTERVAL_SECONDS).coerceIn(5, 120),
            lastStatus = prefs.getString(KEY_LAST_STATUS, "Not configured") ?: "Not configured",
            lastUploadAtMs = prefs.getLong(KEY_LAST_UPLOAD_AT_MS, 0L)
        )
    }

    /**
     * Writes the settings the user owns. [AbrpConnectionConfig.lastStatus] and
     * [AbrpConnectionConfig.lastUploadAtMs] are deliberately not among them: they belong to the
     * uploader, and the settings screen saves a config it read when the screen was opened.
     */
    fun save(
        context: Context,
        config: AbrpConnectionConfig
    ) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_USER_TOKEN, config.userToken.trim())
            .putString(KEY_API_KEY, config.apiKey.trim().ifBlank { DEFAULT_PUBLIC_API_KEY })
            .putInt(KEY_UPLOAD_INTERVAL, config.uploadIntervalSeconds.coerceIn(5, 120))
            .apply()
    }

    /**
     * Writes the uploader's own two fields and nothing else, so an upload finishing between the
     * settings screen's read and its save cannot restore the token it replaced.
     */
    fun updateStatus(context: Context, status: String, uploadedAtMs: Long = 0L) {
        val edit = prefs(context).edit().putString(KEY_LAST_STATUS, status)
        if (uploadedAtMs > 0L) edit.putLong(KEY_LAST_UPLOAD_AT_MS, uploadedAtMs)
        edit.apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun maskToken(token: String): String = when {
        token.isBlank() -> ""
        token.length >= 4 -> "••••${token.takeLast(4)}"
        else -> "••••$token"
    }
}
