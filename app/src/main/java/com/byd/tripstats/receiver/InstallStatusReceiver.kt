package com.byd.tripstats.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Receives the install result from [PackageInstaller] after a silent session commit.
 *
 * The PackageInstaller session API requires a PendingIntent target — the system
 * broadcasts to it with the install status once the session is processed.
 * This receiver logs the outcome and restarts the telemetry service after a
 * successful self-update, since the app process is replaced by the new version.
 */
class InstallStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Silent install succeeded (session=$sessionId) — app updated")
                // The process will be restarted by the OS after the install.
                // BootReceiver and BydStatsApplication.onCreate() handle service restart.
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // On some builds the system still requires user confirmation.
                // Launch the confirmation intent the system provided.
                val confirmIntent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else
                        @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmIntent)
                    Log.i(TAG, "Silent install requires user confirmation — launching dialog")
                } else {
                    Log.w(TAG, "STATUS_PENDING_USER_ACTION but no confirmation intent provided")
                }
            }
            else -> {
                Log.e(TAG, "Silent install failed: status=$status message=$message session=$sessionId")
                // Still running: put back the listeners released for the commit, let Install be
                // tapped again, and say why in diag.log — release builds strip the line above.
                runCatching { com.byd.tripstats.service.VehicleTelemetryService.resumeAfterFailedUpdate() }
                com.byd.tripstats.data.repository.UpdateRepository.getInstance(context).endInstall()
                com.byd.tripstats.util.DiagLog.event(
                    context, TAG, "update install: FAILED status=$status :: ${message ?: "-"}",
                )
            }
        }
    }

    companion object {
        private const val TAG = "InstallStatusReceiver"
        const val ACTION_INSTALL_STATUS = "com.byd.tripstats.action.INSTALL_STATUS"
    }
}