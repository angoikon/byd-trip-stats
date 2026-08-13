package com.byd.tripstats.util

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.PixelCopy
import android.view.Window
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Captures what is currently shown on screen and saves it as a PNG to the
 * public Download/Screenshots/ folder via MediaStore.
 *
 * Uses [PixelCopy] against the Activity window so the saved image reflects the
 * real composited frame (gradients, charts, battery animation) rather than a
 * software re-draw of a single view. On the BYD DiLink head unit the public
 * Download directory is "Download" (see LocalBackupManager); MediaStore's
 * RELATIVE_PATH is relative to that root, so "Download/Screenshots" lands in the
 * folder the user expects.
 */
object ScreenshotUtil {
    private const val TAG = "ScreenshotUtil"
    private const val DOWNLOAD_DIR = "Download"
    private const val SCREENSHOT_SUBFOLDER = "Screenshots"

    /**
     * Captures the current window content and writes it to Download/Screenshots/.
     * Must be called from a coroutine. Returns the saved file name on success;
     * throws on failure so the caller can surface an error to the user.
     *
     * [onCaptured] runs on the calling (main) thread the instant the frame has been
     * grabbed, before the slower disk write — the right moment to play a screen
     * flash, since anything drawn after this point is not in the captured image.
     *
     * [window] defaults to the Activity's window, but a Compose `Dialog` renders in
     * its own separate window — pass that window so the saved image is the dialog
     * content (e.g. the HV/12V battery history) rather than the screen behind it.
     */
    suspend fun captureAndSave(
        activity: Activity,
        window: Window = activity.window,
        onCaptured: () -> Unit = {},
    ): String {
        val bitmap = captureWindow(window)
        onCaptured()
        return try {
            withContext(Dispatchers.IO) { saveToDownloads(activity.applicationContext, bitmap) }
        } finally {
            bitmap.recycle()
        }
    }

    /** Grabs the live composited frame of [window] via PixelCopy. */
    private suspend fun captureWindow(window: Window): Bitmap =
        suspendCancellableCoroutine { cont ->
            val view = window.decorView
            val width = view.width
            val height = view.height
            if (width <= 0 || height <= 0) {
                cont.resumeWithException(IllegalStateException("Nothing on screen to capture yet"))
                return@suspendCancellableCoroutine
            }

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(
                window,
                bitmap,
                { result ->
                    if (result == PixelCopy.SUCCESS) {
                        cont.resume(bitmap)
                    } else {
                        bitmap.recycle()
                        cont.resumeWithException(IOException("PixelCopy failed (code $result)"))
                    }
                },
                Handler(Looper.getMainLooper())
            )
        }

    /** Saves [bitmap] as a PNG into Download/Screenshots/ and returns the file name. */
    private fun saveToDownloads(context: Context, bitmap: Bitmap): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
        val fileName = "byd_trip_stats_$timestamp.png"

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
            put(MediaStore.Downloads.RELATIVE_PATH, "$DOWNLOAD_DIR/$SCREENSHOT_SUBFOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create screenshot file in Download")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IOException("Could not encode screenshot")
                }
            } ?: throw IOException("Could not open output stream")

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            // Roll back the half-written pending entry so it doesn't linger.
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }

        Log.i(TAG, "Screenshot saved: $DOWNLOAD_DIR/$SCREENSHOT_SUBFOLDER/$fileName")
        return fileName
    }
}
