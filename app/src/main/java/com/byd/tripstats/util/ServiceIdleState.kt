package com.byd.tripstats.util

import android.content.Context
import android.content.SharedPreferences
import com.byd.tripstats.sdk.DiLink5Platform

/**
 * Persistent flag indicating the telemetry service has self-stopped due to
 * the car being off and not charging, and should remain stopped until a real
 * vehicle event (ACC_ON, IGN_ON, POWER_CONNECTED, or user-initiated launch).
 *
 * Survives process death. Read by every periodic restart source
 * (ServiceWatchdogWorker, ServiceRestarterJobService, ServiceRestartReceiver,
 * Application.onCreate) so they skip restarting the service while idle —
 * eliminating the 15-min wake-lock-and-WiFi-lock cycle that drains the 12V
 * battery overnight.
 *
 * OffStateKeepaliveReceiver is exempt from the flag — it is the legitimate
 * 90-min off-state wake-up that takes a snapshot and lets the service
 * self-stop again.
 */
object ServiceIdleState {
    private const val PREFS = "service_idle_state"
    private const val KEY_STAYING_IDLE = "staying_idle"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Never on DiLink-5, which always runs Always On (effectiveOffStateMode), so a flag left by a
    // self-stop from before can't keep the service from starting.
    fun isStayingIdle(context: Context): Boolean =
        !DiLink5Platform.isDiLink5 && prefs(context).getBoolean(KEY_STAYING_IDLE, false)

    fun setStayingIdle(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_STAYING_IDLE, value).apply()
    }
}
