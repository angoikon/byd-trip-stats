package com.byd.tripstats.adb

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.byd.tripstats.sdk.DiLink5Platform
import com.byd.tripstats.util.DiagLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The DiLink-5 "third-party apps have no internet over the car's 4G" gates — the verified
 * half of the four-gate model in `MD/WRITTEN_REPORT.md` §8.2 (field evidence:
 * Sealion 7, github.com/angoikon/byd-trip-stats/issues/26).
 *
 * BYD's modified Android framework puts every sideloaded app on Android netpolicy
 * `policy=9 (DIABLE_METERED)` — stock bit 1 (reject metered in background) **plus a
 * BYD-only bit 8** that rejects metered networks even in the foreground. Stock Android 11
 * has no such bit, so "4G is up but every non-BYD app has no internet" is invisible in any
 * app's code: it lives in the framework. Two further gates stack behind it: the car's SIM
 * is permanently roaming while `Settings.Global.data_roaming` reads 0, and (unverified,
 * no field evidence yet) a per-app URL whitelist firewall in front of the T-Box.
 *
 * The stock `remove restrict-background-blacklist` no-ops on policy 9 (it only matches a
 * policy of exactly 1), but `cmd netpolicy add restrict-background-whitelist <uid>`
 * **replaces the whole policy** with `POLICY_ALLOW_METERED_BACKGROUND (4)`, wiping BYD's
 * bit — verified on the car: `policy=9 rules=4 (REJECT_METERED)` → `policy=4 rules=1
 * (ALLOW_METERED)`. Roaming is a plain Settings.Global write, which our
 * WRITE_SECURE_SETTINGS grant covers in-process.
 *
 * Both are device-wide changes to the head unit's network posture, so this is strictly
 * opt-in (Settings → App → Power & background, DiLink-5 only) and re-asserted on every
 * process start — on DiLink-5 a process start *is* the wake signal (the car force-stops
 * the app at switch-off; the supervisor/boot revives it) — plus on each watchdog tick, in
 * case BYD re-applies its bit mid-session the way it re-clears the adb settings at user
 * unlock. The "before" half of every log line is the field evidence for whether BYD
 * re-applies the block after car-off cycles (WRITTEN_REPORT §10, open question).
 *
 * Never throws — every caller sits on the startup or watchdog path, where a throw is a
 * crash loop on this platform (DI5 checklist §1).
 */
object Di5MobileDataGate {
    private const val TAG = "Di5MobileDataGate"
    private const val PREFS_NAME = "d5_mobile_data_prefs"
    private const val PREF_CONSENT = "d5_mobile_data_consent_v1"

    /**
     * `POLICY_ALLOW_METERED_BACKGROUND` — the steady state the whitelist command leaves us
     * in. Anything else read back from `dumpsys netpolicy` (BYD's 9, a user-set restrict, or
     * no entry at all) means the whitelist is (re)applied; the command is idempotent.
     */
    internal const val POLICY_ALLOW_METERED_BACKGROUND = 4

    // ── Opt-in consent ─────────────────────────────────────────────────────────────────────

    /**
     * Whether the user has opted in to lifting the car's third-party 4G block for this app.
     * Never throws: this is read on the boot path, where a LOCKED_BOOT_COMPLETED start can
     * catch credential-encrypted storage still shut (same trap `isSetupComplete` guards).
     * A false read before unlock only delays the gate check to the next pass — the boot watch
     * and the watchdog both re-run it after the user unlocks.
     */
    fun hasConsent(context: Context): Boolean = runCatching {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_CONSENT, false)
    }.getOrDefault(false)

    /**
     * Record (or revoke) the opt-in. Revoking only stops the re-asserts — nothing is actively
     * put back, mirroring the hidden-API consent switch: BYD (or a reboot) resets the state
     * on its own soon enough.
     */
    fun setConsent(context: Context, granted: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_CONSENT, granted).apply()
    }

    // ── Pure decision helpers (unit-tested) ───────────────────────────────────────────────

    /** A policy other than the whitelisted allow-metered bit needs the whitelist (re)applied. */
    internal fun netPolicyNeedsWhitelist(policy: Int?): Boolean =
        policy != POLICY_ALLOW_METERED_BACKGROUND

    /** Roaming is left alone only where it is already on; -1 (row absent) counts as off. */
    internal fun shouldEnableDataRoaming(current: Int): Boolean = current != 1

    /** Any line that names a uid — the block boundary when walking a per-uid dumpsys section. */
    private val anyUidLine = Regex("(?i)\\buid[\\s=:]*\\d+\\b")

    /**
     * Extract one `field=NNN` value for [uid] from `dumpsys netpolicy` output. Tolerant of the
     * two shapes Android 11 prints (a `UID=10042:` line with indented `policy=/rules=` lines
     * below, or everything on one line), and of BYD's labels after the number
     * (`policy=9 (DIABLE_METERED)`): only the digits are taken. Walking stops at the next uid
     * line so one app's field can't be read from another's block, and a uid that never appears
     * (or appears without the field) yields null — which the caller treats as "not whitelisted".
     */
    internal fun parseUidIntField(dumpsysNetpolicy: String, uid: Int, field: String): Int? {
        val mine = Regex("(?i)\\buid[\\s=:]*$uid\\b")
        val fieldRe = Regex("(?i)\\b$field\\s*=\\s*(\\d+)")
        val lines = dumpsysNetpolicy.lines()
        var i = 0
        while (i < lines.size) {
            if (!mine.containsMatchIn(lines[i])) { i++; continue }
            var j = i
            while (j < minOf(i + 6, lines.size)) {
                val line = lines[j]
                if (j > i && anyUidLine.containsMatchIn(line)) break
                fieldRe.find(line)?.let { return it.groupValues[1].toIntOrNull() }
                j++
            }
            i++
        }
        return null
    }

    /** Our uid's netpolicy `policy=` value from `dumpsys netpolicy`, or null when absent. */
    internal fun parseUidPolicy(dumpsysNetpolicy: String, uid: Int): Int? =
        parseUidIntField(dumpsysNetpolicy, uid, "policy")

    /** Our uid's computed `rules=` value — informational (BYD's numbering differs from stock). */
    internal fun parseUidRules(dumpsysNetpolicy: String, uid: Int): Int? =
        parseUidIntField(dumpsysNetpolicy, uid, "rules")

    // ── The re-assert ──────────────────────────────────────────────────────────────────────

    /**
     * Check and (only when needed) clear both gates: the BYD netpolicy block for this app and
     * the roaming setting. A no-op returning null on every other platform, without consent, or
     * while the adb setup is missing — safe to call unconditionally from startup and the
     * watchdog. Diag.log gets the full before→after verdict on the first check per process,
     * every user action, and every *change* in between (so a 15-minute tick that finds BYD's
     * bit back says so exactly once, not ninety-six times a day).
     *
     * @return the verdict, or null when nothing was checked.
     */
    suspend fun reassert(context: Context, label: String = "startup", force: Boolean = false): String? {
        if (!DiLink5Platform.isDiLink5) return null
        // The consent flag lives in credential-encrypted storage: nothing to check before the
        // user unlocks. Skip cleanly rather than read a guaranteed false — the boot watch and
        // the watchdog re-run this after unlock.
        if (!AdbPermissionManager.isUserUnlocked(context)) return null
        if (!hasConsent(context)) return null
        // One check at a time: start-up runs it twice (early, then again once the channel is up)
        // and the watchdog can tick in between. Unserialised, two passes would both read BYD's 9
        // and both write — harmless, but the log would show the same clearing twice.
        return mutex.withLock {
            val verdict = runCatching {
                "roaming=${ensureRoamingAllowed(context)} netpolicy=${ensureNetPolicyWhitelisted(context)}"
            }.getOrElse { "error(${it.javaClass.simpleName})" }
            if (force || verdict != lastVerdict) {
                DiagLog.event(context, TAG, "4G gate[$label]: $verdict")
            }
            lastVerdict = verdict
            verdict
        }
    }

    private val mutex = Mutex()
    @Volatile private var lastVerdict: String? = null

    /**
     * Gate 2 — `Settings.Global.DATA_ROAMING`. Written in-process (WRITE_SECURE_SETTINGS is
     * part of the one-time adb setup), so it works even while the shell channel is still down —
     * the same essential property as `ensureAdbEnabled`. The car's SIM is permanently roaming,
     * so with this off Android may refuse mobile data to apps even after the netpolicy gate is
     * cleared.
     */
    private suspend fun ensureRoamingAllowed(context: Context): String = runCatching {
        if (!AdbPermissionManager.isSetupComplete(context)) return@runCatching "no-setup"
        val resolver = context.contentResolver
        val before = Settings.Global.getInt(resolver, Settings.Global.DATA_ROAMING, -1)
        if (!shouldEnableDataRoaming(before)) return@runCatching "ok"
        runCatching { Settings.Global.putInt(resolver, Settings.Global.DATA_ROAMING, 1) }
        var after = runCatching {
            Settings.Global.getInt(resolver, Settings.Global.DATA_ROAMING, -1)
        }.getOrDefault(-1)
        if (after != 1) {
            // In-process write refused (revoked grant, ROM enforcement) — the shell can still
            // do it: `settings put global` as uid 2000 is exactly what the field report used.
            shell(context, "settings put global data_roaming 1")?.let {
                after = runCatching {
                    Settings.Global.getInt(resolver, Settings.Global.DATA_ROAMING, -1)
                }.getOrDefault(-1)
            }
        }
        if (after == 1) "$before→1" else "FAILED(before=$before after=$after)"
    }.getOrElse { "denied(${it.javaClass.simpleName})" }

    /**
     * Gate 1 — the BYD netpolicy block. Read our uid's policy first (so an already-cleared
     * unit costs one `dumpsys` and no write, and the "before" value answers the re-application
     * question), and only when it isn't the allow-metered whitelist, replace it:
     * `add restrict-background-whitelist` swaps the whole policy for bit 4 — the one command
     * that wipes BYD's custom bit (see class doc). Verified by re-reading afterwards; a
     * command that didn't take is reported, never retried within this call.
     */
    private suspend fun ensureNetPolicyWhitelisted(context: Context): String = runCatching {
        val uid = android.os.Process.myUid()
        val first = shell(context, "dumpsys netpolicy") ?: return@runCatching "channel"
        if (first.exitCode != 0 || first.output.isBlank()) {
            return@runCatching "dumpsys-failed(exit=${first.exitCode})"
        }
        val beforePolicy = parseUidPolicy(first.output, uid)
        if (!netPolicyNeedsWhitelist(beforePolicy)) {
            return@runCatching "policy=${beforePolicy} rules=${parseUidRules(first.output, uid)} ok"
        }
        val before = "policy=${beforePolicy ?: "absent"} rules=${parseUidRules(first.output, uid)}"
        val added = shell(context, "cmd netpolicy add restrict-background-whitelist $uid")
            ?: return@runCatching "channel(after-dump)"
        val after = shell(context, "dumpsys netpolicy")
            ?.let { parseUidPolicy(it.output, uid) }
        if (after == POLICY_ALLOW_METERED_BACKGROUND) "$before→policy=$after cleared"
        else "$before→policy=${after ?: "?"} FAILED exit=${added.exitCode} " +
            ":: ${added.output.replace('\n', ' ').take(60)}"
    }.getOrElse { "error(${it.javaClass.simpleName})" }

    /**
     * One shell command over the loopback channel; null when the channel can't be used.
     * Hard timeout: this runs on the startup and watchdog paths, and a `dadb.shell` read that
     * never returns would otherwise hold the watchdog for ever (the Tailscale Connect hang).
     */
    private suspend fun shell(context: Context, command: String): AdbPermissionManager.ShellResult? =
        AdbPermissionManager.runShellBatch(
            context, listOf(command), perCommandTimeoutMs = 5_000L, hardTimeout = true,
        )
            .firstOrNull()
            .also { if (it == null) Log.i(TAG, "channel unavailable for: ${command.take(40)}") }
}
