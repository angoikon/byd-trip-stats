package com.byd.tripstats

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import com.byd.tripstats.data.entitlement.EntitlementManager
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.runtimebridge.RuntimeExtensionBridge
import com.byd.tripstats.sdk.VehicleCompatibilityProbe
import com.byd.tripstats.server.WebServerManager
import com.byd.tripstats.service.ServiceRestarterJobService
import com.byd.tripstats.service.VehicleTelemetryService
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
        DiagLog.event(
            this, TAG,
            "Application.onCreate pid=${android.os.Process.myPid()} stayingIdle=$idle",
        )
        if (idle) {
            // Off-state idle: skip auto-starting the service so we don't undo
            // the self-stop. The process may have been recreated by an alarm
            // or job firing — those will also see the flag and no-op.
        } else {
            ServiceWatchdogWorker.schedule(this)
            ServiceRestarterJobService.schedulePeriodic(this, "application-start")
            startTelemetryService()
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
            try {
                RtDispatch.launch(applicationContext)
            } catch (e: Throwable) {
                Log.w(TAG, "Dispatch threw: ${e.message}")
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
