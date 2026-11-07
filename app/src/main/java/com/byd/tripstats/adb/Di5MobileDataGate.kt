package com.byd.tripstats.adb

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.byd.tripstats.sdk.DiLink5Platform
import com.byd.tripstats.util.DiagLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The DiLink-5 "third-party apps have no internet over the car's 4G" block — gate 1 of the
 * four-gate model in `MD/WRITTEN_REPORT.md` §8.2 (field evidence:
 * github.com/angoikon/byd-trip-stats/issues/26).
 *
 * On some DiLink-5 cars BYD's modified Android framework puts sideloaded apps on Android
 * netpolicy `policy=9 (DIABLE_METERED)` — stock bit 1 (reject metered in background) **plus a
 * BYD-only bit 8** that rejects metered networks even in the foreground. Stock Android 11 has no
 * such bit, so "4G is up but this app has no internet" is invisible in any app's code: it lives
 * in the framework. Read out on a Shark 6 (#26, finch6) and on a Sealion 7 in Germany (2026-11),
 * and the symptom reported on two more Sealion 7s (#20, #26); absent on a Sealion 7 in Australia
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
 * **Some cars put the block back.** A Shark 6 kept the whitelist through four days of nightly
 * restarts, but a Sealion 7 in Germany (2026-11-07) had policy 9 back at every switch-on and
 * switch-off, and within about a minute of each lift while driving; with a check only at process
 * start and every 15 minutes, its MQTT got through for half a minute at a time. So besides those
 * checks, [startWatching] listens for Android's own report that this app's network is blocked
 * (`NetworkCallback.onBlockedStatusChanged`, which Android 11 sends whenever the app's network
 * rules change) and lifts the block again within about a second, backing off if the car keeps
 * winning ([liftDelayMs]). On a car already cleared, or never blocked, a check costs one `dumpsys`
 * and writes nothing, and the callback never fires.
 *
 * Never throws — every caller sits on the startup or watchdog path or on the system's connectivity
 * thread, where a throw is a crash loop on this platform (DI5 checklist §1).
 */
object Di5MobileDataGate {
    private const val TAG = "Di5MobileDataGate"
    private const val PREFS_NAME = "d5_mobile_data_prefs"

    /** Whether the last check left the block lifted (policy 4). */
    private const val PREF_UNBLOCKED = "d5_mobile_data_unblocked_v1"

    /** Set once any check has seen BYD's bit on this car; never cleared. */
    private const val PREF_BLOCK_SEEN = "d5_byd_block_seen_v1"

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

    /** A lift that the car undoes sooner than this counts as lost ([liftDelayMs]). */
    internal const val QUICK_REBLOCK_MS = 15_000L

    /** How long the app's mobile data must stay blocked before Settings says "blocked by the car". */
    internal const val BLOCKED_STATUS_AFTER_MS = 60_000L

    /** Time allowed for Android's "unblocked" report to arrive after a lift. */
    private const val SETTLE_MS = 5_000L

    /** Lost lifts in a row at which diag.log says the app is backing off. */
    private const val BACKOFF_LOG_AT = 3

    // ── Status ─────────────────────────────────────────────────────────────────────────────

    /** What Settings → App's Power & background card says about BYD's 4G block. */
    enum class Status {
        /** Not DiLink-5, or BYD's block has never been seen on this car: nothing to say. */
        NONE,
        /** The block is on this car and the app is keeping it lifted. */
        UNLOCKED,
        /** The block is on this car and the app hasn't been able to keep it off. */
        BLOCKED,
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * The live state of BYD's block for Settings. "Unlocked" only where the app has dealt with the
     * block (a car that never had it has nothing to report), and "blocked" only when this app's
     * mobile data has stayed blocked for a minute or the last lift didn't take — not in the second
     * between the car putting the block back and the app lifting it again. Never throws.
     */
    fun status(context: Context): Status = runCatching {
        if (!DiLink5Platform.isDiLink5) return@runCatching Status.NONE
        val p = prefs(context)
        val seen = p.getBoolean(PREF_BLOCK_SEEN, false) || p.getBoolean(PREF_UNBLOCKED, false)
        val blockedForMs = blockedSinceMs?.let { SystemClock.elapsedRealtime() - it }
        statusFor(seen, lastCheckSawBydBit, blockedForMs, lastLiftFailed)
    }.getOrDefault(Status.NONE)

    private fun setUnblocked(context: Context, unblocked: Boolean) {
        runCatching { prefs(context).edit().putBoolean(PREF_UNBLOCKED, unblocked).apply() }
    }

    private fun markBlockSeen(context: Context) {
        runCatching {
            val p = prefs(context)
            if (!p.getBoolean(PREF_BLOCK_SEEN, false)) p.edit().putBoolean(PREF_BLOCK_SEEN, true).apply()
        }
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

    /**
     * Wait before the next lift after [misses] lost lifts in a row (lifts that didn't take, or that
     * the car undid within [QUICK_REBLOCK_MS]). None lost: one second, enough to let a burst of
     * network changes settle. Then 10 s, doubling to a 5-minute cap, so a car that undoes every
     * lift at once costs at most a few shell commands an hour instead of a tight loop.
     */
    internal fun liftDelayMs(misses: Int): Long {
        if (misses <= 0) return 1_000L
        val shift = (misses - 1).coerceAtMost(5)
        return (10_000L shl shift).coerceAtMost(300_000L)
    }

    /** [misses] after a lift held [heldMs] before the car put the block back. */
    internal fun missesAfterHold(misses: Int, heldMs: Long): Int =
        if (heldMs < QUICK_REBLOCK_MS) misses + 1 else 0

    /** Lifts 1–5 of a process go to diag.log one by one, then every 10th with a summary. */
    internal fun shouldLogLift(n: Int): Boolean = n in 1..5 || (n > 0 && n % 10 == 0)

    /** "41 s", "15 min", "3 h" — durations in diag.log lines. */
    internal fun formatDuration(ms: Long): String {
        val s = (ms / 1000L).coerceAtLeast(0L)
        return when {
            s < 120 -> "$s s"
            s < 120 * 60 -> "${s / 60} min"
            else -> "${s / 3600} h"
        }
    }

    /**
     * What diag.log adds to the line for lift [n]. [heldMs] is how long the previous lift lasted
     * when Android reported the block coming back ([measured]); found by a check instead, it is
     * only the time since the previous lift, so the block was back *within* it. Every 10th line
     * also summarises the lifts measured since the last logged one ([minHeldMs]..[maxHeldMs],
     * [count] of them).
     */
    internal fun liftNote(
        n: Int, heldMs: Long?, measured: Boolean, count: Int, minHeldMs: Long, maxHeldMs: Long,
    ): String {
        if (n <= 1) return ""
        val back = when {
            heldMs == null -> ""
            measured -> ", back ${formatDuration(heldMs)} after the previous one"
            else -> ", back within ${formatDuration(heldMs)} of the previous one"
        }
        if (n <= 5 || count <= 0) return " (lift $n$back)"
        val low = formatDuration(minHeldMs)
        val high = formatDuration(maxHeldMs)
        val range = when {
            low == high -> low
            // "31–58 s", not "31 s–58 s"; mixed units keep both ("50 s–3 min").
            low.substringAfter(' ') == high.substringAfter(' ') -> "${low.substringBefore(' ')}–$high"
            else -> "$low–$high"
        }
        return " (lift $n; the last $count came back after $range)"
    }

    /** See [status]: "blocked" needs BYD's bit in the latest dump and a block that lasted or a lift that failed. */
    internal fun statusFor(
        blockSeen: Boolean, lastCheckSawBydBit: Boolean, blockedForMs: Long?, lastLiftFailed: Boolean,
    ): Status = when {
        !blockSeen -> Status.NONE
        lastCheckSawBydBit && (lastLiftFailed || (blockedForMs ?: 0L) >= BLOCKED_STATUS_AFTER_MS) -> Status.BLOCKED
        else -> Status.UNLOCKED
    }

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

    // ── The check ──────────────────────────────────────────────────────────────────────────

    private enum class Kind {
        /** BYD's bit was there and the whitelist now holds. */
        LIFTED,
        /** No BYD bit: lifted already, or never blocked — nothing to do. */
        CLEAR,
        /** The policy couldn't be read, or the whitelist didn't take. */
        FAILED,
    }

    /** One check's result; [sawBydBit] is null when no dump could be read. */
    private class Check(val kind: Kind, val text: String, val sawBydBit: Boolean?)

    /**
     * Check for BYD's block on this app and, only where it is present, lift it. A no-op returning
     * null on every other platform or while the adb setup is missing — safe to call
     * unconditionally from startup and the watchdog.
     *
     * Diag.log: lifts go through [shouldLogLift] with how long the previous one held; any other
     * result is logged when it differs from the last non-lift result, so a 15-minute tick that finds
     * nothing to do is not repeated ninety-six times a day.
     *
     * @return the verdict, or null when nothing was checked.
     */
    suspend fun reassert(context: Context, label: String = "startup"): String? =
        check(context, label)?.let { "netpolicy=${it.text}" }

    private suspend fun check(context: Context, label: String): Check? {
        if (!DiLink5Platform.isDiLink5) return null
        // The adb key and this gate's prefs live in credential-encrypted storage: nothing to do
        // before the user unlocks — the boot watch and the watchdog re-run this after unlock.
        if (!AdbPermissionManager.isUserUnlocked(context)) return null
        // Without the one-time adb setup there is no channel, so nothing to do.
        if (!AdbPermissionManager.isSetupComplete(context)) return null
        // One check at a time: start-up, the watchdog and the blocked callback can overlap.
        // Unserialised, two passes would both read BYD's 9 and both write — harmless, but the log
        // would show the same lift twice.
        return mutex.withLock {
            runCatching { undoBetaRoaming(context) }.getOrNull()
                ?.let { DiagLog.event(context, TAG, "4G gate[$label]: data roaming put back: $it") }
            val result = runCatching { ensureNetPolicyWhitelisted(context) }
                .getOrElse { Check(Kind.FAILED, "error(${it.javaClass.simpleName})", null) }
            runCatching { record(context, label, result) }
            result
        }
    }

    private val mutex = Mutex()

    // Guarded by [mutex].
    private var lastOtherVerdict: String? = null
    private var lifts = 0

    /** State shared between the check (under [mutex]) and the connectivity thread. */
    private val lock = Any()
    @Volatile private var blockedSinceMs: Long? = null   // elapsedRealtime; null = not blocked now
    @Volatile private var lastLiftAtMs: Long? = null
    @Volatile private var lastCheckSawBydBit = false
    @Volatile private var lastLiftFailed = false
    private var holdPending = false   // the last lift's hold not measured yet (under [lock])
    private var measuredHoldMs: Long? = null   // the last lift's measured hold, for its log line (under [lock])
    private var misses = 0   // lost lifts in a row (under [lock])
    private var windowCount = 0   // holds measured since the last logged lift line (under [lock])
    private var windowMinMs = Long.MAX_VALUE
    private var windowMaxMs = 0L

    private fun record(context: Context, label: String, check: Check) {
        val verdict = "netpolicy=${check.text}"
        check.sawBydBit?.let { lastCheckSawBydBit = it }
        when (check.kind) {
            Kind.LIFTED -> {
                lastLiftFailed = false
                val now = SystemClock.elapsedRealtime()
                val n = ++lifts
                val previous = lastLiftAtMs
                var held: Long? = null
                var measured = false
                var count = 0
                var minMs = 0L
                var maxMs = 0L
                synchronized(lock) {
                    held = measuredHoldMs
                    measured = held != null
                    if (held == null && previous != null) held = now - previous
                    measuredHoldMs = null
                    // Set together with the time, so the callback never pairs one with the other's old value.
                    holdPending = true
                    lastLiftAtMs = now
                    count = windowCount
                    minMs = windowMinMs
                    maxMs = windowMaxMs
                    if (shouldLogLift(n)) {
                        windowCount = 0; windowMinMs = Long.MAX_VALUE; windowMaxMs = 0L
                    }
                }
                if (shouldLogLift(n)) {
                    DiagLog.event(
                        context, TAG,
                        "4G gate[$label]: $verdict" + liftNote(n, held, measured, count, minMs, maxMs),
                    )
                }
            }
            Kind.CLEAR, Kind.FAILED -> {
                if (check.kind == Kind.FAILED && check.sawBydBit == true) lastLiftFailed = true
                if (check.kind == Kind.CLEAR) {
                    lastLiftFailed = false
                    // A hold measured before a check that found nothing of BYD's belongs to no lift.
                    synchronized(lock) { measuredHoldMs = null }
                }
                if (verdict != lastOtherVerdict) DiagLog.event(context, TAG, "4G gate[$label]: $verdict")
                lastOtherVerdict = verdict
            }
        }
    }

    // ── The blocked watch ──────────────────────────────────────────────────────────────────

    private val watching = AtomicBoolean(false)

    /**
     * Wakes the lift worker. Conflated, so a block reported while a pass is running is kept and
     * seen when it ends — a plain "is a job running?" check could drop it in the moment a pass
     * finishes.
     */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** The spell [runLifts] found was not BYD's doing; left alone until a new one starts. */
    @Volatile private var notOursSpell: Long? = null

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, t -> Log.w(TAG, "blocked watch threw: ${t.javaClass.simpleName}") },
    )

    /**
     * Lift BYD's block again the moment it comes back. Registers, once per process, a callback on
     * the default network; Android reports through it whether this app may use that network, at
     * registration and on every change of the app's network rules — which is what BYD's policy
     * write is. Only a block on a metered network (4G) is reported this way, so Wi-Fi and hotspot
     * use never trigger it. A no-op on every other platform; safe to call again (the watchdog
     * does, in case start-up never got here). Never throws.
     */
    fun startWatching(context: Context) {
        if (!DiLink5Platform.isDiLink5) return
        if (!watching.compareAndSet(false, true)) return
        val app = context.applicationContext
        val cm = runCatching { app.getSystemService(ConnectivityManager::class.java) }.getOrNull()
        if (cm == null) {
            watching.set(false)
            return
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            // Never let anything escape onto the system's connectivity thread.
            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                runCatching { onBlockedChanged(app, blocked) }
            }

            override fun onLost(network: Network) {
                runCatching { onBlockedChanged(app, false) }
            }
        }
        val registered = runCatching { cm.registerDefaultNetworkCallback(callback) }
        if (registered.isFailure) {
            watching.set(false)
            DiagLog.event(
                app, TAG,
                "4G gate: can't watch for the block (${registered.exceptionOrNull()?.javaClass?.simpleName})" +
                    " — checks at start-up and every 15 min only",
            )
            return
        }
        scope.launch {
            for (signal in wake) {
                try {
                    runLifts(app)
                } catch (t: Throwable) {
                    Log.w(TAG, "lift pass threw: ${t.javaClass.simpleName}")
                }
            }
        }
    }

    private fun onBlockedChanged(context: Context, blocked: Boolean) {
        if (!blocked) {
            blockedSinceMs = null
            return
        }
        var held: Long? = null
        synchronized(lock) {
            if (blockedSinceMs != null) return@synchronized
            val now = SystemClock.elapsedRealtime()
            blockedSinceMs = now
            val liftAt = lastLiftAtMs
            if (holdPending && liftAt != null) {
                held = now - liftAt
                holdPending = false
                measuredHoldMs = held
                windowCount++
                windowMinMs = minOf(windowMinMs, now - liftAt)
                windowMaxMs = maxOf(windowMaxMs, now - liftAt)
            }
        }
        held?.let { h -> updateMisses(context) { missesAfterHold(it, h) } }
        wake.trySend(Unit)
    }

    /**
     * Lift while the app stays blocked and BYD's bit is what blocks it. A spell is one stretch of
     * "blocked", named by when it started; the car putting the block back after a lift starts a
     * new one, and the callback has already measured how long the lift held.
     */
    private suspend fun runLifts(context: Context) {
        while (true) {
            val spell = blockedSinceMs ?: return
            if (spell == notOursSpell) return
            delay(liftDelayMs(synchronized(lock) { misses }))
            if (blockedSinceMs != spell) continue
            val result = check(context, "blocked")
            when (result?.kind) {
                Kind.CLEAR -> {
                    // No BYD bit, yet blocked: something else (Data Saver, a power mode) — not ours.
                    notOursSpell = spell
                    continue
                }
                Kind.LIFTED -> {
                    delay(SETTLE_MS)
                    if (blockedSinceMs != spell) continue
                }
                else -> Unit
            }
            // Still the same spell: the lift didn't take, Android never reported it, or the check
            // couldn't run (channel still down at a cold boot). Count it and wait longer next time.
            synchronized(lock) { holdPending = false }
            updateMisses(context) { it + 1 }
        }
    }

    /** Change the lost-lift count; diag.log hears when the app starts backing off and when lifts hold again. */
    private fun updateMisses(context: Context, next: (Int) -> Int) {
        val before: Int
        val after: Int
        synchronized(lock) {
            before = misses
            misses = next(before)
            after = misses
        }
        if (before < BACKOFF_LOG_AT && after >= BACKOFF_LOG_AT) {
            DiagLog.event(
                context, TAG,
                "4G gate: $after lifts in a row lost (undone within ${formatDuration(QUICK_REBLOCK_MS)} or not taken)" +
                    " — waiting ${formatDuration(liftDelayMs(after))} before the next, longer after each lost one",
            )
        } else if (before >= BACKOFF_LOG_AT && after == 0) {
            DiagLog.event(context, TAG, "4G gate: lifts are holding again — back to lifting at once")
        }
    }

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
     * costs one `dumpsys` and no write, and the "before" value shows whether the car put the block
     * back), and only when it carries BYD's bit, replace it: `add restrict-background-whitelist`
     * swaps the whole policy for bit 4 — the one command that wipes BYD's custom bit (see class
     * doc). Verified by re-reading afterwards; a command that didn't take is reported, never
     * retried within this call.
     */
    private suspend fun ensureNetPolicyWhitelisted(context: Context): Check {
        val uid = android.os.Process.myUid()
        val first = shell(context, "dumpsys netpolicy") ?: return Check(Kind.FAILED, "channel", null)
        if (first.exitCode != 0 || first.output.isBlank()) {
            return Check(Kind.FAILED, "dumpsys-failed(exit=${first.exitCode})", null)
        }
        val beforePolicy = parseUidPolicy(first.output, uid)
        val before = "policy=${beforePolicy ?: "absent"} rules=${parseUidRules(first.output, uid)}"
        if (!netPolicyNeedsWhitelist(beforePolicy)) {
            // 4 is a block already lifted (by us, an earlier beta, or by hand); anything else here
            // is a car that never had it.
            setUnblocked(context, beforePolicy == POLICY_ALLOW_METERED_BACKGROUND)
            return Check(Kind.CLEAR, "$before ok", false)
        }
        markBlockSeen(context)
        val added = shell(context, "cmd netpolicy add restrict-background-whitelist $uid")
            ?: return Check(Kind.FAILED, "channel(after-dump)", true)
        val after = shell(context, "dumpsys netpolicy")
            ?.let { parseUidPolicy(it.output, uid) }
        setUnblocked(context, after == POLICY_ALLOW_METERED_BACKGROUND)
        return if (after == POLICY_ALLOW_METERED_BACKGROUND) {
            Check(Kind.LIFTED, "$before→policy=$after cleared", true)
        } else {
            Check(
                Kind.FAILED,
                "$before→policy=${after ?: "?"} FAILED exit=${added.exitCode} " +
                    ":: ${added.output.replace('\n', ' ').take(60)}",
                true,
            )
        }
    }

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
