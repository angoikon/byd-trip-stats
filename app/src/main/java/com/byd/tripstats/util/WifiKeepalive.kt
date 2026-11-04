package com.byd.tripstats.util

import android.content.Context
import android.os.SystemClock
import com.byd.tripstats.adb.AdbPermissionManager
import com.byd.tripstats.data.preferences.OffStateMode
import com.byd.tripstats.data.preferences.PreferencesManager
import kotlinx.coroutines.flow.first

/**
 * Bridges the "keep Wi-Fi alive when car is off" preference to the privileged UID-2000 telemetry
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
 *
 * Only Always On runs it, whatever the toggle says ([isActive]). The daemon's drain guard reads the
 * 12V/SoC that only the service feeds ([pushGuard]), and Minimal and Deep Sleep stop the service
 * after switch-off while the daemon lives on — so there it kept turning Wi-Fi back on all night with
 * a stale guard, which the daemon reads as "unknown, don't block". The toggle itself is kept, and
 * applies again once the user picks Always On.
 */
object WifiKeepalive {

    private fun s(vararg v: Int): String = v.map { it.toChar() }.joinToString("")

    /** "/data/local/tmp/.bydwifikeep" */
    private val FLAG_PATH = s(
        47, 100, 97, 116, 97, 47, 108, 111, 99, 97, 108, 47, 116, 109, 112, 47,
        46, 98, 121, 100, 119, 105, 102, 105, 107, 101, 101, 112,
    )

    /** "/data/local/tmp/.bydwifiguard" — "12V,SoC,carOn" the daemon reads for its guards. */
    private val GUARD_PATH = s(
        47, 100, 97, 116, 97, 47, 108, 111, 99, 97, 108, 47, 116, 109, 112, 47,
        46, 98, 121, 100, 119, 105, 102, 105, 103, 117, 97, 114, 100,
    )

    // 12V/SoC drift slowly while parked, and this feeds a guard against gradual multi-hour drain, so
    // a slow cadence is plenty — no benefit to pushing more often, just more adb round-trips. A change
    // of car state goes out at once instead (see pushGuard).
    private const val GUARD_PUSH_INTERVAL_MS = 300_000L   // 5 min
    private val guardPushLock = Any()
    private var lastGuardPushMs = 0L          // guarded by guardPushLock
    private var lastPushedCarOn: Int? = null  // guarded by guardPushLock

    /** Whether the daemon should keep Wi-Fi up: the toggle is on and the off-state mode is Always On. */
    fun isActive(toggleOn: Boolean, mode: OffStateMode): Boolean =
        toggleOn && mode == OffStateMode.ENABLED

    /**
     * Push the saved settings to the daemon: the toggle and the off-state mode, see [isActive].
     * Safe to call repeatedly (idempotent) — call it whenever either changes and once on app start
     * so a reinstall/boot re-syncs the persisted preferences.
     */
    suspend fun sync(context: Context) {
        val prefs = PreferencesManager(context)
        apply(context, isActive(prefs.wifiKeepaliveWhenOff.first(), prefs.offStateMode.first()))
    }

    /** Enable → write the flag; disable → remove it. */
    private suspend fun apply(context: Context, enabled: Boolean) {
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
     * so its parked keepalive can guard against battery drain, plus [carOn]
     * (VehicleTelemetrySnapshot.effectiveCarOn(), null = no source) so it acts only once the car is
     * switched off. The daemon can't read the battery device from its bare shell context
     * (getInstance returns null there), so the app, which reads them fine, writes them to a file the
     * daemon polls. This is transport, not a second reading.
     *
     * Called from the telemetry loop; throttled to [GUARD_PUSH_INTERVAL_MS], except that a change of
     * [carOn] goes out at once — the keepalive must stop the moment the car is switched on, and the
     * app's first push after BYD's switch-off sweep revives it carries the switch-off. A no-op unless
     * the keepalive is [isActive] and the privileged setup is complete. Null (unknown) values are
     * written blank — the daemon treats a blank/absent value as "unknown, don't block".
     */
    suspend fun pushGuard(context: Context, v12: Double?, soc: Double?, carOn: Int?) {
        val prefs = PreferencesManager(context)
        if (!isActive(prefs.getCachedWifiKeepaliveWhenOff(), prefs.getCachedOffStateMode())) return
        if (!AdbPermissionManager.isSetupComplete(context)) return
        // The loop launches this every second; decide and record under the lock so two launches
        // never both push the same change.
        val due = synchronized(guardPushLock) {
            val now = SystemClock.elapsedRealtime()
            val due = lastGuardPushMs == 0L || carOn != lastPushedCarOn ||
                now - lastGuardPushMs >= GUARD_PUSH_INTERVAL_MS
            if (due) {
                lastGuardPushMs = now
                lastPushedCarOn = carOn
            }
            due
        }
        if (!due) return
        val line = WifiKeepaliveGuard.format(v12, soc, carOn)
        AdbPermissionManager.runShellBatch(
            context,
            listOf("echo '$line' > $GUARD_PATH 2>/dev/null; chmod 644 $GUARD_PATH 2>/dev/null; echo ok"),
            perCommandTimeoutMs = 3_000L,
        )
    }
}
