package com.byd.tripstats.util

import android.content.Context
import com.byd.tripstats.adb.AdbPermissionManager
import com.byd.tripstats.sdk.DiLink5Platform

/**
 * Whether a dispatch's output is worth a write test: only on DiLink 5, only before the files have
 * moved, and only when the shell said it couldn't create a file in /data/local/tmp — the exact
 * error a Shark 6 printed on every dispatch. A head unit where /data/local/tmp works never prints
 * it, so it never gets past this.
 */
internal fun shouldTestShellStorage(isDiLink5: Boolean, relocated: Boolean, dispatchOutput: String): Boolean =
    isDiLink5 && !relocated && dispatchOutput.contains("can't create ${ShellStorage.LEGACY_DIR}/")

/**
 * Where the files the app runs as the shell user live: the background restarter's script, log and
 * pid files, the telemetry daemon's pid file, a staged in-app update, and Tailscale.
 *
 * They have always lived in /data/local/tmp, and on every head unit but one they still do. A Shark 6
 * (DiLink 5, Desay SV firmware) labels that folder `data_local` for SELinux instead of
 * `shell_data_file`, so the shell can't create anything in it although it owns it. Every dispatch
 * printed `can't create /data/local/tmp/.supd.log`, the restarter never started once in five weeks,
 * and the app couldn't come back after a switch-off. Confirmed on that car (2026-10-10): `ls -ldZ`
 * showed the label and a `touch` was denied. Overdrive documents the same wall in its
 * DaemonStorage.java.
 *
 * The shell user's own data folder carries the right label, so on such a unit everything moves to
 * [RELOCATED_DIR]. Verified on the same Shark: writing, running a script and running a binary from
 * there all work.
 *
 * The switch is deliberately narrow — see [shouldTestShellStorage]. Nothing changes until a DiLink-5
 * dispatch fails with that exact error AND a write test then shows the shell can't write
 * /data/local/tmp but can write the new folder. A unit where /data/local/tmp works never runs the
 * test and keeps every path exactly as before. The decision is kept in device-protected storage, so
 * it can be read before the user unlocks, and it is never undone: the new folder keeps working even
 * if a firmware update fixes the label.
 */
internal object ShellStorage {
    private const val TAG = "ShellStorage"

    const val LEGACY_DIR = "/data/local/tmp"
    const val RELOCATED_DIR = "/data/user_de/0/com.android.shell/bydtripstats"

    private const val PREFS = "shell_storage"
    private const val KEY_RELOCATED = "relocated"

    /**
     * One command, three answers: LEGACY-OK (the old folder works after all — change nothing),
     * RELOCATE (it doesn't, the new one does), NEITHER. Its test files are removed either way.
     */
    internal const val WRITE_TEST =
        "if touch $LEGACY_DIR/.bts_w 2>/dev/null; then rm -f $LEGACY_DIR/.bts_w; echo LEGACY-OK; " +
            "elif mkdir -p $RELOCATED_DIR 2>/dev/null && touch $RELOCATED_DIR/.bts_w 2>/dev/null; " +
            "then rm -f $RELOCATED_DIR/.bts_w; echo RELOCATE; " +
            "else echo NEITHER; fi"

    @Volatile private var relocatedCache: Boolean? = null
    @Volatile private var loggedNoMove = false

    fun isRelocated(context: Context): Boolean {
        relocatedCache?.let { return it }
        // Not cached on a failed read, so a transient failure can't pin the old folder for the
        // rest of the process.
        val value = runCatching { prefs(context).getBoolean(KEY_RELOCATED, false) }.getOrNull() ?: return false
        relocatedCache = value
        return value
    }

    /** The folder to use: [LEGACY_DIR] unless this head unit has been moved to [RELOCATED_DIR]. */
    fun dir(context: Context): String = if (isRelocated(context)) RELOCATED_DIR else LEGACY_DIR

    /**
     * Called with a dispatch's output. Returns true only when it has just moved the files, so the
     * caller can dispatch again at once; false in every other case.
     */
    suspend fun relocateIfLegacyUnwritable(context: Context, dispatchOutput: String): Boolean {
        if (!shouldTestShellStorage(DiLink5Platform.isDiLink5, isRelocated(context), dispatchOutput)) return false
        val verdict = AdbPermissionManager.runShellBatch(context, listOf(WRITE_TEST), perCommandTimeoutMs = 3_000L)
            .firstOrNull()?.output?.trim().orEmpty()
        if (verdict != "RELOCATE") {
            // Once per process: a unit where neither folder works would otherwise log this on
            // every dispatch.
            if (!loggedNoMove) {
                loggedNoMove = true
                DiagLog.event(
                    context, TAG,
                    "dispatch couldn't write $LEGACY_DIR; write test: ${verdict.ifBlank { "(no answer)" }} — not moving",
                )
            }
            return false
        }
        val saved = runCatching { prefs(context).edit().putBoolean(KEY_RELOCATED, true).commit() }.getOrDefault(false)
        if (!saved) return false
        relocatedCache = true
        DiagLog.event(context, TAG, "the shell can't write $LEGACY_DIR (SELinux) — files moved to $RELOCATED_DIR")
        return true
    }

    private fun prefs(context: Context) =
        context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
