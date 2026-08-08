package com.byd.tripstats.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent rolling diagnostic log for service lifecycle and keepalive events.
 *
 * Writes to the app's external files dir so it can be pulled without root:
 *   adb pull /sdcard/Android/data/com.byd.tripstats/files/diag.log
 *
 * The logcat ring buffer on DiLink is only 256 KiB and gets shredded by the
 * system GPS provider at ~600 B/sec — only ~7 min of history survives, which
 * makes any overnight debugging impossible. This file persists across reboots,
 * caps at MAX_BYTES (rotated to .prev), and survives even after the process is
 * killed. Cheap to write (low call frequency, synchronous append).
 */
object DiagLog {
    private const val TAG = "DiagLog"
    private const val FILE_NAME = "diag.log"
    private const val MAX_BYTES = 10L * 1024L * 1024L  // 10 MB main + 10 MB .prev = ~20 MB total
    private const val BACKUP_SUFFIX = ".prev"

    private val lock = Any()
    private val timestampFormat = ThreadLocal.withInitial {
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    /**
     * Append a line. Always also mirrors to logcat at INFO level so live debugging works.
     * Safe to call before/after service lifecycle; never throws.
     */
    fun event(context: Context, tag: String, message: String) {
        Log.i(tag, message)
        try {
            val dir = context.applicationContext.getExternalFilesDir(null) ?: return
            val file = File(dir, FILE_NAME)
            synchronized(lock) {
                if (file.length() > MAX_BYTES) {
                    val backup = File(dir, FILE_NAME + BACKUP_SUFFIX)
                    if (backup.exists()) backup.delete()
                    file.renameTo(backup)
                }
                PrintWriter(FileWriter(file, true)).use { out ->
                    out.println("${timestampFormat.get()!!.format(Date())} $tag: $message")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Write failed: ${e.message}")
        }
    }
}
