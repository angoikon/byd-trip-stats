package com.byd.tripstats.sdk

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.hardware.bydauto.statistic.AbsBYDAutoStatisticListener
import android.hardware.bydauto.statistic.BYDAutoStatisticDevice
import android.hardware.bydauto.tyre.AbsBYDAutoTyreListener
import android.hardware.bydauto.collectdata.AbsBYDAutoCollectDataListener
import android.hardware.bydauto.instrument.AbsBYDAutoInstrumentListener
import android.hardware.bydauto.speed.AbsBYDAutoSpeedListener
import android.hardware.bydauto.charging.AbsBYDAutoChargingListener

/**
 * DiLink-5 (Sealion 7) telemetry client — present ONLY in the `dilink5` flavor; loaded reflectively
 * by BydVehicleDataSource.startDilink5Client() when DiLink5Platform.isDiLink5.
 *
 * On DiLink-5 the statistic data comes via a typed listener (AbsBYDAutoStatisticListener), not the
 * DiLink-3 feature-ID path. We register that listener (which also primes the TS adapter so the
 * getters go live), and poll the other telemetry devices reflectively (charging/speed/vehiclehealth
 * — no compile dependency on their D5 types). All values are pushed into the shared snapshot via
 * BydVehicleDataSource.applyDilink5Telemetry(...) / applyDaemonTelemetry(...).
 *
 * Confirmed field map: soc, total-mileage, elec-range, usable-kWh, SOH, charge-power via the
 * statistic listener; HV V/I, motor RPM/temp/torque via the collectdata events (real power = V·I);
 * drive mode + ambient temp + 12V via the instrument/ac/ota devices.
 */
class Dilink5Client {
    private val tag = "Dilink5Client"
    @Volatile private var running = false
    private var pollThread: Thread? = null
    private var statDevice: BYDAutoStatisticDevice? = null
    private var statListener: AbsBYDAutoStatisticListener? = null

    // reflective device handles (charging/speed/vehiclehealth) — no motor device, see below
    private var chargingDev: Any? = null
    private var chargingListener: Any? = null   // event-driven charge power
    private var speedDev: Any? = null
    private var speedListener: Any? = null   // event-driven speed
    private var healthDev: Any? = null
    private var tyreDev: Any? = null
    private var instrumentDev: Any? = null   // drive mode + ambient temp
    private var instrumentListener: Any? = null   // event-driven mode/ambient (D3 parity, no poll lag)
    private var otaDev: Any? = null   // 12V aux voltage via getBatteryVoltage(0)
    private var acDev: Any? = null    // ambient temp via getTemprature(4=AC_TEMPERATURE_OUT)
    private var tyreListener: Any? = null   // typed tyre listener (per-wheel temp events)
    private var collectDataDev: Any? = null
    private var collectDataListener: Any? = null
    // Latest HV bus readings from collectdata events → real power = V·I.
    private var lastHvVolt: Int = 0
    private var lastHvCurrent: Int? = null

    // derived-power state
    private var lastUsableKwh: Double = Double.NaN
    private var lastUsableAtMs: Long = 0L
    private var emaPowerKw: Double = Double.NaN

    fun start(ctx: Context, ds: BydVehicleDataSource) {
        if (running) return
        running = true
        Log.i(tag, "starting DiLink-5 client")

        // 1) statistic typed listener (push) — primes the adapter + delivers soc/mileage/range/kWh
        try {
            val dev = BYDAutoStatisticDevice.getInstance(ctx)
            statDevice = dev
            if (dev != null) {
                val l = object : AbsBYDAutoStatisticListener() {
                    // Confirmed integer-only on D5 — the dash/panel reading, not decimal BMS.
                    // BMS mode's decimal precision comes from usableKwh instead (derivedBmsSoc).
                    // onSOCBatteryPercentageChanged is the same value via a separate event; kept
                    // wired but never observed firing (getter twin is a hardcoded-0 stub).
                    override fun onElecPercentageChanged(v: Double) {
                        ds.applyDilink5Telemetry(socPanelPct = kotlin.math.round(v).toInt())
                    }
                    override fun onSOCBatteryPercentageChanged(v: Int) { ds.applyDilink5Telemetry(socPanelPct = v) }
                    override fun onTotalMileageValueChanged(v: Float) { ds.applyDilink5Telemetry(totalMileageKm = v.toDouble()) }
                    override fun onElecDrivingRangeChanged(v: Int) { ds.applyDilink5Telemetry(elecRangeKm = v) }
                    override fun onDrivingRangeValueChanged(v: Int) { ds.applyDilink5Telemetry(elecRangeKm = v) }
                    override fun onEVRemainingBatteryPowerChanged(v: Float) { onUsable(v.toDouble(), ds) }
                }
                statListener = l
                dev.registerListener(l)
                Log.i(tag, "statistic listener registered")
            } else Log.w(tag, "statistic getInstance returned null")
        } catch (t: Throwable) {
            Log.w(tag, "statistic listener failed: ${t.javaClass.simpleName}: ${t.message}")
        }

        // 2) bind the reflective devices once (sequential, guarded)
        chargingDev = bind(ctx, "android.hardware.bydauto.charging.BYDAutoChargingDevice")
        chargingDev?.let { registerChargingListener(it, ds) }
        speedDev    = bind(ctx, "android.hardware.bydauto.speed.BYDAutoSpeedDevice")
        speedDev?.let { registerSpeedListener(it, ds) }
        healthDev   = bind(ctx, "android.hardware.bydauto.vehiclehealth.BYDAutoVehicleHealthDevice")
        // Motor: BYDAutoMotorDevice.getInstance() throws "Stub!" — never binds. Rear RPM comes
        // solely from the collectdata event (onDriverMotorSpeed, registerCollectData below).
        // Tyre: per-wheel pressure via getTyrePressureValueByType(area). Needs BYDAUTO_TYRE_COMMON.
        tyreDev     = bind(ctx, "android.hardware.bydauto.tyre.BYDAutoTyreDevice")
        // Per-wheel tyre TEMPERATURE: register a typed listener for the events (wheel index 0-based:
        // 0=LF/1=RF/2=LR/3=RR); also polled via instrument.getWheelTemperature in pollOnce.
        tyreDev?.let { registerTyreListener(it, ds) }
        // collectdata: HV voltage/current + motor RPM via EVENTS (getters dead). Real power = V·I.
        collectDataDev = bind(ctx, "android.hardware.bydauto.collectdata.BYDAutoCollectDataDevice")
        collectDataDev?.let { registerCollectData(it, ds) }
        // Instrument: drive mode + ambient temp. Needs BYDAUTO_INSTRUMENT_COMMON
        // (already granted). Event-driven via the listener (instant, D3 parity); the slow-tick getters
        // are only an initial-value / missed-event backstop.
        instrumentDev = bind(ctx, "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice")
        instrumentDev?.let { registerInstrumentListener(it, ds) }
        // ota: 12V aux voltage. getBatteryVoltage(0) == 13 V on-car; the no-arg
        // getBatteryPowerVoltage is dead (-1). Arg-indexed → polled on the slow tick.
        otaDev = bind(ctx, "android.hardware.bydauto.ota.BYDAutoOtaDevice")
        // ac: ambient/outside-air temp via getTemprature(4) (4 = AC_TEMPERATURE_OUT; SDK range
        // -40..50 °C). The instrument getOutCarTemperature getter is dead; this is the live source.
        acDev = bind(ctx, "android.hardware.bydauto.ac.BYDAutoAcDevice")

        // 3) adaptive poll — fast ONLY while driving / DC-charging; backs off to 30s when parked so
        //    we don't wake the head unit at 1 Hz on a parked car (the statistic LISTENER still pushes
        //    soc/mileage/range live regardless). Mirrors the main service loop's battery-aware cadence.
        pollThread = Thread {
            var lastSlowMs = 0L
            while (running) {
                val now = SystemClock.elapsedRealtime()
                val slowTick = now - lastSlowMs >= 30_000L      // statistic-getter backstop ~every 30s
                if (slowTick) lastSlowMs = now
                try { pollOnce(ds, slowTick) } catch (_: Throwable) {}
                try { Thread.sleep(pollIntervalMs(ds)) } catch (e: InterruptedException) { break }
            }
        }.apply { isDaemon = true; name = "Dilink5Poll"; start() }
    }

    @Volatile private var lastActiveMs = 0L  // last time driving or charging (for idle back-off)

    // Battery-aware cadence: 1s driving/DC-charge, 5s AC-charge or just-stopped, 30s parked/idle.
    private fun pollIntervalMs(ds: BydVehicleDataSource): Long {
        val s = ds.vehicleSnapshot.value
        val speed = s.directSpeedKmh ?: 0.0
        val charging = s.isChargingActive || s.chargingPower > 0.0
        val now = SystemClock.elapsedRealtime()
        if (speed > 2.0 || charging) lastActiveMs = now
        return when {
            speed > 2.0 -> 1_000L
            charging && s.chargingPower > 23.0 -> 1_000L   // DC fast charge
            charging -> 5_000L                             // AC charge
            now - lastActiveMs < 120_000L -> 5_000L        // recently active (brief stop) — stay responsive
            else -> 30_000L                                // parked/idle — back off
        }
    }

    fun stop() {
        running = false
        pollThread?.interrupt(); pollThread = null
        try { statListener?.let { statDevice?.unregisterListener(it) } } catch (_: Throwable) {}
        statListener = null; statDevice = null
        try { tyreListener?.let { l -> tyreDev?.javaClass?.getMethod("unregisterListener", AbsBYDAutoTyreListener::class.java)?.invoke(tyreDev, l) } } catch (_: Throwable) {}
        // NOTE: collectdata uses "unRegisterListener" (capital R), unlike the others.
        try { collectDataListener?.let { l -> collectDataDev?.javaClass?.getMethod("unRegisterListener", AbsBYDAutoCollectDataListener::class.java)?.invoke(collectDataDev, l) } } catch (_: Throwable) {}
        try { instrumentListener?.let { l -> instrumentDev?.javaClass?.getMethod("unregisterListener", AbsBYDAutoInstrumentListener::class.java)?.invoke(instrumentDev, l) } } catch (_: Throwable) {}
        try { speedListener?.let { l -> speedDev?.javaClass?.getMethod("unregisterListener", AbsBYDAutoSpeedListener::class.java)?.invoke(speedDev, l) } } catch (_: Throwable) {}
        try { chargingListener?.let { l -> chargingDev?.javaClass?.getMethod("unregisterListener", AbsBYDAutoChargingListener::class.java)?.invoke(chargingDev, l) } } catch (_: Throwable) {}
        tyreListener = null; collectDataListener = null; instrumentListener = null; speedListener = null; chargingListener = null
        chargingDev = null; speedDev = null; healthDev = null; tyreDev = null; collectDataDev = null; instrumentDev = null; otaDev = null; acDev = null
        Log.i(tag, "stopped")
    }

    private fun pollOnce(ds: BydVehicleDataSource, slowTick: Boolean) {
        // FAST (every tick): speed + rear-motor RPM (driving) + charge power (charging) — all change
        // fast and are cheap getters. Push speed and RPM together so a partial update never wipes
        // the other (applyDaemonTelemetry ignores null fields).
        // Speed getter is now just a backstop — the speed listener (registerSpeedListener) is the
        // primary, instant source.
        val spd = reflGetDouble(speedDev, "getSpeedValue")?.takeIf { it in 0.0..400.0 }
        if (spd != null) {
            ds.applyDaemonTelemetry(speedKmh = spd, gear = null, powerKw = null, rearRpm = null)
        }
        // getChargingPower is now just a backstop — the charging listener (registerChargingListener)
        // is the primary, instant source.
        reflGetDouble(chargingDev, "getChargingPower")?.takeIf { it in 0.0..250.0 }
            ?.let { ds.applyDilink5Telemetry(chargingPowerKw = it) }
        if (!slowTick) return
        // SLOW (~30s): the statistic LISTENER already pushes soc/mileage/range live, so these getters
        // are only a missed-callback backstop; SOH barely changes. No need to read them every tick.
        statDevice?.let { d ->
            try {
                val soc = d.getElecPercentageValue();        if (soc in 1.0..100.0) ds.applyDilink5Telemetry(socPanelPct = kotlin.math.round(soc).toInt())
                val mil = d.getTotalMileageValue().toDouble(); if (mil > 1.0)        ds.applyDilink5Telemetry(totalMileageKm = mil)
                val rng = d.getElecDrivingRangeValue();        if (rng in 1..2000)   ds.applyDilink5Telemetry(elecRangeKm = rng)
                val usb = d.getEVRemainingBatteryPower().toDouble(); if (usb in 0.5..200.0) onUsable(usb, ds)
            } catch (_: Throwable) {}
        }
        reflGetInt(healthDev, "getBatteryHealthStatus")?.takeIf { it in 50..110 }
            ?.let { ds.applyDilink5Telemetry(sohPct = it.toDouble()) }
        // per-wheel tyre PRESSURE via getTyrePressureValueByType(area) — area LF=1/RF=2/
        // LR=3/RR=4, tenths of psi (respects area). Slow tick. TEMPERATURE is NOT polled: the getter
        // returns an index-0 sentinel (uniform/wrong) — real per-wheel temp comes from the tyre
        // listener (registerTyre → applyDilink5TyreTemp).
        tyreDev?.let { t ->
            ds.applyDilink5Tyre(
                reflGetIntArg(t, "getTyrePressureValueByType", 1), reflGetIntArg(t, "getTyrePressureValueByType", 2),
                reflGetIntArg(t, "getTyrePressureValueByType", 3), reflGetIntArg(t, "getTyrePressureValueByType", 4),
                reflGetIntArg(t, "getTyrePressureState", 1), reflGetIntArg(t, "getTyrePressureState", 2),
                reflGetIntArg(t, "getTyrePressureState", 3), reflGetIntArg(t, "getTyrePressureState", 4),
            )
        }
        // drive mode + ambient temp. Primary path is the instrument LISTENER (instant);
        // these getters are just an initial-value / missed-event backstop. getSportModeState raw ==
        // app canonical (1=Eco/2=Sport/3=Normal/4=Snow); getOutCarTemperature is plain °C.
        reflGetInt(instrumentDev, "getSportModeState")?.let { ds.applyDilink5DriveMode(it) }
        reflGetInt(instrumentDev, "getOutCarTemperature")?.let { ds.applyDilink5AmbientTemp(it) }
        // 12V aux voltage via ota.getBatteryVoltage(0) (arg-indexed; confirmed 13 V).
        reflGetIntArg(otaDev, "getBatteryVoltage", 0)?.let { ds.applyDilink5AuxVoltage(it) }
        // ambient temp via ac.getTemprature(4=AC_TEMPERATURE_OUT). instrument getter is
        // dead; this arg-indexed AC getter is the live source (its event still updates it too).
        reflGetIntArg(acDev, "getTemprature", 4)?.let { ds.applyDilink5AmbientTemp(it) }
        // per-wheel tyre TEMP via instrument.getWheelTemperature(int) — a POLLABLE source
        // (0-based 0=LF..3=RR, matching the tyre event index). Complements the sparse tyre-temp events.
        // Returns 0 when the TPMS sensors sleep (parked); applyDilink5TyreTemp drops 0 so the last
        // known temp is retained rather than blanked.
        instrumentDev?.let { d ->
            for (w in 0..3) reflGetIntArg(d, "getWheelTemperature", w)?.let { ds.applyDilink5TyreTemp(w, it) }
        }
        // T-Box serial (ota) — hashed into the non-PII license device id (raw serial never persists).
        // The VIN is intentionally NOT read on DiLink-5 (privacy).
        reflGetString(otaDev, "getTBoxSerialNumber")?.let { ds.applyDilink5TboxSerial(it) }
    }

    // Derived driving power: -Δ(usable kWh)/Δt, EMA-smoothed; pushed only while discharging.
    private fun onUsable(usableKwh: Double, ds: BydVehicleDataSource) {
        ds.applyDilink5Telemetry(usableKwh = usableKwh)
        val now = SystemClock.elapsedRealtime()
        if (!lastUsableKwh.isNaN() && lastUsableAtMs > 0) {
            val dtH = (now - lastUsableAtMs) / 3_600_000.0
            if (dtH > 0.0008) { // ~3s minimum to avoid divide noise
                val inst = -(usableKwh - lastUsableKwh) / dtH   // discharge => positive
                if (kotlin.math.abs(inst) <= 400.0) {
                    emaPowerKw = if (emaPowerKw.isNaN()) inst else 0.3 * inst + 0.7 * emaPowerKw
                    if (emaPowerKw > 0.0) ds.applyDaemonTelemetry(speedKmh = null, gear = null, powerKw = emaPowerKw)
                }
                lastUsableKwh = usableKwh; lastUsableAtMs = now
            }
        } else { lastUsableKwh = usableKwh; lastUsableAtMs = now }
    }

    // Typed tyre listener for per-wheel temperature (event-only). Registered reflectively so the
    // dilink5 flavor stays reflection-based for device handles; the listener subclasses the (compile)
    // stub AbsBYDAutoTyreListener, which the real class shadows at runtime.
    private fun registerTyreListener(dev: Any, ds: BydVehicleDataSource) {
        try {
            val l = object : AbsBYDAutoTyreListener() {
                override fun onTyreTemperatureValueChanged(wheel: Int, value: Int) {
                    ds.applyDilink5TyreTemp(wheel, value)
                }
            }
            dev.javaClass.getMethod("registerListener", AbsBYDAutoTyreListener::class.java).invoke(dev, l)
            tyreListener = l
            Log.i(tag, "tyre listener registered")
        } catch (t: Throwable) {
            val c = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
            Log.w(tag, "tyre listener failed: ${c.javaClass.simpleName}: ${c.message}")
        }
    }

    // Charging typed listener — event-driven; onChargingPowerChanged gives far more resolution during
    // a charge ramp than the 5s poll. getChargingPower() stays as a backstop in pollOnce.
    private fun registerChargingListener(dev: Any, ds: BydVehicleDataSource) {
        try {
            // Both overloads declared: D5 calls Float (confirmed), D3 may call Double — same
            // belt-and-suspenders pattern as the speed listener's int/double pair.
            val l = object : AbsBYDAutoChargingListener() {
                override fun onChargingPowerChanged(power: Float) { ds.applyDilink5Telemetry(chargingPowerKw = power.toDouble()) }
                override fun onChargingPowerChanged(power: Double) { ds.applyDilink5Telemetry(chargingPowerKw = power) }
            }
            dev.javaClass.getMethod("registerListener", AbsBYDAutoChargingListener::class.java).invoke(dev, l)
            chargingListener = l
            Log.i(tag, "charging listener registered")
        } catch (t: Throwable) {
            val c = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
            Log.w(tag, "charging listener failed: ${c.javaClass.simpleName}: ${c.message}")
        }
    }

    // Speed typed listener — event-driven, instant. getSpeedValue() stays as a backstop in pollOnce
    // (parked/no-change periods where an onChanged event legitimately never fires).
    private fun registerSpeedListener(dev: Any, ds: BydVehicleDataSource) {
        try {
            val l = object : AbsBYDAutoSpeedListener() {
                override fun onSpeedChanged(speed: Double) {
                    ds.applyDaemonTelemetry(speedKmh = speed, gear = null, powerKw = null, rearRpm = null)
                }
            }
            dev.javaClass.getMethod("registerListener", AbsBYDAutoSpeedListener::class.java).invoke(dev, l)
            speedListener = l
            Log.i(tag, "speed listener registered")
        } catch (t: Throwable) {
            val c = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
            Log.w(tag, "speed listener failed: ${c.javaClass.simpleName}: ${c.message}")
        }
    }

    // collectdata typed listener — HV bus V/I + motor RPM (event-only; getters dead). Decompiled
    // CollectDataManagerImpl.collectData() confirms (a, b) = (front, rear) for onDriverMotorSpeed —
    // separate HAL IDs/packet fields per side, not a tag. On RWD "a" reads a constant 50535 (outside
    // the RPM guard below, so it's naturally suppressed); on AWD it should carry real front RPM.
    // Volt/current stay rear-only (untested whether "a" is meaningful there too, and both callbacks
    // are @Deprecated in the real listener).
    private fun registerCollectData(dev: Any, ds: BydVehicleDataSource) {
        try {
            val l = object : AbsBYDAutoCollectDataListener() {
                override fun onMotorMCUGeneratrixVolt(a: Int, b: Int) {
                    if (b in 100..1000) { lastHvVolt = b; ds.applyDilink5HvVoltage(b); pushPower(ds) }
                }
                override fun onMotorMCUGeneratrixCurrent(a: Int, b: Int) {
                    if (b in -2000..2000) { lastHvCurrent = b; ds.applyDilink5HvCurrent(b); pushPower(ds) }  // signed A (regen negative)
                }
                override fun onDriverMotorSpeed(a: Int, b: Int) {
                    val front = a.takeIf { it in 0..30_000 }
                    val rear = b.takeIf { it in 0..30_000 }
                    if (front != null || rear != null) {
                        ds.applyDaemonTelemetry(speedKmh = null, gear = null, powerKw = null, frontRpm = front, rearRpm = rear)
                    }
                }
            }
            dev.javaClass.getMethod("registerListener", AbsBYDAutoCollectDataListener::class.java).invoke(dev, l)
            collectDataListener = l
            Log.i(tag, "collectdata listener registered")
        } catch (t: Throwable) {
            val c = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
            Log.w(tag, "collectdata listener failed: ${c.javaClass.simpleName}: ${c.message}")
        }
    }

    // Instrument typed listener — drive mode + ambient temp via EVENTS (instant, matches the D3
    // gearbox-listener approach; no 30 s poll lag). getSportModeState raw == app canonical; ambient
    // is plain °C.
    private fun registerInstrumentListener(dev: Any, ds: BydVehicleDataSource) {
        try {
            val l = object : AbsBYDAutoInstrumentListener() {
                override fun onSportModeStateChanged(state: Int) { ds.applyDilink5DriveMode(state) }
                override fun onOutCarTemperatureChanged(tempC: Int) { ds.applyDilink5AmbientTemp(tempC) }
            }
            dev.javaClass.getMethod("registerListener", AbsBYDAutoInstrumentListener::class.java).invoke(dev, l)
            instrumentListener = l
            Log.i(tag, "instrument listener registered")
        } catch (t: Throwable) {
            val c = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
            Log.w(tag, "instrument listener failed: ${c.javaClass.simpleName}: ${c.message}")
        }
    }

    // Real driving power = HV volts × amps / 1000. Sign follows the current sign (regen negative).
    // NOTE: verify sign convention on-car (drive should be positive); flip here if inverted.
    private fun pushPower(ds: BydVehicleDataSource) {
        val v = lastHvVolt; val i = lastHvCurrent ?: return
        if (v <= 0) return
        val kw = v * i / 1000.0
        if (kotlin.math.abs(kw) <= 500.0) ds.applyDaemonTelemetry(speedKmh = null, gear = null, powerKw = kw)
    }

    private fun bind(ctx: Context, className: String): Any? = try {
        Class.forName(className).getMethod("getInstance", Context::class.java).invoke(null, ctx)
            ?.also { Log.i(tag, "bound ${className.substringAfterLast('.')}") }
    } catch (t: Throwable) {
        // Unwrap InvocationTargetException so the *real* failure (e.g. the platform manager throwing)
        // is visible — bare "InvocationTargetException" tells us nothing about why a device won't bind.
        val c = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
        Log.w(tag, "bind ${className.substringAfterLast('.')} failed: ${c.javaClass.simpleName}: ${c.message}")
        null
    }

    private fun reflGetDouble(dev: Any?, getter: String): Double? = dev?.let {
        runCatching { (it.javaClass.getMethod(getter).invoke(it) as? Number)?.toDouble() }.getOrNull()
    }
    // Single-int-arg reflective getter (e.g. getTyrePressureValueByType(area)).
    private fun reflGetIntArg(dev: Any?, getter: String, arg: Int): Int? = dev?.let {
        runCatching {
            (it.javaClass.getMethod(getter, Int::class.javaPrimitiveType).invoke(it, arg) as? Number)?.toInt()
        }.getOrNull()
    }
    private fun reflGetInt(dev: Any?, getter: String): Int? = dev?.let {
        runCatching { (it.javaClass.getMethod(getter).invoke(it) as? Number)?.toInt() }.getOrNull()
    }
    private fun reflGetString(dev: Any?, getter: String): String? = dev?.let {
        runCatching { (it.javaClass.getMethod(getter).invoke(it) as? String)?.takeIf { s -> s.isNotBlank() } }.getOrNull()
    }
}
