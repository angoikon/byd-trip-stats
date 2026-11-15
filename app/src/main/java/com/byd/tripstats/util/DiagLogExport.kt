package com.byd.tripstats.util

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
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
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.GZIPOutputStream

/**
 * Export routes for the persistent diagnostic log, mirroring what the vehicle-compatibility probe
 * already offers (Telegram / save locally / email-by-QR).
 */
object DiagLogExport {

    private const val TAG = "DiagLogExport"
    private const val DIAG_FILE = "diag.log"

    /** Folder under Download that Save writes to, as backups and the probe report do. */
    private const val DOWNLOAD_SUBFOLDER = "BydTripStats"

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
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val started = SystemClock.elapsedRealtime()
            sendLogs(app, telegram).also { result ->
                val tookMs = SystemClock.elapsedRealtime() - started
                when (result) {
                    is TelegramResult.Failed -> DiagLog.event(
                        app, TAG, exportFailedLine("Telegram send", result.reason, tookMs, networkNow(app))
                    )
                    is TelegramResult.Sent -> DiagLog.event(
                        app, TAG, "diagnostics log sent to Telegram in ${tookMs / 1000} s (network: ${networkNow(app)})"
                    )
                    TelegramResult.NothingToSend -> Unit
                }
            }
        }
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
    fun saveToDownloads(context: Context): File = try {
        copyToDownloads(context)
    } catch (e: Exception) {
        DiagLog.event(context, TAG, exportFailedLine("save", describe(e), elapsedMs = null, network = null))
        throw e
    }

    /**
     * Each file goes in by its path first, which overwrites the copy an earlier Save left. That
     * needs WRITE_EXTERNAL_STORAGE: installs that never ran ADB setup may not hold it (on DiLink-3
     * the folder is then read-only and the copy fails with EACCES), and on Android 13+ (DiLink 100)
     * the app can't hold it at all. A refused file goes in through the media store instead, which
     * needs no permission, as backups always have.
     */
    private fun copyToDownloads(context: Context): File {
        val files = sources(context)
        check(files.isNotEmpty()) { "no diagnostic log to save yet" }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            DOWNLOAD_SUBFOLDER,
        )
        var refused: Exception? = null
        files.forEach { src ->
            try {
                if (!dir.exists() && !dir.mkdirs()) error("Could not create ${dir.absolutePath}")
                val dst = File(dir, src.name)
                src.copyTo(dst, overwrite = true)
                Log.i(TAG, "exported ${src.name} (${src.length()} B) -> ${dst.absolutePath}")
            } catch (pathError: Exception) {
                try {
                    copyThroughMediaStore(context, src)
                } catch (storeError: Exception) {
                    throw IOException(
                        "${storeError.message ?: storeError.javaClass.simpleName} " +
                            "(direct copy: ${pathError.message ?: pathError.javaClass.simpleName})",
                        storeError,
                    )
                }
                if (refused == null) refused = pathError
            }
        }
        // Says the app lacks write access to shared storage, which nothing else on screen shows.
        refused?.let {
            DiagLog.event(
                context, TAG,
                "diagnostics log saved through the media store; the direct copy was refused: ${describe(it)}",
            )
        }
        return dir
    }

    /**
     * Writes [src] to Download/BydTripStats/ through the media store, replacing the copy an earlier
     * save by this install made there. A file of that name this install didn't create (an earlier
     * install's, or one copied in by hand) can't be replaced: the media store then numbers the new
     * one, e.g. `diag (1).log`.
     */
    private fun copyThroughMediaStore(context: Context, src: File) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_SUBFOLDER"
        // Matches only rows this install owns; any other stays where it is.
        runCatching {
            resolver.delete(
                collection,
                "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf(src.name, "$relativePath%"),
            )
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, src.name)
            put(MediaStore.Downloads.MIME_TYPE, mimeTypeFor(src.name))
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("Could not create ${src.name} in Download")
        try {
            val bytes = resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out, 64 * 1024) }
            } ?: error("Could not open ${src.name} in Download for writing")
            // SIZE and DATE_MODIFIED are stamped by hand: this BYD ROM's media store doesn't fill
            // them in when IS_PENDING clears (see LocalBackupManager.saveArchiveToDownloads).
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            values.put(MediaStore.Downloads.SIZE, bytes)
            values.put(MediaStore.Downloads.DATE_MODIFIED, System.currentTimeMillis() / 1000L)
            resolver.update(uri, values, null, null)
            Log.i(TAG, "exported ${src.name} ($bytes B) through the media store -> $uri")
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    /**
     * The type Android maps the file's extension to, so the media store keeps the name as given:
     * with a type the extension doesn't map to, it would append that type's extension instead.
     */
    private fun mimeTypeFor(name: String): String =
        MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    /**
     * Upload the tail of the log (plus the supervisor snapshot, appended) and return the public URL
     * for the mailto-QR dialog. Blocking network call — background dispatcher only.
     */
    fun uploadTail(context: Context, retention: String = "24h"): String {
        val started = SystemClock.elapsedRealtime()
        return try {
            uploadTailOnce(context, retention).also {
                DiagLog.event(
                    context, TAG,
                    "diagnostics log uploaded for the QR code in ${(SystemClock.elapsedRealtime() - started) / 1000} s, " +
                        "link valid $retention (network: ${networkNow(context)})"
                )
            }
        } catch (e: Exception) {
            DiagLog.event(
                context, TAG,
                exportFailedLine("upload for the QR code", describe(e), SystemClock.elapsedRealtime() - started, networkNow(context))
            )
            throw e
        }
    }

    private fun uploadTailOnce(context: Context, retention: String): String {
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

    // ── diag.log lines for the routes ─────────────────────────────────────────

    /** An exception as one short line: its type and message. */
    private fun describe(e: Throwable): String = "${e.javaClass.simpleName}: ${e.message ?: "-"}"

    /**
     * The diag.log line for a send that failed. Written because the QR route shows a failure only as
     * one small line under its buttons and drops it when the screen is left, so a tester could only
     * report that no code appeared (Sealion 7, 2026-11-14) — and the next log that does get through
     * now says why. The error is kept to one line, and a Telegram bot token is cut out of it: a
     * failed request's message can carry the URL, and the token sits in that URL.
     */
    internal fun exportFailedLine(route: String, error: String, elapsedMs: Long?, network: String?): String {
        val oneLine = error.replace(Regex("\\s+"), " ")
            .replace(Regex("bot\\d+:[A-Za-z0-9_-]+"), "bot<token>")
            .take(200)
        val after = elapsedMs?.let { " after ${it / 1000} s" }.orEmpty()
        val net = network?.let { " (network: $it)" }.orEmpty()
        return "diagnostics log $route FAILED$after: $oneLine$net"
    }

    /**
     * The network this app has right now, for a send's diag.log line: "Wi-Fi", "mobile data",
     * either with "(no internet)" when Android hasn't validated it, "blocked for this app" when
     * the system blocks this app's data (BYD's 4G block on DiLink-5), or "none". Never throws.
     */
    @Suppress("DEPRECATION")  // NetworkInfo is the only per-app view of a policy block.
    private fun networkNow(context: Context): String = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching "unknown"
        val info = cm.activeNetworkInfo
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val kind = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "mobile data"
            caps != null -> "other"
            info?.type == ConnectivityManager.TYPE_WIFI -> "Wi-Fi"
            info?.type == ConnectivityManager.TYPE_MOBILE -> "mobile data"
            else -> null
        }
        networkLabel(
            kind = kind,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            blockedForApp = info?.detailedState == NetworkInfo.DetailedState.BLOCKED,
        )
    }.getOrDefault("unknown")

    /** [networkNow]'s wording, from what it read. */
    internal fun networkLabel(kind: String?, validated: Boolean, blockedForApp: Boolean): String = when {
        kind == null -> if (blockedForApp) "blocked for this app" else "none"
        blockedForApp -> "$kind (blocked for this app)"
        !validated -> "$kind (no internet)"
        else -> kind
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
