package com.byd.tripstats.data.backup

import android.content.Context
import android.util.Log
import com.byd.tripstats.BuildConfig
import com.byd.tripstats.connections.AbrpConnectionStore
import com.byd.tripstats.connections.MqttConnectionStore
import com.byd.tripstats.data.config.CarCatalog
import com.byd.tripstats.data.entitlement.EntitlementManager
import com.byd.tripstats.data.notify.TelegramNotifier
import com.byd.tripstats.data.preferences.DashboardCardId
import com.byd.tripstats.data.preferences.DashboardLayout
import com.byd.tripstats.data.preferences.OffStateMode
import com.byd.tripstats.data.preferences.PowerMetricId
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.data.preferences.SocSource
import com.byd.tripstats.data.preferences.ThemeMode
import com.byd.tripstats.data.preferences.UnitSystem
import com.byd.tripstats.util.TailscaleManager
import com.byd.tripstats.ui.screens.settings.AppDiagnosticsMonitor
import com.byd.tripstats.util.LocaleHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Export/import of everything the app knows that is NOT in the database: the DataStore
 * preferences, the MQTT/ABRP/Telegram connection stores, personal goals, the language
 * override, the tyre-pressure unit and the Pro unlock code.
 *
 * The database backup ([LocalBackupManager]) carries trips and charging sessions; this
 * carries the configuration around them, so a reinstall can be put back exactly as it was.
 *
 * Two rules make this safe to apply to a *running* app:
 *
 *  1. **Import writes through the existing setters** ([PreferencesManager.saveThemeMode],
 *     [MqttConnectionStore.save], …) rather than dropping files into `shared_prefs/` or
 *     `datastore/`. DataStore caches its state in memory and would never see a swapped
 *     file, and PreferencesManager mirrors UI-critical values into its `startup_cache`
 *     SharedPreferences — going through the setters keeps both in sync and makes every
 *     restored value live immediately, with no process kill (which is unsafe on DiLink-5).
 *  2. **A key that is absent is left alone.** Older files, and files written with
 *     credentials excluded, simply don't carry those keys; import must never interpret
 *     that as "set it to empty" and wipe a working configuration.
 *
 * Deliberately NOT included, because they describe the device/session rather than the
 * user's choices, and restoring them would be wrong (or actively harmful):
 * `trip_prefs`, `telemetry_cache`, `charging_shutdown_state`, `vehicle_state_cache`,
 * `mqtt_gps_cache`, `startup_cache` (derived), the ADB permission grants, the probe
 * cache, `maintenance` backfill flags, boot/idle trackers, and `trip_history_prefs`
 * (the history screen's sort and filter state — restoring a stale distance filter would
 * make the trip list look empty).
 *
 * The Pro unlock code IS carried: [EntitlementManager] re-derives the expected code from
 * the live vehicle id on every launch, so a copied code unlocks nothing on another car —
 * but carrying it saves re-entering it after a reinstall. The vehicle id itself is not
 * restored; it comes from telemetry.
 */
object SettingsBackup {

    private const val TAG = "SettingsBackup"

    const val FILE_PREFIX = "byd_stats_settings"
    const val EXTENSION   = ".json"
    const val MIME_TYPE   = "application/json"

    /** Marker so a file picked by hand can be told apart from any other .json. */
    private const val FORMAT = "byd-trip-stats-settings"
    private const val FORMAT_VERSION = 1

    /** Largest file we will even try to parse — a real settings file is ~2 KB. */
    const val MAX_FILE_BYTES = 512L * 1024L

    private const val GOALS_PREFS  = "trip_goals"
    private const val GOAL_CONSUMPTION_KEY = "goal_consumption"
    private const val GOAL_DISTANCE_KEY    = "goal_distance_monthly"
    private const val TYRE_PREFS   = "tyre_unit_prefs"
    private const val TYRE_UNIT_KEY = "unit"

    /** What an import actually did, for the confirmation banner and follow-up actions. */
    data class ImportResult(
        val sections: List<String>,
        /** True when the language override changed — the Activity must be recreated. */
        val localeChanged: Boolean,
        /** True when the file carried tokens/passwords. */
        val credentialsIncluded: Boolean,
        val sourceAppVersion: String,
    )

    // ── Export ────────────────────────────────────────────────────────────────

    /**
     * Serialises the current settings. When [includeCredentials] is false every secret is
     * omitted — MQTT username/password, the ABRP user token, the Telegram bot token and
     * chat id, the web-companion PIN and the Pro unlock code — so the file can be shared.
     */
    suspend fun export(context: Context, includeCredentials: Boolean): String =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val pm  = PreferencesManager(app)

            val root = JSONObject()
            root.put("format", FORMAT)
            root.put("formatVersion", FORMAT_VERSION)
            root.put("appVersion", BuildConfig.VERSION_NAME)
            root.put("createdAt", System.currentTimeMillis())
            root.put("includesCredentials", includeCredentials)

            root.put("preferences", JSONObject().apply {
                pm.getSelectedCarId()?.let { put("selectedCarId", it) }
                put("themeMode",                 pm.themeMode.first().name)
                put("unitSystem",                pm.unitSystem.first().name)
                put("socSource",                 pm.socSource.first().name)
                put("offStateMode",              pm.offStateMode.first().name)
                put("dashboardAnimations",       pm.dashboardAnimationsEnabled.first())
                put("dashboardLayout",           pm.dashboardLayout.first().name)
                put("dashboardCardOrder",        JSONArray(pm.dashboardCardOrder.first().map { it.name }))
                put("dashboardHiddenCards",      JSONArray(pm.dashboardHiddenCards.first().map { it.name }))
                put("dashboardPowerOrder",       JSONArray(pm.dashboardPowerOrder.first().map { it.name }))
                put("dashboardChartHidden",      pm.dashboardChartHidden.first())
                put("dashboardShowRemainingKwh", pm.dashboardShowRemainingKwh.first())
                put("electricityPricePerKwh",    pm.electricityPricePerKwh.first())
                put("currencySymbol",            pm.currencySymbol.first())
                put("carOffTimeoutMinutes",      pm.carOffTimeoutMinutes.first())
                put("confirmBeforeAutoStop",     pm.confirmBeforeAutoStop.first())
                put("minTripDistanceKm",         pm.minTripDistanceKm.first())
                put("cellImbalanceAlertEnabled", pm.cellImbalanceAlertEnabled.first())
                put("cellImbalanceThresholdV",   pm.cellImbalanceThresholdV.first())
                put("webServerEnabled",          pm.webServerEnabled.first())
                put("webServerPort",             pm.webServerPort.first())
                put("settingsBackupIncludeCredentials", pm.settingsBackupIncludeCredentials.first())
                pm.sohExclusionMode.first()?.let { put("sohExclusionMode", it) }
                pm.sohCustomCutoffMs.first()?.let { put("sohCustomCutoffMs", it) }
                if (includeCredentials) {
                    pm.webServerPin.first().takeIf { it.isNotEmpty() }?.let { put("webServerPin", it) }
                }
            })

            val mqtt = MqttConnectionStore.load(app)
            root.put("mqtt", JSONObject().apply {
                put("enabled",                mqtt.enabled)
                put("brokerUrl",              mqtt.brokerUrl)
                put("brokerPort",             mqtt.brokerPort)
                put("friendlyName",           mqtt.friendlyName)
                put("publishIntervalSeconds", mqtt.publishIntervalSeconds)
                put("useTls",                 mqtt.useTls)
                put("useWebSocket",           mqtt.useWebSocket)
                put("webSocketPath",          mqtt.webSocketPath)
                if (includeCredentials) {
                    put("username", mqtt.username)
                    put("password", mqtt.password)
                }
            })

            val abrp = AbrpConnectionStore.load(app)
            root.put("abrp", JSONObject().apply {
                put("enabled",               abrp.enabled)
                put("apiKey",                abrp.apiKey)
                put("uploadIntervalSeconds", abrp.uploadIntervalSeconds)
                if (includeCredentials) put("userToken", abrp.userToken)
            })

            val telegram = TelegramManager.getInstance(app)
            val notifier = TelegramNotifier.getInstance(app)
            root.put("telegram", JSONObject().apply {
                put("schedule",    telegram.schedule.value.name)
                put("autoEnabled", telegram.autoEnabled.value)
                put("wifiOnly",    telegram.wifiOnly.value)
                // Event pushes ride in the same section as the bot they are sent through.
                put("notifyEnabled",       notifier.enabled.value)
                put("notifyTripSummary",   notifier.tripSummaryEnabled.value)
                put("notifyCharging",      notifier.chargingFinishedEnabled.value)
                put("notifyCellImbalance", notifier.cellImbalanceEnabled.value)
                if (includeCredentials) telegram.config.value?.let { cfg ->
                    put("botToken", cfg.token)
                    put("chatId",   cfg.chatId)
                    put("botName",  cfg.botName)
                    put("botId",    cfg.botId)
                }
            })

            // Tailscale: the auth key is a credential like the MQTT password, so it only travels
            // when the user asked for credentials to be included. Without it a restored install is
            // configured but signed out, which is the same contract the other connections have.
            root.put("tailscale", JSONObject().apply {
                put("enabled", TailscaleManager.isEnabled(app))
                put("hostname", TailscaleManager.hostname(app))
                if (includeCredentials) {
                    TailscaleManager.savedAuthKey(app).takeIf { it.isNotBlank() }
                        ?.let { put("authKey", it) }
                }
            })

            val goalPrefs = app.getSharedPreferences(GOALS_PREFS, Context.MODE_PRIVATE)
            root.put("goals", JSONObject().apply {
                goalPrefs.getFloat(GOAL_CONSUMPTION_KEY, 0f).takeIf { it > 0f }
                    ?.let { put("targetConsumptionKwhPer100km", it.toDouble()) }
                goalPrefs.getFloat(GOAL_DISTANCE_KEY, 0f).takeIf { it > 0f }
                    ?.let { put("targetDistanceKmPerMonth", it.toDouble()) }
            })

            root.put("app", JSONObject().apply {
                put("languageTag", LocaleHelper.getSelectedTag(app))
                put("tyrePressureUnit",
                    app.getSharedPreferences(TYRE_PREFS, Context.MODE_PRIVATE).getInt(TYRE_UNIT_KEY, 0))
                put("diagnosticsEnabled", AppDiagnosticsMonitor.isEnabled(app))
            })

            if (includeCredentials) {
                EntitlementManager.savedUnlockCode()?.let { code ->
                    root.put("entitlement", JSONObject().put("unlockCode", code))
                }
            }

            root.toString(2)
        }

    // ── Import ────────────────────────────────────────────────────────────────

    /** True when [text] parses as one of our settings files. */
    fun isSettingsFile(text: String): Boolean = runCatching {
        JSONObject(text).optString("format") == FORMAT
    }.getOrDefault(false)

    /**
     * Applies [text] to the running app. Throws only when the file itself is unusable;
     * a section that fails to apply is logged and skipped so one bad field can't cost
     * the user everything else in the file.
     */
    suspend fun import(context: Context, text: String): ImportResult = withContext(Dispatchers.IO) {
        val app  = context.applicationContext
        val root = JSONObject(text)
        require(root.optString("format") == FORMAT) {
            "Not a BYD Trip Stats settings file."
        }
        val version = root.optInt("formatVersion", 1)
        require(version <= FORMAT_VERSION) {
            "This settings file was written by a newer version of the app (format $version)."
        }

        val applied = mutableListOf<String>()
        var localeChanged = false

        root.optJSONObject("preferences")?.let { p ->
            runCatching { applyPreferences(app, p) }
                .onSuccess { applied += "Preferences" }
                .onFailure { Log.w(TAG, "Preferences section failed: ${it.message}") }
        }

        root.optJSONObject("mqtt")?.let { m ->
            runCatching {
                val current = MqttConnectionStore.load(app)
                MqttConnectionStore.save(app, current.copy(
                    enabled                = m.optBoolean("enabled", current.enabled),
                    brokerUrl              = m.optString("brokerUrl", current.brokerUrl),
                    brokerPort             = m.optInt("brokerPort", current.brokerPort),
                    username               = m.optString("username", current.username),
                    password               = m.optString("password", current.password),
                    friendlyName           = m.optString("friendlyName", current.friendlyName),
                    publishIntervalSeconds = m.optInt("publishIntervalSeconds", current.publishIntervalSeconds),
                    useTls                 = m.optBoolean("useTls", current.useTls),
                    useWebSocket           = m.optBoolean("useWebSocket", current.useWebSocket),
                    webSocketPath          = m.optString("webSocketPath", current.webSocketPath),
                ))
            }.onSuccess { applied += "MQTT" }
                .onFailure { Log.w(TAG, "MQTT section failed: ${it.message}") }
        }

        root.optJSONObject("abrp")?.let { a ->
            runCatching {
                val current = AbrpConnectionStore.load(app)
                AbrpConnectionStore.save(app, current.copy(
                    enabled               = a.optBoolean("enabled", current.enabled),
                    userToken             = a.optString("userToken", current.userToken),
                    apiKey                = a.optString("apiKey", current.apiKey),
                    uploadIntervalSeconds = a.optInt("uploadIntervalSeconds", current.uploadIntervalSeconds),
                ))
            }.onSuccess { applied += "ABRP" }
                .onFailure { Log.w(TAG, "ABRP section failed: ${it.message}") }
        }

        root.optJSONObject("tailscale")?.let { t ->
            runCatching {
                t.optString("hostname").takeIf { it.isNotBlank() }
                    ?.let { TailscaleManager.saveHostname(app, it) }
                // Deliberately NOT started here: joining a tailnet is a network action with a
                // credential, and a restore should not silently put a car on a network. The key is
                // kept so Settings → Connections can connect with one tap.
                t.optString("authKey").takeIf { it.isNotBlank() }?.let { key ->
                    app.getSharedPreferences("tailscale_prefs", Context.MODE_PRIVATE)
                        .edit().putString("auth_key", key).apply()
                }
            }.onSuccess { applied += "Tailscale" }
                .onFailure { Log.w(TAG, "Tailscale section failed: ${it.message}") }
        }

        root.optJSONObject("telegram")?.let { t ->
            runCatching { applyTelegram(app, t) }
                .onSuccess { applied += "Telegram" }
                .onFailure { Log.w(TAG, "Telegram section failed: ${it.message}") }
        }

        root.optJSONObject("goals")?.let { g ->
            runCatching {
                val editor = app.getSharedPreferences(GOALS_PREFS, Context.MODE_PRIVATE).edit()
                if (g.has("targetConsumptionKwhPer100km"))
                    editor.putFloat(GOAL_CONSUMPTION_KEY, g.getDouble("targetConsumptionKwhPer100km").toFloat())
                if (g.has("targetDistanceKmPerMonth"))
                    editor.putFloat(GOAL_DISTANCE_KEY, g.getDouble("targetDistanceKmPerMonth").toFloat())
                editor.apply()
            }.onSuccess { if (g.length() > 0) applied += "Goals" }
                .onFailure { Log.w(TAG, "Goals section failed: ${it.message}") }
        }

        root.optJSONObject("app")?.let { a ->
            runCatching {
                if (a.has("languageTag")) {
                    val tag = a.getString("languageTag")
                    if (tag != LocaleHelper.getSelectedTag(app)) {
                        LocaleHelper.saveTag(app, tag)
                        localeChanged = true
                    }
                }
                if (a.has("tyrePressureUnit")) {
                    app.getSharedPreferences(TYRE_PREFS, Context.MODE_PRIVATE)
                        .edit().putInt(TYRE_UNIT_KEY, a.getInt("tyrePressureUnit")).apply()
                }
                if (a.has("diagnosticsEnabled")) {
                    AppDiagnosticsMonitor.setEnabled(app, a.getBoolean("diagnosticsEnabled"))
                }
            }.onSuccess { applied += "App" }
                .onFailure { Log.w(TAG, "App section failed: ${it.message}") }
        }

        root.optJSONObject("entitlement")?.let { e ->
            runCatching {
                e.optString("unlockCode").takeIf { it.isNotBlank() }?.let {
                    EntitlementManager.restoreUnlockCode(it)
                }
            }.onSuccess { applied += "Pro licence" }
                .onFailure { Log.w(TAG, "Entitlement section failed: ${it.message}") }
        }

        Log.i(TAG, "Settings imported: ${applied.joinToString()} (locale changed=$localeChanged)")

        ImportResult(
            sections            = applied,
            localeChanged       = localeChanged,
            credentialsIncluded = root.optBoolean("includesCredentials", false),
            sourceAppVersion    = root.optString("appVersion", "?"),
        )
    }

    private suspend fun applyPreferences(context: Context, p: JSONObject) {
        val pm = PreferencesManager(context)

        if (p.has("selectedCarId")) {
            val id = p.getString("selectedCarId")
            // Ignore a car this build doesn't know — a null CarConfig would break the
            // projection/energy maths that reads it on every telemetry tick.
            if (CarCatalog.fromId(id) != null) pm.saveSelectedCar(id)
        }
        p.enumOrNull<ThemeMode>("themeMode")?.let     { pm.saveThemeMode(it) }
        p.enumOrNull<UnitSystem>("unitSystem")?.let   { pm.saveUnitSystem(it) }
        p.enumOrNull<SocSource>("socSource")?.let     { pm.saveSocSource(it) }
        p.enumOrNull<OffStateMode>("offStateMode")?.let { pm.saveOffStateMode(it) }
        p.enumOrNull<DashboardLayout>("dashboardLayout")?.let { pm.saveDashboardLayout(it) }

        if (p.has("dashboardAnimations")) pm.saveDashboardAnimationsEnabled(p.getBoolean("dashboardAnimations"))
        if (p.has("dashboardChartHidden")) pm.saveDashboardChartHidden(p.getBoolean("dashboardChartHidden"))
        if (p.has("dashboardShowRemainingKwh")) pm.saveDashboardShowRemainingKwh(p.getBoolean("dashboardShowRemainingKwh"))

        if (p.has("dashboardCardOrder") || p.has("dashboardHiddenCards")) {
            val order  = DashboardCardId.parseOrder(p.csv("dashboardCardOrder")
                ?: pm.dashboardCardOrder.first().joinToString(",") { it.name })
            val hidden = DashboardCardId.parseHidden(p.csv("dashboardHiddenCards")
                ?: pm.dashboardHiddenCards.first().joinToString(",") { it.name })
            pm.saveDashboardCardLayout(order, hidden)
        }
        p.csv("dashboardPowerOrder")?.let { pm.saveDashboardPowerOrder(PowerMetricId.parseOrder(it)) }

        if (p.has("electricityPricePerKwh") || p.has("currencySymbol")) {
            pm.saveElectricityPrice(
                price  = p.optDouble("electricityPricePerKwh", pm.electricityPricePerKwh.first()),
                symbol = p.optString("currencySymbol", pm.currencySymbol.first()),
            )
        }
        if (p.has("carOffTimeoutMinutes")) pm.saveCarOffTimeoutMinutes(p.getInt("carOffTimeoutMinutes"))
        if (p.has("confirmBeforeAutoStop")) pm.saveConfirmBeforeAutoStop(p.getBoolean("confirmBeforeAutoStop"))
        if (p.has("minTripDistanceKm")) pm.saveMinTripDistanceKm(p.getDouble("minTripDistanceKm"))
        if (p.has("cellImbalanceAlertEnabled")) pm.saveCellImbalanceAlertEnabled(p.getBoolean("cellImbalanceAlertEnabled"))
        if (p.has("cellImbalanceThresholdV")) pm.saveCellImbalanceThresholdV(p.getDouble("cellImbalanceThresholdV"))
        if (p.has("webServerEnabled")) pm.saveWebServerEnabled(p.getBoolean("webServerEnabled"))
        if (p.has("webServerPort")) pm.saveWebServerPort(p.getInt("webServerPort"))
        if (p.has("webServerPin")) pm.saveWebServerPin(p.getString("webServerPin"))
        if (p.has("settingsBackupIncludeCredentials"))
            pm.saveSettingsBackupIncludeCredentials(p.getBoolean("settingsBackupIncludeCredentials"))

        // CUSTOM carries a cutoff; saveSohCustomCutoffMs writes both and clears the legacy key.
        val sohMode = p.optString("sohExclusionMode").takeIf { it.isNotBlank() }
        if (sohMode == "CUSTOM" && p.has("sohCustomCutoffMs")) pm.saveSohCustomCutoffMs(p.getLong("sohCustomCutoffMs"))
        else if (sohMode != null) pm.saveSohExclusionMode(sohMode)
    }

    private fun applyTelegram(context: Context, t: JSONObject) {
        val telegram = TelegramManager.getInstance(context)

        // Restore the bot first: setAutoEnabled()/setSchedule() only enqueue the periodic
        // worker when a config exists, so a token restored afterwards would leave auto
        // backup switched on with nothing scheduled until the user touched the toggle.
        if (t.has("botToken") && t.has("chatId")) {
            telegram.restoreConfig(
                token   = t.getString("botToken"),
                chatId  = t.getString("chatId"),
                botName = t.optString("botName", ""),
                botId   = t.optLong("botId", 0L),
            )
        }
        t.optString("schedule").takeIf { it.isNotBlank() }
            ?.let { name -> TelegramManager.Schedule.entries.firstOrNull { it.name == name } }
            ?.let { telegram.setSchedule(it) }
        if (t.has("wifiOnly")) telegram.setWifiOnly(t.getBoolean("wifiOnly"))
        if (t.has("autoEnabled")) telegram.setAutoEnabled(t.getBoolean("autoEnabled"))

        val notifier = TelegramNotifier.getInstance(context)
        if (t.has("notifyTripSummary")) notifier.setTripSummaryEnabled(t.getBoolean("notifyTripSummary"))
        if (t.has("notifyCharging")) notifier.setChargingFinishedEnabled(t.getBoolean("notifyCharging"))
        if (t.has("notifyCellImbalance")) notifier.setCellImbalanceEnabled(t.getBoolean("notifyCellImbalance"))
        // Master switch last: turning it on flushes the outbox, which is pointless until
        // the per-event gates and the bot it sends through are both back in place.
        if (t.has("notifyEnabled")) notifier.setEnabled(t.getBoolean("notifyEnabled"))
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    /** Reads a JSON array of names back as the CSV form the parseOrder() helpers expect. */
    private fun JSONObject.csv(key: String): String? {
        val array = optJSONArray(key) ?: return null
        return (0 until array.length()).joinToString(",") { array.optString(it) }
    }

    private inline fun <reified T : Enum<T>> JSONObject.enumOrNull(key: String): T? =
        optString(key).takeIf { it.isNotBlank() }
            ?.let { name -> enumValues<T>().firstOrNull { it.name == name } }
}
