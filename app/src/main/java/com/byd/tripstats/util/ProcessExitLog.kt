package com.byd.tripstats.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Why this app's earlier processes ended, as Android recorded it (Android 11 and later), written to
 * diag.log at the next start, once per process. Diagnostic only: nothing reads it.
 *
 * On DiLink 5 a switch-off shows up only as the car force-stopping the app and the supervisor
 * starting it again seconds later, and a crash looks the same from inside the new process. Android
 * keeps a record of how each process ended (`ActivityManager.getHistoricalProcessExitReasons`), so
 * the new process can tell the two apart: a force-stop should read USER_REQUESTED ("stop <package>
 * due to from pid N"), a crash CRASH. Whether BYD's head units record it that way is what these
 * lines are for. If they do, a DiLink-5 trip could close right after the restart, instead of
 * waiting out a car-off timeout the head unit often sleeps through. The pid matches the
 * `Application.onCreate pid=…` line of the process that ended.
 *
 * DiLink 3 runs Android 10, which keeps no such record: there this does nothing.
 */
object ProcessExitLog {
    private const val TAG = "ProcessExit"
    private const val PREFS = "process_exit_log"
    private const val KEY_LAST_LOGGED_MS = "last_logged_exit_ms"

    /** Android keeps a few per package, so the first start of a build with this logs what's there. */
    private const val MAX_READ = 10

    /** Logs every recorded exit newer than the last one logged. Never throws. */
    fun logNewExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            logNewExitsR(context)
        } catch (t: Throwable) {
            DiagLog.event(context, TAG, "previous process exits: unreadable (${t.javaClass.simpleName})")
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun logNewExitsR(context: Context) {
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastLogged = prefs.getLong(KEY_LAST_LOGGED_MS, 0L)
        val fresh = am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_READ)
            .filter { it.timestamp > lastLogged }
            .sortedBy { it.timestamp }
        if (fresh.isEmpty()) return
        val now = System.currentTimeMillis()
        for (e in fresh) {
            DiagLog.event(
                context, TAG,
                processExitLine(
                    timestampMs = e.timestamp,
                    nowMs = now,
                    pid = e.pid,
                    processName = e.processName,
                    reason = e.reason,
                    status = e.status,
                    importance = e.importance,
                    description = e.description,
                ),
            )
        }
        prefs.edit().putLong(KEY_LAST_LOGGED_MS, fresh.last().timestamp).apply()
    }
}

/**
 * One diag.log line for an ended process, e.g. `previous process pid=4940 (com.byd.tripstats)
 * ended 11-09 13:09:02, 25 s before this start: USER_REQUESTED status=0 importance=foreground
 * service :: stop com.byd.tripstats due to from pid 1234`.
 */
internal fun processExitLine(
    timestampMs: Long,
    nowMs: Long,
    pid: Int,
    processName: String?,
    reason: Int,
    status: Int,
    importance: Int,
    description: String?,
    zone: TimeZone = TimeZone.getDefault(),
): String {
    val at = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).apply { timeZone = zone }.format(Date(timestampMs))
    val desc = description?.replace('\n', ' ')?.trim()?.take(120)?.takeIf { it.isNotEmpty() }
    return "previous process pid=$pid (${processName ?: "?"}) ended $at, ${agoText(nowMs - timestampMs)} before this start: " +
        "${exitReasonName(reason)} status=$status importance=${exitImportanceName(importance)}" +
        (desc?.let { " :: $it" } ?: "")
}

/** `ApplicationExitInfo.REASON_*` by name; the numbers are Android's (14–16 came in Android 13/14). */
internal fun exitReasonName(reason: Int): String = when (reason) {
    0 -> "UNKNOWN"
    1 -> "EXIT_SELF"
    2 -> "SIGNALED"
    3 -> "LOW_MEMORY"
    4 -> "CRASH"
    5 -> "CRASH_NATIVE"
    6 -> "ANR"
    7 -> "INITIALIZATION_FAILURE"
    8 -> "PERMISSION_CHANGE"
    9 -> "EXCESSIVE_RESOURCE_USAGE"
    10 -> "USER_REQUESTED"
    11 -> "USER_STOPPED"
    12 -> "DEPENDENCY_DIED"
    13 -> "OTHER"
    14 -> "FREEZER"
    15 -> "PACKAGE_STATE_CHANGE"
    16 -> "PACKAGE_UPDATED"
    else -> "reason=$reason"
}

/** `RunningAppProcessInfo.IMPORTANCE_*` — how much the process mattered when it ended. */
internal fun exitImportanceName(importance: Int): String = when (importance) {
    100 -> "foreground"
    125 -> "foreground service"
    200 -> "visible"
    230 -> "perceptible"
    300 -> "service"
    325 -> "top sleeping"
    350 -> "can't save state"
    400 -> "cached"
    1000 -> "gone"
    else -> importance.toString()
}

private fun agoText(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 120 -> "$s s"
        s < 120 * 60 -> "${s / 60} min"
        s < 48 * 3600 -> "${s / 3600} h"
        else -> "${s / 86400} d"
    }
}
