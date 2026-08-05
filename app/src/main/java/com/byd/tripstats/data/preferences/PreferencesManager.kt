package com.byd.tripstats.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.byd.tripstats.data.config.CarCatalog
import com.byd.tripstats.data.config.CarConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.util.Locale

// The backing file keeps its historical name so existing installs preserve
// their selected car and other preferences across upgrades.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mqtt_settings")

private val SELECTED_CAR_ID             = stringPreferencesKey("selected_car_id")
private val LAST_SEEN_VERSION_CODE       = intPreferencesKey("last_seen_version_code")
private val DASHBOARD_ANIMATIONS_ENABLED = booleanPreferencesKey("dashboard_animations_enabled")
private val KEEP_SERVICE_ALIVE_WHEN_OFF  = booleanPreferencesKey("keep_service_alive_when_off")
private val OFF_STATE_MODE               = stringPreferencesKey("off_state_mode")
private val ELECTRICITY_PRICE            = doublePreferencesKey("electricity_price_per_kwh")
private val CURRENCY_SYMBOL              = stringPreferencesKey("currency_symbol")
// ISO currency code → the symbol used in the tariff currency picker (PreferencesTab.currencyOptions).
// Used only to pre-select a sensible default from the device locale on a fresh install.
private val LOCALE_CURRENCY_SYMBOLS = mapOf(
    "EUR" to "€", "GBP" to "£", "USD" to "$", "AUD" to "A$",
    "THB" to "฿", "BRL" to "R$", "MYR" to "RM",
)
private val SOH_BASELINE_EPOCH_MS        = longPreferencesKey("soh_baseline_epoch_ms") // legacy; migrated
private val SOH_EXCLUSION_MODE           = stringPreferencesKey("soh_exclusion_mode")   // AUTO|CUSTOM|OFF
private val SOH_CUSTOM_CUTOFF_MS         = longPreferencesKey("soh_custom_cutoff_ms")
private val UNIT_SYSTEM                  = stringPreferencesKey("unit_system")
private val THEME_MODE                   = stringPreferencesKey("theme_mode")
private val DASHBOARD_LAYOUT             = stringPreferencesKey("dashboard_layout")
private val DASHBOARD_CARD_ORDER         = stringPreferencesKey("dashboard_card_order")
private val DASHBOARD_HIDDEN_CARDS       = stringPreferencesKey("dashboard_hidden_cards")
private val DASHBOARD_CHART_HIDDEN       = booleanPreferencesKey("dashboard_chart_hidden")
private val DASHBOARD_POWER_ORDER        = stringPreferencesKey("dashboard_power_order")
private val SOC_SOURCE                   = stringPreferencesKey("soc_source")
private val DASHBOARD_SHOW_REMAINING_KWH = booleanPreferencesKey("dashboard_show_remaining_kwh")
private val CAR_OFF_TIMEOUT_MINUTES      = intPreferencesKey("car_off_timeout_minutes")
private val CONFIRM_BEFORE_AUTO_STOP     = booleanPreferencesKey("confirm_before_auto_stop")
private val MIN_TRIP_DISTANCE_KM         = doublePreferencesKey("min_trip_distance_km")
private val WEB_SERVER_ENABLED           = booleanPreferencesKey("web_server_enabled")
private val WEB_SERVER_PORT              = intPreferencesKey("web_server_port")
private val WEB_SERVER_PIN               = stringPreferencesKey("web_server_pin")
private val CELL_IMBALANCE_ALERT_ENABLED = booleanPreferencesKey("cell_imbalance_alert_enabled")
private val CELL_IMBALANCE_THRESHOLD_V   = doublePreferencesKey("cell_imbalance_threshold_v")

const val DEFAULT_CAR_OFF_TIMEOUT_MINUTES = 3
const val DEFAULT_CONFIRM_BEFORE_AUTO_STOP = true
const val DEFAULT_MIN_TRIP_DISTANCE_KM    = 0.0
const val DEFAULT_CELL_IMBALANCE_THRESHOLD_V = 0.05   // 50 mV

class PreferencesManager(private val context: Context) {

    // ── Synchronous bootstrap cache ───────────────────────────────────────────
    // All UI-critical preferences are mirrored into SharedPreferences so
    // collectAsState(initial = cached) renders the correct value on the very
    // first frame without waiting for DataStore's async disk read.
    private val cache: SharedPreferences =
        context.getSharedPreferences("startup_cache", Context.MODE_PRIVATE)

    // ── Selected car ──────────────────────────────────────────────────────────

    val selectedCarId: Flow<String?> = context.dataStore.data
        .map { it[SELECTED_CAR_ID] }
        .onEach { id -> if (id != null) cache.edit().putString("selected_car_id", id).apply() }

    val selectedCarConfig: Flow<CarConfig?> = selectedCarId.map { CarCatalog.fromId(it) }

    fun getCachedSelectedCarId(): String? = cache.getString("selected_car_id", null)

    /** Synchronous — returns CarConfig immediately from SharedPreferences cache.
     *  Safe to call from any thread, including service onCreate/onStartCommand.
     */
    fun getCachedSelectedCarConfig(): com.byd.tripstats.data.config.CarConfig? =
        CarCatalog.fromId(getCachedSelectedCarId())

    suspend fun saveSelectedCar(carId: String) {
        context.dataStore.edit { it[SELECTED_CAR_ID] = carId }
        cache.edit().putString("selected_car_id", carId).apply()
    }

    suspend fun saveInitialSetup(carId: String) = saveSelectedCar(carId)

    suspend fun getSelectedCarId(): String? =
        context.dataStore.data.map { it[SELECTED_CAR_ID] }.first()

    suspend fun getSelectedCarConfig(): CarConfig? = CarCatalog.fromId(getSelectedCarId())

    // ── Animations ────────────────────────────────────────────────────────────

    val dashboardAnimationsEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[DASHBOARD_ANIMATIONS_ENABLED] ?: false }   // off by default; users opt in
        .onEach { cache.edit().putBoolean("animations_enabled", it).apply() }

    /** Synchronous read — safe to use as collectAsState initial value. */
    fun getCachedAnimationsEnabled(): Boolean = cache.getBoolean("animations_enabled", false)

    suspend fun saveDashboardAnimationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[DASHBOARD_ANIMATIONS_ENABLED] = enabled }
        cache.edit().putBoolean("animations_enabled", enabled).apply()
    }

    // ── Dashboard layout (Pro) ────────────────────────────────────────────────
    // CLASSIC is the chart-centric default for everyone; CARDS is the Pro-only
    // customisable tile layout. Gating is enforced at the UI (EntitlementManager);
    // this only persists the user's choice.

    val dashboardLayout: Flow<DashboardLayout> = context.dataStore.data
        .map {
            it[DASHBOARD_LAYOUT]?.let { name -> runCatching { DashboardLayout.valueOf(name) }.getOrNull() }
                ?: DashboardLayout.CLASSIC
        }
        .onEach { cache.edit().putString("dashboard_layout", it.name).apply() }

    fun getCachedDashboardLayout(): DashboardLayout =
        cache.getString("dashboard_layout", null)
            ?.let { runCatching { DashboardLayout.valueOf(it) }.getOrNull() }
            ?: DashboardLayout.CLASSIC

    suspend fun saveDashboardLayout(layout: DashboardLayout) {
        context.dataStore.edit { it[DASHBOARD_LAYOUT] = layout.name }
        cache.edit().putString("dashboard_layout", layout.name).apply()
    }

    // All three are mirrored into the startup cache so the CARDS layout renders the
    // saved order/visibility on the very first frame — no flash of the default order.

    /** Ordered list of the customisable middle-section cards (CARDS layout). */
    val dashboardCardOrder: Flow<List<DashboardCardId>> = context.dataStore.data
        .map { DashboardCardId.parseOrder(it[DASHBOARD_CARD_ORDER]) }
        .onEach { cache.edit().putString("dashboard_card_order", it.joinToString(",") { id -> id.name }).apply() }

    fun getCachedDashboardCardOrder(): List<DashboardCardId> =
        DashboardCardId.parseOrder(cache.getString("dashboard_card_order", null))

    /** Cards the user has hidden in the CARDS layout. */
    val dashboardHiddenCards: Flow<Set<DashboardCardId>> = context.dataStore.data
        .map { DashboardCardId.parseHidden(it[DASHBOARD_HIDDEN_CARDS]) }
        .onEach { cache.edit().putString("dashboard_hidden_cards", it.joinToString(",") { id -> id.name }).apply() }

    fun getCachedDashboardHiddenCards(): Set<DashboardCardId> =
        DashboardCardId.parseHidden(cache.getString("dashboard_hidden_cards", null))

    suspend fun saveDashboardCardLayout(order: List<DashboardCardId>, hidden: Set<DashboardCardId>) {
        val orderCsv = order.joinToString(",") { id -> id.name }
        val hiddenCsv = hidden.joinToString(",") { id -> id.name }
        context.dataStore.edit {
            it[DASHBOARD_CARD_ORDER] = orderCsv
            it[DASHBOARD_HIDDEN_CARDS] = hiddenCsv
        }
        cache.edit().putString("dashboard_card_order", orderCsv).putString("dashboard_hidden_cards", hiddenCsv).apply()
    }

    /** Order of the always-visible power-metric tiles (CARDS layout). */
    val dashboardPowerOrder: Flow<List<PowerMetricId>> = context.dataStore.data
        .map { PowerMetricId.parseOrder(it[DASHBOARD_POWER_ORDER]) }
        .onEach { cache.edit().putString("dashboard_power_order", it.joinToString(",") { id -> id.name }).apply() }

    fun getCachedDashboardPowerOrder(): List<PowerMetricId> =
        PowerMetricId.parseOrder(cache.getString("dashboard_power_order", null))

    suspend fun saveDashboardPowerOrder(order: List<PowerMetricId>) {
        val csv = order.joinToString(",") { id -> id.name }
        context.dataStore.edit { it[DASHBOARD_POWER_ORDER] = csv }
        cache.edit().putString("dashboard_power_order", csv).apply()
    }

    /** Whether the pinned centre range-projection chart is hidden (CARDS layout). */
    val dashboardChartHidden: Flow<Boolean> = context.dataStore.data
        .map { it[DASHBOARD_CHART_HIDDEN] ?: false }
        .onEach { cache.edit().putBoolean("dashboard_chart_hidden", it).apply() }

    fun getCachedDashboardChartHidden(): Boolean = cache.getBoolean("dashboard_chart_hidden", false)

    suspend fun saveDashboardChartHidden(hidden: Boolean) {
        context.dataStore.edit { it[DASHBOARD_CHART_HIDDEN] = hidden }
        cache.edit().putBoolean("dashboard_chart_hidden", hidden).apply()
    }

    // ── Keep service alive when off ───────────────────────────────────────────
    // When true, the telemetry service does NOT self-stop after carOff+notCharging.
    // Benefits: continuous 12V/SoC sampling, ADB-over-WiFi stays reachable without
    // remote-waking the car. Cost: small additional wake-lock/WiFi-lock load on
    // top of the BYD stock-process drain (which is the dominant component).
    // Defaulting to true: real-world data shows our app's contribution to off-state
    // drain is within measurement noise vs. the BYD GPS/telematics baseline, and
    // the convenience win is large.
    val keepServiceAliveWhenOff: Flow<Boolean> = context.dataStore.data
        .map { it[KEEP_SERVICE_ALIVE_WHEN_OFF] ?: true }
        .onEach { cache.edit().putBoolean("keep_service_alive_when_off", it).apply() }

    fun getCachedKeepServiceAliveWhenOff(): Boolean =
        cache.getBoolean("keep_service_alive_when_off", true)

    suspend fun saveKeepServiceAliveWhenOff(enabled: Boolean) {
        context.dataStore.edit { it[KEEP_SERVICE_ALIVE_WHEN_OFF] = enabled }
        cache.edit().putBoolean("keep_service_alive_when_off", enabled).apply()
    }

    // ── Off-state mode ────────────────────────────────────────────────────────
    // Three-way enum replacing the old boolean keepServiceAliveWhenOff:
    //   ENABLED    — service runs 24/7 (old true)
    //   DISABLED   — service stops after 5 min, 90-min wakeup for snapshots (old false)
    //   DEEP_SLEEP — service stops after 5 min, no wakeups, car can fully deep sleep
    // Default is ENABLED (Always On) — the only mode that keeps parked telemetry and
    // off-state charging detection working on cars that cut head-unit power at park.
    // Backward compat: only an EXPLICIT legacy keepServiceAliveWhenOff=false is honoured
    // as DISABLED (Minimal); everyone else — new installs and the old `true` default —
    // gets Always On. An explicit OFF_STATE_MODE choice always wins.

    val offStateMode: Flow<OffStateMode> = context.dataStore.data
        .map { prefs ->
            prefs[OFF_STATE_MODE]
                ?.let { runCatching { OffStateMode.valueOf(it) }.getOrNull() }
                ?: if (prefs[KEEP_SERVICE_ALIVE_WHEN_OFF] == false) OffStateMode.DISABLED
                   else OffStateMode.ENABLED
        }
        .onEach { cache.edit().putString("off_state_mode", it.name).apply() }

    fun getCachedOffStateMode(): OffStateMode =
        cache.getString("off_state_mode", null)
            ?.let { runCatching { OffStateMode.valueOf(it) }.getOrNull() }
            ?: if (cache.contains("keep_service_alive_when_off") && !getCachedKeepServiceAliveWhenOff())
                   OffStateMode.DISABLED
               else OffStateMode.ENABLED

    suspend fun saveOffStateMode(mode: OffStateMode) {
        context.dataStore.edit { it[OFF_STATE_MODE] = mode.name }
        cache.edit().putString("off_state_mode", mode.name).apply()
    }

    // ── Electricity cost ──────────────────────────────────────────────────────

    val electricityPricePerKwh: Flow<Double> = context.dataStore.data
        .map { it[ELECTRICITY_PRICE] ?: 0.0 }
        .onEach { cache.edit().putFloat("electricity_price", it.toFloat()).apply() }

    fun getCachedElectricityPrice(): Double =
        cache.getFloat("electricity_price", 0f).toDouble()

    val currencySymbol: Flow<String> = context.dataStore.data
        .map { it[CURRENCY_SYMBOL] ?: localeDefaultCurrencySymbol() }
        .onEach { cache.edit().putString("currency_symbol", it).apply() }

    fun getCachedCurrencySymbol(): String =
        cache.getString("currency_symbol", localeDefaultCurrencySymbol()) ?: localeDefaultCurrencySymbol()

    /**
     * Currency symbol to pre-select on a fresh install, derived from the device's locale
     * (e.g. RM for Malaysia, ฿ for Thailand, $ for the US). Falls back to € for any locale
     * whose currency the app doesn't offer, or when the locale has no country. Only used as
     * the default — once the user saves a currency, that saved value wins.
     */
    private fun localeDefaultCurrencySymbol(): String = runCatching {
        val code = java.util.Currency.getInstance(java.util.Locale.getDefault()).currencyCode
        LOCALE_CURRENCY_SYMBOLS[code]
    }.getOrNull() ?: "€"

    suspend fun saveElectricityPrice(price: Double, symbol: String) {
        context.dataStore.edit {
            it[ELECTRICITY_PRICE] = price
            it[CURRENCY_SYMBOL]   = symbol
        }
        cache.edit()
            .putFloat("electricity_price", price.toFloat())
            .putString("currency_symbol", symbol)
            .apply()
    }

    // ── Version tracking ──────────────────────────────────────────────────────

    /** Returns 0 if never persisted (i.e. first install or fresh data). */
    suspend fun getLastSeenVersionCode(): Int =
        context.dataStore.data.map { it[LAST_SEEN_VERSION_CODE] ?: 0 }.first()

    suspend fun saveLastSeenVersionCode(versionCode: Int) {
        context.dataStore.edit { it[LAST_SEEN_VERSION_CODE] = versionCode }
    }

    // ── SoH degradation exclusion ─────────────────────────────────────────────
    // The battery degradation chart/trend/report excludes a span of early trips
    // whose SoH was recorded by the legacy *calculated* method (it under-estimated
    // SoH, producing a spurious dip-and-recover). This is a view filter only — it
    // never deletes data and is fully reversible.
    //
    // Three modes:
    //   AUTO   → exclude trips before the statistical-SoH cutover (the default)
    //   CUSTOM → exclude trips before [sohCustomCutoffMs]
    //   OFF    → include everything (legacy values may read low)
    // The effective cutoff is resolved in DashboardViewModel.

    /** Null until the user makes an explicit choice; resolved against the legacy key. */
    val sohExclusionMode: Flow<String?> = context.dataStore.data
        .map { it[SOH_EXCLUSION_MODE] }

    val sohCustomCutoffMs: Flow<Long?> = context.dataStore.data
        .map { it[SOH_CUSTOM_CUTOFF_MS] }

    /** Legacy "baseline" key, kept only so existing users' choice can be migrated. */
    val sohBaselineEpochMs: Flow<Long?> = context.dataStore.data
        .map { it[SOH_BASELINE_EPOCH_MS] }

    suspend fun saveSohExclusionMode(mode: String) {
        context.dataStore.edit {
            it[SOH_EXCLUSION_MODE] = mode
            it.remove(SOH_BASELINE_EPOCH_MS) // supersede the legacy key once a choice is made
        }
    }

    suspend fun saveSohCustomCutoffMs(epochMs: Long) {
        context.dataStore.edit {
            it[SOH_CUSTOM_CUTOFF_MS] = epochMs
            it[SOH_EXCLUSION_MODE] = "CUSTOM"
            it.remove(SOH_BASELINE_EPOCH_MS)
        }
    }

    // ── Unit system ───────────────────────────────────────────────────────────

    val unitSystem: Flow<UnitSystem> = context.dataStore.data
        .map { prefs ->
            prefs[UNIT_SYSTEM]
                ?.let { runCatching { UnitSystem.valueOf(it) }.getOrNull() }
                ?: localeDefaultUnitSystem()
        }
        .onEach { cache.edit().putString("unit_system", it.name).apply() }

    fun getCachedUnitSystem(): UnitSystem =
        cache.getString("unit_system", null)
            ?.let { runCatching { UnitSystem.valueOf(it) }.getOrNull() }
            ?: localeDefaultUnitSystem()

    suspend fun saveUnitSystem(system: UnitSystem) {
        context.dataStore.edit { it[UNIT_SYSTEM] = system.name }
        cache.edit().putString("unit_system", system.name).apply()
    }

    private fun localeDefaultUnitSystem(): UnitSystem =
        if (Locale.getDefault().country.uppercase() == "GB") UnitSystem.IMPERIAL
        else UnitSystem.METRIC

    // ── Theme mode ────────────────────────────────────────────────────────────

    val themeMode: Flow<ThemeMode> = context.dataStore.data
        .map { prefs ->
            prefs[THEME_MODE]
                ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM
        }
        .onEach { cache.edit().putString("theme_mode", it.name).apply() }

    fun getCachedThemeMode(): ThemeMode =
        cache.getString("theme_mode", null)
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.SYSTEM

    suspend fun saveThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[THEME_MODE] = mode.name }
        cache.edit().putString("theme_mode", mode.name).apply()
    }

    // ── SoC source ────────────────────────────────────────────────────────────

    val socSource: Flow<SocSource> = context.dataStore.data
        .map { prefs ->
            prefs[SOC_SOURCE]
                ?.let { runCatching { SocSource.valueOf(it) }.getOrNull() }
                ?: SocSource.PANEL
        }
        .onEach { cache.edit().putString("soc_source", it.name).apply() }

    fun getCachedSocSource(): SocSource =
        cache.getString("soc_source", null)
            ?.let { runCatching { SocSource.valueOf(it) }.getOrNull() }
            ?: SocSource.PANEL

    suspend fun saveSocSource(source: SocSource) {
        context.dataStore.edit { it[SOC_SOURCE] = source.name }
        cache.edit().putString("soc_source", source.name).apply()
    }

    // ── Dashboard battery readout: show remaining kWh instead of SoC % ──────────
    // Dashboard-only view flag on the live SoC tile. When true the tile shows the BMS
    // remaining-EV energy (powerBatteryRemainPowerEV) instead of a SoC percentage;
    // the underlying [socSource] (Panel/BMS) is left untouched so charts/history and the
    // Settings → Preferences choice are unaffected. Tapping the tile toggles this flag,
    // switching the view between the settings-chosen SoC and kWh remaining.
    val dashboardShowRemainingKwh: Flow<Boolean> = context.dataStore.data
        .map { it[DASHBOARD_SHOW_REMAINING_KWH] ?: false }
        .onEach { cache.edit().putBoolean("dashboard_show_remaining_kwh", it).apply() }

    fun getCachedDashboardShowRemainingKwh(): Boolean =
        cache.getBoolean("dashboard_show_remaining_kwh", false)

    suspend fun saveDashboardShowRemainingKwh(show: Boolean) {
        context.dataStore.edit { it[DASHBOARD_SHOW_REMAINING_KWH] = show }
        cache.edit().putBoolean("dashboard_show_remaining_kwh", show).apply()
    }

    // ── Engine-off timeout (trip auto-end) ────────────────────────────────────
    // Minutes the trip stays open after the engine turns off. If the car comes
    // back on within this window the trip resumes seamlessly; otherwise it is
    // auto-ended. Read synchronously by TripRepository on every telemetry tick
    // so the cache mirror must always be in sync with DataStore.

    val carOffTimeoutMinutes: Flow<Int> = context.dataStore.data
        .map { it[CAR_OFF_TIMEOUT_MINUTES] ?: DEFAULT_CAR_OFF_TIMEOUT_MINUTES }
        .onEach { cache.edit().putInt("car_off_timeout_minutes", it).apply() }

    fun getCachedCarOffTimeoutMinutes(): Int =
        cache.getInt("car_off_timeout_minutes", DEFAULT_CAR_OFF_TIMEOUT_MINUTES)
            .coerceAtLeast(1)

    suspend fun saveCarOffTimeoutMinutes(minutes: Int) {
        val clamped = minutes.coerceAtLeast(1)
        context.dataStore.edit { it[CAR_OFF_TIMEOUT_MINUTES] = clamped }
        cache.edit().putInt("car_off_timeout_minutes", clamped).apply()
    }

    // When true, an active trip that hits the car-off timeout while the app is in
    // the foreground is held (not auto-stopped) and a confirmation prompt is shown,
    // so a parked-but-occupied stop (e.g. taking a phone call) doesn't silently end
    // the trip. Backgrounded / screen-off parking still auto-stops as before.
    // Read synchronously by TripRepository, so the cache mirror must stay in sync.
    val confirmBeforeAutoStop: Flow<Boolean> = context.dataStore.data
        .map { it[CONFIRM_BEFORE_AUTO_STOP] ?: DEFAULT_CONFIRM_BEFORE_AUTO_STOP }
        .onEach { cache.edit().putBoolean("confirm_before_auto_stop", it).apply() }

    fun getCachedConfirmBeforeAutoStop(): Boolean =
        cache.getBoolean("confirm_before_auto_stop", DEFAULT_CONFIRM_BEFORE_AUTO_STOP)

    suspend fun saveConfirmBeforeAutoStop(enabled: Boolean) {
        context.dataStore.edit { it[CONFIRM_BEFORE_AUTO_STOP] = enabled }
        cache.edit().putBoolean("confirm_before_auto_stop", enabled).apply()
    }

    // ── Minimum trip distance ─────────────────────────────────────────────────
    // Trips shorter than this on auto-end are deleted (entity, datapoints,
    // segments, stats). Stored in km; the UI converts to/from miles when the
    // user has imperial units selected. 0.0 disables the filter.

    val minTripDistanceKm: Flow<Double> = context.dataStore.data
        .map { it[MIN_TRIP_DISTANCE_KM] ?: DEFAULT_MIN_TRIP_DISTANCE_KM }
        .onEach { cache.edit().putFloat("min_trip_distance_km", it.toFloat()).apply() }

    fun getCachedMinTripDistanceKm(): Double =
        cache.getFloat("min_trip_distance_km", DEFAULT_MIN_TRIP_DISTANCE_KM.toFloat()).toDouble()
            .coerceAtLeast(0.0)

    suspend fun saveMinTripDistanceKm(km: Double) {
        val clamped = km.coerceAtLeast(0.0)
        context.dataStore.edit { it[MIN_TRIP_DISTANCE_KM] = clamped }
        cache.edit().putFloat("min_trip_distance_km", clamped.toFloat()).apply()
    }

    // ── Cell imbalance alert ──────────────────────────────────────────────────
    // Opt-in notification fired by the telemetry service when the live cell
    // voltage spread (batteryCellVoltageMax − batteryCellVoltageMin) stays above
    // the configured threshold. Read synchronously on every telemetry tick, so
    // both the enabled flag and the threshold mirror into the cache.

    val cellImbalanceAlertEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[CELL_IMBALANCE_ALERT_ENABLED] ?: false }
        .onEach { cache.edit().putBoolean("cell_imbalance_alert_enabled", it).apply() }

    fun getCachedCellImbalanceAlertEnabled(): Boolean =
        cache.getBoolean("cell_imbalance_alert_enabled", false)

    suspend fun saveCellImbalanceAlertEnabled(enabled: Boolean) {
        context.dataStore.edit { it[CELL_IMBALANCE_ALERT_ENABLED] = enabled }
        cache.edit().putBoolean("cell_imbalance_alert_enabled", enabled).apply()
    }

    val cellImbalanceThresholdV: Flow<Double> = context.dataStore.data
        .map { it[CELL_IMBALANCE_THRESHOLD_V] ?: DEFAULT_CELL_IMBALANCE_THRESHOLD_V }
        .onEach { cache.edit().putFloat("cell_imbalance_threshold_v", it.toFloat()).apply() }

    fun getCachedCellImbalanceThresholdV(): Double =
        cache.getFloat("cell_imbalance_threshold_v", DEFAULT_CELL_IMBALANCE_THRESHOLD_V.toFloat())
            .toDouble().coerceIn(0.01, 0.5)

    suspend fun saveCellImbalanceThresholdV(v: Double) {
        val clamped = v.coerceIn(0.01, 0.5)
        context.dataStore.edit { it[CELL_IMBALANCE_THRESHOLD_V] = clamped }
        cache.edit().putFloat("cell_imbalance_threshold_v", clamped.toFloat()).apply()
    }

    // ── Web companion server ──────────────────────────────────────────────────

    val webServerEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[WEB_SERVER_ENABLED] ?: true }

    fun getCachedWebServerEnabled(): Boolean =
        context.dataStore.data.let { false }  // non-critical; always read from Flow

    suspend fun saveWebServerEnabled(enabled: Boolean) {
        context.dataStore.edit { it[WEB_SERVER_ENABLED] = enabled }
    }

    val webServerPort: Flow<Int> = context.dataStore.data
        .map { it[WEB_SERVER_PORT] ?: DEFAULT_WEB_SERVER_PORT }

    suspend fun saveWebServerPort(port: Int) {
        context.dataStore.edit { it[WEB_SERVER_PORT] = port }
    }

    val webServerPin: Flow<String> = context.dataStore.data
        .map { it[WEB_SERVER_PIN] ?: "" }

    /** Returns the stored PIN, auto-generating and persisting one if none exists yet. */
    suspend fun getOrCreateWebServerPin(): String {
        val existing = webServerPin.first()
        if (existing.isNotEmpty()) return existing
        val generated = (100_000..999_999).random().toString()
        saveWebServerPin(generated)
        return generated
    }

    suspend fun saveWebServerPin(pin: String) {
        context.dataStore.edit { it[WEB_SERVER_PIN] = pin }
    }

    companion object {
        const val DEFAULT_WEB_SERVER_PORT = 8888
    }
}