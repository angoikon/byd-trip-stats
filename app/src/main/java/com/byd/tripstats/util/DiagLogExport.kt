package com.byd.tripstats.util

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * Export routes for the persistent diagnostic log, mirroring what the vehicle-compatibility probe
 * already offers (Telegram / save locally / email-by-QR).
 */
object DiagLogExport {

    private const val TAG = "DiagLogExport"
    private const val DIAG_FILE = "diag.log"

    /**
     * Upload cap. diag.log runs to 10 MB before it rotates; 2.5 MB of tail covers several days of
     * ordinary logging, which is well past the window any investigation needs.
     */
    private const val UPLOAD_TAIL_BYTES = 2_500_000L

    /** Both files an auto-start or telemetry investigation needs. */
    private fun sources(context: Context): List<File> {
        val dir = context.getExternalFilesDir(null) ?: return emptyList()
        return listOf(File(dir, DIAG_FILE), File(dir, RtDispatch.SNAPSHOT_FILE))
            .filter { it.exists() && it.length() > 0L }
    }

    fun hasAnything(context: Context): Boolean = sources(context).isNotEmpty()

    /**
     * Copy the log(s) into Downloads/BydTripStats/, where the head unit's own file manager can reach
     * them for a USB-stick transfer. Returns the directory written to.
     */
    fun saveToDownloads(context: Context): File {
        val files = sources(context)
        check(files.isNotEmpty()) { "no diagnostic log to save yet" }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "BydTripStats",
        )
        if (!dir.exists() && !dir.mkdirs()) error("Could not create ${dir.absolutePath}")
        files.forEach { src ->
            val dst = File(dir, src.name)
            src.copyTo(dst, overwrite = true)
            Log.i(TAG, "exported ${src.name} (${src.length()} B) -> ${dst.absolutePath}")
        }
        return dir
    }

    /**
     * Upload the tail of the log (plus the supervisor snapshot, appended) and return the public URL
     * for the mailto-QR dialog. Blocking network call — background dispatcher only.
     */
    fun uploadTail(context: Context, retention: String = "24h"): String {
        val files = sources(context)
        check(files.isNotEmpty()) { "no diagnostic log to upload yet" }
        val body = buildString {
            files.forEach { f ->
                append("===== ${f.name} (${f.length()} bytes total) =====\n")
                append(tailOf(f, UPLOAD_TAIL_BYTES))
                append("\n\n")
            }
        }
        return LitterboxUploader.upload(
            fileName = "byd_diag_log.txt",
            content = body,
            retention = retention,
        )
    }

    /** Last [maxBytes] of a file, starting at a line boundary so the head isn't a broken line. */
    private fun tailOf(file: File, maxBytes: Long): String = runCatching {
        if (file.length() <= maxBytes) return@runCatching file.readText()
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(file.length() - maxBytes)
            raf.readLine()               // discard the partial line we landed in
            val out = StringBuilder()
            while (true) out.append(raf.readLine() ?: break).append('\n')
            out.toString()
        }
    }.getOrElse { "(could not read ${file.name}: ${it.message})" }
}
