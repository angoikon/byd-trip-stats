package com.byd.tripstats.util

import android.content.Context
import android.os.Environment
import android.util.Log
import com.byd.tripstats.data.backup.TelegramManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.GZIPOutputStream

/**
 * Export routes for the persistent diagnostic log, mirroring what the vehicle-compatibility probe
 * already offers (Telegram / save locally / email-by-QR).
 */
object DiagLogExport {

    private const val TAG = "DiagLogExport"
    private const val DIAG_FILE = "diag.log"

    /** DiagLog's rotation target; see [DiagLog]. */
    private const val DIAG_PREV_FILE = "diag.log.prev"

    /** Name of the compressed copy sent to Telegram; lives in the cache dir only while sending. */
    private const val TELEGRAM_ARCHIVE = "diag.log.gz"

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

    // ── Telegram ──────────────────────────────────────────────────────────────

    /** How a [sendToTelegram] ended, for the caller to tell the user. */
    sealed class TelegramResult {
        object NothingToSend : TelegramResult()
        data class Sent(val withSupervisorLog: Boolean) : TelegramResult()
        data class Failed(val reason: String) : TelegramResult()
    }

    /**
     * True from the tap on the Telegram button until the send has finished. The compression before
     * the upload sets no TelegramManager state, so without this the button stays enabled and looks
     * as if the tap did nothing — and a second tap sends a second copy (same failure as Send backup
     * now and Install now, 2026-10-04). Process-wide, so it also holds after leaving the screen.
     */
    private val _telegramSending = MutableStateFlow(false)
    val telegramSending: StateFlow<Boolean> = _telegramSending.asStateFlow()

    /**
     * Gzips the log and sends it to the Telegram chat, with the supervisor-log snapshot beside it
     * when there is one. A log is ~9x smaller compressed, which matters on the car's 4G.
     *
     * The busy flag is claimed here, synchronously, before anything is launched — a tap that
     * arrives while the coroutine is still waiting for its first dispatch is refused too.
     *
     * @return false when a send is already under way (nothing was started, [onResult] won't run).
     */
    fun sendToTelegram(
        scope: CoroutineScope,
        context: Context,
        telegram: TelegramManager,
        onResult: (TelegramResult) -> Unit,
    ): Boolean = launchOnce(scope, _telegramSending, onResult) {
        withContext(Dispatchers.IO) { sendLogs(context.applicationContext, telegram) }
    }

    /**
     * Runs [work] on [scope] unless [busy] is already set. [busy] is cleared by the job's
     * completion handler rather than a `finally` in [work], so it is cleared even when the scope
     * is cancelled before the coroutine ever starts.
     */
    internal fun <T> launchOnce(
        scope: CoroutineScope,
        busy: MutableStateFlow<Boolean>,
        onResult: (T) -> Unit,
        work: suspend () -> T,
    ): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        scope.launch { onResult(work()) }.invokeOnCompletion { busy.value = false }
        return true
    }

    private suspend fun sendLogs(context: Context, telegram: TelegramManager): TelegramResult {
        val dir = context.getExternalFilesDir(null) ?: return TelegramResult.NothingToSend
        val previous = File(dir, DIAG_PREV_FILE)
        // Oldest first, so the archive reads as one continuous log. The rotated half is included
        // because a log that rotated just before the tap leaves the current file nearly empty.
        val logs = listOf(previous, File(dir, DIAG_FILE)).filter { it.exists() && it.length() > 0L }
        if (logs.isEmpty()) return TelegramResult.NothingToSend

        val archive = File(context.cacheDir, TELEGRAM_ARCHIVE)
        try {
            compressLogs(logs, archive)
            val caption = "BYD Trip Stats — diagnostics log (gzip" +
                if (previous in logs) ", previous + current)" else ")"
            // sendFile reports failure through its state, never by throwing.
            telegram.sendFile(archive, caption)
            (telegram.state.value as? TelegramManager.TelegramState.Error)?.let {
                return TelegramResult.Failed(it.message)
            }

            // Ship the supervisor-log snapshot alongside it when one exists. It is taken just before
            // the app re-dispatches the background restarter (which truncates the original), so it is
            // the only post-hoc record of whether that restarter was alive through a failed
            // auto-start. Best-effort: a failure here must not lose the diagnostics send above.
            val supd = File(dir, RtDispatch.SNAPSHOT_FILE)
            val supdSent = supd.exists() && supd.length() > 0L && run {
                telegram.sendFile(supd, caption = "BYD Trip Stats — supervisor log snapshot")
                telegram.state.value !is TelegramManager.TelegramState.Error
            }
            return TelegramResult.Sent(withSupervisorLog = supdSent)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Telegram diag send failed: ${e.message}")
            return TelegramResult.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            archive.delete()
        }
    }

    /** Gzips [sources], in order, into one stream in [dest], replacing it. */
    internal fun compressLogs(sources: List<File>, dest: File) {
        GZIPOutputStream(FileOutputStream(dest), 64 * 1024).use { gz ->
            sources.forEach { src -> src.inputStream().use { it.copyTo(gz, 64 * 1024) } }
        }
    }

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
