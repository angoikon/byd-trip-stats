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
import android.hardware.bydauto.setting.AbsBYDAutoSettingListener
import android.hardware.bydauto.sensor.AbsBYDAutoSensorListener
import android.hardware.bydauto.pm2p5.AbsBYDAutoPM2p5Listener
import android.hardware.bydauto.energy.AbsBYDAutoEnergyListener
import android.hardware.bydauto.ac.AbsBYDAutoAcListener
import android.hardware.bydauto.BYDAutoEventValue
import com.byd.tripstats.util.DiagLog
import kotlin.math.roundToInt

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
    // Stored so DiagLog calls work from any function, not just start() — DiagLog persists to a
    // file (unlike plain Log.i/w, which this class otherwise relies on exclusively and which never
    // reaches diag.log in a release build — confirmed empirically: a full diag.log pull after an
    // on-car test showed zero lines from this class despite the service clearly having run. Also
    // used by registerCollectData to read the per-model decode overrides (CarConfig.motorRpmOffsetFront
    // etc.) without threading ctx through every callback.
    private var appCtx: Context? = null
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
    private var settingDev: Any? = null     // regen (energy feedback) mode select
    private var settingListener: Any? = null   // typed regen-mode listener (poll once, then events)

    // ── Compat-probe-only devices ────────────────────────────────────────────────
    // Confirmed dead on the dev car (slope on a real incline,
    // PM2.5 on an AC toggle, energy.getEnergyFeedback on a parked regen toggle, all 3
    // battery-temp candidates on a full DC-charge session). Wired here to feed ONLY
    // VehicleCompatibilityProbe (never ds.applyDilink5*) so another vehicle's firmware
    // can prove one of these real without polluting this car's confirmed telemetry.
    private var sensorDev: Any? = null
    private var sensorListener: Any? = null
    private var pm2p5Dev: Any? = null
    private var pm2p5Listener: Any? = null
    private var energyDev: Any? = null
    private var energyListener: Any? = null
    private var acListener: Any? = null   // battery-temp event only; acDev already bound for ambient temp
    private var collectDataDev: Any? = null
    private var collectDataListener: Any? = null
    // Latest HV bus readings from collectdata events → real power = V·I. Current stays Double end
    // to end (not rounded to Int) so a fractional-amp decode scale (e.g. Shark's 0.1 A/step) isn't
    // thrown away before it reaches the power calc — was previously rounded on write, discarding
    // ~0.4 kW of resolution for no reason.
    private var lastHvVolt: Int = 0
    private var lastHvCurrent: Double? = null

    // Dedup keys for the two EXPERIMENTAL decoded-value probe logs below — onDriverMotorSpeed fires
    // at ~10 Hz, so logging the decoded value on every call would double the busiest signal in a
    // 500-entry probe ring buffer and evict the rarer signals a not-yet-calibrated car most needs
    // captured. Only log when the decoded value actually changes (raw is still logged every call).
    private var lastLoggedDecodedCurrent: String? = null
    private var lastLoggedDecodedRpm: String? = null

    // derived-power state
    private var lastUsableKwh: Double = Double.NaN
    private var lastUsableAtMs: Long = 0L
    private var emaPowerKw: Double = Double.NaN

    fun start(ctx: Context, ds: BydVehicleDataSource) {
        if (running) return
        running = true
        appCtx = ctx.applicationContext
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
                        VehicleCompatibilityProbe.recordTypedEvent("statistic", "onElecPercentageChanged", v)
                        ds.applyDilink5Telemetry(socPanelPct = kotlin.math.round(v).toInt())
                    }
                    override fun onSOCBatteryPercentageChanged(v: Int) {
                        VehicleCompatibilityProbe.recordTypedEvent("statistic", "onSOCBatteryPercentageChanged", v)
                        ds.applyDilink5Telemetry(socPanelPct = v)
                    }
                    override fun onTotalMileageValueChanged(v: Float) {
                        VehicleCompatibilityProbe.recordTypedEvent("statistic", "onTotalMileageValueChanged", v)
                        ds.applyDilink5Telemetry(totalMileageKm = v.toDouble())
                    }
                    override fun onElecDrivingRangeChanged(v: Int) {
                        VehicleCompatibilityProbe.recordTypedEvent("statistic", "onElecDrivingRangeChanged", v)
                        ds.applyDilink5Telemetry(elecRangeKm = v)
                    }
                    override fun onDrivingRangeValueChanged(v: Int) {
                        VehicleCompatibilityProbe.recordTypedEvent("statistic", "onDrivingRangeValueChanged", v)
                        ds.applyDilink5Telemetry(elecRangeKm = v)
                    }
                    override fun onEVRemainingBatteryPowerChanged(v: Float) {
                        VehicleCompatibilityProbe.recordTypedEvent("statistic", "onEVRemainingBatteryPowerChanged", v)
                        onUsable(v.toDouble(), ds)
                    }
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
        // 0=LF/1=RF/2=LR/3=RR). This is the ONLY genuine per-wheel temp source in the SDK — no
        // getter (tyre or instrument device) returns real per-wheel data, see pollOnce.
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
        // Regen (energy feedback) mode select: poll once for the initial value, then rely on the
        // event for changes (see registerSettingListener). Needs BYDAUTO_SETTING_COMMON.
        settingDev = bind(ctx, "android.hardware.bydauto.setting.BYDAutoSettingDevice")
        settingDev?.let { registerSettingListener(it, ds) }
        // Compat-probe-only: dead-on-dev-car signals, probed for other vehicles (see field comments).
        sensorDev = bind(ctx, "android.hardware.bydauto.sensor.BYDAutoSensorDevice")
        sensorDev?.let { registerSensorProbe(it) }
        pm2p5Dev = bind(ctx, "android.hardware.bydauto.pm2p5.BYDAutoPM2p5Device")
        pm2p5Dev?.let { registerPm2p5Probe(it) }
        energyDev = bind(ctx, "android.hardware.bydauto.energy.BYDAutoEnergyDevice")
        energyDev?.let { registerEnergyProbe(it) }
        registerAcBatteryTempProbe(acDev)
        // charging/ota already bound above (real telemetry uses them) — just add the battery-temp
        // poll here; no new device, no new permission.
        reflGetDouble(chargingDev, "getChargeBatteryTemp")?.let {
            VehicleCompatibilityProbe.recordTypedEvent("battery-temp", "charging.getChargeBatteryTemp(poll)", it)
        }
        reflGetIntArg(otaDev, "getBatteryTemp", 1)?.let {
            VehicleCompatibilityProbe.recordTypedEvent("battery-temp", "ota.getBatteryTemp(1)(poll)", it)
        }

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
        try { settingListener?.let { l -> settingDev?.javaClass?.getMethod("unregisterListener", AbsBYDAutoSettingListener::class.java)?.invoke(settingDev, l) } } catch (_: Throwable) {}
        try { sensorListener?.let { l -> sensorDev?.javaClass?.getMethod("unregisterListener", AbsBYDAutoSensorListener::class.java)?.invoke(sensorDev, l) } } catch (_: Throwable) {}
        try { pm2p5Listener?.let { l -> pm2p5Dev?.javaClass?.getMethod("unregisterListener", AbsBYDAutoPM2p5Listener::class.java)?.invoke(pm2p5Dev, l) } } catch (_: Throwable) {}
        try { energyListener?.let { l -> energyDev?.javaClass?.getMethod("unregisterListener", AbsBYDAutoEnergyListener::class.java)?.invoke(energyDev, l) } } catch (_: Throwable) {}
        try { acListener?.let { l -> acDev?.javaClass?.getMethod("unregisterListener", AbsBYDAutoAcListener::class.java)?.invoke(acDev, l) } } catch (_: Throwable) {}
        tyreListener = null; collectDataListener = null; instrumentListener = null; speedListener = null; chargingListener = null; settingListener = null
        sensorListener = null; pm2p5Listener = null; energyListener = null; acListener = null
        chargingDev = null; speedDev = null; healthDev = null; tyreDev = null; collectDataDev = null; instrumentDev = null; otaDev = null; acDev = null; settingDev = null
        sensorDev = null; pm2p5Dev = null; energyDev = null
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
        // FAST: PHEV energy mode (1=EV/2=ForceEV/3=HEV/4=Fuel/5=Keep). Read every driving tick because
        // it gates each ~100 m projection sample as EV vs ICE distance (see the ICE-aware projection).
        // Returns 0/absent on BEVs → filtered out in applyDilink5Phev, so this is a no-op there.
        reflGetInt(energyDev, "getEnergyMode")?.let { ds.applyDilink5Phev(energyMode = it) }
        if (!slowTick) return
        // SLOW (~30s): the statistic LISTENER already pushes soc/mileage/range live, so these getters
        // are only a missed-callback backstop; SOH barely changes. No need to read them every tick.
        // Each getter is isolated (safeGet) rather than sharing one try/catch: on some firmwares an
        // individual statistic getter throws, and a shared catch silently took the rest of the block
        // down with it — SOC, odometer, range and usable-battery all going dark together on one bad
        // call. Same per-call isolation the reflGet* helpers already have.
        statDevice?.let { d ->
            safeGet { d.getElecPercentageValue() }?.takeIf { it in 1.0..100.0 }
                ?.let { ds.applyDilink5Telemetry(socPanelPct = kotlin.math.round(it).toInt()) }
            safeGet { d.getTotalMileageValue().toDouble() }?.takeIf { it > 1.0 }
                ?.let { ds.applyDilink5Telemetry(totalMileageKm = it) }
            safeGet { d.getElecDrivingRangeValue() }?.takeIf { it in 1..2000 }
                ?.let { ds.applyDilink5Telemetry(elecRangeKm = it) }
            safeGet { d.getEVRemainingBatteryPower().toDouble() }?.takeIf { it in 0.5..200.0 }
                ?.let { onUsable(it, ds) }
        }
        reflGetInt(healthDev, "getBatteryHealthStatus")?.takeIf { it in 50..110 }
            ?.let { ds.applyDilink5Telemetry(sohPct = it.toDouble()) }
        // SLOW: PHEV fuel + cumulative EV/ICE mileage from the statistic device (confirmed readable via
        // the compat-probe phev-sweep). These are lifetime counters / slow-moving state, so 30 s is
        // ample. Range-guarded + change-gated in applyDilink5Phev; all null/0/absent on a BEV.
        //  - Fuel % and fuel range → dashboard Fuel/Total range.
        //  - Total fuel litres + EV/ICE mileage → Hybrid Breakdown card.
        //  - Water temp → engine-coolant (cold-start insight).
        statDevice?.let { d ->
            ds.applyDilink5Phev(
                fuelPercentage       = reflGetInt(d, "getFuelPercentageValue"),
                fuelDrivingRangeKm   = reflGetInt(d, "getFuelDrivingRangeValue"),
                totalFuelConsumption = reflGetDouble(d, "getTotalFuelConValue"),
                avgFuelConsumption   = reflGetDouble(d, "getTotalFuelConPHMValue"),
                evMileageKm          = reflGetInt(d, "getEVMileageValue"),
                iceMileageKm         = reflGetInt(d, "getHEVMileageValue"),
                engineCoolantTempC   = reflGetInt(d, "getWaterTemperature"),
            )
        }
        // Per-wheel tyre PRESSURE VALUE is NOT polled here — getTyrePressureValueByType(area)
        // re-scales its raw output to whatever pressure unit the dash cluster currently has
        // selected (confirmed on-car: raw ~27 in Bar mode, ~277 in kPa mode, ~401 in PSI mode, same
        // physical tyre). The event onTyrePressureValueByTypeChanged carries the same ambiguity but
        // reliably covers all 4 wheels, and BydVehicleDataSource's tyre listener decodes it using the
        // live unit state from applyDilink5PressureUnit (instrument fid=4208, see
        // registerInstrumentListener) — see BydVehicleDataSource.onTyrePressureValueByTypeChanged.
        // STATE is unit-independent (a 0/1/2 enum, not a physical value) so it's still safe to poll.
        // TEMPERATURE is NOT polled: the getter returns an index-0 sentinel (uniform/wrong) — real
        // per-wheel temp comes from the tyre listener (registerTyre → applyDilink5TyreTemp).
        tyreDev?.let { t ->
            ds.applyDilink5TyreState(
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
        // instrument.getWheelTemperature(int) is NOT polled: decompiled BYDAutoInstrumentDevice
        // shows its real body is `return 0;` — hardcoded, ignores the wheel arg entirely. There is
        // no per-wheel temp getter anywhere in this SDK (BYDAutoTyreDevice.getTyreTemperatureValue
        // similarly discards its arg and calls a no-arg method underneath); the tyre listener's
        // onTyreTemperatureValueChanged event is the only genuine per-wheel source that exists.
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
                    VehicleCompatibilityProbe.recordTypedEvent("tyre", "onTyreTemperatureValueChanged[$wheel]", value)
                    ds.applyDilink5TyreTemp(wheel, value)
                }
                // Routed here (not BydVehicleDataSource's own tyreListener) because THIS class's
                // subclass of AbsBYDAutoTyreListener is the one confirmed, on-car, to actually link
                // against the injected real SDK class — BydVehicleDataSource's own tyreListener
                // deterministically throws NoClassDefFoundError on every DiLink-5 launch. See
                // handleDilink5TyrePressureByType's own comment for what's been ruled out.
                override fun onTyrePressureValueByTypeChanged(wheel: Int, value: Float) {
                    VehicleCompatibilityProbe.recordTypedEvent("tyre", "onTyrePressureValueByTypeChanged[$wheel]", value)
                    ds.handleDilink5TyrePressureByType(wheel, value)
                }
            }
            dev.javaClass.getMethod("registerListener", AbsBYDAutoTyreListener::class.java).invoke(dev, l)
            tyreListener = l
            Log.i(tag, "tyre listener registered")
            diag("tyre listener registered (Dilink5Client's own temp-only registration)")
        } catch (t: Throwable) {
            val c = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
            Log.w(tag, "tyre listener failed: ${c.javaClass.simpleName}: ${c.message}")
            diag("tyre listener FAILED (Dilink5Client's own): ${c.javaClass.simpleName}: ${c.message}")
        }
    }

    // Charging typed listener — event-driven; onChargingPowerChanged gives far more resolution during
    // a charge ramp than the 5s poll. getChargingPower() stays as a backstop in pollOnce.
    private fun registerChargingListener(dev: Any, ds: BydVehicleDataSource) {
        try {
            // Both overloads declared: D5 calls Float (confirmed), D3 may call Double — same
            // belt-and-suspenders pattern as the speed listener's int/double pair.
            val l = object : AbsBYDAutoChargingListener() {
                override fun onChargingPowerChanged(power: Float) {
                    VehicleCompatibilityProbe.recordTypedEvent("charging", "onChargingPowerChanged(Float)", power)
                    ds.applyDilink5Telemetry(chargingPowerKw = power.toDouble())
                }
                override fun onChargingPowerChanged(power: Double) {
                    VehicleCompatibilityProbe.recordTypedEvent("charging", "onChargingPowerChanged(Double)", power)
                    ds.applyDilink5Telemetry(chargingPowerKw = power)
                }
            }
            dev.javaClass.getMethod("registerListener", AbsBYDAutoChargingListener::class.java).invoke(dev, l)
            chargingListener = l
            Log.i(tag, "charging listener registered")
            diag("charging listener registered (Dilink5Client's own, FIRST subclass touch of AbsBYDAutoChargingListener)")
        } catch (t: Throwable) {
            val c = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
            Log.w(tag, "charging listener failed: ${c.javaClass.simpleName}: ${c.message}")
            diag("charging listener FAILED (Dilink5Client's own, FIRST subclass touch): ${c.javaClass.simpleName}: ${c.message}")
        }
    }

    // Speed typed listener — event-driven, instant. getSpeedValue() stays as a backstop in pollOnce
    // (parked/no-change periods where an onChanged event legitimately never fires).
    private fun registerSpeedListener(dev: Any, ds: BydVehicleDataSource) {
        try {
            val l = object : AbsBYDAutoSpeedListener() {
                override fun onSpeedChanged(speed: Double) {
                    VehicleCompatibilityProbe.recordTypedEvent("speed", "onSpeedChanged", speed)
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

    // EXPERIMENTAL per-model decode (see CarConfig.motorRpmOffsetFront etc.) — cheap to call per
    // event (SharedPreferences-backed cache, no disk I/O), and Sealion 7 gets the all-zero/scale-1
    // default so this is a no-op there. Not cached in a field: the user can switch the selected
    // car without restarting the telemetry service, and this is called at most a few times/sec.
    private fun collectDataCarConfig(): com.byd.tripstats.data.config.CarConfig? =
        appCtx?.let { com.byd.tripstats.data.preferences.PreferencesManager(it).getCachedSelectedCarConfig() }

    // collectdata typed listener — HV bus V/I + motor RPM (event-only; getters dead). Decompiled
    // CollectDataManagerImpl.collectData() confirms (a, b) = (front, rear) for onDriverMotorSpeed —
    // separate HAL IDs/packet fields per side, not a tag. On RWD "a" reads a constant 50535 (outside
    // the RPM guard below, so it's naturally suppressed); on AWD it should carry real front RPM.
    // Volt/current stay rear-only (untested whether "a" is meaningful there too, and both callbacks
    // are @Deprecated in the real listener).
    //
    // RAW values are logged unconditionally via VehicleCompatibilityProbe (both before AND after
    // the per-model decode) so a compat probe from a not-yet-calibrated DiLink-5 car always carries
    // enough evidence to derive its own offset/scale — this is exactly how the Shark 6 DM-o AWD
    // values here were themselves inferred, and the framework needs the same raw evidence from
    // every other model to get confirmed.
    private fun registerCollectData(dev: Any, ds: BydVehicleDataSource) {
        try {
            val l = object : AbsBYDAutoCollectDataListener() {
                override fun onMotorMCUGeneratrixVolt(a: Int, b: Int) {
                    VehicleCompatibilityProbe.recordTypedEvent("collectdata", "onMotorMCUGeneratrixVolt", "a=$a b=$b")
                    if (b in 100..1000) { lastHvVolt = b; ds.applyDilink5HvVoltage(b); pushPower(ds) }
                }
                override fun onMotorMCUGeneratrixCurrent(a: Int, b: Int) {
                    VehicleCompatibilityProbe.recordTypedEvent("collectdata", "onMotorMCUGeneratrixCurrent", "a=$a b=$b")
                    val cfg = collectDataCarConfig()
                    val decoded = (b - (cfg?.hvCurrentOffset ?: 0)) * (cfg?.hvCurrentScale ?: 1.0)
                    val decodedStr = decoded.toString()
                    if (lastLoggedDecodedCurrent != decodedStr) {
                        lastLoggedDecodedCurrent = decodedStr
                        VehicleCompatibilityProbe.recordTypedEvent("collectdata", "onMotorMCUGeneratrixCurrent-decoded", decoded)
                    }
                    // Kept as Double (not rounded to Int) end to end — a fractional scale like
                    // Shark's 0.1 A/step is otherwise thrown away before it reaches the power calc.
                    decoded.takeIf { it in -2000.0..2000.0 }?.let {
                        lastHvCurrent = it; ds.applyDilink5HvCurrent(it); pushPower(ds)  // signed A (regen negative)
                    }
                }
                override fun onDriverMotorSpeed(a: Int, b: Int) {
                    VehicleCompatibilityProbe.recordTypedEvent("collectdata", "onDriverMotorSpeed", "a=$a b=$b")
                    val cfg = collectDataCarConfig()
                    // scale (not just offset): Shark's front channel counts DOWN as RPM rises (a
                    // sign flip, confirmed via a 12 km/h creep sample — |a-15000| and b-30000 both
                    // hit 0 at standstill and scale linearly with speed), so a plain offset alone
                    // reads 0 parked and goes negative (rejected by the guard) the moment it moves.
                    // Default scale 1.0 keeps this a no-op for every model but Shark (front = -1.0).
                    val front = ((a - (cfg?.motorRpmOffsetFront ?: 0)) * (cfg?.motorRpmScaleFront ?: 1.0))
                        .roundToInt().takeIf { it in 0..30_000 }
                    val rear = ((b - (cfg?.motorRpmOffsetRear ?: 0)) * (cfg?.motorRpmScaleRear ?: 1.0))
                        .roundToInt().takeIf { it in 0..30_000 }
                    val decodedStr = "front=$front rear=$rear"
                    if (lastLoggedDecodedRpm != decodedStr) {
                        lastLoggedDecodedRpm = decodedStr
                        VehicleCompatibilityProbe.recordTypedEvent("collectdata", "onDriverMotorSpeed-decoded", decodedStr)
                    }
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
    //
    // Also carries the tyre-pressure display unit: fid=4208 (INSTRUMENT_UNIT_PRESSURE, no dedicated
    // getter/callback — only reachable through the generic onDataEventChanged catch-all). Confirmed
    // on-car: int value 1=Bar, 2=PSI, 3=kPa, matching the dash cluster's currently selected unit —
    // the tyre pressure getter/event re-scales its raw output to match this same setting, so it's
    // undecodable without it. Poll once for the initial value, then listen for changes.
    private fun registerInstrumentListener(dev: Any, ds: BydVehicleDataSource) {
        val polled = reflGetPressureUnit(dev)
        diag("pressureUnit poll -> $polled")
        // Seed tyre pressure from a one-time poll now that the unit is known, so the dashboard
        // shows a real value on launch instead of sitting blank until the SDK happens to fire an
        // onTyrePressureValueByTypeChanged event (which can take a while — the on-car symptom was
        // "empty until an event fires, either naturally or by forcing one via a dash-unit change").
        // Only meaningful once the unit is known (nothing to decode raw against otherwise), and
        // must run AFTER the unit poll above, not before — same ordering constraint the event path
        // already has. registerTyreListener runs earlier in start()'s device-bind sequence, so
        // tyreDev is already bound by the time this executes.
        polled?.let { ds.applyDilink5PressureUnit(it); pollTyrePressureOnce(ds) }
        try {
            val l = object : AbsBYDAutoInstrumentListener() {
                override fun onSportModeStateChanged(state: Int) {
                    VehicleCompatibilityProbe.recordTypedEvent("instrument", "onSportModeStateChanged", state)
                    ds.applyDilink5DriveMode(state)
                }
                override fun onOutCarTemperatureChanged(tempC: Int) {
                    VehicleCompatibilityProbe.recordTypedEvent("instrument", "onOutCarTemperatureChanged", tempC)
                    ds.applyDilink5AmbientTemp(tempC)
                }
                override fun onDataEventChanged(fid: Int, value: BYDAutoEventValue) {
                    if (fid == 4208) {
                        diag("pressureUnit event fid=4208 int=${value.intValue}")
                        VehicleCompatibilityProbe.recordTypedEvent("instrument", "onDataEventChanged(4208=pressureUnit)", value.intValue)
                        ds.applyDilink5PressureUnit(value.intValue)
                    }
                }
            }
            dev.javaClass.getMethod("registerListener", AbsBYDAutoInstrumentListener::class.java).invoke(dev, l)
            dev.javaClass.getMethod("registerListener", AbsBYDAutoInstrumentListener::class.java, IntArray::class.java)
                .invoke(dev, l, intArrayOf(4208, 4209, 4210))
            instrumentListener = l
            Log.i(tag, "instrument listener registered")
            diag("instrument listener registered (class ${dev.javaClass.name})")
        } catch (t: Throwable) {
            val c = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
            Log.w(tag, "instrument listener failed: ${c.javaClass.simpleName}: ${c.message}")
            diag("instrument listener FAILED: ${c.javaClass.simpleName}: ${c.message}")
        }
    }

    // Re-registers ONLY the instrument listener (drive mode / ambient temp / pressure unit). Called
    // from BydVehicleDataSource.recoverEventDelivery() on an SDK event-delivery wedge — that function
    // re-arms its own D3-shared device listeners (gearbox/charging/tyre) but has no reference to this
    // class's separate instrumentDev/instrumentListener, so without this hook a wedge that hits the
    // instrument device would silently freeze drive mode, ambient temp, AND the pressure-unit
    // tracking forever (the tyre pressure VALUE event keeps flowing via the D3-shared listener, so it
    // would look like a partial, confusing failure rather than an obvious one).
    fun recoverInstrumentListener(ds: BydVehicleDataSource) {
        val dev = instrumentDev ?: return
        diag("recoverInstrumentListener: re-registering")
        runCatching {
            instrumentListener?.let { l ->
                dev.javaClass.getMethod("unregisterListener", AbsBYDAutoInstrumentListener::class.java).invoke(dev, l)
            }
        }
        registerInstrumentListener(dev, ds)
    }

    private fun diag(msg: String) {
        appCtx?.let { DiagLog.event(it, tag, msg) }
    }

    // Regen mode: poll once for the initial value (no repeated polling — the setter is a manual,
    // rarely-changed UI toggle, not a fast-changing telemetry field), then register the typed
    // listener for onEnergyFeedbackStrengthChanged so subsequent changes arrive live.
    private fun registerSettingListener(dev: Any, ds: BydVehicleDataSource) {
        reflGetInt(dev, "getEnergyFeedback")?.let {
            VehicleCompatibilityProbe.recordTypedEvent("setting", "getEnergyFeedback(poll)", it)
            ds.applyDilink5RegenMode(it)
        }
        try {
            val l = object : AbsBYDAutoSettingListener() {
                override fun onEnergyFeedbackStrengthChanged(strength: Int) {
                    VehicleCompatibilityProbe.recordTypedEvent("setting", "onEnergyFeedbackStrengthChanged", strength)
                    ds.applyDilink5RegenMode(strength)
                }
            }
            dev.javaClass.getMethod("registerListener", AbsBYDAutoSettingListener::class.java).invoke(dev, l)
            settingListener = l
            Log.i(tag, "setting listener registered")
        } catch (t: Throwable) {
            val c = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
            Log.w(tag, "setting listener failed: ${c.javaClass.simpleName}: ${c.message}")
        }
    }

    // ── Compat-probe-only registrations (poll once, then listen) ─────────────────
    // All confirmed dead on the dev car — feed VehicleCompatibilityProbe only, never ds.applyDilink5*.

    private fun registerSensorProbe(dev: Any) {
        reflGetInt(dev, "getSlope")?.let { VehicleCompatibilityProbe.recordTypedEvent("sensor", "getSlope(poll)", it) }
        try {
            val l = object : AbsBYDAutoSensorListener() {
                override fun onSlopeValueChanged(slope: Int) {
                    VehicleCompatibilityProbe.recordTypedEvent("sensor", "onSlopeValueChanged", slope)
                }
            }
            dev.javaClass.getMethod("registerListener", AbsBYDAutoSensorListener::class.java).invoke(dev, l)
            sensorListener = l
            Log.i(tag, "sensor probe listener registered")
        } catch (t: Throwable) {
            val c = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
            Log.w(tag, "sensor probe listener failed: ${c.javaClass.simpleName}: ${c.message}")
        }
    }

    private fun registerPm2p5Probe(dev: Any) {
        reflGetInt(dev, "getPM2p5OnlineState")?.let { VehicleCompatibilityProbe.recordTypedEvent("pm2p5", "getPM2p5OnlineState(poll)", it) }
        reflGetIntArray(dev, "getPM2p5Level")?.let { VehicleCompatibilityProbe.recordTypedEvent("pm2p5", "getPM2p5Level(poll)", it.joinToString(",")) }
        reflGetIntArray(dev, "getPM2p5Value")?.let { VehicleCompatibilityProbe.recordTypedEvent("pm2p5", "getPM2p5Value(poll)", it.joinToString(",")) }
        try {
            val l = object : AbsBYDAutoPM2p5Listener() {
                override fun onPM2p5ValueChanged(inCar: Int, outCar: Int) {
                    VehicleCompatibilityProbe.recordTypedEvent("pm2p5", "onPM2p5ValueChanged", "in=$inCar out=$outCar")
                }
                override fun onPM2p5LevelChanged(inCar: Int, outCar: Int) {
                    VehicleCompatibilityProbe.recordTypedEvent("pm2p5", "onPM2p5LevelChanged", "in=$inCar out=$outCar")
                }
                override fun onPM2p5OnlineStateChanged(state: Int) {
                    VehicleCompatibilityProbe.recordTypedEvent("pm2p5", "onPM2p5OnlineStateChanged", state)
                }
            }
            dev.javaClass.getMethod("registerListener", AbsBYDAutoPM2p5Listener::class.java).invoke(dev, l)
            pm2p5Listener = l
            Log.i(tag, "pm2p5 probe listener registered")
        } catch (t: Throwable) {
            val c = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
            Log.w(tag, "pm2p5 probe listener failed: ${c.javaClass.simpleName}: ${c.message}")
        }
    }

    private fun registerEnergyProbe(dev: Any) {
        reflGetInt(dev, "getEnergyFeedback")?.let { VehicleCompatibilityProbe.recordTypedEvent("energy", "getEnergyFeedback(poll)", it) }
        try {
            val l = object : AbsBYDAutoEnergyListener() {
                override fun onEnergyFeedbackLevelChanged(level: Int) {
                    VehicleCompatibilityProbe.recordTypedEvent("energy", "onEnergyFeedbackLevelChanged", level)
                }
            }
            dev.javaClass.getMethod("registerListener", AbsBYDAutoEnergyListener::class.java).invoke(dev, l)
            energyListener = l
            Log.i(tag, "energy probe listener registered")
        } catch (t: Throwable) {
            val c = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
            Log.w(tag, "energy probe listener failed: ${c.javaClass.simpleName}: ${c.message}")
        }
    }

    private fun registerAcBatteryTempProbe(dev: Any?) {
        if (dev == null) return
        reflGetDouble(dev, "getAcSubBatteryTemperature")?.let {
            VehicleCompatibilityProbe.recordTypedEvent("battery-temp", "ac.getAcSubBatteryTemperature(poll)", it)
        }
        try {
            val l = object : AbsBYDAutoAcListener() {
                override fun onOtaSubBatteryTemperatureChanged(temp: Int) {
                    VehicleCompatibilityProbe.recordTypedEvent("battery-temp", "onOtaSubBatteryTemperatureChanged", temp)
                }
            }
            dev.javaClass.getMethod("registerListener", AbsBYDAutoAcListener::class.java).invoke(dev, l)
            acListener = l
            Log.i(tag, "ac battery-temp probe listener registered")
        } catch (t: Throwable) {
            val c = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
            Log.w(tag, "ac battery-temp probe listener failed: ${c.javaClass.simpleName}: ${c.message}")
        }
    }

    // Real driving power = HV volts × amps / 1000. Sign follows the current sign (regen negative).
    // NOTE: verify sign convention on-car (drive should be positive); flip here if inverted.
    //
    // Voltage fallback: onMotorMCUGeneratrixVolt confirmed live on Sealion 7 (~430-475 V), but never
    // fired ONCE in a 35-minute Shark 6 DM-o AWD capture while current fired continuously — without
    // a fallback, power reads 0 forever on any car whose MCU firmware doesn't broadcast this signal,
    // even though current (and therefore power) is right there. `cellCount * 3.2` is the same LFP
    // nominal-voltage estimate already used throughout CarCatalog's own cellCount comments (e.g.
    // "422.4/3.2 = 132S Blade LFP") — reusing it here instead of adding a redundant CarConfig field.
    // Only used while no live voltage has arrived yet; a real onMotorMCUGeneratrixVolt reading
    // always wins once one shows up.
    private fun pushPower(ds: BydVehicleDataSource) {
        val i = lastHvCurrent ?: return
        val v = lastHvVolt.takeIf { it > 0 }?.toDouble()
            ?: collectDataCarConfig()?.cellCount?.takeIf { it > 0 }?.let { it * 3.2 }
            ?: return
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
    // Float-returning single-int-arg getter — getTyrePressureValueByType(area) matches the real
    // listener's Float-typed onTyrePressureValueByTypeChanged, unlike reflGetIntArg's callers.
    private fun reflGetFloatArg(dev: Any?, getter: String, arg: Int): Float? = dev?.let {
        runCatching {
            (it.javaClass.getMethod(getter, Int::class.javaPrimitiveType).invoke(it, arg) as? Number)?.toFloat()
        }.getOrNull()
    }
    // One-time startup seed for tyre pressure — see registerInstrumentListener's call site for why.
    // Feeds the SAME decode path as the event (handleDilink5TyrePressureByType), so a poll result and
    // a later event agree exactly; the poll is just a bridge until the first real event arrives.
    private fun pollTyrePressureOnce(ds: BydVehicleDataSource) {
        val t = tyreDev ?: return
        for (wheel in 1..4) {
            reflGetFloatArg(t, "getTyrePressureValueByType", wheel)?.let { ds.handleDilink5TyrePressureByType(wheel, it) }
        }
    }
    private fun reflGetInt(dev: Any?, getter: String): Int? = dev?.let {
        runCatching { (it.javaClass.getMethod(getter).invoke(it) as? Number)?.toInt() }.getOrNull()
    }
    /**
     * Typed analogue of the reflGet* helpers: isolates ONE getter so a throwing call can't take
     * its neighbours down with it. Use for the statistic-device getters that exist on the compile-
     * time stub signature (no reflection needed) but can still throw on a given firmware.
     */
    private inline fun <T> safeGet(read: () -> T): T? = runCatching(read).getOrNull()
    private fun reflGetString(dev: Any?, getter: String): String? = dev?.let {
        runCatching { (it.javaClass.getMethod(getter).invoke(it) as? String)?.takeIf { s -> s.isNotBlank() } }.getOrNull()
    }
    private fun reflGetIntArray(dev: Any?, getter: String): IntArray? = dev?.let {
        runCatching { it.javaClass.getMethod(getter).invoke(it) as? IntArray }.getOrNull()
    }
    // One-shot poll of the tyre-pressure-unit feature (fid=4208) — see registerInstrumentListener.
    private fun reflGetPressureUnit(dev: Any?): Int? = dev?.let {
        runCatching {
            val value = it.javaClass.getMethod("get", IntArray::class.java, Class::class.java)
                .invoke(it, intArrayOf(4208), BYDAutoEventValue::class.java) as? BYDAutoEventValue
            value?.intValue
        }.getOrNull()
    }
}
