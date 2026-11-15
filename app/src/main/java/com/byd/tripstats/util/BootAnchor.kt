package com.byd.tripstats.util

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import com.byd.tripstats.adb.AdbPermissionManager
import com.byd.tripstats.data.preferences.OffStateMode
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.sdk.DiLink5Platform
import kotlinx.coroutines.flow.first

/**
 * Keeps notification access for the boot listener (`service/BootAnchorListenerService`, dilink3
 * build only) in step with the background mode, over the adb channel.
 *
 * Why: on DiLink-3 a head-unit boot with the car switched off — BYD's daily cloud restart of a parked
 * car among them — lands in BYD's QuickBoot state, where no broadcast may start an app (BOOT_COMPLETED
 * included), so the app and the Tailscale it launches stayed down until the car was next switched on.
 * Android binds an enabled notification listener at every boot regardless, which starts the app
 * (verified on the dev Seal, 2026-11-15: back 48 s after a reboot with the car off). It also brings the
 * app back ~11 s after BYD's switch-off sweep, where the background helper takes 60–90 s.
 *
 * Granted only in Always On, the one mode meant to keep running while parked: in Deep Sleep the
 * listener would restart the app after every switch-off and every boot, and that mode is meant to
 * stay dark — so leaving Always On withdraws it (a stored Minimal runs as Deep Sleep). Only on DiLink-3 proper (not DiLink-5, which
 * doesn't ship the listener, nor a newer Android such as DiLink-100, which has no QuickBoot).
 *
 * Idempotent and cheap when nothing needs changing: the current state is read locally and the shell
 * is used only to change it. Never throws; a channel that isn't up yet is retried by the next caller
 * (app start, the 15-minute watchdog, a mode change).
 */
object BootAnchor {
    private const val TAG = "BootAnchor"
    const val COMPONENT = "com.byd.tripstats/com.byd.tripstats.service.BootAnchorListenerService"

    /** Whether the boot listener should hold notification access. */
    fun shouldHoldAccess(classicDiLink3: Boolean, mode: OffStateMode): Boolean =
        classicDiLink3 && mode == OffStateMode.ENABLED

    suspend fun sync(context: Context) {
        runCatching { syncInternal(context.applicationContext) }
            .onFailure { DiagLog.event(context.applicationContext, TAG, "sync failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private suspend fun syncInternal(app: Context) {
        val cn = ComponentName.unflattenFromString(COMPONENT) ?: return
        // Not in this build (dilink5): never touch the setting.
        val present = runCatching { app.packageManager.getServiceInfo(cn, 0); true }.getOrDefault(false)
        if (!present) return
        val classic = !DiLink5Platform.isDiLink5 && !DiLink5Platform.isPostDiLink3
        val want = shouldHoldAccess(classic, PreferencesManager(app).offStateMode.first())
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        val have = nm.isNotificationListenerAccessGranted(cn)
        if (want == have) return
        if (!AdbPermissionManager.isSetupComplete(app)) return
        val verb = if (want) "allow_listener" else "disallow_listener"
        val r = AdbPermissionManager.runShellBatch(app, listOf("cmd notification $verb $COMPONENT"), perCommandTimeoutMs = 5_000L)
            .firstOrNull()
        val after = nm.isNotificationListenerAccessGranted(cn)
        DiagLog.event(
            app, TAG,
            "notification access for the boot listener ${if (want) "granted" else "withdrawn"}: " +
                if (after == want) "ok" else "FAILED (exit=${r?.exitCode ?: "no channel"})",
        )
    }
}
