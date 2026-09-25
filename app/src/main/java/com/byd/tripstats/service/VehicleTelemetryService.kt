package com.byd.tripstats.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.byd.tripstats.util.DiagLog
import com.byd.tripstats.util.McuWakeHelper
import com.byd.tripstats.util.ServiceIdleState
import com.byd.tripstats.receiver.OffStateKeepaliveReceiver
import com.byd.tripstats.worker.ServiceWatchdogWorker
import kotlinx.coroutines.flow.collectLatest
import com.byd.tripstats.MainActivity
import com.byd.tripstats.R
import com.byd.tripstats.data.config.CarConfig
import com.byd.tripstats.data.entitlement.EntitlementManager
import com.byd.tripstats.data.model.VehicleTelemetry
import com.byd.tripstats.data.notify.TelegramNotifier
import com.byd.tripstats.data.preferences.OffStateMode
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.data.repository.BatteryVoltageHistoryRepository
import com.byd.tripstats.data.repository.ChargingRepository
import com.byd.tripstats.data.repository.TripRepository
import com.byd.tripstats.connections.AbrpConnectionManager
import com.byd.tripstats.connections.MqttConnectionManager
import com.byd.tripstats.receiver.ServiceRestartReceiver
import com.byd.tripstats.sdk.BydVehicleDataSource
import com.byd.tripstats.sdk.DiLink5Platform
import com.byd.tripstats.sdk.VehicleCompatibilityProbe
import com.byd.tripstats.sdk.VehicleTelemetrySnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Background service that acquires vehicle telemetry from the car at a
 * 1-second interval.
 *
 * Runs as a foreground service with type dataSync|location. The location type
 * is actively satisfied by a real LocationManager subscription so the OS sets
 * oom_adj=PERCEPTIBLE_APP_ADJ (125) instead of SERVICE_ADJ (200). At adj=125
 * the process survives all but the most extreme memory pressure.
 */
class VehicleTelemetryService : Service() {

    private val TAG = "VehicleTelemetrySvc"
    private val CHANNEL_ID = "vehicle_telemetry_service_channel"
    private val NOTIFICATION_ID = 1

    private val binder = LocalBinder()

    private lateinit var vehicleDataSource: BydVehicleDataSource
    val telemetrySnapshot: StateFlow<VehicleTelemetrySnapshot>
        get() = vehicleDataSource.vehicleSnapshot

    fun refreshVehicleSnapshot() {
        vehicleDataSource.refreshSnapshots()
    }

    /**
     * Runs [BydVehicleDataSource.refreshSnapshots] on an isolated worker with a
     * timeout. If a BYD getter is blocking (HAL/binder hang after an engine
     * off→on cycle), the call won't wedge the telemetry loop — we abandon the
     * wait, keep looping on the last snapshot, and record the wedge to DiagLog so
     * it can be reviewed in-app after parking (no logcat needed). A previously
     * submitted refresh that is still stuck is NOT resubmitted, so hung work can't
     * pile up; the moment the getter returns, normal refreshes resume.
     */
    private fun refreshSnapshotsGuarded() {
        val pending = pendingRefresh
        if (pending == null || pending.isDone) {
            pendingRefresh = refreshExecutor.submit {
                runCatching { vehicleDataSource.refreshSnapshots() }
                    .onFailure { Log.w(TAG, "refreshSnapshots threw: ${it.message}") }
            }
        }
        try {
            pendingRefresh?.get(REFRESH_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (refreshWedgeStrikes > 0) {
                DiagLog.event(this, TAG,
                    "✅ telemetry refresh recovered after $refreshWedgeStrikes wedged cycle(s) — a blocked BYD getter has returned")
                refreshWedgeStrikes = 0
            }
        } catch (te: java.util.concurrent.TimeoutException) {
            refreshWedgeStrikes++
            // Log the first wedge and then sparsely, so a long hang doesn't spam the file.
            if (refreshWedgeStrikes == 1 || refreshWedgeStrikes % 15 == 0) {
                DiagLog.event(this, TAG,
                    "⚠️ telemetry refresh WEDGED at stage '${vehicleDataSource.refreshStage}' " +
                        "(cycle $refreshWedgeStrikes, >${REFRESH_TIMEOUT_MS}ms): that BYD device getter is blocking. " +
                        "Speed/telemetry are frozen and the compatibility probe will hang until it returns " +
                        "(an engine off/on clears it). The loop itself is kept alive.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "guarded refresh failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    companion object {
        @Volatile
        private var stopRequested = false

        // Live reference to the running data source, so an in-app update can gracefully release the
        // BYD SDK listeners before the install force-kills this process (prevents the post-update wedge).
        @Volatile
        private var activeDataSource: com.byd.tripstats.sdk.BydVehicleDataSource? = null

        fun start(context: Context) {
            stopRequested = false
            context.startForegroundService(Intent(context, VehicleTelemetryService::class.java))
        }

        fun stop(context: Context) {
            stopRequested = true
            context.stopService(Intent(context, VehicleTelemetryService::class.java))
        }

        /** Call right before committing an in-app update — unregisters all SDK listeners while alive. */
        fun prepareForUpdate() {
            runCatching { activeDataSource?.prepareForUpdate() }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var telemetryLoopJob: Job? = null

    // ── Snapshot-refresh wedge guard ───────────────────────────────────────────
    // refreshSnapshots() invokes BYD device getters via synchronous reflection /
    // binder calls that have no timeout of their own. On some firmwares a getter
    // can block indefinitely after an engine off→on cycle, which — if run directly
    // on the telemetry loop — would freeze speed/telemetry (and hang the compat
    // probe) until the HAL recovers. We run it on a dedicated single worker thread
    // and wait with a timeout, so one hung getter can never wedge the loop; the
    // loop keeps running on the last snapshot and the wedge is recorded to DiagLog.
    private val refreshExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "telemetry-refresh").apply { isDaemon = true }
    }
    private var pendingRefresh: java.util.concurrent.Future<*>? = null
    private var refreshWedgeStrikes = 0
    private val REFRESH_TIMEOUT_MS = 4_000L

    private var tripRepository: TripRepository? = null
    private var chargingRepository: ChargingRepository? = null
    private var batteryVoltageHistoryRepository: BatteryVoltageHistoryRepository? = null
    private var carConfig: CarConfig? = null
    private var abrpConnectionManager: AbrpConnectionManager? = null
    private var mqttConnectionManager: MqttConnectionManager? = null
    private var cellImbalanceMonitor: CellImbalanceMonitor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var intentionalStop = false
    private var notificationStartedAtWallClock: Long = 0L
    @Volatile
    private var telemetryLoopActive = false
    @Volatile
    private var telemetryLoopStarting = false

    // ── Location subscription — satisfies foregroundServiceType=location ───────
    // The OS validates that a location-type foreground service actually uses
    // LocationManager. Without a real subscription, the service type is ignored
    // and oom_adj stays at SERVICE_ADJ (200) rather than PERCEPTIBLE_APP_ADJ (125).
    private var locationManager: LocationManager? = null
    // All LocationListener methods must be implemented on API 29 (Android 10).
    // Missing onProviderDisabled/onProviderEnabled causes AbstractMethodError crash.
    private val keepaliveLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // The app's telemetry source handles trip location; this subscription
            // only satisfies the OS foreground service type check.
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }

    // ── Connection state ───────────────────────────────────────────────────────
    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting   : ConnectionState()
        object Connected    : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _telemetryCount = MutableStateFlow(0)
    val telemetryCount: StateFlow<Int> = _telemetryCount.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): VehicleTelemetryService = this@VehicleTelemetryService
    }

    override fun onBind(intent: Intent): IBinder = binder

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        DiagLog.event(applicationContext, TAG, "onCreate")
        notificationStartedAtWallClock = System.currentTimeMillis()
        createNotificationChannel()
        acquireWakeLock()
        acquireLocationSubscription()
        vehicleDataSource = BydVehicleDataSource(this)
        activeDataSource = vehicleDataSource
        vehicleDataSource.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (telemetryLoopActive || telemetryLoopStarting) {
            Log.d(TAG, "onStartCommand — telemetry loop already active, ignoring duplicate start")
            return START_STICKY
        }
        telemetryLoopStarting = true
        Log.d(TAG, "onStartCommand — starting telemetry loop")
        intentionalStop = stopRequested
        // We're starting up — clear the off-state idle flag and re-arm the
        // periodic restart sources so they protect the service from process
        // death during this session. Both schedule calls are idempotent:
        // ServiceWatchdogWorker uses KEEP policy and JobScheduler ignores
        // duplicate jobIds.
        ServiceIdleState.setStayingIdle(applicationContext, false)
        ServiceWatchdogWorker.schedule(applicationContext)
        ServiceRestarterJobService.schedulePeriodic(applicationContext, "service-onStartCommand")

        // Declare both dataSync and location foreground service types.
        // The location type is what drives the OS to assign oom_adj=125 (PERCEPTIBLE)
        // rather than adj=200 (SERVICE). The acquireLocationSubscription() call in
        // onCreate() ensures the OS can verify the location type is genuine.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            createNotification("Trip recording and data visualization"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            else
                0
        )
        _connectionState.value = ConnectionState.Connecting

        tripRepository = TripRepository.getInstance(applicationContext)
        chargingRepository = ChargingRepository.getInstance(applicationContext)
        batteryVoltageHistoryRepository = BatteryVoltageHistoryRepository.getInstance(applicationContext)
        abrpConnectionManager = AbrpConnectionManager(applicationContext)
        mqttConnectionManager = MqttConnectionManager(applicationContext)
        cellImbalanceMonitor = CellImbalanceMonitor(applicationContext)

        // Retry any event push that couldn't go out last time (no network while parked,
        // or the process died with the queue unsent). The service starting is the best
        // available proxy for "the car is awake and the link is likely back".
        TelegramNotifier.getInstance(applicationContext).flushPending()

        // Bring the tailnet back up if the user enabled it. This is the app's "the car woke up"
        // moment, and a head-unit reboot wipes /data/local/tmp — daemon, state directory and all —
        // so without this, remote access would silently stay down until someone opened Settings.
        serviceScope.launch {
            runCatching { com.byd.tripstats.util.TailscaleManager.restoreIfEnabled(applicationContext) }
                .onFailure { Log.w(TAG, "Tailscale restore failed: ${it.message}") }
        }

        // Start telemetry loop IMMEDIATELY using the synchronous SharedPreferences
        // cache — do NOT wait for DataStore async emit. The notification already
        // shows "Starting…" so the user thinks the service is ready; if we wait
        // for DataStore (100–500 ms on DiLink) we miss the first telemetry packets
        // and auto-trip detection never fires on the drive after a fresh start.
        val prefs = PreferencesManager(applicationContext)
        carConfig = prefs.getCachedSelectedCarConfig()
        Log.d(TAG, "Car config (cached): ${carConfig?.displayName ?: "none"} — starting loop immediately")
        pushCarConfigToProbe(carConfig)
        startTelemetryLoop()

        // Also watch DataStore for car selection changes (user switches car model).
        // When it emits a different value, restart the loop with updated config.
        serviceScope.launch {
            try {
                prefs.selectedCarConfig
                    .distinctUntilChanged()
                    .collect { config ->
                        if (config?.id != carConfig?.id) {
                            carConfig = config
                            Log.d(TAG, "Car config changed to ${config?.displayName ?: "none"} — restarting loop")
                            pushCarConfigToProbe(config)
                            startTelemetryLoop()
                        } else {
                            // Same car — just update the reference in case object differs
                            carConfig = config
                        }
                    }
            } catch (t: Throwable) {
                Log.w(TAG, "Car config watcher error: ${t.message}")
            }
        }

        return START_STICKY
    }

    private fun pushCarConfigToProbe(config: CarConfig?) {
        VehicleCompatibilityProbe.setVehicleInfo(
            userModel            = config?.displayName,
            userModelId          = config?.id,
            isPhev               = config?.isPhev,
            drivetrain           = config?.drivetrain?.name,
            batteryKwh           = config?.batteryKwh,
            phevUsableBatteryKwh = config?.phevUsableBatteryKwh,
            wltpKm               = config?.wltpKm,
        )
    }

    override fun onDestroy() {
        DiagLog.event(
            applicationContext, TAG,
            "onDestroy stopRequested=$stopRequested intentionalStop=$intentionalStop",
        )
        telemetryLoopJob?.cancel()
        telemetryLoopActive = false
        telemetryLoopStarting = false
        releaseLocationSubscription()
        wakeLock?.release()
        wifiLock?.release()
        serviceScope.cancel()
        refreshExecutor.shutdownNow()
        _connectionState.value = ConnectionState.Disconnected
        vehicleDataSource.stop()
        activeDataSource = null
        mqttConnectionManager?.shutdown()
        if (!stopRequested && !intentionalStop) {
            scheduleSelfRestart("onDestroy")
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "Task removed — scheduling vehicle telemetry restart")
        if (!stopRequested) {
            scheduleSelfRestart("onTaskRemoved")
        }
        super.onTaskRemoved(rootIntent)
    }

    // ── Location subscription ──────────────────────────────────────────────────

    /**
     * Registers a minimal LocationManager update to satisfy the OS requirement
     * that a foreground service with type=location actually uses location APIs.
     * Without this, the OS ignores the declared type and keeps oom_adj at
     * SERVICE_ADJ (200) rather than the more protected PERCEPTIBLE_APP_ADJ (125).
     *
     * Uses a 30-second interval with 0m displacement — low frequency, no battery
     * impact, but enough to keep the OS convinced the subscription is real.
     */
    private fun acquireLocationSubscription() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            Log.w(TAG, "Location permission not granted — foreground service type=location may not be honoured by OS")
            return
        }

        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager = lm
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            ).filter { provider ->
                runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)
            }
            providers.forEach { provider ->
                runCatching {
                    lm.requestLocationUpdates(
                        provider,
                        30_000L,   // 30 s interval — enough to satisfy OS, minimal battery
                        0f,
                        keepaliveLocationListener,
                        Looper.getMainLooper()
                    )
                }.onFailure { Log.w(TAG, "requestLocationUpdates failed for $provider: ${it.message}") }
            }
            if (providers.isNotEmpty()) {
                Log.i(TAG, "Location subscription active on ${providers.joinToString()} — foreground type=location satisfied")
            }
        } catch (e: Exception) {
            Log.w(TAG, "acquireLocationSubscription failed: ${e.message}")
        }
    }

    private fun releaseLocationSubscription() {
        runCatching { locationManager?.removeUpdates(keepaliveLocationListener) }
        locationManager = null
    }

    // ── Telemetry loop ─────────────────────────────────────────────────────────

    private fun startTelemetryLoop() {
        telemetryLoopJob?.cancel()
        telemetryLoopActive = true
        telemetryLoopStarting = false
        telemetryLoopJob = serviceScope.launch {
            try {
                val carName = carConfig?.displayName ?: "BYD"
                Log.d(TAG, "Vehicle telemetry loop started for $carName")
                _connectionState.value = ConnectionState.Connected
                updateNotification("Trip recording and data visualization")

                // When the car is off, slow the poll interval to 30 s regardless of
                // whether a charging session is in progress. This keeps the CPU idle
                // between ticks instead of spinning at 1 Hz, reducing 12V drain
                // overnight and during long AC charging sessions. 30 s matches the
                // MQTT publish rate, so no data is lost. The service stays alive as
                // a foreground service throughout; the loop never stops.
                //
                // Delay comes BEFORE the work so the interval is based on the last
                // known state — avoids one unnecessary fast poll on ON→OFF transition.
                // null on first boot resolves to 0 ms (immediate first poll).
                var lastTelemetry: VehicleTelemetry? = null
                var lastLoopTime = SystemClock.elapsedRealtime()
                var lastChargingKeepaliveMs = 0L
                // Tracks when the car was last confirmed on or charging.
                // 0L = car is active (or first tick not yet received).
                // Non-zero = elapsedRealtime() when we first saw off+not-charging.
                var carOffSinceMs = 0L
                // One-shot log gate for the "keepServiceAliveWhenOff" skip — otherwise
                // we'd write a diag.log line every 5 min of car-off.
                var loggedKeepAliveSkip = false
                while (true) {
                    val msSinceLastEvent = SystemClock.elapsedRealtime() - vehicleDataSource.lastFeatureEventElapsedMs
                    // SDK event-delivery wedge recovery: GPS (independent of the BYD SDK) shows the car
                    // moving, yet no SDK callback has arrived for a while ⇒ the SDK push channel has
                    // stalled (getters still work). Re-register listeners in-process to recover rather
                    // than waiting for a full service restart. Throttled inside recoverEventDelivery().
                    if (vehicleDataSource.lastFeatureEventElapsedMs > 0L && msSinceLastEvent > 8_000L) {
                        val gpsKmh = vehicleDataSource.vehicleSnapshot.value.locationGpsSpeed ?: 0.0
                        if (gpsKmh > 2.0) vehicleDataSource.recoverEventDelivery()
                    }
                    val pollIntervalMs = when {
                        lastTelemetry == null -> 0L
                        lastTelemetry.isCarOn -> 1_000L
                        // DiLink-5: a trip in progress keeps the driving cadence even when isCarOn
                        // reads false. With no power-state signal there, every slow-down below
                        // 2 km/h did, and the loop then slept 30 s through the next acceleration —
                        // those blind windows were stored as off-state and cut a third of the
                        // trip's duration. DiLink-3's isCarOn holds through a stop, so it is left
                        // on its own cadence.
                        DiLink5Platform.isDiLink5 && tripRepository?.isInTrip?.value == true -> 1_000L
                        lastTelemetry.isCharging && lastTelemetry.chargingPower > 23.0 -> 1_000L
                        lastTelemetry.isCharging -> 30_000L      // AC/slow charging: 30s for SoC granularity
                        msSinceLastEvent < 60_000L -> 5_000L     // car awake (recent SDK event) but not driving
                        // Always-On mode: keep polling at 30 s so MQTT publishes on its
                        // configured cadence rather than only once every 5 min.
                        PreferencesManager(applicationContext).getCachedOffStateMode() ==
                            OffStateMode.ENABLED -> 30_000L
                        else -> 5 * 60 * 1000L                   // car off, not charging: 5 min
                    }
                    delay(pollIntervalMs)

                    val now = SystemClock.elapsedRealtime()
                    val delta = now - lastLoopTime
                    lastLoopTime = now

                    if (pollIntervalMs > 0L && delta > pollIntervalMs * 2) {
                        Log.w(TAG, "⏱ Telemetry loop delayed: ${delta}ms (expected ~${pollIntervalMs}ms) — system may have suspended")
                        if (delta > 60_000L) {
                            Log.w(TAG, "⏱ Long suspension detected (${delta}ms) — forcing immediate snapshot refresh")
                            try { vehicleDataSource.refreshSnapshots() } catch (t: Throwable) { Log.w(TAG, "Force refresh after sleep failed: ${t.message}") }
                        }
                    }

                    try {
                        refreshSnapshotsGuarded()
                        val snapshot = vehicleDataSource.vehicleSnapshot.value
                        val telemetry = snapshot.toTelemetry(carConfig)
                        lastTelemetry = telemetry

                        batteryVoltageHistoryRepository?.onTelemetry(telemetry)
                        tripRepository?.processTelemetry(telemetry)
                        chargingRepository?.onTelemetry(telemetry, carConfig)
                        abrpConnectionManager?.onTelemetry(telemetry, carConfig, serviceScope)
                        mqttConnectionManager?.onTelemetry(telemetry, serviceScope)
                        cellImbalanceMonitor?.onTelemetry(telemetry)
                        // Feed the vehicle id to the Pro entitlement gate so a vehicle-bound license
                        // can confirm it's running on the right car. Prefer the non-PII T-Box serial
                        // (DiLink-5); fall back to the VIN on DiLink-3, where the serial isn't read.
                        EntitlementManager.onDeviceIdObserved(snapshot.tboxSerialNumber ?: snapshot.bodyworkAutoVin)

                        // Feed the parked Wi-Fi keepalive daemon our 12V / SoC (the same values we
                        // publish to MQTT) so its drain guard has real numbers — the daemon can't read
                        // the battery device from its own shell context. No-op unless the toggle is on;
                        // throttled internally. Launched off-loop so the shell write never stalls the poll.
                        serviceScope.launch {
                            com.byd.tripstats.util.WifiKeepalive.pushGuard(
                                applicationContext,
                                telemetry.battery12vVoltage.takeIf { it > 0.0 },
                                telemetry.soc.takeIf { it > 0.0 },
                            )
                        }

                        // Keep WiFi alive while the car is off and a charger gun is present
                        // or the charger is actively working. Without this, the MCU cuts WiFi
                        // ~15 min after ACC_OFF and charging telemetry + MQTT are lost.
                        //
                        // We use the BMS-level chargerWorkState and chargingGunState from
                        // AbsBYDAutoChargingListener (gateway-driven, survives IVI deep sleep)
                        // rather than telemetry.isCharging, which is derived from direct power
                        // readings that go stale when the car is in deep sleep. This keeps WiFi
                        // alive throughout an off-state charge while still allowing the MCU to
                        // enter deeper sleep during normal unconnected overnight parking.
                        //
                        // Throttled to once per 10 min — well within the ~15 min WiFi cut window.
                        // The keepalive uses the *union* of the three charging signals because a
                        // false positive here (WiFi alive a bit longer than needed) is cheap, while
                        // a false negative (missing telemetry mid-charge because WiFi got cut) is
                        // expensive. This is deliberately more inclusive than computeChargingActive,
                        // which drives UI state and prefers a single authoritative signal.
                        //
                        // NOTE (2026-06-26): do NOT broaden this to fire for all parked time in
                        // Always On to keep WiFi up. b01 only
                        // *momentarily* wakes the MCU; it does NOT hold the WiFi link at park —
                        // verified on a real car, parked-not-charging stays dark with it. The
                        // mechanism other apps use is privileged , which we deliberately do
                        // not implement. Broadening this just wakes the MCU every 10 min for no
                        // connectivity gain (pure drain). See the parked-power-cut memory note.
                        val snap = vehicleDataSource.vehicleSnapshot.value
                        val chargingGunPresent = snap.chargingGunState != 0
                        val chargerWorking = snap.chargerWorkState != 0
                        if (!telemetry.isCarOn && (telemetry.isCharging || chargingGunPresent || chargerWorking)) {
                            val nowMs = SystemClock.elapsedRealtime()
                            if (nowMs - lastChargingKeepaliveMs >= 10 * 60 * 1000L) {
                                lastChargingKeepaliveMs = nowMs
                                try {
                                    McuWakeHelper.keepAlive(applicationContext)
                                    Log.d(TAG, "MCU keepalive sent (gun=$chargingGunPresent work=$chargerWorking charging=${telemetry.isCharging})")
                                } catch (e: Exception) {
                                    Log.w(TAG, "MCU keepalive failed: ${e.message}")
                                }
                            }
                        }

                        _telemetryCount.value = _telemetryCount.value + 1

                        // Self-stop when car is off and not charging.
                        // Releasing the wake lock lets the infotainment CPU enter deep
                        // sleep, stopping the DC-DC cycling that drains the HV pack.
                        // intentionalStop=true prevents onDestroy from calling
                        // scheduleSelfRestart — without it, START_STICKY causes an
                        // infinite restart loop that defeats the purpose of stopping.
                        //
                        // Stale-data tripwire: if the SDK has fired at least once but
                        // then gone silent for 10 min, the car is definitively asleep
                        // regardless of what cached values say. This catches stuck
                        // `enginePower` / `chargingPower` / `gear` readings that would
                        // otherwise keep `isCarOn` or `isCharging` true forever after
                        // key-off.
                        //
                        // The `> 0L` guard is critical: a fresh service start has
                        // lastFeatureEventElapsedMs == 0, which would make
                        // msSinceLastEvent = full device uptime (huge) and trip
                        // immediately — killing the service before the SDK has a
                        // chance to register devices and emit its first event.
                        val sdkSilent = vehicleDataSource.lastFeatureEventElapsedMs > 0L &&
                                msSinceLastEvent > 10 * 60 * 1000L
                        // A DiLink-5 standstill held as car-on by the trip (see TripRepository.
                        // di5StandstillHeld; always false on DiLink-3) is a queue, not parking.
                        val carOnForSelfStop = telemetry.isCarOn ||
                            tripRepository?.di5StandstillHeld?.value == true
                        val effectivelyOff = (!carOnForSelfStop && !telemetry.isCharging) ||
                                (sdkSilent && !telemetry.isCharging)
                        if (effectivelyOff) {
                            if (carOffSinceMs == 0L) {
                                carOffSinceMs = SystemClock.elapsedRealtime()
                                loggedKeepAliveSkip = false
                                if (sdkSilent && telemetry.isCarOn) {
                                    Log.w(TAG, "Self-stop tripwire: SDK silent ${msSinceLastEvent}ms but isCarOn=true " +
                                            "(gear=${telemetry.gear} enginePower=${telemetry.enginePower} " +
                                            "chargingPower=${telemetry.chargingPower}) — treating as off")
                                }
                            } else if (SystemClock.elapsedRealtime() - carOffSinceMs >= 5 * 60 * 1000L) {
                                val offStateMode = PreferencesManager(applicationContext)
                                    .getCachedOffStateMode()
                                when (offStateMode) {
                                    OffStateMode.ENABLED -> {
                                        if (!loggedKeepAliveSkip) {
                                            DiagLog.event(
                                                applicationContext, TAG,
                                                "self-stop skipped — offStateMode=ENABLED (continuous-sampling mode)",
                                            )
                                            loggedKeepAliveSkip = true
                                        }
                                        // Fall through to next loop iteration without stopping.
                                    }
                                    OffStateMode.DISABLED -> {
                                        DiagLog.event(
                                            applicationContext, TAG,
                                            "self-stop: car off + not charging for 5 min — arming 90-min keepalive and stopping",
                                        )
                                        ServiceIdleState.setStayingIdle(applicationContext, true)
                                        ServiceWatchdogWorker.cancel(applicationContext)
                                        ServiceRestarterJobService.cancelPeriodic(applicationContext)
                                        // Defensive arm — BootReceiver on ACC_OFF may not fire reliably
                                        // (remote-off, sleep timeout). Upsert-safe.
                                        OffStateKeepaliveReceiver.schedule(
                                            applicationContext,
                                            iteration = 0,
                                            source = "service-self-stop",
                                        )
                                        intentionalStop = true
                                        stopSelf()
                                        return@launch
                                    }
                                    OffStateMode.DEEP_SLEEP -> {
                                        DiagLog.event(
                                            applicationContext, TAG,
                                            "self-stop: car off + not charging for 5 min — deep sleep mode, no keepalive scheduled",
                                        )
                                        ServiceIdleState.setStayingIdle(applicationContext, true)
                                        ServiceWatchdogWorker.cancel(applicationContext)
                                        ServiceRestarterJobService.cancelPeriodic(applicationContext)
                                        // Explicitly cancel any previously scheduled keepalive chain
                                        // (e.g. from a prior mode switch or BootReceiver).
                                        OffStateKeepaliveReceiver.cancel(applicationContext)
                                        intentionalStop = true
                                        stopSelf()
                                        return@launch
                                    }
                                }
                            }
                        } else {
                            carOffSinceMs = 0L
                            loggedKeepAliveSkip = false
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Error in telemetry loop tick", t)
                    }
                }
            } finally {
                telemetryLoopActive = false
                telemetryLoopStarting = false
            }
        }
    }

    // ── Notification helpers ───────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Vehicle Data Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Maintains live vehicle connection"
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun createNotification(message: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val serviceWhen = notificationStartedAtWallClock.takeIf { it > 0L }
            ?: System.currentTimeMillis()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BYD Trip Stats")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            // Show a counting-up chronometer anchored to when the service started.
            // setWhen sets the base time; setUsesChronometer(true) replaces the static
            // timestamp with a live "1:23" elapsed counter. setShowWhen(true) is required
            // to force the time field visible — without it some firmware builds hide it.
            .setWhen(serviceWhen)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .build()
    }

    fun updateNotification(message: String) {
        val notification = createNotification(message)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification)
    }

    private fun buildStatusText(@Suppress("UNUSED_PARAMETER") telemetry: VehicleTelemetry): String {
        return "Trip recording and data visualization"
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BydStats::VehicleTelemetryWakeLock"
        ).apply { acquire() }
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wifiManager?.createWifiLock(
                WifiManager.WIFI_MODE_FULL,
                "BydStats::VehicleTelemetryWifiLock"
            )?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to acquire Wi-Fi lock", e)
        }
    }

    private fun scheduleSelfRestart(reason: String) {
        ServiceRestartReceiver.schedule(applicationContext, delayMs = 8_000L, reason = reason)
        ServiceRestarterJobService.scheduleEarlyKick(
            applicationContext,
            delayMs = 8_000L,
            reason = "service:$reason"
        )
    }
}
