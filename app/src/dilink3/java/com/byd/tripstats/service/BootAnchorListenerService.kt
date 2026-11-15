package com.byd.tripstats.service

import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.byd.tripstats.util.DiagLog
import com.byd.tripstats.util.ServiceIdleState

/**
 * A notification listener that does nothing with notifications. It exists so that Android binds it —
 * and so starts this app's process — at every boot and whenever the process has died.
 *
 * Why (DiLink-3, dev Seal, 2026-11-15): a boot with the car switched off puts the head unit in BYD's
 * QuickBoot state, in which `BroadcastQueue` refuses to start any app's process for a broadcast
 * unless the package is on a hard-coded list (two BYD OTA apps and Qualcomm's time service). So
 * BOOT_COMPLETED can't start the app after BYD's daily cloud restart of a parked car, and the app —
 * and the Tailscale it launches — stays down until the car is next switched on. BYD gates only
 * broadcasts and alarms on QuickBoot, not service binds: at the same boot, Android started another 3rd party app
 * through its bound accessibility service. A listener is bound the same way, with a lighter
 * permission (notification access, granted over the adb channel with `cmd notification
 * allow_listener`).
 *
 * dilink3 flavor only (manifest in src/dilink3), so the DiLink-5 build doesn't contain it. It is
 * inert until notification access is granted.
 *
 * Starting the process already runs Application.onCreate, which starts the telemetry service unless
 * the app is staying idle (Deep Sleep after its self-stop). The explicit start here
 * follows the same rule, so a rebind after the process died never undoes a self-stop.
 */
class BootAnchorListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        val sinceBoot = SystemClock.elapsedRealtime() / 1000
        val idle = runCatching { ServiceIdleState.isStayingIdle(applicationContext) }.getOrDefault(false)
        DiagLog.event(
            applicationContext, TAG,
            "listener bound by Android (sinceBoot=${sinceBoot}s) — " +
                if (idle) "staying idle, service not started" else "starting the service",
        )
        if (!idle) runCatching { VehicleTelemetryService.start(applicationContext) }
            .onFailure { DiagLog.event(applicationContext, TAG, "service start failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        DiagLog.event(applicationContext, TAG, "listener unbound by Android")
    }

    // Notifications are not read or kept; the listener exists only to be bound.
    override fun onNotificationPosted(sbn: StatusBarNotification?) = Unit
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit

    private companion object {
        const val TAG = "BootAnchor"
    }
}
