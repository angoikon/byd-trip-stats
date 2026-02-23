package com.byd.tripstats.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.byd.tripstats.MainActivity
import com.byd.tripstats.service.MqttService

/**
 * Receives BOOT_COMPLETED broadcast and starts MQTT service
 * This ensures the app runs automatically when the car starts
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("BootReceiver", "Boot completed, starting app...")

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_REBOOT,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                // Start MainActivity
                val mainIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(mainIntent)

                Log.i("BootReceiver", "MainActivity launched successfully")
            }
        }
    }
}
