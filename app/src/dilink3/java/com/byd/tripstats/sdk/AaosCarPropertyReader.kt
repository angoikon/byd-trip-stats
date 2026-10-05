package com.byd.tripstats.sdk

import android.car.Car
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.byd.tripstats.adb.AdbPermissionManager
import com.byd.tripstats.util.DiagLog
import kotlinx.coroutines.runBlocking
import kotlin.math.abs

/**
 * Reads the standard Android Automotive vehicle properties on head units built on it — today the
 * DiLink 100 in the Atto 3 EVO (Android 14) — and hands them to [BydVehicleDataSource.applyAaosTelemetry].
 *
 * Why: BYD's own service refuses every bydauto getter to a third-party app there
 * (`SecurityException: [getInt] permission deny!`), so battery, range, odometer, charging and the
 * car's on/off state all read 0. The same car publishes most of them through `android.car`, and a
 * `dumpsys car_service` (2026-10-05) showed them live: EV_BATTERY_LEVEL 36000 Wh with the dashboard
 * at 48 %, RANGE_REMAINING 214 km, outside temperature 23 °C, GEAR_SELECTION P, and IGNITION_STATE
 * dropping to OFF the moment the car was switched off.
 *
 * Permissions there: CAR_POWERTRAIN (gear, ignition), CAR_INFO (battery capacity),
 * CAR_EXTERIOR_ENVIRONMENT and CAR_ENERGY_PORTS are `normal`, granted at install; CAR_ENERGY
 * (battery, range, charging) and CAR_SPEED are `dangerous` and granted over the adb channel
 * ([AdbPermissionManager.ensureCarPropertyPermissions]), to the driver's user. Each read is skipped
 * while its permission is missing and picked up as soon as it arrives — no restart needed.
 * The odometer (CAR_MILEAGE) and tyre pressures (CAR_TIRES) are signature-only, so not here.
 * TODO(dilink100): read them (and cabin temperature) in the uid-2000 daemon, whose shell user holds
 *  those permissions — see MD/DILINK100_FOLLOWUPS.md.
 *
 * Read-only throughout. Started reflectively by the data source (src/main can't reference
 * android.car — the dilink5 build has neither this class nor the library), and only where
 * [AaosPlatform.isCarPropertyCapable]: Android 12+, Android Automotive, never DiLink 5.
 * Everything runs on one background thread; nothing here can throw into the app.
 */
class AaosCarPropertyReader {

    private lateinit var context: Context
    private lateinit var sink: BydVehicleDataSource
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var stopped = false

    private var car: Car? = null
    private var manager: CarPropertyManager? = null
    private var subscribed = false
    private var capacityWh: Double? = null
    private var lastIgnition: Int? = null
    private var lastChargeState: Int? = null

    private val unreadableLogged = HashSet<Int>()
    private val missingPermissionLogged = HashSet<String>()
    private var lastGrantAttemptMs = 0L
    private var grantAttempts = 0
    private var lastSummary: String? = null
    private var lastSummaryMs = 0L

    fun start(context: Context, sink: BydVehicleDataSource) {
        this.context = context.applicationContext
        this.sink = sink
        val t = HandlerThread("aaos-props").apply { start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        h.post { connect() }
        h.postDelayed(tick, FIRST_POLL_DELAY_MS)
    }

    fun stop() {
        stopped = true
        val h = handler ?: return
        h.removeCallbacksAndMessages(null)
        h.post {
            runCatching { manager?.unregisterCallback(callback) }
            runCatching { car?.disconnect() }
            manager = null
            car = null
            thread?.quitSafely()
        }
    }

    // ── Connection ────────────────────────────────────────────────────────────

    /**
     * Connects without blocking, with a lifecycle listener — so if the car service ever restarts,
     * the library reconnects and tells us, instead of failing the app (which is what it does to a
     * client created without one).
     */
    private fun connect() {
        if (stopped || car != null) return
        try {
            car = Car.createCar(context, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT, lifecycle)
        } catch (t: Throwable) {
            DiagLog.event(context, TAG, "🚘 vehicle properties: connect failed: ${t.javaClass.simpleName}: ${t.message}")
            car = null
            handler?.postDelayed({ connect() }, RECONNECT_DELAY_MS)
        }
    }

    private val lifecycle = Car.CarServiceLifecycleListener { connectedCar, ready ->
        if (stopped) return@CarServiceLifecycleListener
        if (!ready) {
            manager = null
            subscribed = false
            DiagLog.event(context, TAG, "🚘 vehicle properties: car service went away — waiting for it")
            return@CarServiceLifecycleListener
        }
        manager = runCatching { connectedCar.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager }.getOrNull()
        val missing = AaosPlatform.missingCarRuntimePermissions(context).joinToString { it.substringAfterLast('.') }
        DiagLog.event(
            context, TAG,
            "🚘 vehicle properties: connected=${manager != null} user=${AaosPlatform.myUserId} " +
                "missing=[${missing.ifEmpty { "none" }}]",
        )
    }

    // ── Polling ───────────────────────────────────────────────────────────────

    /**
     * One read of everything, every second while the car is on or charging and every
     * [IDLE_POLL_MS] otherwise. Gear and ignition also arrive as change events in between, so a
     * switch-off is seen at once, not on the next poll.
     */
    private val tick = object : Runnable {
        override fun run() {
            if (stopped) return
            runCatching { poll() }.onFailure { Log.w(TAG, "poll failed: ${it.javaClass.simpleName}: ${it.message}") }
            val active = AaosPlatform.carOnFromIgnitionState(lastIgnition) == 2 ||
                lastChargeState == AaosPlatform.CHARGE_STATE_CHARGING
            if (!stopped) handler?.postDelayed(this, if (active) ACTIVE_POLL_MS else IDLE_POLL_MS)
        }
    }

    private fun poll() {
        val pm = manager ?: return
        ensurePermissions()
        if (!subscribed) subscribe(pm)

        val energy = granted(PERM_ENERGY)
        if (capacityWh == null) {
            capacityWh = readFloat(pm, INFO_EV_BATTERY_CAPACITY)?.takeIf { it > 1_000.0 }
                ?: if (energy) readFloat(pm, EV_CURRENT_BATTERY_CAPACITY)?.takeIf { it > 1_000.0 } else null
            capacityWh?.let { DiagLog.event(context, TAG, "🚘 battery capacity ${it.toInt()} Wh") }
        }
        val levelWh = if (energy) readFloat(pm, EV_BATTERY_LEVEL) else null
        val capacity = capacityWh
        val socPct = if (levelWh != null && capacity != null && capacity > 0.0) {
            Math.round(levelWh / capacity * 1000.0) / 10.0
        } else null
        val rangeKm = if (energy) readFloat(pm, RANGE_REMAINING)?.let { Math.round(it / 1000.0).toInt() } else null
        val chargeState = if (energy) readInt(pm, EV_CHARGE_STATE) else null
        val chargeRate = if (energy) readFloat(pm, EV_BATTERY_INSTANTANEOUS_CHARGE_RATE) else null
        val outsideTemp = readFloat(pm, ENV_OUTSIDE_TEMPERATURE)
        val ignition = readInt(pm, IGNITION_STATE)
        val gear = readInt(pm, GEAR_SELECTION)
        // m/s, negative in reverse.
        val speedKmh = if (granted(PERM_SPEED)) readFloat(pm, PERF_VEHICLE_SPEED)?.let { abs(it) * 3.6 } else null
        val plugged = readBoolean(pm, EV_CHARGE_PORT_CONNECTED)

        if (ignition != null) lastIgnition = ignition
        if (chargeState != null) lastChargeState = chargeState
        sink.applyAaosTelemetry(
            socPct = socPct,
            rangeKm = rangeKm,
            outsideTempC = outsideTemp,
            ignitionState = ignition,
            gearSelection = gear,
            chargeState = chargeState,
            chargeRateMilliwatts = chargeRate,
            speedKmh = speedKmh,
        )

        // One line when something other than speed changed, at most once a minute: what the car
        // reported and what it became, so a wrong unit or a dead property shows up in diag.log.
        val summary = "soc=${socPct ?: "-"} (${levelWh?.toInt() ?: "-"}/${capacity?.toInt() ?: "-"} Wh) " +
            "range=${rangeKm ?: "-"} oat=${outsideTemp ?: "-"} ign=${ignition ?: "-"} gear=${gear ?: "-"} " +
            "chg=${chargeState ?: "-"} rate=${chargeRate ?: "-"} plug=${plugged ?: "-"}"
        val now = SystemClock.elapsedRealtime()
        if (summary != lastSummary && (lastSummaryMs == 0L || now - lastSummaryMs >= SUMMARY_INTERVAL_MS)) {
            lastSummary = summary
            lastSummaryMs = now
            DiagLog.event(context, TAG, "🚘 car props: $summary spd=${speedKmh?.let { "%.1f".format(it) } ?: "-"}")
        }
    }

    private fun subscribe(pm: CarPropertyManager) {
        var any = false
        for (prop in intArrayOf(IGNITION_STATE, GEAR_SELECTION)) {
            any = runCatching {
                pm.registerCallback(callback, prop, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            }.getOrDefault(false) || any
        }
        subscribed = true
        if (!any) DiagLog.event(context, TAG, "🚘 gear/ignition change events unavailable — polling only")
    }

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>?) {
            if (stopped || value == null || value.status != CarPropertyValue.STATUS_AVAILABLE) return
            val v = value.value as? Int ?: return
            runCatching {
                when (value.propertyId) {
                    IGNITION_STATE -> {
                        lastIgnition = v
                        sink.applyAaosTelemetry(ignitionState = v)
                    }
                    GEAR_SELECTION -> sink.applyAaosTelemetry(gearSelection = v)
                }
            }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) = Unit
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun granted(permission: String): Boolean {
        val ok = context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!ok && missingPermissionLogged.add(permission)) {
            DiagLog.event(context, TAG, "🚘 ${permission.substringAfterLast('.')} not granted — its readings are skipped until it is")
        }
        return ok
    }

    /**
     * Asks the adb channel for the missing grants: right away, then every few minutes for a while.
     * Cars set up before this existed get them here, without running setup again.
     */
    private fun ensurePermissions() {
        if (AaosPlatform.missingCarRuntimePermissions(context).isEmpty()) return
        if (grantAttempts >= MAX_GRANT_ATTEMPTS) return
        val now = SystemClock.elapsedRealtime()
        if (lastGrantAttemptMs != 0L && now - lastGrantAttemptMs < GRANT_RETRY_MS) return
        lastGrantAttemptMs = now
        grantAttempts++
        val outcome = runCatching { runBlocking { AdbPermissionManager.ensureCarPropertyPermissions(context) } }
            .getOrElse { "failed: ${it.javaClass.simpleName}: ${it.message}" }
        DiagLog.event(context, TAG, "🚘 vehicle-property permissions: $outcome (attempt $grantAttempts)")
    }

    // ── Reads ─────────────────────────────────────────────────────────────────

    private fun read(pm: CarPropertyManager, propertyId: Int): Any? = try {
        val v = pm.getProperty<Any?>(propertyId, 0)
        if (v != null && v.status == CarPropertyValue.STATUS_AVAILABLE) v.value else null
    } catch (t: Throwable) {
        if (unreadableLogged.add(propertyId)) {
            DiagLog.event(
                context, TAG,
                "🚘 property 0x${Integer.toHexString(propertyId)} unreadable: ${t.javaClass.simpleName}: ${t.message}",
            )
        }
        null
    }

    private fun readFloat(pm: CarPropertyManager, propertyId: Int): Double? =
        (read(pm, propertyId) as? Number)?.toDouble()?.takeIf { it.isFinite() }

    private fun readInt(pm: CarPropertyManager, propertyId: Int): Int? =
        (read(pm, propertyId) as? Number)?.toInt()

    private fun readBoolean(pm: CarPropertyManager, propertyId: Int): Boolean? =
        read(pm, propertyId) as? Boolean

    companion object {
        private const val TAG = "AaosCarProps"

        private const val PERM_ENERGY = "android.car.permission.CAR_ENERGY"
        private const val PERM_SPEED = "android.car.permission.CAR_SPEED"

        private const val FIRST_POLL_DELAY_MS = 2_000L
        private const val ACTIVE_POLL_MS = 1_000L
        private const val IDLE_POLL_MS = 10_000L
        private const val RECONNECT_DELAY_MS = 30_000L
        private const val SUMMARY_INTERVAL_MS = 60_000L
        private const val GRANT_RETRY_MS = 5 * 60_000L
        private const val MAX_GRANT_ATTEMPTS = 6

        // VehiclePropertyIds, as raw ids: some are newer than the API level the app targets, and
        // these are the ids the car lists in `dumpsys car_service`.
        private const val INFO_EV_BATTERY_CAPACITY = 0x11600106            // Wh, CAR_INFO
        private const val EV_BATTERY_LEVEL = 0x11600309                    // Wh, CAR_ENERGY
        private const val EV_CURRENT_BATTERY_CAPACITY = 0x1160030d         // Wh, CAR_ENERGY
        private const val RANGE_REMAINING = 0x11600308                     // m, CAR_ENERGY
        private const val EV_CHARGE_STATE = 0x11400f41                     // EvChargeState, CAR_ENERGY
        private const val EV_BATTERY_INSTANTANEOUS_CHARGE_RATE = 0x1160030c // mW, CAR_ENERGY
        private const val EV_CHARGE_PORT_CONNECTED = 0x1120030b            // CAR_ENERGY_PORTS
        private const val ENV_OUTSIDE_TEMPERATURE = 0x11600703             // °C, CAR_EXTERIOR_ENVIRONMENT
        private const val IGNITION_STATE = 0x11400409                      // CAR_POWERTRAIN
        private const val GEAR_SELECTION = 0x11400400                      // VehicleGear, CAR_POWERTRAIN
        private const val PERF_VEHICLE_SPEED = 0x11600207                  // m/s, CAR_SPEED
    }
}
