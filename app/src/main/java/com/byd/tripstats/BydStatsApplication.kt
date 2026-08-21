package com.byd.tripstats

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.work.Configuration
import com.byd.tripstats.data.entitlement.EntitlementManager
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.receiver.ServiceRestartReceiver
import com.byd.tripstats.runtimebridge.RuntimeExtensionBridge
import com.byd.tripstats.sdk.VehicleCompatibilityProbe
import com.byd.tripstats.server.WebServerManager
import com.byd.tripstats.service.ServiceRestarterJobService
import com.byd.tripstats.service.VehicleTelemetryService
import com.byd.tripstats.util.BootSessionTracker
import com.byd.tripstats.util.RtDispatch
import com.byd.tripstats.util.RtInProcessPatches
import com.byd.tripstats.util.RtShellPatches
import com.byd.tripstats.util.DiagLog
import com.byd.tripstats.util.ServiceIdleState
import com.byd.tripstats.worker.DatabaseMaintenanceWorker
import com.byd.tripstats.worker.ServiceWatchdogWorker
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Application entry point for BYD Trip Stats.
 *
 * Starts the vehicle telemetry service immediately on every process start.
 */
class BydStatsApplication : Application(), Configuration.Provider {

    companion object {
        private const val TAG = "BydStatsApp"
        /** Stack frames persisted per crash — enough to identify the site, short enough
         *  that a crash loop can't flood the 10 MB diag.log. */
        private const val CRASH_LOG_FRAMES = 8
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        // MUST be the first thing — runs before anything else can touch a vehicle device.
        val primed = RuntimeExtensionBridge.prime()
        val dc = RuntimeExtensionBridge.registerDataCache(this)
        val acc = RuntimeExtensionBridge.whitelistAcc(this)
        DiagLog.event(this, TAG, "runtime prime=$primed dc=$dc acc=$acc")
        Log.d(TAG, "=== BYD Trip Stats starting (pid=${android.os.Process.myPid()}) ===")
        installCrashRestartHandler()
        applyStartupSafeguards()
        applyRuntimePatches()
        DatabaseMaintenanceWorker.schedule(this)
        VehicleCompatibilityProbe.initialize(this)
        // Premium entitlement — single source of truth for Pro gating. Must be
        // initialised here (before any Activity/Service touches it) so the
        // synchronous isProNow() check is ready for the telemetry loop.
        EntitlementManager.init(this)
        // Restore the web companion server if the user had it enabled.
        // Runs unconditionally — the server only needs the DB, not the telemetry service.
        // It doesn't run in deep sleep mode.
        CoroutineScope(Dispatchers.IO).launch {
            val prefs = PreferencesManager(applicationContext)
            if (prefs.webServerEnabled.first()) {
                val port = prefs.webServerPort.first()
                val pin  = prefs.getOrCreateWebServerPin()
                val err  = WebServerManager.start(applicationContext, port, pin)
                if (err != null) Log.w(TAG, "Web companion failed to start at boot on port $port: $err")
            }
        }
        // If the service self-stopped due to off-state idle, skip re-arming the
        // periodic restart sources and skip auto-starting the service. The
        // process may have been recreated by an alarm/job firing — letting
        // those fire and silently no-op (they also check the flag) is
        // preferable to immediately re-acquiring the wake lock.
        val idle = ServiceIdleState.isStayingIdle(this)
        // sinceBoot separates a cold boot (small value — the head unit genuinely rebooted) from a
        // resume-from-suspend (hours), and pins how late in the boot we were started. Together with
        // the BootReceiver and MainActivity launch markers it classifies every "app didn't
        // autostart" report: never started vs started-and-killed vs started-late.
        DiagLog.event(
            this, TAG,
            "Application.onCreate pid=${android.os.Process.myPid()} stayingIdle=$idle " +
                "sinceBoot=${SystemClock.elapsedRealtime() / 1000}s",
        )
        // Once per head-unit boot: did the app actually come up with it? A force-stopped package
        // gets no BOOT_COMPLETED, so on DiLink 5 it can miss a boot entirely — silently, since with
        // no process there is nothing to log, notify or retry. The only trace is this late first
        // start, so record it and keep a hit rate. Instrumentation only: no UI, because reading a
        // card would require opening the app, which is itself the recovery.
        runCatching { BootSessionTracker.recordStart(this) }.getOrNull()?.let { boot ->
            if (boot.firstStartOfBoot) {
                DiagLog.event(
                    this, TAG,
                    "boot session: first app start at boot+${boot.sinceBootSec}s — " +
                        (if (boot.missed) "MISSED (app did not start with the head unit)" else "ok") +
                        "; missed ${boot.recentMissed}/${boot.recentTotal} recent boots",
                )
            }
        }
        if (idle) {
            // Off-state idle: skip auto-starting the service so we don't undo
            // the self-stop. The process may have been recreated by an alarm
            // or job firing — those will also see the flag and no-op.
        } else {
            ServiceWatchdogWorker.schedule(this)
            ServiceRestarterJobService.schedulePeriodic(this, "application-start")
            startTelemetryService()
            // Fast catch-up kicks on EVERY start path, not just BootReceiver's. A process started by
            // the OEM autostart (an activity launch) or by a periodic job used to arm nothing faster
            // than the 15-minute watchdog/job — so if the boot storm killed it before the foreground
            // service took hold, telemetry stayed down for up to 15 minutes, which from the driver's
            // seat is indistinguishable from "autostart never fired".
            // Safe by construction: these only ever call VehicleTelemetryService.start() — no
            // activity launch, no self-kill, no process relaunch (the DI5 boot-loop trigger) — a
            // duplicate start is ignored by onStartCommand, and ServiceRestartReceiver re-checks the
            // idle flag when each one fires, so a pending kick can't undo an off-state self-stop.
            ServiceRestartReceiver.schedule(this, delayMs = 15_000L, reason = "app-start")
            ServiceRestartReceiver.schedule(this, delayMs = 45_000L, reason = "app-start-followup")
            ServiceRestartReceiver.schedule(this, delayMs = 120_000L, reason = "app-start-platform-ready")
        }
    }

    private fun applyStartupSafeguards() {
        RuntimeExtensionBridge.applyStartupSafeguards(this)
    }

    private fun applyRuntimePatches() {
        thread(name = "rt-patches", isDaemon = true) {
            try {
                RtInProcessPatches.apply(applicationContext)
            } catch (e: Throwable) {
                Log.w(TAG, "In-process patches threw: ${e.message}")
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                RtShellPatches.apply(applicationContext)
            } catch (e: Throwable) {
                Log.w(TAG, "Shell patches threw: ${e.message}")
            }
            // Early retry ladder. The first attempt lands within a second or two of process start,
            // which at a cold boot is ~20 s in — before WiFi has associated and, on DiLink 5, before
            // the wireless-adb listener exists. That single miss is what left a Sealion 7 with no
            // background restarter for nine hours on 2026-08-19. The watchdog retries too, but only
            // every 15 min, and a short errand can end before the first tick.
            //
            // In-process delays, not alarms: the process is already alive through this window, so
            // this adds no wakeups (deep sleep stays dark). Each attempt short-circuits on a healthy
            // probe, and an unreachable channel fails instantly on the port check, so the steady
            // states both cost nothing.
            for (delayMs in longArrayOf(0L, 30_000L, 60_000L, 210_000L)) {
                if (delayMs > 0L) kotlinx.coroutines.delay(delayMs)
                val up = try {
                    RtDispatch.launch(applicationContext)
                } catch (e: Throwable) {
                    Log.w(TAG, "Dispatch threw: ${e.message}")
                    false
                }
                if (up) break   // supervisor running — stop probing
            }
        }
    }

    /**
     * Installs an uncaught exception handler that schedules a service restart
     * 5 seconds after a crash. AlarmManager survives process death so the
     * alarm fires even after the runtime kills the process.
     */
    private fun installCrashRestartHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception — scheduling restart in 5s", throwable)
            // Persist the reason too. Log.e only reaches logcat, whose 256 KiB ring is
            // shredded within ~7 min on DiLink, so in a release build a crash loop shows
            // up in diag.log as nothing but unexplained repeated process starts — the
            // symptom is recorded and the cause is not. Runs first (the process is about
            // to die), stays bounded, and must never throw from inside a crash handler.
            try {
                val chain = generateSequence(throwable) { it.cause }
                    .take(3)
                    .joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" }
                val frames = throwable.stackTrace.take(CRASH_LOG_FRAMES)
                    .joinToString(" | ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
                DiagLog.event(
                    applicationContext, TAG,
                    "CRASH thread=${thread.name} $chain @ $frames",
                )
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to persist crash to diag log", e)
            }
            try {
                com.byd.tripstats.receiver.ServiceRestartReceiver.schedule(
                    applicationContext, delayMs = 5_000L, reason = "crash-restart"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule crash restart", e)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun startTelemetryService() {
        try {
            Log.d(TAG, "Starting vehicle telemetry service")
            VehicleTelemetryService.start(applicationContext)
            Log.d(TAG, "Vehicle telemetry service start command sent")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start vehicle telemetry service", e)
        }
    }
}
