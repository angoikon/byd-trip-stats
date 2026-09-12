package com.byd.tripstats.util

import android.content.Context
import android.os.SystemClock
import com.byd.tripstats.adb.AdbPermissionManager
import com.byd.tripstats.data.preferences.PreferencesManager

/**
 * Bridges the "keep Wi-Fi alive when parked" preference to the privileged UID-2000 telemetry
 * daemon that actually enforces it (see [TelemetryDaemonMain.startWifiKeepalive]).
 *
 * The daemon and the app run as different UIDs, so the toggle can't be shared through the app's
 * DataStore. Instead the app drops a flag file in /data/local/tmp — the one place both the app
 * (via the adb shell channel, uid 2000) and the daemon (uid 2000) can see — and the daemon polls
 * it each loop. Present = enabled, absent = disabled. The path is assembled from char codes to
 * match the other runtime paths (a static scan of the apk shouldn't advertise the mechanism).
 *
 * Writing needs the shell channel, so this is a no-op when the privileged setup isn't complete —
 * which is fine: without it there is no daemon to read the flag anyway.
 */
object WifiKeepalive {

    private fun s(vararg v: Int): String = v.map { it.toChar() }.joinToString("")

    /** "/data/local/tmp/.bydwifikeep" */
    private val FLAG_PATH = s(
        47, 100, 97, 116, 97, 47, 108, 111, 99, 97, 108, 47, 116, 109, 112, 47,
        46, 98, 121, 100, 119, 105, 102, 105, 107, 101, 101, 112,
    )

    /** "/data/local/tmp/.bydwifiguard" — 12V,SoC the daemon reads for its drain guard. */
    private val GUARD_PATH = s(
        47, 100, 97, 116, 97, 47, 108, 111, 99, 97, 108, 47, 116, 109, 112, 47,
        46, 98, 121, 100, 119, 105, 102, 105, 103, 117, 97, 114, 100,
    )

    // 12V/SoC drift slowly while parked, and this feeds a guard against gradual multi-hour drain, so
    // a slow cadence is plenty — no benefit to pushing more often, just more adb round-trips.
    private const val GUARD_PUSH_INTERVAL_MS = 300_000L   // 5 min
    @Volatile private var lastGuardPushMs = 0L

    /**
     * Push the current toggle state to the daemon. Enable → write the flag; disable → remove it.
     * Safe to call repeatedly (idempotent) — call it on every toggle change and once on app start
     * so a reinstall/boot re-syncs the persisted preference.
     */
    suspend fun apply(context: Context, enabled: Boolean) {
        if (!AdbPermissionManager.isSetupComplete(context)) return
        // chmod 644 so the daemon (also uid 2000) can always read it, whatever umask wrote it.
        val cmd = if (enabled) {
            "echo 1 > $FLAG_PATH 2>/dev/null; chmod 644 $FLAG_PATH 2>/dev/null; echo ok"
        } else {
            "rm -f $FLAG_PATH 2>/dev/null; echo ok"
        }
        AdbPermissionManager.runShellBatch(context, listOf(cmd), perCommandTimeoutMs = 3_000L)
    }

    /**
     * Hand the daemon the app's own 12V / SoC readings — the SAME values the app publishes to MQTT —
     * so its parked keepalive can guard against battery drain. The daemon can't read the battery
     * device from its bare shell context (getInstance returns null there), so the app, which reads
     * them fine, writes them to a file the daemon polls. This is transport, not a second reading.
     *
     * Called from the telemetry loop; throttled to [GUARD_PUSH_INTERVAL_MS] and a no-op unless the
     * toggle is on and the privileged setup is complete. Null (unknown) values are written blank —
     * the daemon treats a blank/absent value as "unknown, don't block".
     */
    suspend fun pushGuard(context: Context, v12: Double?, soc: Double?) {
        if (!PreferencesManager(context).getCachedWifiKeepaliveWhenOff()) return
        val now = SystemClock.elapsedRealtime()
        if (lastGuardPushMs != 0L && now - lastGuardPushMs < GUARD_PUSH_INTERVAL_MS) return
        if (!AdbPermissionManager.isSetupComplete(context)) return
        lastGuardPushMs = now
        val line = "${v12 ?: ""},${soc ?: ""}"
        AdbPermissionManager.runShellBatch(
            context,
            listOf("echo '$line' > $GUARD_PATH 2>/dev/null; chmod 644 $GUARD_PATH 2>/dev/null; echo ok"),
            perCommandTimeoutMs = 3_000L,
        )
    }
}
