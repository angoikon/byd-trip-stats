package com.byd.tripstats.sdk

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.util.Log
import com.byd.tripstats.BuildConfig
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.byd.tripstats.runtimebridge.RuntimeExtensionBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "VehicleCompatProbe"
private const val PREFS_NAME = "vehicle_compat_probe"
private const val KEY_ENABLED = "enabled"

/**
 * Captures raw telemetry values from every BYD device type and serialises them
 * to a JSON report that users can share for cross-vehicle compatibility debugging.
 *
 * GPS latitude and longitude are not included. Everything else is captured so the
 * report can be used to extend support to additional vehicle variants.
 *
 * Usage:
 *  1. Call [initialize] once on app start.
 *  2. Toggle [setEnabled] from UI.
 *  3. Call [recordDevice] from every log*Snapshot() in BydVehicleDataSource.
 *  4. Call [recordPhevSection] from the slow-tier refresh with all registered devices.
 *  5. Call [buildReportJson] + [exportReportFile] from the UI send button.
 */
object VehicleCompatibilityProbe {

    // ── Public state ──────────────────────────────────────────────────────────

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _entryCount = MutableStateFlow(0)
    val entryCount: StateFlow<Int> = _entryCount.asStateFlow()

    private val _lastCaptureAt = MutableStateFlow<String?>(null)
    val lastCaptureAt: StateFlow<String?> = _lastCaptureAt.asStateFlow()

    // ── Internal state ────────────────────────────────────────────────────────

    private val initialized = AtomicBoolean(false)
    private lateinit var appContext: Context

    /**
     * Per-device snapshot: deviceLabel → (key → rawValueString).
     * We keep only the latest value per key so repeated "same value" calls
     * don't bloat the file.
     */
    private val deviceSnapshots = linkedMapOf<String, LinkedHashMap<String, String>>()
    private val deviceClasses    = linkedMapOf<String, String>()

    // Static metadata captured once
    private var androidBuild: String = ""
    private var appVersionName: String = ""
    private var appVersionCode: Int = 0
    private var captureStartedAt: String = ""

    // ── Vehicle identity (set externally) ─────────────────────────────────────
    @Volatile private var userModelName: String? = null
    @Volatile private var userModelId: String? = null
    @Volatile private var userModelIsPhev: Boolean? = null
    @Volatile private var userModelDrivetrain: String? = null
    @Volatile private var userModelBatteryKwh: Double? = null
    @Volatile private var userModelPhevUsableKwh: Double? = null
    @Volatile private var userModelWltpKm: Int? = null

    // Queue of "changed" events for change-detection log (bounded)
    private val changeLog = ConcurrentLinkedQueue<String>()
    private const val CHANGE_LOG_MAX = 500

    // ── PHEV-specific getter names to sweep across every registered device ────
    // Non-null results on any device are captured under the "phev-sweep" label.
    // Names are supplied by the private extension (probe01) to keep them out of public source.
    private val PHEV_NAMED_GETTERS: List<String> by lazy {
        RuntimeExtensionBridge.methodGroups("probe01").flatMap { (_, v) -> v }
    }

    // ── Temperature getter names to sweep across every registered device ──────
    // Consolidates m36 (pack/avg), m39 (cell max), m40 (cell min), and m51 candidates.
    // Non-null results are captured under the "temp-sweep" label.
    private val TEMP_NAMED_GETTERS: List<String> by lazy {
        RuntimeExtensionBridge.methodGroups("probe03").flatMap { (_, v) -> v }
    }

    // Indexed getter names to probe with indices 0..INDEXED_PROBE_MAX on every device.
    // Covers wheel slots, seat slots, door slots, and low-range feature IDs.
    // Names are supplied by the private extension (probe02) to keep them out of public source.
    private val INDEXED_GETTER_NAMES: Set<String> by lazy {
        RuntimeExtensionBridge.methodGroups("probe02").flatMap { (_, v) -> v }.toSet()
    }
    private const val INDEXED_PROBE_MAX = 6

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Synchronized
    fun initialize(context: Context) {
        if (initialized.getAndSet(true)) return
        appContext = context.applicationContext
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val persisted = prefs.getBoolean(KEY_ENABLED, false)
        _isEnabled.value = persisted
        androidBuild = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) — ${Build.MANUFACTURER} ${Build.MODEL}"
        appVersionName = BuildConfig.VERSION_NAME
        appVersionCode = BuildConfig.VERSION_CODE
    }

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _isEnabled.value = enabled
        if (enabled && captureStartedAt.isBlank()) {
            captureStartedAt = Instant.now().toString()
        }
        Log.i(TAG, "VehicleCompatibilityProbe ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Store vehicle identity so it appears in every exported report.
     *
     * Two callers:
     *  - VehicleTelemetryService — passes the user-selected [CarConfig] fields whenever
     *    the config is loaded or changed.
     *  - BydVehicleDataSource — passes the VIN read from BodyworkDevice.
     *
     * All parameters are optional; only non-null values overwrite the stored field.
     */
    fun setVehicleInfo(
        userModel: String? = null,
        userModelId: String? = null,
        isPhev: Boolean? = null,
        drivetrain: String? = null,
        batteryKwh: Double? = null,
        phevUsableBatteryKwh: Double? = null,
        wltpKm: Int? = null,
    ) {
        userModel?.let     { userModelName = it }
        userModelId?.let   { this.userModelId = it }
        isPhev?.let        { userModelIsPhev = it }
        drivetrain?.let    { userModelDrivetrain = it }
        batteryKwh?.let    { userModelBatteryKwh = it }
        phevUsableBatteryKwh?.let { userModelPhevUsableKwh = it }
        wltpKm?.let        { userModelWltpKm = it }
    }

    // ── Core recording API ────────────────────────────────────────────────────

    /**
     * Records all accessible values from [device] into the snapshot for [label].
     * Called from BydVehicleDataSource.log*Snapshot() methods when enabled.
     *
     * Captures:
     *  - All public no-arg getter methods (numeric, string, boolean, array)
     *  - Public fields (location excluded for privacy)
     *  - Selected 1-arg int getters ([INDEXED_GETTER_NAMES]) with indices 0..[INDEXED_PROBE_MAX]
     *    to surface slot-based data such as tyre pressure per wheel and door state per slot
     */
    fun recordDevice(label: String, device: Any) {
        if (!_isEnabled.value) return
        try {
            val cls = device.javaClass
            val excludedMethods = setOf(
                "getClass", "wait", "notify", "notifyAll", "hashCode", "equals", "toString",
                // VIN uniquely identifies the physical vehicle — exclude for privacy
                "getAutoVIN", "getVIN", "getVin", "getVehicleVIN", "getVehicleIdentificationNumber"
            )
            val changed = mutableListOf<String>()

            // deviceSnapshots/deviceClasses are plain (non-concurrent) maps. This is the loop
            // thread on D3, but on D5 typed listeners can call recordTypedEvent on separate
            // binder threads concurrently — synchronize every mutation/iteration on the same
            // lock so they can never race (ConcurrentModificationException, dropped entries,
            // wrong entryCount).
            synchronized(deviceSnapshots) {
                deviceClasses[label] = cls.name
                val snapshot = deviceSnapshots.getOrPut(label) { LinkedHashMap() }

                // ── No-arg getters ────────────────────────────────────────────
                // NOT on DiLink-5. This blindly invokes EVERY no-arg method on the *injected* OEM
                // device — including side-effecting ones (resetData(), setAllStatus() were both seen
                // in DI5 captures) — and on some DI5 firmware that wedges com.byd.data.collect and
                // boot-loops the head unit (cluster/ADAS fault). D3 uses inert stubs so it stays there.
                // On D5 we keep the field constants + allowlisted indexed getters below and the pushed
                // event path (recordDispatchedFeature); a future event-tap restores the rest safely.
                if (!DiLink5Platform.isDiLink5) {
                    cls.methods
                        .filter { it.parameterCount == 0 && it.name !in excludedMethods }
                        .sortedBy { it.name }
                        .forEach { method ->
                            runCatching {
                                val result = method.invoke(device)
                                val rawStr = encodeValue(result)
                                val prev = snapshot.put(method.name, rawStr)
                                if (prev != rawStr) changed.add("${method.name}=$rawStr")
                            }
                        }
                }

                // ── Public fields (location and VIN excluded) ────────────────
                cls.fields
                    .filter { !it.name.contains("latitude", ignoreCase = true) &&
                              !it.name.contains("longitude", ignoreCase = true) &&
                              !it.name.contains("vin", ignoreCase = true) }
                    .sortedBy { it.name }
                    .forEach { field ->
                        runCatching {
                            val rawStr = encodeValue(field.get(device))
                            val key = "field:${field.name}"
                            val prev = snapshot.put(key, rawStr)
                            if (prev != rawStr) changed.add("$key=$rawStr")
                        }
                    }

                // ── Indexed (1-arg int) getters — wheel, seat, door, cell slots ──
                val indexedMethods = cls.methods
                    .filter { m ->
                        m.parameterCount == 1 &&
                        m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                        m.name in INDEXED_GETTER_NAMES
                    }
                    .sortedBy { it.name }
                if (indexedMethods.isNotEmpty()) {
                    for (idx in 0..INDEXED_PROBE_MAX) {
                        indexedMethods.forEach { method ->
                            runCatching {
                                val result = method.invoke(device, idx)
                                val rawStr = encodeValue(result)
                                // Skip zero/null to keep the report readable; non-zero values
                                // are the diagnostic signal we care about.
                                if (rawStr != "null" && rawStr != "0" && rawStr != "0.0" && rawStr != "false") {
                                    val key = "${method.name}[$idx]"
                                    val prev = snapshot.put(key, rawStr)
                                    if (prev != rawStr) changed.add("$key=$rawStr")
                                }
                            }
                        }
                    }
                }

                _entryCount.value = deviceSnapshots.values.sumOf { it.size }
            }

            val now = Instant.now().toString()
            _lastCaptureAt.value = now
            if (changed.isNotEmpty()) {
                val line = "[$now][$label] ${changed.joinToString(" | ")}"
                changeLog.offer(line)
                while (changeLog.size > CHANGE_LOG_MAX) changeLog.poll()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "recordDevice($label) failed: ${t.message}")
        }
    }

    /**
     * Sweep [PHEV_NAMED_GETTERS] across every device in [devices] and record
     * any non-null result under the "phev-sweep" label.
     *
     * Called once per slow-tier refresh from BydVehicleDataSource. Intentionally
     * runs only when the probe is enabled. Results appear in the report under
     * "deviceSnapshots.phev-sweep" so analysts can quickly see which PHEV-specific
     * getters return data and on which device class.
     */
    fun recordPhevSection(devices: Map<String, Any?>) {
        if (!_isEnabled.value) return
        try {
            val changed = mutableListOf<String>()

            synchronized(deviceSnapshots) {
                val snapshot = deviceSnapshots.getOrPut("phev-sweep") { LinkedHashMap() }
                devices.forEach { (deviceLabel, device) ->
                    if (device == null) return@forEach
                    val cls = device.javaClass
                    PHEV_NAMED_GETTERS.forEach { getterName ->
                        runCatching {
                            val method = cls.methods.firstOrNull {
                                it.name == getterName && it.parameterCount == 0
                            } ?: return@runCatching
                            val result = method.invoke(device) ?: return@runCatching
                            val rawStr = encodeValue(result)
                            if (rawStr != "null" && rawStr != "0" && rawStr != "0.0" && rawStr != "false") {
                                val key = "$deviceLabel.$getterName"
                                val prev = snapshot.put(key, rawStr)
                                if (prev != rawStr) changed.add("$key=$rawStr")
                            }
                        }
                    }
                }
                if (changed.isNotEmpty()) _entryCount.value = deviceSnapshots.values.sumOf { it.size }
            }

            if (changed.isNotEmpty()) {
                val now = Instant.now().toString()
                _lastCaptureAt.value = now
                val line = "[$now][phev-sweep] ${changed.joinToString(" | ")}"
                changeLog.offer(line)
                while (changeLog.size > CHANGE_LOG_MAX) changeLog.poll()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "recordPhevSection failed: ${t.message}")
        }
    }

    /**
     * Sweep [TEMP_NAMED_GETTERS] across every device in [devices] and record any
     * non-null, non-zero result under the "temp-sweep" label, prefixed with the
     * device name so the report shows exactly which device and method name returned data.
     *
     * Called once per slow-tier refresh alongside [recordPhevSection].
     */
    fun recordTemperatureSection(devices: Map<String, Any?>) {
        if (!_isEnabled.value) return
        try {
            val changed = mutableListOf<String>()

            synchronized(deviceSnapshots) {
                val snapshot = deviceSnapshots.getOrPut("temp-sweep") { LinkedHashMap() }
                devices.forEach { (deviceLabel, device) ->
                    if (device == null) return@forEach
                    val cls = device.javaClass
                    TEMP_NAMED_GETTERS.forEach { getterName ->
                        runCatching {
                            val method = cls.methods.firstOrNull {
                                it.name == getterName && it.parameterCount == 0
                            } ?: return@runCatching
                            val result = method.invoke(device) ?: return@runCatching
                            val rawStr = encodeValue(result)
                            if (rawStr != "null" && rawStr != "0" && rawStr != "0.0" && rawStr != "false") {
                                val key = "$deviceLabel.$getterName"
                                val prev = snapshot.put(key, rawStr)
                                if (prev != rawStr) changed.add("$key=$rawStr")
                            }
                        }
                    }
                }
                if (changed.isNotEmpty()) _entryCount.value = deviceSnapshots.values.sumOf { it.size }
            }

            if (changed.isNotEmpty()) {
                val now = Instant.now().toString()
                _lastCaptureAt.value = now
                val line = "[$now][temp-sweep] ${changed.joinToString(" | ")}"
                changeLog.offer(line)
                while (changeLog.size > CHANGE_LOG_MAX) changeLog.poll()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "recordTemperatureSection failed: ${t.message}")
        }
    }

    /**
     * Records named feature-ID getter results from [device] under [label]-features.
     * Used to capture StatisticDevice feature-ID-based fields (cell voltages, SOH, etc.)
     * that are not accessible via reflection on named no-arg methods.
     *
     * [featureEntries] is a map of humanReadableName → featureId; the caller
     * provides both the device and the feature ID list (from RuntimeExtensionBridge).
     */
    fun recordFeatureIdGetters(label: String, device: Any, featureEntries: Map<String, Int>) {
        if (!_isEnabled.value) return
        if (featureEntries.isEmpty()) return
        try {
            val changed = mutableListOf<String>()

            // BYD StatisticDevice-style: device.get(int featureId) → BYDAutoDeviceValue
            val getMethod = device.javaClass.methods.firstOrNull { m ->
                m.name == "get" &&
                m.parameterCount == 1 &&
                m.parameterTypes[0] == Int::class.javaPrimitiveType
            }

            synchronized(deviceSnapshots) {
                val snapshot = deviceSnapshots.getOrPut("$label-features") { LinkedHashMap() }
                featureEntries.forEach { (name, featureId) ->
                    runCatching {
                        val result = getMethod?.invoke(device, featureId) ?: return@runCatching
                        // BYDAutoDeviceValue: try getIntValue() then getDoubleValue()
                        val value: Any? = runCatching {
                            result.javaClass.getMethod("getIntValue").invoke(result)
                        }.getOrNull() ?: runCatching {
                            result.javaClass.getMethod("getDoubleValue").invoke(result)
                        }.getOrNull() ?: runCatching {
                            result.javaClass.getMethod("getStringValue").invoke(result)
                        }.getOrNull()

                        val rawStr = encodeValue(value)
                        if (rawStr != "null" && rawStr != "0" && rawStr != "0.0") {
                            val key = "$name[fid=$featureId]"
                            val prev = snapshot.put(key, rawStr)
                            if (prev != rawStr) changed.add("$key=$rawStr")
                        }
                    }
                }
                if (changed.isNotEmpty()) _entryCount.value = deviceSnapshots.values.sumOf { it.size }
            }

            if (changed.isNotEmpty()) {
                val now = Instant.now().toString()
                _lastCaptureAt.value = now
                val line = "[$now][$label-features] ${changed.joinToString(" | ")}"
                changeLog.offer(line)
                while (changeLog.size > CHANGE_LOG_MAX) changeLog.poll()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "recordFeatureIdGetters($label) failed: ${t.message}")
        }
    }

    /**
     * Records a pushed (listener-delivered) feature-ID event under "[label]-dispatched".
     *
     * Unlike [recordFeatureIdGetters] (synchronous polls), this captures the event channel —
     * the only place SoH actually arrives on these firmwares. Keyed by hex feature ID so each
     * ID's latest raw value is kept; includes a tenths-decode hint (e.g. 1000 → 100.0) so a
     * SoH-looking register is easy to spot. Capturing EVERY id (matched or not) is the point:
     * the real SoH register is one we don't currently map.
     */
    fun recordDispatchedFeature(label: String, featureId: Int, raw: Number) {
        if (!_isEnabled.value) return
        try {
            val d = raw.toDouble()
            val pctHint = when {
                d in 0.0..100.0 -> " (pct≈${"%.1f".format(d)})"
                d in 100.0..1100.0 -> " (÷10≈${"%.1f".format(d / 10.0)})"
                else -> ""
            }
            val key = "0x%08X".format(featureId)
            val value = "${if (d == Math.floor(d)) d.toLong().toString() else d.toString()}$pctHint"

            val changedNow: Boolean
            synchronized(deviceSnapshots) {
                val snapshot = deviceSnapshots.getOrPut("$label-dispatched") { LinkedHashMap() }
                val prev = snapshot.put(key, value)
                changedNow = prev != value
                _entryCount.value = deviceSnapshots.values.sumOf { it.size }
            }

            val now = Instant.now().toString()
            _lastCaptureAt.value = now
            if (changedNow) {
                changeLog.offer("[$now][$label-dispatched] $key=$value")
                while (changeLog.size > CHANGE_LOG_MAX) changeLog.poll()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "recordDispatchedFeature($label) failed: ${t.message}")
        }
    }

    /**
     * Records a value delivered through one of DiLink-5's typed listener callbacks
     * (statistic/charging/speed/tyre/collectdata/instrument/setting — see Dilink5Client)
     * under "[label]-events". DiLink-5 only: the D3 flavor has no equivalent typed-listener
     * tap and this bucket would be meaningless there.
     *
     * Unlike [recordDevice] (disabled on D5 — blindly invoking every no-arg getter on the
     * injected OEM device hit side-effecting methods and boot-looped the head unit), this
     * only forwards values already delivered to an existing, already-firing callback — no
     * new getter or reflection call is made, so it carries none of that risk.
     */
    fun recordTypedEvent(label: String, key: String, value: Any?) {
        if (!DiLink5Platform.isDiLink5) return
        if (!_isEnabled.value) return
        try {
            val rawStr = encodeValue(value)
            val changedNow: Boolean
            synchronized(deviceSnapshots) {
                val snapshot = deviceSnapshots.getOrPut("$label-events") { LinkedHashMap() }
                val prev = snapshot.put(key, rawStr)
                changedNow = prev != rawStr
                _entryCount.value = deviceSnapshots.values.sumOf { it.size }
            }

            val now = Instant.now().toString()
            _lastCaptureAt.value = now
            if (changedNow) {
                changeLog.offer("[$now][$label-events] $key=$rawStr")
                while (changeLog.size > CHANGE_LOG_MAX) changeLog.poll()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "recordTypedEvent($label, $key) failed: ${t.message}")
        }
    }

    // ── Export ────────────────────────────────────────────────────────────────

    /**
     * Serialise the current snapshot to a JSON string ready to write to disk.
     * Structure:
     * {
     *   "schema": 3,
     *   "capturedAt": "...",
     *   "androidBuild": "...",
     *   "deviceClasses": { "climate": "...", ... },
     *   "deviceSnapshots": { "climate": { ... }, "phev-sweep": { ... },
     *                        "statistic-events": { ... }, ... },  // schema 3: DiLink-5 typed-listener taps
     *   "vehicleAnalysis": { "energyMode": "...", "phevSignalsFound": [...] },
     *   "changeLog": [ "...", ... ]
     * }
     */
    fun buildReportJson(): String {
        val root = JSONObject()
        root.put("schema", 3)
        root.put("capturedAt", Instant.now().toString())
        root.put("captureStartedAt", captureStartedAt.ifBlank { Instant.now().toString() })
        root.put("androidBuild", androidBuild)
        root.put("entryCount", _entryCount.value)

        // ── Vehicle identity ──────────────────────────────────────────────────
        val vehicleInfo = JSONObject()
        vehicleInfo.put("appVersion",              "$appVersionName ($appVersionCode)")
        vehicleInfo.put("userSelectedModel",       userModelName ?: "not set")
        vehicleInfo.put("userSelectedModelId",     userModelId ?: "not set")
        vehicleInfo.put("isPhev",                  userModelIsPhev ?: false)
        vehicleInfo.put("drivetrain",              userModelDrivetrain ?: "unknown")
        vehicleInfo.put("batteryKwh",              userModelBatteryKwh ?: 0.0)
        vehicleInfo.put("phevUsableBatteryKwh",    userModelPhevUsableKwh ?: 0.0)
        vehicleInfo.put("wltpKm",                  userModelWltpKm ?: 0)
        root.put("vehicleInfo", vehicleInfo)

        // deviceSnapshots/deviceClasses can be mutated concurrently by D5's typed-listener
        // binder threads (recordTypedEvent) — lock the whole read (including the nested
        // buildVehicleAnalysis() call, safe via Java's reentrant synchronized) so a report
        // can never be serialized mid-mutation.
        synchronized(deviceSnapshots) {
            val classes = JSONObject()
            deviceClasses.forEach { (label, cls) -> classes.put(label, cls) }
            root.put("deviceClasses", classes)

            val snapshots = JSONObject()
            deviceSnapshots.forEach { (label, methods) ->
                val deviceObj = JSONObject()
                methods.forEach { (method, value) -> deviceObj.put(method, value) }
                snapshots.put(label, deviceObj)
            }
            root.put("deviceSnapshots", snapshots)

            // ── PHEV analysis summary ─────────────────────────────────────────
            root.put("vehicleAnalysis", buildVehicleAnalysis())
        }

        val log = JSONArray()
        changeLog.toList().forEach { log.put(it) }
        root.put("changeLog", log)

        return root.toString(2)
    }

    private fun buildVehicleAnalysis(): JSONObject {
        val obj = JSONObject()

        val isConfirmedBev = userModelIsPhev == false

        // Determine if this vehicle is a PHEV from energy mode
        val energySnap = deviceSnapshots["energy"] ?: deviceSnapshots["phev-sweep"]
        val energyMode = energySnap?.get("getEnergyMode")?.toIntOrNull()
            ?: deviceSnapshots["phev-sweep"]?.entries
                ?.firstOrNull { it.key.endsWith(".getEnergyMode") }?.value?.toIntOrNull()
        val vehicleType = when (energyMode) {
            0    -> "BEV (energy mode 0)"
            1    -> if (isConfirmedBev) "BEV-constant (energy mode 1, not PHEV on this firmware)" else "PHEV/EV-mode (energy mode 1)"
            3    -> if (isConfirmedBev) "BEV-constant (energy mode 3, not PHEV on this firmware)" else "PHEV/HEV-mode (energy mode 3)"
            else -> if (energyMode != null) "unknown (energy mode $energyMode)" else "unknown"
        }
        obj.put("detectedVehicleType", vehicleType)
        obj.put("energyMode", energyMode ?: "n/a")

        // List which PHEV getters returned non-zero, non-sentinel results and on which device.
        // Excluded from signal count:
        //  - Sentinel values ≥ 100,000: BYD uses 0xFFFFF (1048575) and 0xFFFFF/10 (104857.5)
        //    as "not applicable" placeholders for fuel/mileage fields on BEV variants.
        //  - getEnergyMode on confirmed BEVs: value 3 (HEV enum) is a firmware constant on
        //    some BEV builds and does not indicate a hybrid powertrain.
        val phevSweep = deviceSnapshots["phev-sweep"]
        val foundSignals = JSONArray()
        val skippedSentinels = JSONArray()
        phevSweep?.forEach { (key, value) ->
            val numeric = value.toDoubleOrNull()
            if (numeric != null && numeric >= 100_000.0) {
                skippedSentinels.put("$key=$value")
                return@forEach
            }
            if (isConfirmedBev && key.endsWith(".getEnergyMode")) return@forEach
            val signal = JSONObject()
            signal.put("key", key)
            signal.put("value", value)
            foundSignals.put(signal)
        }
        obj.put("phevSignalsFound", foundSignals)
        obj.put("phevSignalCount", foundSignals.length())
        if (skippedSentinels.length() > 0) obj.put("phevSentinelsSkipped", skippedSentinels)

        // Tyre pressure presence summary
        val tyreSnap = deviceSnapshots["tyre"]
        val instrSnap = deviceSnapshots["instrument"]
        val tyrePressureSource = when {
            tyreSnap?.any { (k, v) -> k.startsWith("getTyrePressureValue[") && v != "0.0" } == true -> "TyreDevice"
            instrSnap?.any { (k, v) ->
                (k.startsWith("getWheelPressure[") || k.startsWith("getDirectTyrePressValue[") || k.startsWith("getTyrePressureValue[")) && v != "0" && v != "0.0"
            } == true -> "InstrumentDevice (getWheelPressure fallback)"
            else -> "none found"
        }
        obj.put("tyrePressureSource", tyrePressureSource)

        // Gear source summary
        val gearboxSnap = deviceSnapshots["gearbox"]
        val gearSource = when {
            gearboxSnap != null -> "GearboxDevice"
            instrSnap?.containsKey("getCurrentGear") == true -> "InstrumentDevice (fallback)"
            else -> "speed inference only"
        }
        obj.put("gearSource", gearSource)

        // Battery health availability
        val statSnap = deviceSnapshots["statistic"]
        val battSnap = deviceSnapshots["battery"]
        val bmsSnap  = deviceSnapshots["bms"]
        val sohPresent = listOf(statSnap, battSnap, bmsSnap, phevSweep).any { snap ->
            snap?.any { (k, v) ->
                (k.contains("SOH", ignoreCase = true) || k.contains("Health", ignoreCase = true)) && v != "0" && v != "null"
            } == true
        }
        obj.put("batteryHealthAvailable", sohPresent)

        // StatisticDevice registered?
        obj.put("statisticDeviceRegistered", statSnap != null)
        obj.put("gearboxDeviceRegistered", gearboxSnap != null)
        obj.put("tyreDeviceRegistered", tyreSnap != null)

        // ── Battery / cell temperature — full categorisation with cross-validation ──
        val tempSnap = deviceSnapshots["temp-sweep"]
        val tempAnalysis = JSONObject()
        if (tempSnap == null) {
            tempAnalysis.put("note", "temp-sweep has not run yet (charging or power device may not be registered)")
        } else if (tempSnap.isEmpty()) {
            tempAnalysis.put("note", "temp-sweep ran but no getters returned a non-zero value")
        } else {
            val avgEntries = tempSnap.entries.filter { (k, _) ->
                !k.contains("Max", ignoreCase = true) && !k.contains("Min", ignoreCase = true)
            }
            val maxEntries = tempSnap.entries.filter { (k, _) -> k.contains("Max", ignoreCase = true) }
            val minEntries = tempSnap.entries.filter { (k, _) -> k.contains("Min", ignoreCase = true) }

            tempAnalysis.put("packTempAvailable",    avgEntries.isNotEmpty())
            tempAnalysis.put("cellTempMaxAvailable", maxEntries.isNotEmpty())
            tempAnalysis.put("cellTempMinAvailable", minEntries.isNotEmpty())
            tempAnalysis.put("totalGettersWithData", tempSnap.size)

            // All matching sources (may be multiple getters per role)
            if (avgEntries.isNotEmpty()) tempAnalysis.put("packTempSources",    JSONArray(avgEntries.map { "${it.key}=${it.value}" }))
            if (maxEntries.isNotEmpty()) tempAnalysis.put("cellTempMaxSources", JSONArray(maxEntries.map { "${it.key}=${it.value}" }))
            if (minEntries.isNotEmpty()) tempAnalysis.put("cellTempMinSources", JSONArray(minEntries.map { "${it.key}=${it.value}" }))

            // Parsed °C (handles both direct-degree and tenths-of-degree encoding)
            val avgParsed = avgEntries.firstOrNull()?.value?.let { inferTempDegC(it) }
            val maxParsed = maxEntries.firstOrNull()?.value?.let { inferTempDegC(it) }
            val minParsed = minEntries.firstOrNull()?.value?.let { inferTempDegC(it) }
            if (avgParsed != null) tempAnalysis.put("packTempDegC",    avgParsed)
            if (maxParsed != null) tempAnalysis.put("cellTempMaxDegC", maxParsed)
            if (minParsed != null) tempAnalysis.put("cellTempMinDegC", minParsed)

            // Cross-validation — flag physically impossible combinations
            if (avgParsed != null && minParsed != null && minParsed > avgParsed)
                tempAnalysis.put("suspiciousMinTemp",
                    "cellTempMin (${minParsed}°C) > packTemp (${avgParsed}°C) — mislabeled probe getter suspected (observed on Seal Excellence 0x44700020)")
            if (maxParsed != null && avgParsed != null && maxParsed < avgParsed)
                tempAnalysis.put("suspiciousMaxVsAvg",
                    "cellTempMax (${maxParsed}°C) < packTemp (${avgParsed}°C) — physically impossible")
            if (maxParsed != null && minParsed != null && maxParsed < minParsed)
                tempAnalysis.put("suspiciousMaxVsMin",
                    "cellTempMax (${maxParsed}°C) < cellTempMin (${minParsed}°C) — inconsistent readings")
        }
        obj.put("batteryTemperatureAnalysis", tempAnalysis)

        // ── Registered devices summary ────────────────────────────────────────
        val deviceSummary = JSONObject()
        deviceSnapshots.forEach { (label, snap) ->
            val entry = JSONObject()
            entry.put("entryCount", snap.size)
            entry.put("class", deviceClasses[label]?.substringAfterLast('.') ?: "unknown")
            deviceSummary.put(label, entry)
        }
        obj.put("registeredDevicesSummary", deviceSummary)

        // ── StatisticDevice feature-ID values ────────────────────────────────
        val statFeatSnap = deviceSnapshots["statistic-features"]
        if (statFeatSnap != null) {
            val featSummary = JSONObject()
            featSummary.put("totalFeaturesWithData", statFeatSnap.size)
            val values = JSONObject()
            statFeatSnap.forEach { (k, v) -> values.put(k, v) }
            featSummary.put("values", values)
            obj.put("statisticFeatureSummary", featSummary)
        }

        // ── SoH candidate analysis (report-only) ─────────────────────────────
        // Scans the pushed statistic events for any feature ID whose value decodes to a
        // plausible SoH (90–110%), so the report names the SoH register(s) directly. This is
        // purely descriptive — it reads probe data and never feeds the live SoH the app shows.
        val dispatched = deviceSnapshots["statistic-dispatched"]
        if (dispatched != null && dispatched.isNotEmpty()) {
            val sohAnalysis = JSONObject()
            val candidates = JSONArray()
            dispatched.forEach { (idHex, valueStr) ->
                // valueStr looks like "939 (÷10≈93.9)"; parse the leading raw number.
                val raw = valueStr.substringBefore(' ').toDoubleOrNull() ?: return@forEach
                val decoded = when {
                    raw in 0.0..110.0 -> raw                       // whole percent
                    raw in 900.0..1100.0 -> raw / 10.0             // tenths
                    else -> null
                }
                if (decoded != null && decoded in 90.0..110.0) {
                    val c = JSONObject()
                    c.put("featureId", idHex)
                    c.put("raw", valueStr)
                    c.put("decodedSoh", "%.1f".format(decoded))
                    candidates.put(c)
                }
            }
            sohAnalysis.put("dispatchedFeatureCount", dispatched.size)
            sohAnalysis.put("candidates", candidates)
            sohAnalysis.put("note",
                "Feature IDs whose pushed value decodes to 90–110% — likely SoH registers. " +
                "Compare against the SoH shown in-app and in Electro to pick the correct one.")
            obj.put("sohAnalysis", sohAnalysis)
        }

        // ── Charging analysis ─────────────────────────────────────────────────
        val chargingSnap = deviceSnapshots["charging"]
        val chargingAnalysis = JSONObject()
        chargingAnalysis.put("chargingDeviceRegistered", chargingSnap != null)
        if (chargingSnap != null) {
            chargingAnalysis.put("entryCount", chargingSnap.size)
            val powerEntry   = chargingSnap.entries.firstOrNull { (k, _) -> k.contains("Power",   ignoreCase = true) || k.contains("Watt", ignoreCase = true) }
            val currentEntry = chargingSnap.entries.firstOrNull { (k, _) -> k.contains("Current", ignoreCase = true) }
            val modeEntry    = chargingSnap.entries.firstOrNull { (k, _) -> k.contains("Mode",    ignoreCase = true) || k.contains("Type", ignoreCase = true) }
            val statusEntry  = chargingSnap.entries.firstOrNull { (k, _) -> k.contains("Status",  ignoreCase = true) || k.contains("State", ignoreCase = true) }
            val voltageEntry = chargingSnap.entries.firstOrNull { (k, _) -> k.contains("Voltage", ignoreCase = true) }
            if (powerEntry   != null) chargingAnalysis.put("powerGetter",   "${powerEntry.key}=${powerEntry.value}")
            if (currentEntry != null) chargingAnalysis.put("currentGetter", "${currentEntry.key}=${currentEntry.value}")
            if (voltageEntry != null) chargingAnalysis.put("voltageGetter", "${voltageEntry.key}=${voltageEntry.value}")
            if (modeEntry    != null) chargingAnalysis.put("modeGetter",    "${modeEntry.key}=${modeEntry.value}")
            if (statusEntry  != null) chargingAnalysis.put("statusGetter",  "${statusEntry.key}=${statusEntry.value}")
        }
        obj.put("chargingAnalysis", chargingAnalysis)

        // ── Slope / sensor analysis ───────────────────────────────────────────
        val slopeHit = deviceSnapshots.entries
            .flatMap { (label, snap) -> snap.entries.map { Triple(label, it.key, it.value) } }
            .firstOrNull { (_, k, _) -> k.contains("Slope", ignoreCase = true) }
        val slopeAnalysis = JSONObject()
        slopeAnalysis.put("slopeAvailable", slopeHit != null)
        if (slopeHit != null)
            slopeAnalysis.put("slopeGetter", "${slopeHit.first}.${slopeHit.second}=${slopeHit.third}")
        obj.put("slopeAnalysis", slopeAnalysis)

        // ── Motor analysis ────────────────────────────────────────────────────
        val motorSnap = deviceSnapshots["motor"]
        if (motorSnap != null) {
            val motorAnalysis = JSONObject()
            motorAnalysis.put("motorDeviceRegistered", true)
            motorAnalysis.put("entryCount", motorSnap.size)
            listOf("Torque", "Speed", "Power", "Temp", "Current", "Voltage", "Rpm").forEach { kw ->
                motorSnap.entries.firstOrNull { (k, _) -> k.contains(kw, ignoreCase = true) }
                    ?.let { (k, v) -> motorAnalysis.put("${kw.lowercase()}Example", "$k=$v") }
            }
            obj.put("motorAnalysis", motorAnalysis)
        }

        return obj
    }

    /**
     * Upload the current report to litterbox and return the public download URL.
     *
     * Blocking network call — invoke from a background dispatcher. The URL is meant to
     * be embedded in a `mailto:` QR code so the user can email the link from their phone;
     * litterbox auto-deletes the file after [retention] (default 24h).
     */
    fun uploadReport(retention: String = "24h"): String {
        val json = buildReportJson()
        return com.byd.tripstats.util.LitterboxUploader.upload(
            fileName = "byd_compat_probe.json",
            content = json,
            retention = retention
        )
    }

    /**
     * Write the report to the app's cache dir and return a content URI suitable
     * for sharing via [Intent.ACTION_SEND] even on restricted Android builds.
     */
    fun exportReportFile(saveToDownloads: Boolean = false): File {
        val json = buildReportJson()
        val ts = Instant.now().toString().replace(":", "-").replace(".", "-")
        val file = File(appContext.cacheDir, "byd_compat_probe_$ts.json")
        file.writeText(json, Charsets.UTF_8)
        Log.i(TAG, "Probe report written: ${file.absolutePath} (${file.length()} bytes)")
        if (saveToDownloads) exportReportToDownloads(json)
        return file
    }

    private fun exportReportToDownloads(json: String) {
        try {
            val downloadsDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "BydTripStats"
            )
            if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
                throw IllegalStateException("Could not create ${downloadsDir.absolutePath}")
            }
            val file = File(downloadsDir, "compat_probe.json")
            file.writeText(json, Charsets.UTF_8)
            Log.i(TAG, "Probe report exported to filesystem: ${file.absolutePath}")
            return
        } catch (filesystemError: Exception) {
            Log.w(TAG, "Filesystem export failed, trying MediaStore fallback", filesystemError)
        }

        val resolver = appContext.contentResolver
        val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val name = "compat_probe.json"
        val relativePath = "Download/BydTripStats"
        resolver.delete(
            collection,
            "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ? AND ${android.provider.MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
            arrayOf(name, "%BydTripStats%")
        )
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/json")
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("Could not create compat probe file in Download")
        resolver.openOutputStream(uri)?.use { output ->
            output.write(json.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("Could not open compat probe file for writing")
        val published = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
        }
        resolver.update(uri, published, null, null)
        Log.i(TAG, "Probe report exported to Downloads via MediaStore: $uri")
    }

    /**
     * Build a share [Intent] for the report file.
     * Uses FileProvider so it works across all Android versions supported by DiLink.
     */
    fun buildShareIntent(reportFile: File): Intent {
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            reportFile
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_SUBJECT,
                "BYD Trip Stats — Vehicle Compatibility Report"
            )
            putExtra(
                Intent.EXTRA_TEXT,
                "Attached is a vehicle compatibility probe report generated by BYD Trip Stats.\n" +
                "Device: $androidBuild\n" +
                "Captured at: ${_lastCaptureAt.value ?: "n/a"}"
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Clear all accumulated data (useful after sharing or when disabling).
     */
    @Synchronized
    fun clear() {
        synchronized(deviceSnapshots) {
            deviceSnapshots.clear()
            deviceClasses.clear()
        }
        changeLog.clear()
        captureStartedAt = ""
        _entryCount.value = 0
        _lastCaptureAt.value = null
        // Vehicle identity is intentionally preserved across clear() so users don't
        // have to re-select their car after resetting the probe data.
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Try to interpret a raw SDK string as a temperature in °C.
     * SDK may return direct degrees (22.7) or tenths-of-degree (227).
     * Returns null if the value is outside any plausible range.
     */
    private fun inferTempDegC(rawStr: String): Double? {
        val v = rawStr.toDoubleOrNull() ?: return null
        return when {
            v in -50.0..80.0   -> v           // direct °C
            v in -500.0..800.0 -> v / 10.0    // tenths of °C
            else               -> null
        }
    }

    private fun encodeValue(value: Any?): String {
        if (value == null) return "null"
        return when (value) {
            is ByteArray   -> value.decodeToString().ifBlank { "ByteArray(${value.size})" }
            is IntArray    -> value.joinToString(",", "[", "]")
            is FloatArray  -> value.joinToString(",", "[", "]")
            is DoubleArray -> value.joinToString(",", "[", "]")
            is BooleanArray -> value.joinToString(",", "[", "]")
            is Array<*>    -> value.joinToString(",", "[", "]")
            else           -> value.toString()
        }
    }
}
