package com.byd.tripstats.util

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock

/**
 * Records, once per head-unit boot, whether the app actually started at that boot.
 *
 * On DiLink 5 the app is force-stopped at ignition-off, and a force-stopped package does not receive
 * `BOOT_COMPLETED` — so after an overnight reboot it can fail to start at all. That failure is
 * completely silent from inside the app: no process means no log, no notification, no retry. The
 * only trace it leaves is a *late first start* in the next boot session (observed 2026-08-20 on a
 * Sealion 7: unit booted 02:30, first app start 13:27 and only because the owner tapped the icon).
 *
 * This turns that into a measurement. It is instrumentation, not a user-facing feature: a card
 * would be pointless, since reading it requires opening the app, which is itself the recovery.
 */
object BootSessionTracker {

    private const val PREFS = "boot_session_tracker"
    private const val KEY_BOOT_ID = "last_boot_id"
    private const val KEY_HISTORY = "history"

    /**
     * A first start later than this into a boot means the app did not come up with the head unit.
     * Generous on purpose — a cold boot is slow, and BOOT_COMPLETED can arrive tens of seconds in
     * (observed at boot+20 s on both platforms).
     */
    private const val PROMPT_START_LIMIT_MS = 180_000L

    /** Boot ids drift by a second or two as the clock is adjusted; anything larger is a new boot. */
    private const val BOOT_ID_TOLERANCE_MS = 60_000L

    /** How many recent boots the hit-rate is computed over. */
    private const val HISTORY_LEN = 20

    data class Result(
        /** False when another process in this same boot already recorded it. */
        val firstStartOfBoot: Boolean,
        val missed: Boolean,
        val sinceBootSec: Long,
        val recentMissed: Int,
        val recentTotal: Int,
    )

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Call once from `Application.onCreate`. Only the first call within a given boot records
     * anything; later process starts in the same boot return [Result.firstStartOfBoot] = false.
     */
    fun recordStart(context: Context): Result {
        // Measured from Android's own start, not the kernel's: on DiLink-3 `adb reboot` restarts
        // Android without rebooting the kernel (uptime keeps counting), which kernel uptime alone
        // took for the same boot — no line, no score. See [sinceAndroidStartMs].
        val elapsed = sinceAndroidStartMs(SystemClock.elapsedRealtime(), systemServerStartMs())
        val sinceBootSec = elapsed / 1000
        // Wall clock minus time-since-start is stable within a boot and changes across one — the
        // same trick that lets sinceBoot distinguish a real cold boot from BYD's ignition-on
        // re-broadcast of BOOT_COMPLETED (which arrives with uptime still climbing).
        val bootId = System.currentTimeMillis() - elapsed

        val p = prefs(context)
        val lastBootId = p.getLong(KEY_BOOT_ID, 0L)
        val history = p.getString(KEY_HISTORY, "").orEmpty()

        if (lastBootId != 0L && kotlin.math.abs(bootId - lastBootId) <= BOOT_ID_TOLERANCE_MS) {
            return Result(
                firstStartOfBoot = false,
                missed = false,
                sinceBootSec = sinceBootSec,
                recentMissed = history.count { it == 'x' },
                recentTotal = history.length,
            )
        }

        // First run ever (fresh install / cleared data): seed the boot id but don't score it. The
        // user has just opened the app by hand, arbitrarily long after boot, which would otherwise
        // register as a miss and skew the rate from the very first sample.
        if (lastBootId == 0L) {
            p.edit().putLong(KEY_BOOT_ID, bootId).apply()
            return Result(
                firstStartOfBoot = false,
                missed = false,
                sinceBootSec = sinceBootSec,
                recentMissed = 0,
                recentTotal = 0,
            )
        }

        val missed = elapsed > PROMPT_START_LIMIT_MS
        val updated = (history + if (missed) 'x' else 'o').takeLast(HISTORY_LEN)
        p.edit()
            .putLong(KEY_BOOT_ID, bootId)
            .putString(KEY_HISTORY, updated)
            .apply()

        return Result(
            firstStartOfBoot = true,
            missed = missed,
            sinceBootSec = sinceBootSec,
            recentMissed = updated.count { it == 'x' },
            recentTotal = updated.length,
        )
    }

    /**
     * Why the head unit booted, for the boot-session line in diag.log — see [describeBootReason].
     *
     * Read in-process. `persist.sys.cloud_reboot_time` and `sys.system_server.start_elapsed` are
     * `system_prop`, which the app can read; `sys.boot.reason` is `system_boot_reason_prop`, which it
     * can't (empty on the dev Seal, 2026-11-15), so the reason itself usually reads "unknown". Times
     * are measured from Android's start, like [recordStart]: from the kernel's, an Android-only
     * restart the morning after BYD's cloud restart was matched to that restart and tagged with it.
     * Never throws: an unreadable property reads as empty.
     */
    fun bootReason(): String {
        val elapsed = SystemClock.elapsedRealtime()
        val serverStart = systemServerStartMs()
        return describeBootReason(
            sysBootReason = prop("sys.boot.reason"),
            bootStartMs = System.currentTimeMillis() - sinceAndroidStartMs(elapsed, serverStart),
            cloudRebootSec = prop("persist.sys.cloud_reboot_time").toLongOrNull(),
            androidOnlyRestart = isAndroidOnlyRestart(elapsed, serverStart),
        )
    }

    private fun systemServerStartMs(): Long? = prop("sys.system_server.start_elapsed").toLongOrNull()

    private fun prop(key: String): String = try {
        @Suppress("PrivateApi")
        val sp = Class.forName("android.os.SystemProperties")
        (sp.getMethod("get", String::class.java).invoke(null, key) as? String).orEmpty().trim()
    } catch (t: Throwable) {
        ""
    }
}

/**
 * Milliseconds since Android last started, given the kernel's uptime and `sys.system_server.start_elapsed`
 * (the uptime at which SystemServer started; Android 10+). A restart of Android alone — `adb reboot`
 * on DiLink-3 — leaves the kernel's uptime running, so only this tells it apart from the boot before.
 * Falls back to the kernel's uptime when the property is missing, unreadable or doesn't fit.
 */
internal fun sinceAndroidStartMs(elapsedMs: Long, systemServerStartElapsedMs: Long?): Long =
    if (systemServerStartElapsedMs != null && systemServerStartElapsedMs in 0..elapsedMs) elapsedMs - systemServerStartElapsedMs
    else elapsedMs

/**
 * SystemServer starts within seconds of the kernel at a real boot; one that started later than this
 * into the kernel's uptime is a restart of Android alone (`adb reboot` on DiLink-3).
 */
internal const val ANDROID_ONLY_RESTART_MIN_MS = 5 * 60_000L

internal fun isAndroidOnlyRestart(elapsedMs: Long, systemServerStartElapsedMs: Long?): Boolean =
    systemServerStartElapsedMs != null && systemServerStartElapsedMs in ANDROID_ONLY_RESTART_MIN_MS..elapsedMs

/** How close before a boot BYD's `cloud_reboot_time` has to fall for that boot to be its restart. */
internal const val CLOUD_REBOOT_MATCH_MS = 10 * 60_000L

/**
 * The boot reason as the boot-session line shows it: Android's `sys.boot.reason` (or "unknown"),
 * tagged when the boot was BYD's cloud restart.
 *
 * On DiLink-3, BYD's cloud service reboots a parked, locked head unit roughly every 24 hours
 * (`reboot,bydcloud`, services.jar `BYDTCPConnectService.setDeviceReboot`). After such a parked
 * boot BYD lets only its own allow-list start, so the app — and the Tailscale it launches — stays
 * down until the car is next switched on, which shows up as a `MISSED` boot with no cause. BYD
 * records the time of that decision in `persist.sys.cloud_reboot_time` (seconds), about a minute
 * before the boot (63 s on the dev Seal, 2026-11-14), so a boot that starts within
 * [CLOUD_REBOOT_MATCH_MS] after it is tagged even when `sys.boot.reason` can't be read. A restart of
 * Android alone ([isAndroidOnlyRestart]) is tagged as such.
 */
internal fun describeBootReason(
    sysBootReason: String,
    bootStartMs: Long,
    cloudRebootSec: Long?,
    androidOnlyRestart: Boolean = false,
): String {
    val reason = sysBootReason.ifBlank { "unknown" }
    val sinceCloudDecision = cloudRebootSec?.let { bootStartMs - it * 1000L }
    val byCloud = reason.contains("bydcloud", ignoreCase = true) ||
        (sinceCloudDecision != null && sinceCloudDecision in -60_000L..CLOUD_REBOOT_MATCH_MS)
    return when {
        byCloud -> "boot reason $reason (BYD cloud restart)"
        androidOnlyRestart -> "boot reason $reason (Android-only restart)"
        else -> "boot reason $reason"
    }
}
