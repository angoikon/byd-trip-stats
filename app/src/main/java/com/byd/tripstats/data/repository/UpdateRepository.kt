package com.byd.tripstats.data.repository

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.util.Log
import com.byd.tripstats.adb.AdbPermissionManager
import com.byd.tripstats.receiver.InstallStatusReceiver
import com.byd.tripstats.sdk.DiLink5Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.net.URL

/**
 * Checks GitHub Releases for a newer version and manages APK download + install.
 *
 * Flow:
 *   1. checkForUpdate() — calls GitHub API, compares tag against BuildConfig.VERSION_NAME
 *   2. downloadUpdate() — uses DownloadManager, exposes progress via [downloadProgress]
 *   3. installUpdate() — fires ACTION_INSTALL_PACKAGE via FileProvider URI
 *
 * The caller (DashboardViewModel) is responsible for gating install on:
 *   - gear == "P" (parked)
 *   - !isInTrip
 *   - !isChargingSession
 */
class UpdateRepository private constructor(private val context: Context) {

    private val TAG = "UpdateRepository"
    private val GITHUB_OWNER = "angoikon"
    private val GITHUB_REPO  = "byd-trip-stats"
    private val PROVIDER_AUTHORITY = "${context.packageName}.fileprovider"

    private val json = Json { ignoreUnknownKeys = true }
    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    // ── Public state ──────────────────────────────────────────────────────────

    /** Non-null when a newer version is available. */
    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    /** null = idle, 0–100 = downloading, -1 = failed */
    private val _downloadProgress = MutableStateFlow<Int?>(null)
    val downloadProgress: StateFlow<Int?> = _downloadProgress.asStateFlow()

    /** True once APK is downloaded and ready to install. */
    private val _downloadedApk = MutableStateFlow<File?>(null)
    val downloadedApk: StateFlow<File?> = _downloadedApk.asStateFlow()

    /**
     * True from the tap on Install until the install is known to have failed; a successful one
     * replaces this process. One install at a time: a second tap while the first was still taking
     * its backup ran a second install alongside it, and on DiLink-5 the two failed each other
     * (2026-10-04). Lives here, not in the ViewModel, so the install-result receiver can release it.
     */
    private val _installInProgress = MutableStateFlow(false)
    val installInProgress: StateFlow<Boolean> = _installInProgress.asStateFlow()

    /** Claims the install; false when one is already running. */
    fun beginInstall(): Boolean = _installInProgress.compareAndSet(false, true)

    /** Releases it after an install that didn't happen, so Install can be tapped again. */
    fun endInstall() {
        _installInProgress.value = false
    }

    private var activeDownloadId: Long? = null

    // ── GitHub API model ──────────────────────────────────────────────────────

    @Serializable
    data class GitHubRelease(
        @SerialName("tag_name") val tagName: String,
        @SerialName("body")     val body: String? = "",
        @SerialName("assets")   val assets: List<GitHubAsset> = emptyList()
    )

    @Serializable
    data class GitHubAsset(
        @SerialName("name")                 val name: String,
        @SerialName("browser_download_url") val downloadUrl: String,
        @SerialName("size")                 val size: Long = 0L
    )

    data class UpdateInfo(
        val latestVersion: String,  // e.g. "1.3.0"
        val currentVersion: String, // BuildConfig.VERSION_NAME
        val releaseNotes: String,
        val apkUrl: String,
        val apkName: String,
        val apkSizeBytes: Long
    )

    // ── Version check ─────────────────────────────────────────────────────────

    /**
     * Fetches the latest GitHub release and compares against [currentVersion].
     * Call this from a background coroutine (IO dispatcher).
     * Updates [updateInfo] if a newer version is found.
     */
    suspend fun checkForUpdate(currentVersion: String) {
        try {
            val url = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
            val response = URL(url).openConnection().apply {
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                connectTimeout = 10_000
                readTimeout    = 10_000
            }.getInputStream().bufferedReader().readText()

            val release = json.decodeFromString<GitHubRelease>(response)
            val latestVersion = release.tagName.trimStart('v')  // "v1.3.0" → "1.3.0"

            Log.d(TAG, "Current: $currentVersion  Latest: $latestVersion")

            if (isNewerVersion(latestVersion, currentVersion)) {
                // Pick the APK asset for the flavor THIS HARDWARE needs — DiLink5Platform.expectedFlavor
                // (derived from ro.vehicle.type), NOT the installed build's BuildConfig.FLAVOR. This makes
                // the updater self-healing: a dilink5 build sideloaded onto a DiLink-3 car converges back to
                // dilink3 on the next update, and a DiLink-5 car that somehow got a non-D5 build auto-fixes
                // to dilink5. A correctly-installed car sees no change (expectedFlavor == its own flavor).
                // Legacy pre-flavor clients aren't running this code — they use the old contains("release")
                // logic, which is why only the dilink3 release asset is named with "release" (and it still
                // matches contains("dilink3")). No "any apk" fallback: skipping an update is safer than
                // installing the wrong flavor over this app (same applicationId + key installs in place).
                val apkAsset = release.assets
                    .firstOrNull { it.name.contains(DiLink5Platform.expectedFlavor) && it.name.endsWith(".apk") }

                if (apkAsset != null) {
                    _updateInfo.value = UpdateInfo(
                        latestVersion  = latestVersion,
                        currentVersion = currentVersion,
                        releaseNotes   = release.body?.take(500) ?: "",  // trim for UI
                        apkUrl         = apkAsset.downloadUrl,
                        apkName        = apkAsset.name,
                        apkSizeBytes   = apkAsset.size
                    )
                    Log.i(TAG, "Update available: v$latestVersion (${apkAsset.name})")
                } else {
                    Log.w(TAG, "Release v$latestVersion has no APK asset")
                }
            } else {
                Log.d(TAG, "App is up to date")
                _updateInfo.value = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            // Silent failure — don't bother the user if GitHub is unreachable
        }
    }

    /**
     * Compares semantic version strings.
     * Returns true if [latest] is strictly greater than [current].
     */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        // Strip pre-release suffix (e.g. "2.6.0-beta02" → "2.6.0") before comparing
        fun parse(v: String) = v.substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
        val l = parse(latest); val c = parse(current)
        for (i in 0 until maxOf(l.size, c.size)) {
            val diff = (l.getOrElse(i) { 0 }) - (c.getOrElse(i) { 0 })
            if (diff != 0) return diff > 0
        }
        // Same numeric version — stable (no suffix) is newer than a pre-release
        return current.contains("-") && !latest.contains("-")
    }

    // ── Download ──────────────────────────────────────────────────────────────

    /**
     * Downloads the update APK using DownloadManager.
     * Progress is reported via [downloadProgress] (0–100, -1 on failure).
     * On completion, [downloadedApk] is set and [downloadProgress] is set to 100.
     *
     * Safe to call from any coroutine — DownloadManager handles threading internally.
     */
    fun downloadUpdate(info: UpdateInfo) {
        // Cancel any existing download
        activeDownloadId?.let { downloadManager.remove(it) }

        val destFile = File(
            context.getExternalFilesDir(null),
            "Download/${info.apkName}"   // DiLink uses "Download" not "Downloads"
        )
        destFile.delete()  // remove stale file if present

        val request = DownloadManager.Request(Uri.parse(info.apkUrl)).apply {
            setTitle("BYD Trip Stats ${info.latestVersion}")
            setDescription("Downloading update…")
            setDestinationUri(Uri.fromFile(destFile))
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setMimeType("application/vnd.android.package-archive")
        }

        activeDownloadId = downloadManager.enqueue(request)
        _downloadProgress.value = 0
        Log.i(TAG, "Download enqueued: ${info.apkName}")

        // Register completion receiver
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != activeDownloadId) return

                val query = DownloadManager.Query().setFilterById(id)
                val cursor = downloadManager.query(query)
                if (cursor.moveToFirst()) {
                    val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    when (cursor.getInt(statusCol)) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            _downloadProgress.value = 100
                            _downloadedApk.value = destFile
                            Log.i(TAG, "Download complete: ${destFile.absolutePath}")
                        }
                        DownloadManager.STATUS_FAILED -> {
                            _downloadProgress.value = -1
                            Log.e(TAG, "Download failed")
                        }
                    }
                }
                cursor.close()
                ctx.unregisterReceiver(this)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }

    /**
     * Polls DownloadManager for current progress (0–100).
     * Call this periodically (e.g. every 1s) while download is in progress.
     * Returns null if no active download.
     */
    fun pollDownloadProgress(): Int? {
        val id = activeDownloadId ?: return null
        val cursor = downloadManager.query(DownloadManager.Query().setFilterById(id))
        if (!cursor.moveToFirst()) { cursor.close(); return null }

        val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
        val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
        cursor.close()

        return if (total > 0) ((downloaded * 100) / total).toInt() else 0
    }

    // ── Install ───────────────────────────────────────────────────────────────

    /**
     * Installs [apkFile] silently using the PackageInstaller session API.
     *
     * Equivalent to: pm install-create -g -r -> pm install-write -> pm install-commit
     *
     * The -g flag (INSTALL_GRANT_RUNTIME_PERMISSIONS) ensures all dangerous permissions
     * declared in the manifest are granted with RESTRICTION_INSTALLER_EXEMPT, exactly
     * replicating what the ADB install script does. Location, storage, and background
     * location are granted automatically on every update — no user interaction or ADB.
     *
     * Falls back to the system installer dialog if the session API fails.
     *
     * Only call this when the car is parked, no trip is active, and no charging
     * session is ongoing — enforced by DashboardViewModel.canInstallNow.
     */
    suspend fun installUpdate(apkFile: File) = withContext(Dispatchers.IO) {
        val committed = try {
            installViaSilentSession(apkFile)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Silent install failed (${e.message}), falling back to system installer")
            // The listeners may already have been released for the commit; the system installer
            // is the user's to finish or cancel, and nothing reports back if they cancel.
            runCatching { com.byd.tripstats.service.VehicleTelemetryService.resumeAfterFailedUpdate() }
            endInstall()
            installViaSystemInstaller(apkFile)
            false
        }
        // Committed: success replaces this process, failure reaches InstallStatusReceiver, which
        // releases the install. This only covers a result that never arrives. Outside the try, so
        // a cancelled wait just ends — it must never fall through to the system installer.
        if (committed) {
            kotlinx.coroutines.delay(INSTALL_RESULT_TIMEOUT_MS)
            if (_installInProgress.value) {
                runCatching { com.byd.tripstats.service.VehicleTelemetryService.resumeAfterFailedUpdate() }
                endInstall()
            }
        }
    }

    /**
     * Install over the local adb channel as the shell user — the route for head units where the
     * silent [PackageInstaller] session can't commit and there is no installer dialog to fall back
     * to (see [AdbPermissionManager.installApkViaShell]).
     *
     * Deliberately has **no** fallback to the two paths above: on those units both are known to
     * fail, and driving them would only produce a second, more confusing error. A false return
     * means the caller should surface the manual `adb install -r` instructions instead.
     *
     * @return true only when `pm` reported Success. On success this process is about to be killed
     *         by the package replace, so callers should not expect to run much afterwards.
     */
    suspend fun installUpdateViaShell(apkFile: File): Boolean = withContext(Dispatchers.IO) {
        val result = AdbPermissionManager.installApkViaShell(context, apkFile)
        if (result.exitCode == 0) {
            Log.i(TAG, "Shell install succeeded — process will be replaced")
            true
        } else {
            Log.w(TAG, "Shell install failed: ${result.output.take(200)}")
            endInstall()
            false
        }
    }

    /**
     * Silent install via PackageInstaller session API with runtime permission grants.
     * Requires REQUEST_INSTALL_PACKAGES permission (declared in manifest).
     */
    private fun installViaSilentSession(apkFile: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(context.packageName)
            setSize(apkFile.length())
            // Grant all dangerous permissions at install time — equivalent to pm install -g.
            // Sets RESTRICTION_INSTALLER_EXEMPT so grants survive future silent updates too.
            // Primary path: setGrantedRuntimePermissions(null) grants all declared permissions.
            try {
                val method = PackageInstaller.SessionParams::class.java
                    .getMethod("setGrantedRuntimePermissions", Array<String>::class.java)
                method.invoke(this, null as? Array<String>?)
                Log.i(TAG, "setGrantedRuntimePermissions(null) succeeded")
            } catch (_: NoSuchMethodException) {
                // Fallback: set INSTALL_GRANT_RUNTIME_PERMISSIONS flag (0x100) directly.
                try {
                    val field = PackageInstaller.SessionParams::class.java
                        .getDeclaredField("installFlags")
                    field.isAccessible = true
                    field.setInt(this, field.getInt(this) or 0x00000100)
                    Log.i(TAG, "Set INSTALL_GRANT_RUNTIME_PERMISSIONS via reflection on installFlags")
                } catch (ex: Exception) {
                    Log.w(TAG, "Could not set grant flag: ${ex.message}")
                }
            }
        }

        val sessionId = installer.createSession(params)
        Log.i(TAG, "PackageInstaller session created: $sessionId")

        installer.openSession(sessionId).use { session ->
            FileInputStream(apkFile).use { apkStream ->
                session.openWrite("base.apk", 0, apkFile.length()).use { out ->
                    apkStream.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallStatusReceiver::class.java).apply {
                action = InstallStatusReceiver.ACTION_INSTALL_STATUS
            }
            val pi = android.app.PendingIntent.getBroadcast(
                context, sessionId, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE
            )
            // The install force-kills this process (no onDestroy/stop), which would leave stale BYD
            // SDK listener registrations that wedge the SDK for the freshly-installed app. Release them
            // gracefully here, while still alive, before committing.
            runCatching { com.byd.tripstats.service.VehicleTelemetryService.prepareForUpdate() }
            try { Thread.sleep(600) } catch (_: InterruptedException) {}
            session.commit(pi.intentSender)
            Log.i(TAG, "PackageInstaller session committed — install in progress")
        }
    }

    /** Fallback: fires the system installer dialog (user must tap Install). */
    private fun installViaSystemInstaller(apkFile: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, PROVIDER_AUTHORITY, apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            }
            context.startActivity(intent)
            Log.i(TAG, "Fallback system installer launched for ${apkFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Fallback install also failed: ${e.message}")
        }
    }

    /** Cancels any in-progress download and resets state. */
    fun cancelDownload() {
        activeDownloadId?.let { downloadManager.remove(it) }
        activeDownloadId   = null
        _downloadProgress.value = null
        _downloadedApk.value    = null
    }

    // ── Singleton ─────────────────────────────────────────────────────────────

    companion object {
        @Volatile private var INSTANCE: UpdateRepository? = null

        /** How long a committed silent install may go without a result before Install is offered again. */
        private const val INSTALL_RESULT_TIMEOUT_MS = 120_000L

        fun getInstance(context: Context): UpdateRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: UpdateRepository(context.applicationContext).also { INSTANCE = it }
            }
    }
}