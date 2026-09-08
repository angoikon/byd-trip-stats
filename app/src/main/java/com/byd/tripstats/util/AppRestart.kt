package com.byd.tripstats.util

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.byd.tripstats.R
import com.byd.tripstats.sdk.DiLink5Platform

/**
 * The one place the app ends its own process and asks to come back.
 *
 * Restoring a database, resetting it and vacuuming it all have to close Room and restart
 * so the schema reopens cleanly. Each screen used to carry its own copy of the same
 * alarm-then-kill sequence, which meant the relaunch was reliable in exactly as many
 * places as it was correct in.
 *
 * Coming back is not guaranteed, for two independent reasons:
 *
 *  1. **The platform may refuse it.** The relaunch is an activity start from a process
 *     that no longer exists, which Android only permits inside a few seconds' grace after
 *     the app's last visible window — [Build.VERSION_CODES.Q] onwards it is otherwise a
 *     background activity start and is dropped silently, with nothing in the app able to
 *     detect it. An inexact alarm can also be deferred past that grace, so the alarm is
 *     scheduled exactly where the platform allows it.
 *  2. **We deliberately don't try on some head units.** An app-initiated kill followed by
 *     an immediate relaunch races the bydauto SDK's classloader injection and can leave
 *     the unit boot-looping (the 2.13.0 DiLink-5 incident). Only DiLink-3 has ever been
 *     proven safe here — see [DiLink5Platform.isPostDiLink3].
 *
 * So the relaunch is best-effort and a **notification is always posted first**: on a unit
 * that can't (or mustn't) relaunch itself it is the way back, and where the relaunch does
 * work it is cancelled a second later by [cancelReopenNotification] from MainActivity.
 * Tapping a notification is a foreground start, which the platform never refuses.
 */
object AppRestart {

    private const val TAG = "AppRestart"
    private const val CHANNEL_ID = "app_reopen_channel"
    private const val NOTIFICATION_ID = 1002

    /** Distinct PendingIntent request codes, so two pending relaunches can't overwrite. */
    const val REQUEST_RESTORE = 0
    const val REQUEST_RESET   = 1
    const val REQUEST_TRIM    = 2
    private const val REQUEST_NOTIFICATION = 100

    /**
     * Whether this head unit may relaunch itself after a self-kill. False on everything
     * newer than DiLink-3, where the SDK-injection race makes it unsafe; those units get
     * the notification instead. Also drives the wording of the "app will close" messages,
     * which must not promise a relaunch that will never come.
     */
    val canAutoRelaunch: Boolean
        get() = !DiLink5Platform.selfRestartUnsafe

    /**
     * Posts the way back, schedules the relaunch where that is safe, and ends the process.
     * Never returns.
     *
     * @param body      one line telling the user what the app closed to finish.
     * @param reason    short tag for diag.log.
     * @param requestCode one of [REQUEST_RESTORE] / [REQUEST_RESET] / [REQUEST_TRIM].
     */
    fun restart(
        context: Context,
        body: String,
        reason: String,
        requestCode: Int,
        delayMs: Long = 500L,
    ) {
        val appContext = context.applicationContext
        val relaunchScheduled = try {
            postReopenNotification(appContext, body)
            if (canAutoRelaunch) scheduleRelaunch(appContext, requestCode, delayMs) else false
        } catch (e: Exception) {
            // Nothing here may stop the kill: the database file has already been replaced
            // and the process must not go on running against it.
            Log.w(TAG, "Restart preparation failed: ${e.message}")
            false
        }
        runCatching {
            DiagLog.event(
                appContext, TAG,
                "restart reason=$reason relaunchScheduled=$relaunchScheduled " +
                    "canAutoRelaunch=$canAutoRelaunch sdk=${Build.VERSION.SDK_INT}",
            )
        }
        Log.i(TAG, "Ending process ($reason), relaunch scheduled=$relaunchScheduled")
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /** Clears the "tap to reopen" notification once the app is up again. */
    fun cancelReopenNotification(context: Context) {
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIFICATION_ID)
        }
    }

    private fun scheduleRelaunch(context: Context, requestCode: Int, delayMs: Long): Boolean {
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
            ?: return false
        val pending = PendingIntent.getActivity(
            context, requestCode, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return false
        val at = System.currentTimeMillis() + delayMs

        // Exact where it is allowed: an inexact alarm can be held back past the few
        // seconds in which the platform still accepts an activity start from an app that
        // has just disappeared, and a relaunch that arrives late is a relaunch refused.
        // The app targets API 29, so SCHEDULE_EXACT_ALARM is not required of it; the
        // check is there for the case where a future target bump makes it so.
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            runCatching { alarm.canScheduleExactAlarms() }.getOrDefault(false)
        return runCatching {
            if (exactAllowed) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            else alarm.set(AlarmManager.RTC, at, pending)
            true
        }.getOrElse {
            // An exact alarm can still be refused at runtime; an inexact one is better
            // than nothing, and the notification covers it either way.
            runCatching { alarm.set(AlarmManager.RTC, at, pending); true }.getOrDefault(false)
        }
    }

    private fun postReopenNotification(context: Context, body: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // IMPORTANCE_LOW: silent and no heads-up. On a unit that does relaunch itself this
        // notification lives for about a second, and it must not make a noise on the way.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.reopen_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = context.getString(R.string.reopen_notification_channel_desc) }
        )

        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context, REQUEST_NOTIFICATION, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.reopen_notification_title))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setOngoing(false)
                .build()
        )
    }
}
