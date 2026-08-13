package com.byd.tripstats.connections

import android.content.Context
import android.util.Log
import com.byd.tripstats.data.model.VehicleTelemetry
import com.byd.tripstats.data.preferences.PreferencesManager
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import android.provider.Settings
import android.os.Build
import com.byd.tripstats.ui.components.energyModeLabel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.time.Instant
import kotlin.math.abs

class MqttConnectionManager(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val inFlight = AtomicBoolean(false)
    @Volatile private var client: Mqtt3AsyncClient? = null
    @Volatile private var connected = false
    @Volatile private var lastEndpoint: String = ""
    @Volatile private var currentDeviceId: String = ""
    @Volatile private var discoveryPublishedForId: String = ""
    @Volatile private var lastOnlineAssertMs: Long = 0L
    @Volatile private var lastKnownLat: Double = 0.0
    @Volatile private var lastKnownLon: Double = 0.0
    @Volatile private var lastKnownAlt: Double = 0.0
    @Volatile private var lastKnownHeading: Double? = null

    init {
        val prefs = appContext.getSharedPreferences("mqtt_gps_cache", android.content.Context.MODE_PRIVATE)
        lastKnownLat = Double.fromBits(prefs.getLong("lat", 0L))
        lastKnownLon = Double.fromBits(prefs.getLong("lon", 0L))
        lastKnownAlt = Double.fromBits(prefs.getLong("alt", 0L))
    }

    private fun persistGps() {
        appContext.getSharedPreferences("mqtt_gps_cache", android.content.Context.MODE_PRIVATE)
            .edit()
            .putLong("lat", lastKnownLat.toBits())
            .putLong("lon", lastKnownLon.toBits())
            .putLong("alt", lastKnownAlt.toBits())
            .apply()
    }

    private val availabilityTopic get() = "byd-trip-stats/$currentDeviceId/availability"
    private val stateTopic get() = "byd-trip-stats/$currentDeviceId/state"

    fun onTelemetry(
        telemetry: VehicleTelemetry,
        scope: CoroutineScope
    ) {
        val config = MqttConnectionStore.load(appContext)
        if (!config.enabled || config.brokerUrl.isBlank()) return
        val intervalMs = if (telemetry.isCarOn) {
            config.publishIntervalSeconds.coerceIn(1, 120) * 1000L
        } else {
            30_000L
        }
        val now = System.currentTimeMillis()
        val lastPublished = MqttConnectionStore.load(appContext).lastPublishAtMs
        if (lastPublished > 0L && now - lastPublished < intervalMs) return
        if (!inFlight.compareAndSet(false, true)) return

        scope.launch(Dispatchers.IO) {
            try {
                val ok = publish(buildPayload(telemetry), config)
                val status = if (ok) "MQTT publish ok" else "MQTT publish failed"
                if (ok) {
                    MqttConnectionStore.updateStatus(appContext, status, now)
                } else {
                    MqttConnectionStore.updateStatus(appContext, status)
                }
            } finally {
                inFlight.set(false)
            }
        }
    }

    fun testPublish(telemetry: VehicleTelemetry): Pair<Boolean, String> {
        val config = MqttConnectionStore.load(appContext)
        if (!config.enabled || config.brokerUrl.isBlank()) {
            return false to "MQTT is not configured"
        }
        val ok = publish(buildPayload(telemetry), config)
        val status = if (ok) "MQTT test publish ok" else "MQTT test publish failed"
        if (ok) {
            MqttConnectionStore.updateStatus(appContext, status, System.currentTimeMillis())
        } else {
            MqttConnectionStore.updateStatus(appContext, status)
        }
        return ok to status
    }

    fun shutdown() {
        synchronized(lock) {
            runCatching { client?.disconnect() }
            client = null
            connected = false
            lastEndpoint = ""
        }
    }

    private fun ensureConnected(config: MqttConnectionConfig): Boolean {
        val resolvedId = resolveDeviceId(config)
        val endpoint = "${config.brokerUrl.trim()}:${config.brokerPort}"
        synchronized(lock) {
            if (connected && client != null && endpoint == lastEndpoint && resolvedId == currentDeviceId) return true
            shutdown()
            lastEndpoint = endpoint
            currentDeviceId = resolvedId
            val avTopic = "byd-trip-stats/$resolvedId/availability"
            val stTopic = "byd-trip-stats/$resolvedId/state"

            // Do NOT use automaticReconnect — it reconnects internally and bypasses
            // ensureConnected(), so the availability "online" publish never fires
            // after a WiFi drop/LWT. We rely on ensureConnected() on every tick:
            // a failed publish resets connected=false and the next tick does a full
            // reconnect that re-publishes "online".
            val builder = MqttClient.builder()
                .useMqttVersion3()
                .identifier("BydTripStats")
                .serverHost(config.brokerUrl.trim())
                .serverPort(config.brokerPort.coerceIn(1, 65535))

            // WebSocket transport lets users front the broker with a reverse proxy
            // (nginx/Traefik/Caddy) that speaks HTTP/WS rather than raw MQTT TCP.
            if (config.useWebSocket) {
                builder.webSocketConfig()
                    // HiveMQ expects the path without a leading slash (e.g. "mqtt").
                    .serverPath(config.webSocketPath.trim().removePrefix("/").ifBlank { "mqtt" })
                    .applyWebSocketConfig()
            }

            // wss:// = WebSocket + TLS; otherwise TLS over raw TCP (mqtts).
            if (config.useTls) {
                builder.sslWithDefaultConfig()
            }

            if (config.username.isNotBlank()) {
                builder.simpleAuth()
                    .username(config.username.trim())
                    .password(config.password.toByteArray())
                    .applySimpleAuth()
            }

            builder.identifier("BydTripStats-$resolvedId")
            val newClient = builder.buildAsync()
            val latch = CountDownLatch(1)
            var ok = false
            // Set Last Will and Testament so Home Assistant sees offline on unexpected disconnect
            try {
                newClient.connectWith()
                    .cleanSession(true)
                    .willPublish()
                        .topic(avTopic)
                        .payload("offline".toByteArray(Charsets.UTF_8))
                        .qos(MqttQos.AT_LEAST_ONCE)
                        .retain(true)
                    .applyWillPublish()
                    .send()
                    .whenComplete { _, throwable ->
                        ok = throwable == null
                        if (throwable != null) {
                            Log.e(TAG, "MQTT connect failed", throwable)
                        }
                        latch.countDown()
                    }
            } catch (t: Throwable) {
                // Fallback to normal connect if willPublish not supported by client
                newClient.connectWith()
                    .cleanSession(true)
                    .send()
                    .whenComplete { _, throwable ->
                        ok = throwable == null
                        if (throwable != null) {
                            Log.e(TAG, "MQTT connect failed", throwable)
                        }
                        latch.countDown()
                    }
            }
            latch.await(10, TimeUnit.SECONDS)
            connected = ok
            client = if (ok) newClient else null
            if (ok) {
                try {
                    // Discovery first (retained) so HA knows entities before seeing them go online
                    if (discoveryPublishedForId != resolvedId) {
                        publishAllDiscovery(config, resolvedId, avTopic, stTopic)
                        discoveryPublishedForId = resolvedId
                    }
                    // Then mark available — state arrives right after from publish()
                    publishRetained(avTopic, "online")
                    lastOnlineAssertMs = System.currentTimeMillis()
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to publish discovery/availability", e)
                }
            }
            return ok
        }
    }

    private fun publish(payload: JSONObject, config: MqttConnectionConfig): Boolean {
        if (!ensureConnected(config)) return false
        val activeClient = client ?: return false
        val latch = CountDownLatch(1)
        var ok = false
        try {
            activeClient.publishWith()
                .topic(stateTopic)
                .payload(payload.toString().toByteArray(Charsets.UTF_8))
                .qos(MqttQos.AT_LEAST_ONCE)
                .retain(true)
                .send()
                .whenComplete { _, throwable ->
                    ok = throwable == null
                    if (throwable != null) {
                        Log.e(TAG, "MQTT publish failed", throwable)
                    }
                    latch.countDown()
                }
            latch.await(10, TimeUnit.SECONDS)
            if (!ok) synchronized(lock) { connected = false }
            // Re-assert retained "online" periodically. The will only ever publishes
            // "offline", and we otherwise publish "online" just once on connect — so a
            // stray "offline" (e.g. a retained will that lands after a same-client-id
            // takeover) can leave Home Assistant stuck "unavailable" even while state
            // keeps flowing. Re-asserting here lets availability self-heal within ~30s.
            if (ok) {
                val now = System.currentTimeMillis()
                if (now - lastOnlineAssertMs >= ONLINE_REASSERT_INTERVAL_MS) {
                    lastOnlineAssertMs = now
                    publishRetained(availabilityTopic, "online")
                }
            }
            return ok
        } catch (t: Throwable) {
            Log.e(TAG, "MQTT publish error", t)
            synchronized(lock) { connected = false }
            return false
        }
    }

    private fun publishRetained(topic: String, payload: String) {
        val activeClient = client ?: return
        try {
            val latch = CountDownLatch(1)
            activeClient.publishWith()
                .topic(topic)
                .payload(payload.toByteArray(Charsets.UTF_8))
                .qos(MqttQos.AT_LEAST_ONCE)
                .retain(true)
                .send()
                .whenComplete { _, throwable ->
                    if (throwable != null) Log.e(TAG, "publishRetained failed", throwable)
                    latch.countDown()
                }
            latch.await(5, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            Log.w(TAG, "publishRetained error", t)
        }
    }

    private fun publishAllDiscovery(
        config: MqttConnectionConfig,
        resolvedId: String,
        avTopic: String,
        stTopic: String
    ) {
        val displayName = config.friendlyName.trim().ifBlank { "BYD Trip Stats" }
        val device = JSONObject()
            .put("identifiers", org.json.JSONArray().put("byd-trip-stats_$resolvedId"))
            .put("name", displayName)
            .put("manufacturer", "angoikon")
            .put("model", "BYD vehicle")

        data class SensorDef(val id: String, val name: String, val unit: String?, val deviceClass: String?, val stateClass: String?, val valueTemplate: String? = null)

        val allSensors = listOf(
            SensorDef("current_datetime", "Current Datetime", null, "timestamp", null,
                valueTemplate = "{{ value_json.current_datetime | as_datetime | as_local }}"),
            SensorDef("soc", "State of Charge", "%", "battery", "measurement"),
            SensorDef("soc_panel", "SOC (Panel)", "%", "battery", "measurement"),
            SensorDef("battery_12v_voltage", "Battery 12V Voltage", "V", "voltage", null),
            SensorDef("battery_total_voltage", "Battery Total Voltage", "V", "voltage", null),
            SensorDef("battery_total_current", "Battery Total Current", "A", "current", "measurement"),
            SensorDef("electric_driving_range_km", "Driving Range", "km", null, "measurement"),
            SensorDef("total_discharge", "Total Discharge", "kWh", null, "measurement"),
            SensorDef("speed", "Speed", "km/h", "speed", null),
            SensorDef("gear", "Gear", null, null, null),
            SensorDef("odometer", "Odometer", "km", null, "total_increasing"),
            SensorDef("engine_power", "Engine Power", "kW", "power", "measurement"),
            SensorDef("engine_speed_front", "Engine Speed Front", "rpm", null, "measurement"),
            SensorDef("engine_speed_rear", "Engine Speed Rear", "rpm", null, "measurement"),
            // Gross charge power on cars with a working m33 getter (e.g. Seal); on m33-dead cars
            // (e.g. Atto 3) it falls back to a NET-into-battery rate that reads lower than engine_power
            // and dips with accessory/aircon load. See effectiveChargingPowerKw in BydVehicleDataSource.
            SensorDef("charging_power", "Charging Power", "kW", "power", "measurement"),
            SensorDef("fuel_percentage", "Fuel Percentage", "%", null, "measurement"),
            SensorDef("fuel_driving_range_km", "Fuel Driving Range", "km", null, "measurement"),
            SensorDef("drive_mode", "Drive Mode", null, null, null),
            SensorDef("regen_mode", "Regen Mode", null, null, null),
            // PHEV powertrain source (EV / Force EV / HEV / Fuel / Keep). Publishes
            // "—" on BEVs, where energyMode is always 0.
            SensorDef("energy_mode", "Energy Mode", null, null, null),
            SensorDef("location_latitude", "Latitude", null, null, null),
            SensorDef("location_longitude", "Longitude", null, null, null),
            SensorDef("location_altitude", "Altitude", "m", null, null),
            SensorDef("heading", "Heading", "°", null, null),
            SensorDef("ext_temp", "External Temperature", "°C", "temperature", "measurement"),
            SensorDef("cabin_temp", "Cabin Temperature", "°C", "temperature", "measurement"),
            SensorDef("soh", "State of Health", "%", null, "measurement"),
            SensorDef("statistic_soh", "Statistic SOH", "%", null, "measurement"), // Falls back to capacity-based soh or remaining-energy-based 
            SensorDef("available_power", "Available Power", "kW", "power", "measurement"),
            SensorDef("battery_remain_power_ev", "Battery Remaining Energy EV", "kWh", null, "measurement"),
            SensorDef("battery_pack_temp", "Battery Pack Temp", "°C", "temperature", "measurement"),
            SensorDef("battery_cell_temp_min", "Cell Temp Min", "°C", "temperature", "measurement"),
            SensorDef("battery_cell_temp_max", "Cell Temp Max", "°C", "temperature", "measurement"),
            SensorDef("battery_cell_temp_avg", "Cell Temp Avg", "°C", "temperature", "measurement"),
            SensorDef("cell_voltage_min", "Cell Voltage Min", "V", "voltage", "measurement"),
            SensorDef("cell_voltage_max", "Cell Voltage Max", "V", "voltage", "measurement"),
            SensorDef("wifi_ssid", "WiFi SSID", null, null, null),
            SensorDef("tyre_pressure_left_front_psi", "Tyre Pressure LF", "psi", null, "measurement"),
            SensorDef("tyre_pressure_right_front_psi", "Tyre Pressure RF", "psi", null, "measurement"),
            SensorDef("tyre_pressure_left_rear_psi", "Tyre Pressure LR", "psi", null, "measurement"),
            SensorDef("tyre_pressure_right_rear_psi", "Tyre Pressure RR", "psi", null, "measurement"),
            SensorDef("tyre_temperature_left_front_c", "Tyre Temp LF", "°C", "temperature", "measurement"),
            SensorDef("tyre_temperature_right_front_c", "Tyre Temp RF", "°C", "temperature", "measurement"),
            SensorDef("tyre_temperature_left_rear_c", "Tyre Temp LR", "°C", "temperature", "measurement"),
            SensorDef("tyre_temperature_right_rear_c", "Tyre Temp RR", "°C", "temperature", "measurement"),
        )

        for ((id, name, unit, deviceClass, stateClass, valueTemplate) in allSensors) {

            val discovery = JSONObject()
                .put("name", "$name")
                .put("unique_id", "byd-trip-stats_${resolvedId}_$id")
                .put("state_topic", stTopic)
                .put("value_template", valueTemplate ?: "{{ value_json.$id }}")
                .put("availability_topic", avTopic)
                .put("device", device)

            if (unit != null) discovery.put("unit_of_measurement", unit)
            if (deviceClass != null) discovery.put("device_class", deviceClass)
            if (stateClass != null) discovery.put("state_class", stateClass)

            val topic = "homeassistant/sensor/$resolvedId/$id/config"
            publishRetained(topic, discovery.toString().replace("\\/", "/"))
        }

        data class BinarySensorDef(val id: String, val name: String, val deviceClass: String?, val valueTemplate: String? = null)
        val binarySensors = listOf(
            BinarySensorDef("is_charging", "Charging", "battery_charging"),
            BinarySensorDef("car_on", "Car On", "running"),
            // device_class lock: ON = Unlocked, OFF = Locked — invert so true (locked) maps to OFF
            BinarySensorDef("car_locked", "Locked", "lock",
                valueTemplate = "{{ (not value_json.car_locked) | lower }}"),
            BinarySensorDef("any_door_opened", "Door Open", "door"),
        )
        for ((id, name, deviceClass, valueTemplate) in binarySensors) {
            val discovery = JSONObject()
                .put("name", "$name")
                .put("unique_id", "byd-trip-stats_${resolvedId}_$id")
                .put("state_topic", stTopic)
                .put("value_template", valueTemplate ?: "{{ value_json.$id | lower }}")
                .put("payload_on", "true")
                .put("payload_off", "false")
                .put("availability_topic", avTopic)
                .put("device", device)

            if (deviceClass != null) discovery.put("device_class", deviceClass)

            val topic = "homeassistant/binary_sensor/$resolvedId/$id/config"
            publishRetained(topic, discovery.toString().replace("\\/", "/"))
        }
    }

    private fun resolveDeviceId(config: MqttConnectionConfig): String {
        val provided = config.friendlyName.trim()
        return if (provided.isNotEmpty()) {
            provided.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        } else {
            val androidId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            (androidId ?: appContext.packageName).replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        }
    }

    private fun buildPayload(telemetry: VehicleTelemetry): JSONObject {
        val payload = JSONObject()
        payload.put("current_datetime", Instant.now().toString())
        payload.put("soc", telemetry.soc)
        payload.put("soc_panel", telemetry.socPanel)
        payload.put("battery_12v_voltage", telemetry.battery12vVoltage)
        payload.put("battery_total_voltage", telemetry.batteryTotalVoltage)
        telemetry.batteryTotalCurrent?.let { payload.put("battery_total_current", it) }
        payload.put("electric_driving_range_km", telemetry.electricDrivingRangeKm)
        payload.put("total_discharge", telemetry.totalDischarge)
        payload.put("speed", telemetry.locationGpsSpeed?.takeIf { it > 0.1 } ?: telemetry.speed)
        payload.put("gear", telemetry.gear)
        payload.put("odometer", telemetry.odometer)
        payload.put("engine_power", telemetry.enginePower)
        payload.put("engine_speed_front", telemetry.engineSpeedFront)
        payload.put("engine_speed_rear", telemetry.engineSpeedRear)
        payload.put("is_charging", telemetry.isCharging)
        payload.put("charging_power", telemetry.chargingPower)
        payload.put("car_on", telemetry.isCarOn)
        payload.put("car_locked", telemetry.carLocked > 0)
        payload.put("any_door_opened", telemetry.anyDoorOpened > 0)
        payload.put("fuel_percentage", telemetry.fuelPercentage)
        payload.put("fuel_driving_range_km", telemetry.fuelDrivingRangeKm)
        payload.put("drive_mode", telemetry.driveModeName)
        payload.put("regen_mode", telemetry.regenModeName)
        // PHEV only. Gated on the car, not on energyMode != 0: the DiLink-3 poll stores
        // getEnergyMode for every car and a BEV Seal reports 1 (=EV), which would publish a
        // meaningless "EV" on every BEV.
        val isPhevCar = PreferencesManager(appContext).getCachedSelectedCarConfig()?.isPhev == true
        payload.put(
            "energy_mode",
            if (isPhevCar && telemetry.energyMode != 0) energyModeLabel(telemetry.energyMode) else "—"
        )
        var gpsUpdated = false
        telemetry.locationLatitude.takeIf { it != 0.0 }?.let { lastKnownLat = it; gpsUpdated = true }
        telemetry.locationLongitude.takeIf { it != 0.0 }?.let { lastKnownLon = it; gpsUpdated = true }
        telemetry.locationAltitude.takeIf { it > 0.0 }?.let { lastKnownAlt = it; gpsUpdated = true }
        telemetry.locationOrientation?.takeIf { it.isFinite() }?.let { lastKnownHeading = it.toDouble() }
        if (gpsUpdated) persistGps()
        if (lastKnownLat != 0.0) payload.put("location_latitude", lastKnownLat)
        if (lastKnownLon != 0.0) payload.put("location_longitude", lastKnownLon)
        if (lastKnownAlt > 0.0) payload.put("location_altitude", lastKnownAlt.round(2))
        lastKnownHeading?.let { payload.put("heading", it) }
        telemetry.instrumentOutCarTemperature?.let { payload.put("ext_temp", it) }
        telemetry.cabinTemperature?.let { payload.put("cabin_temp", it.round(1)) }
        telemetry.soh.takeIf { it > 0 }?.let { payload.put("soh", it) }
        telemetry.statisticBatterySoh?.takeIf { it > 0.0 }?.let { payload.put("statistic_soh", it.round(1)) }
        telemetry.statisticAvailPower?.let { payload.put("available_power", it.round(1)) }
        telemetry.batteryRemainPowerEV?.let { payload.put("battery_remain_power_ev", it.round(1)) }
        telemetry.batteryPackTemp.takeIf { it > 0.0 }?.let { payload.put("battery_pack_temp", it.round(1)) }
        telemetry.statisticCellTempMin?.let { payload.put("battery_cell_temp_min", it.round(1)) }
        telemetry.statisticCellTempMax?.let { payload.put("battery_cell_temp_max", it.round(1)) }
        telemetry.statisticCellTempAvg?.let { payload.put("battery_cell_temp_avg", it.round(1)) }
        telemetry.statisticCellVoltageMin?.let { payload.put("cell_voltage_min", it.round(3)) }
        telemetry.statisticCellVoltageMax?.let { payload.put("cell_voltage_max", it.round(3)) }
        telemetry.wifiSsid.takeIf { it.isNotBlank() }?.let { payload.put("wifi_ssid", it) }
        telemetry.tyrePressureLF.takeIf { it > 0.0 }?.let { payload.put("tyre_pressure_left_front_psi", it.round(2)) }
        telemetry.tyrePressureRF.takeIf { it > 0.0 }?.let { payload.put("tyre_pressure_right_front_psi", it.round(2)) }
        telemetry.tyrePressureLR.takeIf { it > 0.0 }?.let { payload.put("tyre_pressure_left_rear_psi", it.round(2)) }
        telemetry.tyrePressureRR.takeIf { it > 0.0 }?.let { payload.put("tyre_pressure_right_rear_psi", it.round(2)) }
        telemetry.tyreTempLF.takeIf { it > 0 }?.let { payload.put("tyre_temperature_left_front_c", it) }
        telemetry.tyreTempRF.takeIf { it > 0 }?.let { payload.put("tyre_temperature_right_front_c", it) }
        telemetry.tyreTempLR.takeIf { it > 0 }?.let { payload.put("tyre_temperature_left_rear_c", it) }
        telemetry.tyreTempRR.takeIf { it > 0 }?.let { payload.put("tyre_temperature_right_rear_c", it) }
        return payload
    }

    private fun Double.round(decimals: Int): Double {
        val factor = Math.pow(10.0, decimals.toDouble())
        return Math.round(this * factor) / factor
    }

    companion object {
        private const val TAG = "MqttConnectionMgr"
        private const val ONLINE_REASSERT_INTERVAL_MS = 30_000L
    }
}
