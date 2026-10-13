package com.byd.tripstats.adb

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.byd.tripstats.sdk.DiLink5Platform
import com.byd.tripstats.util.DiagLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The DiLink-5 "third-party apps have no internet over the car's 4G" block — gate 1 of the
 * four-gate model in `MD/WRITTEN_REPORT.md` §8.2 (field evidence:
 * github.com/angoikon/byd-trip-stats/issues/26).
 *
 * On some DiLink-5 cars BYD's modified Android framework puts sideloaded apps on Android
 * netpolicy `policy=9 (DIABLE_METERED)` — stock bit 1 (reject metered in background) **plus a
 * BYD-only bit 8** that rejects metered networks even in the foreground. Stock Android 11 has no
 * such bit, so "4G is up but this app has no internet" is invisible in any app's code: it lives
 * in the framework. Seen on a Sealion 7 (#26) and a Shark 6; absent on a Sealion 7 in Australia
 * (no policy row, rules=0, MQTT publishing over 4G) and one in Turkey — so it is regional or
 * firmware, not DiLink-5-wide.
 *
 * The stock `remove restrict-background-blacklist` no-ops on policy 9 (it only matches a
 * policy of exactly 1), but `cmd netpolicy add restrict-background-whitelist <uid>`
 * **replaces the whole policy** with `POLICY_ALLOW_METERED_BACKGROUND (4)`, wiping BYD's
 * bit — verified on the car: `policy=9 rules=4 (REJECT_METERED)` → `policy=4 rules=1
 * (ALLOW_METERED)`.
 *
 * Data roaming is NOT a gate, though early betas also switched it on. The car's SIM always
 * reports roaming, yet with `data_roaming=0` the T-Box network is up and validated, and on the
 * unblocked Australian car this app publishes MQTT over it (2026-10-13). Where a beta saved the
 * value it replaced, the next check puts it back once ([undoBetaRoaming]).
 *
 * Always on, with no switch (DiLink-5 only): the whitelist covers this app alone, it is written
 * only where BYD's bit is actually present, and every byte it lets through belongs to a feature the
 * user switched on — MQTT, ABRP, Telegram, update checks. A switch could not have undone it anyway:
 * Android has no command that restores BYD's policy 9 — `remove restrict-background-whitelist`
 * resets to policy 0, no restriction at all, and the stock background restriction (policy 1)
 * doesn't stop a foreground service, which this app runs. Pre-2.18.0 betas had an on/off switch;
 * its stored value (`d5_mobile_data_consent_v1`) is no longer read.
 *
 * The car does not put its block back by itself (a Shark 6 kept the whitelist through four days
 * of nightly restarts), but the check still runs at every process start — on DiLink-5 a process
 * start *is* the wake signal — and on each watchdog tick, since other firmwares may differ; on a
 * car already cleared, or never blocked, it costs one `dumpsys` and writes nothing.
 *
 * Never throws — every caller sits on the startup or watchdog path, where a throw is a
 * crash loop on this platform (DI5 checklist §1).
 */
object Di5MobileDataGate {
    private const val TAG = "Di5MobileDataGate"
    private const val PREFS_NAME = "d5_mobile_data_prefs"

    /** Whether the last check found the block lifted — what Settings → App's "4G unlocked" shows. */
    private const val PREF_UNBLOCKED = "d5_mobile_data_unblocked_v1"

    /**
     * `data_roaming` as it was before a pre-2.18.0 beta switched it on — present only where one of
     * those betas ran, and removed once [undoBetaRoaming] has put the value back.
     */
    private const val PREF_ROAMING_BEFORE = "d5_roaming_before_v1"

    /**
     * `POLICY_ALLOW_METERED_BACKGROUND` — the steady state the whitelist command leaves us in.
     */
    internal const val POLICY_ALLOW_METERED_BACKGROUND = 4

    /** BYD's own policy bit (`DIABLE_METERED` = 1 | 8): rejects metered networks even in the foreground. */
    internal const val POLICY_BYD_REJECT_METERED = 8

    // ── Status ─────────────────────────────────────────────────────────────────────────────

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Whether the last check found this app's block lifted: BYD's bit had been there and the
     * whitelist now holds (policy 4). False on a car that never had the block, so Settings → App
     * says "4G unlocked" only where the app actually changed something. Never throws.
     */
    fun isUnblocked(context: Context): Boolean = runCatching {
        prefs(context).getBoolean(PREF_UNBLOCKED, false)
    }.getOrDefault(false)

    private fun setUnblocked(context: Context, unblocked: Boolean) {
        runCatching { prefs(context).edit().putBoolean(PREF_UNBLOCKED, unblocked).apply() }
    }

    // ── Pure decision helpers (unit-tested) ───────────────────────────────────────────────

    /**
     * Only BYD's block is lifted: a policy carrying its bit 8. No policy row (an unblocked car), a
     * policy of 0, or the user's own stock "restrict background data" (1) is left as it is, and the
     * whitelist already in place (4) needs nothing.
     */
    internal fun netPolicyNeedsWhitelist(policy: Int?): Boolean =
        policy != null && (policy and POLICY_BYD_REJECT_METERED) != 0

    /**
     * What a beta's roaming change is put back to, or null to leave it: only a value the beta
     * replaced ([saved]; -1, a missing row, goes back as 0), and only while roaming still reads
     * the 1 the beta wrote — anything else is someone else's later choice.
     */
    internal fun roamingToRestore(saved: Int?, current: Int): Int? =
        if (saved == null || current != 1) null else saved.coerceAtLeast(0)

    /** Any line that names a uid — the block boundary when walking a per-uid dumpsys section. */
    private val anyUidLine = Regex("(?i)\\buid[\\s=:]*\\d+\\b")

    /**
     * Extract one `field=NNN` value for [uid] from `dumpsys netpolicy` output. Tolerant of the
     * two shapes Android 11 prints (a `UID=10042:` line with indented `policy=/rules=` lines
     * below, or everything on one line), and of BYD's labels after the number
     * (`policy=9 (DIABLE_METERED)`): only the digits are taken. Walking stops at the next uid
     * line so one app's field can't be read from another's block, and a uid that never appears
     * (or appears without the field) yields null — for the policy, an app BYD never blocked.
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

    /** Our uid's computed `rules=` value — informational, logged beside the policy. */
    internal fun parseUidRules(dumpsysNetpolicy: String, uid: Int): Int? =
        parseUidIntField(dumpsysNetpolicy, uid, "rules")

    // ── The re-assert ──────────────────────────────────────────────────────────────────────

    /**
     * Check for BYD's block on this app and, only where it is present, lift it. A no-op returning
     * null on every other platform or while the adb setup is missing — safe to call
     * unconditionally from startup and the watchdog. Diag.log gets the full before→after verdict
     * on the first check per process and on every *change* after it (so a 15-minute tick that
     * finds BYD's bit back says so exactly once, not ninety-six times a day).
     *
     * @return the verdict, or null when nothing was checked.
     */
    suspend fun reassert(context: Context, label: String = "startup"): String? {
        if (!DiLink5Platform.isDiLink5) return null
        // The adb key and this gate's prefs live in credential-encrypted storage: nothing to do
        // before the user unlocks — the boot watch and the watchdog re-run this after unlock.
        if (!AdbPermissionManager.isUserUnlocked(context)) return null
        // Without the one-time adb setup there is no channel: nothing to do, and a "no-setup" line
        // at every start would be noise.
        if (!AdbPermissionManager.isSetupComplete(context)) return null
        // One check at a time: start-up and the watchdog can overlap. Unserialised, two passes
        // would both read BYD's 9 and both write — harmless, but the log would show the same
        // clearing twice.
        return mutex.withLock {
            runCatching { undoBetaRoaming(context) }.getOrNull()
                ?.let { DiagLog.event(context, TAG, "4G gate[$label]: data roaming put back: $it") }
            val verdict = runCatching { "netpolicy=${ensureNetPolicyWhitelisted(context)}" }
                .getOrElse { "error(${it.javaClass.simpleName})" }
            if (verdict != lastVerdict) {
                DiagLog.event(context, TAG, "4G gate[$label]: $verdict")
            }
            lastVerdict = verdict
            verdict
        }
    }

    private val mutex = Mutex()
    @Volatile private var lastVerdict: String? = null

    /**
     * Betas before 2.18.0 also switched `data_roaming` on, which turned out to gate nothing (see
     * the class doc). Where one of them saved the value it replaced, put that back — once; the
     * saved value is kept only while the write fails, so the next check retries.
     *
     * @return what happened, or null when nothing was saved — every install that never ran those betas.
     */
    private suspend fun undoBetaRoaming(context: Context): String? {
        val p = prefs(context)
        if (!p.contains(PREF_ROAMING_BEFORE)) return null
        val saved = p.getInt(PREF_ROAMING_BEFORE, 0)
        val current = readRoaming(context)
        val target = roamingToRestore(saved, current)
        if (target == null) {
            p.edit().remove(PREF_ROAMING_BEFORE).apply()
            return "left=$current (changed since)"
        }
        val after = writeRoaming(context, target)
        if (after == target) p.edit().remove(PREF_ROAMING_BEFORE).apply()
        return if (after == target) "1→$target" else "FAILED(target=$target after=$after)"
    }

    private fun readRoaming(context: Context): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.DATA_ROAMING, -1)
    }.getOrDefault(-1)

    /** Write [value] — in-process (WRITE_SECURE_SETTINGS), shell as the fallback; returns what reads back. */
    private suspend fun writeRoaming(context: Context, value: Int): Int {
        runCatching { Settings.Global.putInt(context.contentResolver, Settings.Global.DATA_ROAMING, value) }
        var after = readRoaming(context)
        if (after != value) {
            shell(context, "settings put global data_roaming $value")?.let { after = readRoaming(context) }
        }
        return after
    }

    /**
     * The BYD netpolicy block. Read our uid's policy first (so a cleared or never-blocked unit
     * costs one `dumpsys` and no write, and the "before" value answers the re-application
     * question), and only when it carries BYD's bit, replace it: `add restrict-background-whitelist`
     * swaps the whole policy for bit 4 — the one command that wipes BYD's custom bit (see class
     * doc). Verified by re-reading afterwards; a command that didn't take is reported, never
     * retried within this call.
     */
    private suspend fun ensureNetPolicyWhitelisted(context: Context): String = runCatching {
        val uid = android.os.Process.myUid()
        val first = shell(context, "dumpsys netpolicy") ?: return@runCatching "channel"
        if (first.exitCode != 0 || first.output.isBlank()) {
            return@runCatching "dumpsys-failed(exit=${first.exitCode})"
        }
        val beforePolicy = parseUidPolicy(first.output, uid)
        val before = "policy=${beforePolicy ?: "absent"} rules=${parseUidRules(first.output, uid)}"
        if (!netPolicyNeedsWhitelist(beforePolicy)) {
            // 4 is a block already lifted (by us, an earlier beta, or by hand); anything else here
            // is a car that never had it.
            setUnblocked(context, beforePolicy == POLICY_ALLOW_METERED_BACKGROUND)
            return@runCatching "$before ok"
        }
        val added = shell(context, "cmd netpolicy add restrict-background-whitelist $uid")
            ?: return@runCatching "channel(after-dump)"
        val after = shell(context, "dumpsys netpolicy")
            ?.let { parseUidPolicy(it.output, uid) }
        setUnblocked(context, after == POLICY_ALLOW_METERED_BACKGROUND)
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
