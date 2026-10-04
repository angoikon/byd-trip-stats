package com.byd.tripstats.adb

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.StringRes
import com.byd.tripstats.R
import com.byd.tripstats.runtimebridge.RuntimeExtensionBridge
import com.byd.tripstats.util.DiagLog
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.Socket

/**
 * Whether adb key expiry needs switching off: only where it exists (Android 11, API 30, and later)
 * and only when it isn't off already, so a unit that has it at 0 is never written to again.
 */
internal fun shouldDisableAdbKeyExpiry(sdkInt: Int, currentMs: Long): Boolean =
    sdkInt >= 30 && currentMs != 0L

/**
 * Optional local permission helper for DiLink builds that expose a user-approved
 * debugging channel. The key pair is persistent once the user authorizes it.
 */
object AdbPermissionManager {

    private const val TAG = "AdbPermissionManager"
    private const val ADB_HOST = "127.0.0.1"
    private const val ADB_PORT = 5555
    private const val KEY_FILE = "adbkey"
    private const val KEY_PUB_FILE = "adbkey.pub"
    private const val PREFS_NAME = "adb_permission_prefs"
    private const val PREF_PERMISSIONS_GRANTED = "permissions_granted_v1"
    // Opt-in consent for the DiLink-5 hidden-API exemption. This is a GLOBAL device setting, so we
    // only touch it after the user explicitly agrees (see MainActivity consent dialog / Settings).
    private const val PREF_HIDDEN_API_CONSENT = "d5_hidden_api_consent_v1"
    // Whether we've already shown the one-time consent prompt (so a decline doesn't nag every launch;
    // the user can still opt in later from Settings).
    private const val PREF_HIDDEN_API_PROMPTED = "d5_hidden_api_prompted_v1"
    // The token we write into hidden_api_blacklist_exemptions; used to detect "already applied" so we
    // don't silently re-write the global setting on every launch (only re-apply when missing/reset).
    private val EXEMPTION_TOKENS = listOf("Lcom/ts/", "Ldalvik/system/")

    // The two Settings.Global keys BYD clears at shutdown on DiLink 5. `adb_wifi_enabled` is the
    // operative one — it drives the wireless-debugging TCP listener that dadb reaches on
    // 127.0.0.1:5555. Loopback, so no Wi-Fi network is needed; this works with the radio off.
    // (That also corrects an earlier reading of ours: we wrote Android-11 wireless debugging off
    // because this key read 0 and the ROM has no pairing UI — but it reads 0 simply because nothing
    // had set it, and loopback needs no pairing.)
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    private const val ADB_ENABLED = "adb_enabled"
    private const val ADB_ALLOWED_CONNECTION_TIME = "adb_allowed_connection_time"

    // Permissions that require elevated user-approved grant flow.
    private val REQUIRED_PERMISSIONS = listOf(
        "android.permission.WRITE_SECURE_SETTINGS",
        "android.permission.READ_LOGS",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
    )

    // Ordinary runtime permissions the user can also grant from Android Settings, taken here as a
    // convenience while the shell is open. Deliberately NOT in REQUIRED_PERMISSIONS: that list is
    // also the "is setup complete?" test, so adding to it would make every existing install look
    // un-set-up until it ran setup again.
    //
    // WRITE_EXTERNAL_STORAGE is what puts the app in the sdcard_rw group. Without it, shared
    // storage lists fine and every delete fails, because READ is granted separately — which is
    // exactly what the web companion's Files tab ran into.
    private val BEST_EFFORT_PERMISSIONS = listOf(
        "android.permission.WRITE_EXTERNAL_STORAGE",
    )

    // ── DiLink-5 vehicle-API access ──────────────────────────────────────────────
    // On DiLink-5 (Sealion 7) the OEM bydauto SDK calls a hidden platform member
    // (com.ts.lib.caradapter.CarAdapterManager.getInstance(Context)) that hidden-API
    // enforcement blocks for a non-system app → NoSuchMethodError → all telemetry reads 0.
    // Exempting hidden APIs lets every bydauto device bind. This is a global setting that
    // can be re-initialised on reboot, so we re-assert it (idempotently) on every startup
    // via the same dadb channel. Single-quote the '*' so the device shell doesn't glob it.
    private val VEHICLE_API_SETTINGS = listOf(
        // Narrowed 2026-07-12 (on-car): exempting only the OEM SDK namespace `Lcom/ts/` is
        // sufficient — telemetry binds identically to the old device-wide `'*'`. Verified after a
        // full reboot: enforcement resets to default (getInstance blacklisted → denied), and this
        // narrow list restores binding with 0 denials for both trip-stats and byd-probe. All the
        // blocked hidden members live under com.ts.* (CarAdapterManager.getInstance,
        // CarPowerManager.getInstance, OtaSdkManager, ota listener stubs). `'*'` was overkill.
        "settings put global hidden_api_policy 1",
        // `Ldalvik/system/` added for the SDK-injection prototype (BaseDexClassLoader.pathList /
        // DexPathList.makePathElements are hidden). Drop it if injection is abandoned.
        "settings put global hidden_api_blacklist_exemptions 'Lcom/ts/,Ldalvik/system/'",
    )

    // The runtime-gated bydauto permissions (getInstance enforces *_COMMON server-side).
    // Granted via `pm grant` over the dadb shell (shell uid). Undeclared/ungrantable ones
    // fail harmlessly and are logged.
    private val BYDAUTO_COMMON_PERMISSIONS = listOf(
        "android.permission.BYDAUTO_STATISTIC_COMMON",
        "android.permission.BYDAUTO_CHARGING_COMMON",
        "android.permission.BYDAUTO_SPEED_COMMON",
        "android.permission.BYDAUTO_VEHICLEHEALTH_COMMON",
        "android.permission.BYDAUTO_MOTOR_COMMON",
        "android.permission.BYDAUTO_INSTRUMENT_COMMON",
        "android.permission.BYDAUTO_TYRE_COMMON",
        "android.permission.BYDAUTO_AC_COMMON",
        "android.permission.BYDAUTO_OTA_COMMON",   // getTBoxSerialNumber (license device id) is COMMON-gated
        "android.permission.BYDAUTO_SETTING_COMMON",   // setting.getEnergyFeedback() (regen mode) is COMMON-gated
        // Compat-probe-only devices below — confirmed dead on the dev car,
        // granted anyway so the probe can surface whether they're real on other vehicles.
        "android.permission.BYDAUTO_ENERGY_COMMON",
        "android.permission.BYDAUTO_SENSOR_COMMON",
        "android.permission.BYDAUTO_PM2P5_COMMON",
    )

    sealed class SetupState {
        object Idle         : SetupState()
        object Connecting   : SetupState()
        object WaitingAuth  : SetupState()
        object Granting     : SetupState()
        object Done         : SetupState()
        /** [reasonRes] may take one `%1$s` arg, filled from [detail] (an exception message). */
        data class Failed(@StringRes val reasonRes: Int, val detail: String = "") : SetupState()
    }

    data class ShellResult(
        val exitCode: Int,
        val output: String
    )

    private val _state = MutableStateFlow<SetupState>(SetupState.Idle)
    val state: StateFlow<SetupState> = _state.asStateFlow()

    /**
     * Restart the app's own process. The DiLink-5 hidden-API exemption is captured at process fork,
     * so the bydauto SDK only binds after a fresh start — call this once the exemption is applied
     * (e.g. right after the user grants consent) so telemetry starts without a manual force-stop.
     * Schedules a relaunch of the launcher activity a moment out, then hard-exits.
     */
    fun restartApp(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        } ?: return
        val pending = android.app.PendingIntent.getActivity(
            context, 0, launch,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_CANCEL_CURRENT
        )
        context.getSystemService(android.app.AlarmManager::class.java)
            ?.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 400L, pending)
        Runtime.getRuntime().exit(0)
    }

    // ── Hidden-API exemption consent (opt-in) ────────────────────────────────────
    /** Whether the user has agreed to let us relax the head-unit's global hidden-API setting. */
    fun hasHiddenApiConsent(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_HIDDEN_API_CONSENT, false)

    /** Record (or revoke) that consent. Enabling later re-applies the exemption on the next ensure. */
    fun setHiddenApiConsent(context: Context, granted: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_HIDDEN_API_CONSENT, granted).apply()
    }

    /** True once the one-time consent prompt has been shown (regardless of the answer). */
    fun hasBeenPromptedForHiddenApi(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_HIDDEN_API_PROMPTED, false)

    fun markHiddenApiPrompted(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_HIDDEN_API_PROMPTED, true).apply()
    }

    /**
     * Re-assert Android's `adb_enabled` setting, which BYD switches **off** at shutdown on
     * DiLink 5 — the single reason the local channel is dead after every cold boot.
     *
     * Confirmed on a Sealion 7 (2026-09-18): at `sinceBoot=18s` the app read `adbEnabled=0` with
     * `sock=closed`, and across every sample in that unit's log the two track exactly — `0` always
     * with a closed port, `1` always with an open one. Decompiling BYD's own engineering screen
     * says the same thing from the other side: the button the owner presses to turn wireless adb
     * back on does nothing but `Settings.Global.putInt("adb_enabled", 1)` (`AdbUtil.a()`), so this
     * setting *is* the gate on this platform — not the `persist.sys.adb.wiress.*` properties, which
     * read empty there and are SELinux-denied to us anyway.
     *
     * We can write it because [REQUIRED_PERMISSIONS] already includes `WRITE_SECURE_SETTINGS` and
     * [isSetupComplete] verifies it is really held — so this needs **no shell at the moment it
     * matters**, which is the whole point: the channel can't be used to turn the channel on.
     *
     * No-op where it's already 1 (DiLink 3 keeps it set), so this costs one settings read there.
     *
     * @return a short status for the log: `already-on`, `turned-on`, `denied`, `no-setup`, or an
     *         error tag. Never throws — a revoked permission surfaces as `denied`, not a crash.
     */
    fun ensureAdbEnabled(context: Context): String = runCatching {
        val resolver = context.contentResolver
        // Without the grant the writes throw; check first so the common case logs a clear reason.
        if (!isSetupComplete(context)) return@runCatching "no-setup"

        val wifiBefore = Settings.Global.getInt(resolver, ADB_WIFI_ENABLED, -1)
        val adbBefore = Settings.Global.getInt(resolver, ADB_ENABLED, -1)
        // Wireless knob FIRST: it is the one that drives the loopback TCP listener, and writing it
        // is what actually reopens the port. `adb_enabled` alone is necessary, not sufficient.
        if (wifiBefore != 1) Settings.Global.putInt(resolver, ADB_WIFI_ENABLED, 1)
        if (adbBefore != 1) Settings.Global.putInt(resolver, ADB_ENABLED, 1)
        // Read both back rather than trusting the writes: this is the claim the whole fix rests on,
        // and a silently-ignored write would otherwise look identical to a working one.
        val wifiAfter = Settings.Global.getInt(resolver, ADB_WIFI_ENABLED, -1)
        val adbAfter = Settings.Global.getInt(resolver, ADB_ENABLED, -1)
        val verdict = if (wifiAfter == 1 && adbAfter == 1) "ok" else "FAILED"
        "$verdict wifi=$wifiBefore→$wifiAfter adb=$adbBefore→$adbAfter"
    }.getOrElse { "denied(${it.javaClass.simpleName})" }

    /**
     * Stop Android from revoking our adb authorisation after a week unused.
     *
     * From Android 11 an adb key that hasn't connected for `adb_allowed_connection_time` — seven
     * days by default — is silently revoked. The app reconnects on every start, so this only bites
     * a car left parked for longer than that: it would come back with a channel that refuses us,
     * and the one-time adb setup would have to be redone at the car. `0` turns expiry off; it is the
     * same switch as the developer option "Disable adb authorization timeout". Overdrive (MIT) sets
     * the same value alongside its own adb restore.
     *
     * Not gated on the port being shut, unlike [ensureAdbEnabled]: this protects the authorisation,
     * not the listener, so a healthy channel needs it just as much. Android 11+ only, so DiLink 3
     * (Android 10, which has no key expiry) is never written to.
     *
     * @return null when there was nothing to do — already 0, older Android, or no grant — so the
     *         caller logs only an actual change; otherwise `off (was N)`, `FAILED …` or `denied(…)`.
     *         Never throws.
     */
    fun ensureAdbKeyNeverExpires(context: Context): String? = runCatching {
        if (!isSetupComplete(context)) return@runCatching null
        val resolver = context.contentResolver
        val before = Settings.Global.getLong(resolver, ADB_ALLOWED_CONNECTION_TIME, -1L)
        if (!shouldDisableAdbKeyExpiry(Build.VERSION.SDK_INT, before)) return@runCatching null
        Settings.Global.putLong(resolver, ADB_ALLOWED_CONNECTION_TIME, 0L)
        val after = Settings.Global.getLong(resolver, ADB_ALLOWED_CONNECTION_TIME, -1L)
        if (after == 0L) "off (was $before)" else "FAILED (was $before, now $after)"
    }.getOrElse { "denied(${it.javaClass.simpleName})" }

    /**
     * True if all required permissions are already granted — skip setup entirely.
     *
     * Must not throw before the user is unlocked. Some DiLink-5 boots start the app via
     * LOCKED_BOOT_COMPLETED, while credential-encrypted storage — where ordinary SharedPreferences
     * live — is still shut, and reading the flag then throws `IllegalStateException`. That throw used
     * to abort the whole boot-time channel watch silently, so adb was never re-asserted and no
     * background restarter existed for the rest of the day (2026-10-02). The granted permissions are
     * the real answer anyway, and PackageManager can be asked in that state, so fall through to it.
     */
    fun isSetupComplete(context: Context): Boolean {
        val flagged = runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_PERMISSIONS_GRANTED, false)
        }.getOrDefault(false)
        if (flagged) return true
        // Double-check at runtime in case permissions were revoked
        return checkPermissionsGranted(context)
    }

    /**
     * False only in the seconds after a cold boot that started the app via LOCKED_BOOT_COMPLETED,
     * before credential-encrypted storage opens. The adb key lives there, so nothing that needs the
     * channel can run until this is true. The adb *settings* are device-protected and can be written
     * before it — which is what lets the port be reopened early.
     */
    fun isUserUnlocked(context: Context): Boolean =
        runCatching { context.getSystemService(UserManager::class.java)?.isUserUnlocked }.getOrNull() ?: true

    /** True when BYD (or anyone) has switched either adb setting off — see [ensureAdbEnabled]. */
    fun adbSettingsCleared(context: Context): Boolean = runCatching {
        val resolver = context.contentResolver
        Settings.Global.getInt(resolver, ADB_WIFI_ENABLED, -1) != 1 ||
            Settings.Global.getInt(resolver, ADB_ENABLED, -1) != 1
    }.getOrDefault(false)

    /** Check via dumpsys whether our permissions are actually granted. */
    fun checkPermissionsGranted(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            REQUIRED_PERMISSIONS.all { perm ->
                pm.checkPermission(perm, context.packageName) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Run the full setup flow. Call from a coroutine.
     * Returns true if permissions are now granted.
     *
     * Safe to call multiple times — returns immediately if already done.
     */
    suspend fun runSetup(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isSetupComplete(context)) {
            _state.value = SetupState.Done
            Log.i(TAG, "Setup already complete")
            return@withContext true
        }

        if (_state.value == SetupState.Connecting ||
            _state.value == SetupState.WaitingAuth ||
            _state.value == SetupState.Granting) {
            Log.d(TAG, "Setup already in progress")
            return@withContext false
        }

        _state.value = SetupState.Connecting

        try {
            // Check ADB port is open (adbd running)
            if (!isPortOpen()) {
                _state.value = SetupState.Failed(R.string.adb_fail_not_enabled)
                return@withContext false
            }

            val keyPair = getOrCreateKeyPair(context)
            Log.i(TAG, "Attempting ADB connection to $ADB_HOST:$ADB_PORT")

            // Try quick connect first (already authorized from previous run)
            val dadb = tryConnect(keyPair, timeoutMs = 2_000)
            if (dadb != null) {
                return@withContext grantPermissionsAndClose(dadb, context)
            }

            // Not yet authorized — show waiting state and poll
            _state.value = SetupState.WaitingAuth
            Log.i(TAG, "Waiting for ADB authorization in car UI (max 3 min)...")

            val maxAttempts = 60  // 3 minutes at 3s intervals
            repeat(maxAttempts) { attempt ->
                delay(3_000)
                if (_state.value != SetupState.WaitingAuth) return@withContext false

                Log.d(TAG, "Auth poll ${attempt + 1}/$maxAttempts")
                val d = tryConnect(keyPair, timeoutMs = 2_000)
                if (d != null) {
                    return@withContext grantPermissionsAndClose(d, context)
                }
            }

            _state.value = SetupState.Failed(R.string.adb_fail_auth_timeout)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Setup failed: ${e.message}", e)
            _state.value = SetupState.Failed(R.string.adb_fail_connection, e.message ?: e.javaClass.simpleName)
            false
        }
    }

    suspend fun runShellCommand(context: Context, command: String): ShellResult = withContext(Dispatchers.IO) {
        val safeCommand = command.trim()
        if (safeCommand.isBlank()) return@withContext ShellResult(-1, "No command entered")

        if (!isPortOpen()) {
            return@withContext ShellResult(-1, "Local permission channel is not reachable")
        }

        val keyPair = getOrCreateKeyPair(context)
        val dadb = tryConnect(keyPair, timeoutMs = 2_000)
            ?: return@withContext ShellResult(-1, "ADB is not authorized yet")

        try {
            val result = dadb.shell(safeCommand)
            ShellResult(result.exitCode, result.allOutput.trim())
        } catch (e: Exception) {
            ShellResult(-1, "Command failed: ${e.message}")
        } finally {
            runCatching { dadb.close() }
        }
    }

    /**
     * Run a batch of commands through a single authenticated dadb session.
     * Each command is bounded by [perCommandTimeoutMs]; a hang does not block
     * the rest of the batch. Returns results in order. Empty list on auth/port
     * failure so callers can proceed silently.
     */
    suspend fun runShellBatch(
        context: Context,
        commands: List<String>,
        perCommandTimeoutMs: Long = 5_000L,
    ): List<ShellResult> = withContext(Dispatchers.IO) {
        if (commands.isEmpty()) return@withContext emptyList()
        if (!isPortOpen()) return@withContext emptyList()
        // Same "channel not usable yet" outcome as a shut port: the key can't be read before unlock.
        if (!isUserUnlocked(context)) return@withContext emptyList()

        val keyPair = getOrCreateKeyPair(context)
        val dadb = tryConnect(keyPair, timeoutMs = 2_000) ?: return@withContext emptyList()

        val out = ArrayList<ShellResult>(commands.size)
        try {
            for (cmd in commands) {
                val trimmed = cmd.trim()
                if (trimmed.isBlank()) { out += ShellResult(-1, ""); continue }
                val result = withTimeoutOrNull(perCommandTimeoutMs) {
                    try {
                        val r = dadb.shell(trimmed)
                        ShellResult(r.exitCode, r.allOutput.trim())
                    } catch (e: Exception) {
                        ShellResult(-1, "Command failed: ${e.message}")
                    }
                } ?: ShellResult(-1, "timeout")
                out += result
            }
        } finally {
            runCatching { dadb.close() }
        }
        out
    }

    /**
     * Apply the DiLink-5 vehicle-API access tweaks over an already-open dadb session:
     * exempt hidden APIs (so the bydauto SDK can bind) and grant the bydauto *_COMMON perms.
     * Best-effort — individual failures are logged, never fatal.
     */
    private fun applyVehicleApiAccess(dadb: Dadb, pkg: String, hiddenApiConsent: Boolean) {
        // bydauto *_COMMON grants only affect OUR app — always safe, no consent needed.
        BYDAUTO_COMMON_PERMISSIONS.forEach { perm ->
            runCatching {
                val r = dadb.shell("pm grant $pkg $perm")
                val ok = r.exitCode == 0 || r.allOutput.contains("Success", ignoreCase = true)
                if (!ok && r.allOutput.isNotBlank()) Log.d(TAG, "grant $perm: ${r.allOutput.trim()}")
            }
        }
        // The hidden-API exemption is a GLOBAL device setting → only with explicit consent, and only
        // when actually missing/reset (avoids a silent re-write on every launch — PR #8 item 2b).
        if (hiddenApiConsent) applyHiddenApiExemptionIfNeeded(dadb)
        else Log.i(TAG, "hidden-api exemption skipped (no consent)")
    }

    /** Apply the hidden-API exemption only if it isn't already in effect (reboot resets it). */
    private fun applyHiddenApiExemptionIfNeeded(dadb: Dadb) {
        val current = runCatching { dadb.shell("settings get global hidden_api_blacklist_exemptions").allOutput.trim() }
            .getOrNull()
        if (current != null && EXEMPTION_TOKENS.all { current.contains(it) }) {
            Log.i(TAG, "hidden-api exemption already set ('$current') — not re-asserting")
            return
        }
        VEHICLE_API_SETTINGS.forEach { cmd ->
            runCatching {
                val r = dadb.shell(cmd)
                Log.i(TAG, "vehicle-api: $cmd -> exit ${r.exitCode}")
            }.onFailure { Log.w(TAG, "vehicle-api '$cmd' failed: ${it.message}") }
        }
    }

    /**
     * Idempotently ensure DiLink-5 vehicle-API access (hidden-API exemption + bydauto grants).
     * Safe to call on every app startup: connects via dadb only if adb is already authorised,
     * otherwise silently no-ops (the full [runSetup] flow handles first-time authorisation).
     * Returns true if the commands were applied.
     */
    suspend fun ensureVehicleApiAccess(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!isPortOpen()) return@withContext false
        val keyPair = getOrCreateKeyPair(context)
        val dadb = tryConnect(keyPair, timeoutMs = 2_000) ?: return@withContext false
        try {
            applyVehicleApiAccess(dadb, context.packageName, hasHiddenApiConsent(context))
            Log.i(TAG, "✅ DiLink-5 vehicle-API access ensured")
            true
        } catch (e: Exception) {
            Log.w(TAG, "ensureVehicleApiAccess failed: ${e.message}")
            false
        } finally {
            runCatching { dadb.close() }
        }
    }

    private suspend fun grantPermissionsAndClose(dadb: Dadb, context: Context): Boolean {
        return try {
            _state.value = SetupState.Granting
            val pkg = context.packageName
            var allGranted = true

            REQUIRED_PERMISSIONS.forEach { perm ->
                val result = dadb.shell("pm grant $pkg $perm")
                val ok = result.exitCode == 0 || result.allOutput.contains("Success", ignoreCase = true)
                Log.i(TAG, "grant $perm: ${if (ok) "✅" else "❌"} (${result.allOutput.trim()})")
                if (!ok) allGranted = false
            }

            // Best-effort: a failure here never fails setup, since the user can grant these from
            // Android Settings and nothing core depends on them.
            BEST_EFFORT_PERMISSIONS.forEach { perm ->
                runCatching {
                    val result = dadb.shell("pm grant $pkg $perm")
                    val ok = result.exitCode == 0 || result.allOutput.contains("Success", ignoreCase = true)
                    Log.i(TAG, "grant (best-effort) $perm: ${if (ok) "✅" else "❌"} (${result.allOutput.trim()})")
                }
            }

            RuntimeExtensionBridge.stringList("s01", context.packageName).forEach { command ->
                dadb.shell(command)
            }

            // DiLink-5: grant bydauto *_COMMON (always) + exempt hidden APIs (only if the user
            // consented) so telemetry binds. Consent is captured before setup runs (MainActivity).
            applyVehicleApiAccess(dadb, pkg, hasHiddenApiConsent(context))

            dadb.close()

            if (allGranted) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(PREF_PERMISSIONS_GRANTED, true).apply()
                _state.value = SetupState.Done
                Log.i(TAG, "✅ All permissions granted via ADB")
                true
            } else {
                _state.value = SetupState.Failed(R.string.adb_fail_partial_grant)
                false
            }
        } catch (e: Exception) {
            runCatching { dadb.close() }
            _state.value = SetupState.Failed(R.string.adb_fail_grant, e.message ?: e.javaClass.simpleName)
            false
        }
    }

    /**
     * Install [apkFile] over the local adb channel, as the shell user (uid 2000).
     *
     * Why this exists: on head units newer than DiLink-3 the app is an unprivileged
     * `untrusted_app` and the silent `PackageInstaller` session can't commit, while the system
     * installer dialog the fallback relies on isn't reachable either — so in-app updates were
     * turned off there entirely. uid 2000 *is* a privileged installer, so `pm install -r -g`
     * needs neither `REQUEST_INSTALL_PACKAGES` nor any UI, and sidesteps both problems.
     *
     * The APK is **pushed** rather than installed from the app's own external-files dir: uid 2000
     * cannot be relied on to read an app-private scoped-storage path, whereas /data/local/tmp is
     * shell-owned. The app reads its own file and streams it over the channel it already holds.
     *
     * Verified on a Sealion 7 (2026-09-12): push + `pm install -r -g` on top of a *running*
     * same-key build returns Success, the old process dies, and `MY_PACKAGE_REPLACED` restarts
     * the telemetry service on its own. No boot loop — the OS performs the kill and the restart,
     * so this is not the self-kill+relaunch pattern that boot-loops DiLink-5.
     *
     * Every outcome is written to `diag.log` (`update install: …`), including `pm`'s own words on a
     * failure: release builds strip `Log`, and a failed DiLink-5 update (2026-10-04) left no reason.
     *
     * @return a [ShellResult] whose exitCode is 0 only when `pm` actually reported Success.
     */
    suspend fun installApkViaShell(context: Context, apkFile: File): ShellResult =
        withContext(Dispatchers.IO) {
            fun outcome(result: ShellResult): ShellResult {
                DiagLog.event(
                    context, TAG,
                    "update install: ${if (result.exitCode == 0) "ok" else "FAILED"} " +
                        "exit=${result.exitCode} :: ${result.output.replace('\n', ' ').take(200)}",
                )
                return result
            }
            if (!apkFile.exists() || apkFile.length() == 0L) {
                return@withContext outcome(ShellResult(-1, "APK missing or empty: ${apkFile.name}"))
            }
            if (!isPortOpen()) return@withContext outcome(ShellResult(-1, "Local permission channel is not reachable"))

            val keyPair = getOrCreateKeyPair(context)
            val dadb = tryConnect(keyPair, timeoutMs = 4_000)
                ?: return@withContext outcome(ShellResult(-1, "ADB is not authorized yet"))

            // A name of its own per attempt, so no other attempt can delete or overwrite the file
            // this one is installing from. Two at once (two taps on Install) used to share one name:
            // each deleted the other's copy and both failed. Distinctive prefix, so anything matching
            // it is always ours to clean up, never a user's file.
            val remote = "/data/local/tmp/.bydts-update-${System.currentTimeMillis()}.apk"
            var prepared = false
            try {
                // Leftovers: a successful install kills this process before its own clean-up runs.
                // Only copies older than 30 minutes, and the pre-2.17.1 fixed name.
                runCatching {
                    dadb.shell(
                        "rm -f /data/local/tmp/.bydts-update.apk; " +
                            "find /data/local/tmp -maxdepth 1 -name '.bydts-update-*.apk' -mmin +30 -delete",
                    )
                }
                dadb.push(apkFile, remote)

                // The install force-kills this process with no onDestroy, which would strand BYD SDK
                // listener registrations and wedge the SDK for the freshly-installed app. Release
                // them while still alive — same guard the PackageInstaller path uses.
                runCatching { com.byd.tripstats.service.VehicleTelemetryService.prepareForUpdate() }
                prepared = true
                try { Thread.sleep(600) } catch (_: InterruptedException) {}

                // -r replace in place, -g grant declared runtime permissions (matches `pm install -g`,
                // i.e. what the adb setup script does) so location/storage survive the update.
                val r = dadb.shell("pm install -r -g $remote")
                val out = r.allOutput.trim()
                val ok = out.contains("Success", ignoreCase = true)
                runCatching { dadb.shell("rm -f $remote") }
                Log.i(TAG, "shell install: ok=$ok exit=${r.exitCode} :: ${out.take(160)}")
                // Still running after a failure: put back what prepareForUpdate released.
                if (!ok) runCatching { com.byd.tripstats.service.VehicleTelemetryService.resumeAfterFailedUpdate() }
                outcome(ShellResult(if (ok) 0 else (r.exitCode.takeIf { it != 0 } ?: -1), out))
            } catch (e: Exception) {
                runCatching { dadb.shell("rm -f $remote") }
                if (prepared) runCatching { com.byd.tripstats.service.VehicleTelemetryService.resumeAfterFailedUpdate() }
                outcome(ShellResult(-1, "Install failed: ${e.javaClass.simpleName}: ${e.message}"))
            } finally {
                runCatching { dadb.close() }
            }
        }

    /** Non-throwing connect attempt. Returns null on timeout/auth-pending. */
    private fun tryConnect(keyPair: AdbKeyPair, timeoutMs: Long): Dadb? {
        var result: Dadb? = null
        val thread = Thread {
            try {
                val d = Dadb.create(ADB_HOST, ADB_PORT, keyPair)
                val test = d.shell("echo ok")
                if (test.exitCode == 0) result = d else d.close()
            } catch (_: Exception) {}
        }
        thread.start()
        thread.join(timeoutMs)
        if (thread.isAlive) thread.interrupt()
        return result
    }

    private fun isPortOpen(): Boolean = try {
        Socket(ADB_HOST, ADB_PORT).use { true }
    } catch (_: Exception) { false }

    private fun getOrCreateKeyPair(context: Context): AdbKeyPair {
        // Before unlock the key files can't be seen, so `exists()` reads false and the code below
        // would try to generate a replacement — refused today only because locked storage can't be
        // written. A replaced key would revoke the car's adb authorisation outright, so refuse here,
        // explicitly, before either file is touched.
        check(isUserUnlocked(context)) { "user locked — adb key not readable yet" }
        val privateKey = File(context.filesDir, KEY_FILE)
        val publicKey  = File(context.filesDir, KEY_PUB_FILE)
        if (privateKey.exists() && publicKey.exists()) {
            runCatching { return AdbKeyPair.read(privateKey, publicKey) }
        }
        Log.i(TAG, "Generating new ADB key pair")
        AdbKeyPair.generate(privateKey, publicKey)
        return AdbKeyPair.read(privateKey, publicKey)
    }
}
