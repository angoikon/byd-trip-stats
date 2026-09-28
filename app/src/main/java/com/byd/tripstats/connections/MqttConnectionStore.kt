package com.byd.tripstats.connections

import android.content.Context

data class MqttConnectionConfig(
    val enabled: Boolean = false,
    val brokerUrl: String = "",
    val brokerPort: Int = 1883,
    val username: String = "",
    val password: String = "",
    val friendlyName: String = "",
    val publishIntervalSeconds: Int = 1,
    val useTls: Boolean = false,
    val useWebSocket: Boolean = false,
    val webSocketPath: String = "/mqtt",
    val lastStatus: String = "Not configured",
    val lastPublishAtMs: Long = 0L,
)

object MqttConnectionStore {
    private const val PREFS_NAME = "mqtt_connections"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BROKER_URL = "broker_url"
    private const val KEY_BROKER_PORT = "broker_port"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
    private const val KEY_FRIENDLY_NAME = "device_id"
    private const val KEY_PUBLISH_INTERVAL = "publish_interval_seconds"
    private const val KEY_USE_TLS = "use_tls"
    private const val KEY_USE_WEBSOCKET = "use_websocket"
    private const val KEY_WEBSOCKET_PATH = "websocket_path"
    private const val KEY_LAST_STATUS = "last_status"
    private const val KEY_LAST_PUBLISH_AT_MS = "last_publish_at_ms"
    private const val KEY_DISCOVERY_FINGERPRINT = "discovery_fingerprint"
    private const val KEY_DISCOVERY_PUBLISHED_AT = "discovery_published_at_ms"

    private const val DEFAULT_INTERVAL_SECONDS = 1

    fun load(context: Context): MqttConnectionConfig {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val port = prefs.getInt(KEY_BROKER_PORT, 1883)
        return MqttConnectionConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            brokerUrl = prefs.getString(KEY_BROKER_URL, "") ?: "",
            brokerPort = port,
            username = prefs.getString(KEY_USERNAME, "") ?: "",
            password = prefs.getString(KEY_PASSWORD, "") ?: "",
            friendlyName = prefs.getString(KEY_FRIENDLY_NAME, "") ?: "",
            publishIntervalSeconds = prefs.getInt(KEY_PUBLISH_INTERVAL, DEFAULT_INTERVAL_SECONDS).coerceIn(1, 120),
            // Back-compat: configs saved before the TLS flag existed used port 8883 as the TLS signal.
            useTls = prefs.getBoolean(KEY_USE_TLS, port == 8883),
            useWebSocket = prefs.getBoolean(KEY_USE_WEBSOCKET, false),
            webSocketPath = prefs.getString(KEY_WEBSOCKET_PATH, "/mqtt") ?: "/mqtt",
            lastStatus = prefs.getString(KEY_LAST_STATUS, "Not configured") ?: "Not configured",
            lastPublishAtMs = prefs.getLong(KEY_LAST_PUBLISH_AT_MS, 0L)
        )
    }

    fun save(context: Context, config: MqttConnectionConfig) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_BROKER_URL, config.brokerUrl.trim())
            .putInt(KEY_BROKER_PORT, config.brokerPort.coerceIn(1, 65535))
            .putString(KEY_USERNAME, config.username.trim())
            .putString(KEY_PASSWORD, config.password)
            .putString(KEY_FRIENDLY_NAME, config.friendlyName.trim())
            .putInt(KEY_PUBLISH_INTERVAL, config.publishIntervalSeconds.coerceIn(1, 120))
            .putBoolean(KEY_USE_TLS, config.useTls)
            .putBoolean(KEY_USE_WEBSOCKET, config.useWebSocket)
            .putString(KEY_WEBSOCKET_PATH, config.webSocketPath.trim().ifBlank { "/mqtt" })
            .putString(KEY_LAST_STATUS, config.lastStatus)
            .putLong(KEY_LAST_PUBLISH_AT_MS, config.lastPublishAtMs)
            .apply()
    }

    fun updateStatus(context: Context, status: String, publishedAtMs: Long = 0L) {
        val current = load(context)
        save(
            context,
            current.copy(
                lastStatus = status,
                lastPublishAtMs = publishedAtMs.takeIf { it > 0L } ?: current.lastPublishAtMs
            )
        )
    }

    /**
     * The discovery set the broker is believed to already hold, as a digest.
     *
     * Deliberately not part of [MqttConnectionConfig]: it is not a user setting, and the settings
     * screen saves the whole config back, which would wipe it on every unrelated edit.
     */
    fun loadDiscoveryFingerprint(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DISCOVERY_FINGERPRINT, "") ?: ""

    /** When that set was last actually sent, so it can be restated periodically. */
    fun loadDiscoveryPublishedAt(context: Context): Long =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_DISCOVERY_PUBLISHED_AT, 0L)

    fun saveDiscovery(context: Context, fingerprint: String, publishedAtMs: Long) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DISCOVERY_FINGERPRINT, fingerprint)
            .putLong(KEY_DISCOVERY_PUBLISHED_AT, publishedAtMs)
            .apply()
    }

    fun mask(value: String): String = when {
        value.isBlank() -> ""
        value.length >= 4 -> "••••${value.takeLast(4)}"
        else -> "••••$value"
    }
}
