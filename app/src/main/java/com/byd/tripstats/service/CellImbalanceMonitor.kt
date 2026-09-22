package com.byd.tripstats.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.byd.tripstats.MainActivity
import com.byd.tripstats.R
import com.byd.tripstats.data.entitlement.EntitlementManager
import com.byd.tripstats.data.model.VehicleTelemetry
import com.byd.tripstats.data.notify.VehicleEvents
import com.byd.tripstats.data.preferences.PreferencesManager
import kotlin.math.roundToInt

/**
 * Watches the live cell voltage spread (batteryCellVoltageMax − batteryCellVoltageMin,
 * the same diagnostic the Cell Voltage Spread heatmap visualises) and raises a
 * notification when it stays above the user-configured threshold.
 *
 * Called once per telemetry tick from [VehicleTelemetryService]. The debounce /
 * SoC-guard / hysteresis decision lives in the pure, unit-tested
 * [CellImbalanceEvaluator]; this class only reads preferences (synchronously from
 * the PreferencesManager cache, so a settings change takes effect immediately) and
 * posts the notification when the evaluator says to.
 */
class CellImbalanceMonitor(private val context: Context) {

    private val prefs = PreferencesManager(context)
    private val evaluator = CellImbalanceEvaluator()
    private var channelCreated = false

    fun onTelemetry(t: VehicleTelemetry) {
        val threshold = prefs.getCachedCellImbalanceThresholdV()
        // Premium feature — gated behind Pro entitlement. A non-Pro user (or one
        // whose license lapsed) gets enabled=false, so the evaluator resets and
        // never fires, even if the preference toggle was left on.
        val fire = evaluator.evaluate(
            enabled = prefs.getCachedCellImbalanceAlertEnabled() && EntitlementManager.isProNow(),
            vMax = t.batteryCellVoltageMax,
            vMin = t.batteryCellVoltageMin,
            soc = t.soc,
            thresholdV = threshold,
        )
        if (fire) {
            notifyImbalance(t.batteryCellVoltageMax - t.batteryCellVoltageMin, threshold, t.soc)
        }
    }

    private fun notifyImbalance(spreadV: Double, thresholdV: Double, soc: Double) {
        // Publish to the other channels first, and deliberately outside the POST_NOTIFICATIONS
        // check below: the head-unit notification is only visible to someone sitting in the car,
        // so the Telegram push and the companion feed are the halves that matter when the grant
        // is missing.
        VehicleEvents.getInstance(context).onCellImbalance(spreadV, thresholdV, soc)

        // Android 13+ requires the runtime POST_NOTIFICATIONS grant for a non-foreground
        // notify(). Skip silently if it hasn't been granted rather than crash.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Cell imbalance breach but POST_NOTIFICATIONS not granted — skipping")
            return
        }

        ensureChannel()

        val spreadMv = (spreadV * 1000).roundToInt()
        val thresholdMv = (thresholdV * 1000).roundToInt()

        val pendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Battery cell imbalance")
            .setContentText("Cell spread $spreadMv mV exceeds $thresholdMv mV at ${soc.roundToInt()}% SoC")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Pack cell voltage spread is $spreadMv mV (limit $thresholdMv mV) at " +
                        "${soc.roundToInt()}% SoC. A persistently high spread can indicate a weak " +
                        "or failing cell — check the Cell Voltage Spread heatmap for the trend."
                )
            )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification)

        Log.i(TAG, "Cell imbalance alert: spread=${spreadMv}mV threshold=${thresholdMv}mV soc=${soc.roundToInt()}%")
    }

    private fun ensureChannel() {
        if (channelCreated) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Battery cell imbalance",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when the cell voltage spread exceeds the configured limit"
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
        channelCreated = true
    }

    companion object {
        private const val TAG = "CellImbalanceMonitor"
        private const val CHANNEL_ID = "cell_imbalance_alert_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
