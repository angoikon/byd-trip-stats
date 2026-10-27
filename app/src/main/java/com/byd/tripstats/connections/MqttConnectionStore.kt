package com.byd.tripstats.connections

import android.content.Context
import android.content.SharedPreferences

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
    private const val KEY_PENDING_EVENTS = "pending_events"

    /** Bounded, so an unreachable broker cannot grow this file without limit. Oldest goes first. */
    private const val MAX_PENDING_EVENTS = 20

    /**
     * The event queue is one preferences string read, modified and written back, and it is touched
     * from both the telemetry thread that closes a trip and the MQTT callback thread that clears a
     * sent one. Without this, a close landing mid-flush is overwritten by the flush, or overwrites
     * it.
     */
    private val eventLock = Any()

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

    /**
     * Writes the settings the user owns. [MqttConnectionConfig.lastStatus] and
     * [MqttConnectionConfig.lastPublishAtMs] are deliberately not among them: they belong to the
     * publisher, and the settings screen saves a config it read when the screen was opened, which
     * on a long drive is minutes of publishing old.
     */
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
            .apply()
    }

    /**
     * Writes the publisher's own two fields and nothing else. This runs on the IO thread once a
     * second while the car is on, so reading the whole config and saving it back put every setting
     * in the way of a broker edit: a save from the settings screen landing between that read and
     * that write was silently restored to the value it replaced.
     *
     * A [publishedAtMs] of zero leaves the stored time alone, so a failed publish does not reset
     * the interval the next tick measures against.
     */
    fun updateStatus(context: Context, status: String, publishedAtMs: Long = 0L) {
        val edit = prefs(context).edit().putString(KEY_LAST_STATUS, status)
        if (publishedAtMs > 0L) edit.putLong(KEY_LAST_PUBLISH_AT_MS, publishedAtMs)
        edit.apply()
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

    /**
     * Holds an event that closed while the broker was unreachable. On DiLink-5 a trip is finalised
     * at cold start, exactly when the client is still connecting, so the close path must never
     * wait on the network.
     */
    fun enqueueEvent(context: Context, payload: String) {
        val dropped = synchronized(eventLock) {
            val prefs = prefs(context)
            val queue = PendingEventQueue.parse(prefs.getString(KEY_PENDING_EVENTS, null))
            val dropped = PendingEventQueue.append(queue, payload, MAX_PENDING_EVENTS)
            writeQueue(prefs, queue)
            dropped
        }
        // A summary evicted here never reaches the broker, so it must at least leave a trace.
        if (dropped.isNotEmpty()) {
            val kinds = dropped.joinToString(", ") { eventKindOf(it) ?: "unreadable" }
            com.byd.tripstats.util.DiagLog.event(
                context, "MqttConnectionStore",
                "MQTT event queue full ($MAX_PENDING_EVENTS): dropped the oldest — $kinds",
            )
        }
    }

    /** The kind of a queued envelope (`trip`, `charge_session`), or null when it can't be read. */
    fun eventKindOf(envelope: String): String? = runCatching {
        org.json.JSONObject(envelope).getString(MqttConnectionManager.KEY_EVENT_KIND)
    }.getOrNull()

    /**
     * Everything still waiting, oldest first. Reading does not remove: an event leaves the queue
     * only in [removeEvent], once the broker has acknowledged it.
     */
    fun readEvents(context: Context): List<String> =
        synchronized(eventLock) { PendingEventQueue.parse(prefs(context).getString(KEY_PENDING_EVENTS, null)) }

    /** Drops one event the broker has taken, matched on the exact payload that was queued. */
    fun removeEvent(context: Context, payload: String) {
        synchronized(eventLock) {
            val prefs = prefs(context)
            val queue = PendingEventQueue.parse(prefs.getString(KEY_PENDING_EVENTS, null))
            if (queue.remove(payload)) writeQueue(prefs, queue)
        }
    }

    fun hasPendingEvents(context: Context): Boolean =
        !prefs(context).getString(KEY_PENDING_EVENTS, null).isNullOrEmpty()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun writeQueue(prefs: SharedPreferences, queue: List<String>) {
        val edit = prefs.edit()
        val encoded = PendingEventQueue.encode(queue)
        if (encoded == null) edit.remove(KEY_PENDING_EVENTS) else edit.putString(KEY_PENDING_EVENTS, encoded)
        // commit(), not apply(), for the same reason the Telegram outbox uses it: these writes
        // happen as the car parks and the process can be killed at any moment, and an un-flushed
        // queue is a lost summary.
        edit.commit()
    }

    fun mask(value: String): String = when {
        value.isBlank() -> ""
        value.length >= 4 -> "••••${value.takeLast(4)}"
        else -> "••••$value"
    }
}
