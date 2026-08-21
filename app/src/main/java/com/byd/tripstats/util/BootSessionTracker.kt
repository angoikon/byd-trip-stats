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
        val elapsed = SystemClock.elapsedRealtime()
        val sinceBootSec = elapsed / 1000
        // Wall clock minus uptime is stable within a boot and changes across one — the same trick
        // that lets sinceBoot distinguish a real cold boot from BYD's ignition-on re-broadcast of
        // BOOT_COMPLETED (which arrives with uptime still climbing).
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
}
