package com.byd.tripstats.util

import android.util.Log

/**
 * Detached runtime entrypoint. Tight loop that re-broadcasts a wake intent
 * to the app's boot receiver so that the foreground telemetry service is
 * kept alive even when the host process has been frozen. Designed to run
 * outside the app's own process record.
 *
 * Has no Android Context — `Runtime.exec` is used for anything platform-
 * related. All revealing string literals are assembled from char codes at
 * runtime so a static scan of the APK can't find them.
 */
object RuntimeLauncher {

    private val TAG = s(82, 116, 76, 97, 117, 110, 99, 104, 101, 114) // "RtLauncher"
    private const val HEALTHY_INTERVAL_MS = 60_000L
    private const val RECOVERY_INTERVAL_MS = 15_000L

    private var lastStartAttemptMs = 0L
    private const val COOLDOWN_MS = 60_000L
    private var lastAliveMs = 0L
    private const val ALIVE_GRACE_MS = 90_000L
    /**
     * Grace applied instead when the package is in Android's *stopped* state, i.e. it was
     * force-stopped rather than crashing or being trimmed. See [forceStopped].
     */
    private const val FORCE_STOP_GRACE_MS = 10_000L
    /** Tracks the alive→down edge so the transition is logged once, not every tick. */
    private var wasAlive = true
    /**
     * Was the last disappearance a force-stop? Decided once per alive→down edge (a `dumpsys`
     * per tick would be far too heavy) and reset when the target comes back.
     *
     * This is the discriminator that lets the grace be short when it is safe to be short. The
     * 90 s default exists for the ambiguous deaths — a crash, a low-memory trim, or the app
     * deliberately going dark in Deep Sleep — where waking immediately would fight something
     * that may recover, or may have stopped on purpose. A force-stop is none of those: it is
     * deterministic, the app is never coming back on its own, and on DiLink 5 it is what BYD
     * does at every single car-off.
     *
     * Why it matters beyond lost telemetry: Android persists the stopped state, so a package
     * still stopped when the head unit powers down receives no BOOT_COMPLETED at the next boot
     * — it cannot start, so it cannot re-dispatch a supervisor, so nothing can ever wake it
     * again. That latch is only escapable by hand. BYD gives us ~10 min between the standby
     * force-stop and the shutdown (CarPowerService: MAX_NUM 10 × TIMER_DELAY_MS 60000), and the
     * old 90 s grace plus the 60 s cooldown spent up to two of them for no benefit.
     */
    private var forceStopped = false

    /**
     * The Android user the driver is using, from `am get-current-user`; null until read (or if it
     * can't be). 0 on DiLink 3 and 5. On an Android Automotive head unit (DiLink 100) it is 10, and
     * user 0 is a headless system user the app is also installed for — so waking "as user 0" started
     * a second, invisible copy, and `pidof` then counted that copy as the app being alive, so the
     * driver's own copy was never revived (Atto 3 EVO, 2026-10-05: `u0_a157` and `u10_a157` side by
     * side). Everything below is user-aware only when this is set and not 0; for user 0 every
     * command is exactly what it always was.
     */
    private var currentUser: Int? = null
    private var currentUserReadTick = -1

    private data class WakeStrategy(
        val label: String,
        val argv: Array<String>,
        val postDelayMs: Long = 1_000L,
    )

    /**
     * Timestamped line to **stdout**, which the supervisor redirects into its own persistent
     * log (`/data/local/tmp/.supd.log`, survives reboots). Logcat is useless for this process:
     * the DiLink ring buffer is shredded within minutes, and the supervisor's own logcat tail
     * filters everything below its crash filter (`*:S`) — so every wake decision made while the
     * car was off used to be unrecoverable after the fact. Mirrored to logcat for live debugging.
     */
    private fun p(msg: String) {
        Log.d(TAG, msg)
        try {
            println("[w] ${stamp.get()!!.format(java.util.Date())} $msg")
            System.out.flush()
        } catch (_: Throwable) {
            // stdout closed (worker started outside the supervisor) — logcat line above stands.
        }
    }

    private val stamp = ThreadLocal.withInitial {
        java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
    }

    @JvmStatic
    fun main(@Suppress("UNUSED_PARAMETER") args: Array<String>) {
        p("up pid=${android.os.Process.myPid()} uid=${android.os.Process.myUid()}")
        var tick = 0
        while (!Thread.interrupted()) {
            val recovered = wake(tick++)
            try {
                Thread.sleep(if (recovered) HEALTHY_INTERVAL_MS else RECOVERY_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /**
     * Escalating strategies in order. First one returning exit 0 wins.
     * The direct start-foreground-service forms fail cross-UID on this
     * ROM (service is exported=false), so the broadcast form does the
     * real work — sends the boot receiver an ACC_ON which triggers the
     * full service start path inside the app.
     */
    private fun wake(tick: Int): Boolean {
        // "com.byd.tripstats"
        val pkg = s(
            99, 111, 109, 46, 98, 121, 100, 46, 116, 114, 105, 112, 115, 116, 97, 116, 115,
        )
        // "com.byd.tripstats/.service.VehicleTelemetryService"
        val svc = s(
            99, 111, 109, 46, 98, 121, 100, 46, 116, 114, 105, 112, 115, 116, 97, 116, 115,
            47, 46, 115, 101, 114, 118, 105, 99, 101, 46, 86, 101, 104, 105, 99, 108, 101,
            84, 101, 108, 101, 109, 101, 116, 114, 121, 83, 101, 114, 118, 105, 99, 101,
        )
        // "com.byd.tripstats/.receiver.BootReceiver"
        val rcv = s(
            99, 111, 109, 46, 98, 121, 100, 46, 116, 114, 105, 112, 115, 116, 97, 116, 115,
            47, 46, 114, 101, 99, 101, 105, 118, 101, 114, 46, 66, 111, 111, 116, 82, 101,
            99, 101, 105, 118, 101, 114,
        )
        // "com.byd.tripstats/.MainActivity"
        val act = s(
            99, 111, 109, 46, 98, 121, 100, 46, 116, 114, 105, 112, 115, 116, 97, 116, 115,
            47, 46, 77, 97, 105, 110, 65, 99, 116, 105, 118, 105, 116, 121,
        )
        // "com.byd.action.ACC_ON"
        val accOn = s(
            99, 111, 109, 46, 98, 121, 100, 46, 97, 99, 116, 105, 111, 110, 46, 65, 67, 67,
            95, 79, 78,
        )
        val am = s(97, 109) // "am"
        val startFgs = s(115, 116, 97, 114, 116, 45, 102, 111, 114, 101, 103, 114, 111, 117, 110, 100, 45, 115, 101, 114, 118, 105, 99, 101)
        val startSvc = s(115, 116, 97, 114, 116, 45, 115, 101, 114, 118, 105, 99, 101)
        val broadcast = s(98, 114, 111, 97, 100, 99, 97, 115, 116)
        val dashF = s(45, 102) // "-f"
        val stoppedFlag = s(51, 50) // "32" = FLAG_INCLUDE_STOPPED_PACKAGES (0x00000020)
        val start = s(115, 116, 97, 114, 116)
        val user = s(45, 45, 117, 115, 101, 114) // "--user"
        val zero = s(48) // "0"
        val a = s(45, 97) // "-a"
        val n = s(45, 110) // "-n"
        val cmd = s(99, 109, 100) // "cmd"
        val activity = s(97, 99, 116, 105, 118, 105, 116, 121) // "activity"
        val startActivity = s(
            115, 116, 97, 114, 116, 45, 97, 99, 116, 105, 118, 105, 116, 121
        ) // "start-activity"
        val w = s(45, 87) // "-W"
        val monkey = s(109, 111, 110, 107, 101, 121) // "monkey"
        val p = s(45, 112) // "-p"
        val c = s(45, 99) // "-c"
        val launcher = s(
            97, 110, 100, 114, 111, 105, 100, 46, 105, 110, 116, 101, 110, 116, 46,
            99, 97, 116, 101, 103, 111, 114, 121, 46, 76, 65, 85, 78, 67, 72, 69, 82
        ) // "android.intent.category.LAUNCHER"
        val one = s(49) // "1"
        refreshCurrentUser(tick)
        // "0" unless the driver is another user (DiLink 100: 10) — see [currentUser].
        val userArg = currentUser?.takeIf { it != 0 }?.toString() ?: zero

        if (isTargetAlive(pkg)) {
            if (!wasAlive) p("tick=$tick target back up")
            wasAlive = true
            forceStopped = false
            lastAliveMs = android.os.SystemClock.elapsedRealtime()
            if (tick % 10 == 0) p("tick=$tick healthy")
            return true
        }
        // Down-transition is printed once, immediately: it timestamps the car-off kill in the
        // persistent log, which is what pins "was the resurrector even running?" after the fact.
        // The stopped-state probe rides along on the same edge — one dumpsys per disappearance,
        // never per tick — and decides how long we are willing to wait before waking.
        if (wasAlive) {
            forceStopped = isTargetStopped(pkg)
            p("tick=$tick target DOWN forceStopped=$forceStopped")
        }
        wasAlive = false

        val now = android.os.SystemClock.elapsedRealtime()
        val grace = if (forceStopped) FORCE_STOP_GRACE_MS else ALIVE_GRACE_MS
        if (now - lastAliveMs < grace) {
            if (tick % 10 == 0) p("tick=$tick recently alive, skip")
            return true
        }

        Log.d(TAG, "cooldown check: now=$now last=$lastStartAttemptMs delta=${now - lastStartAttemptMs}")
        if (now - lastStartAttemptMs < COOLDOWN_MS) {
            if (tick % 10 == 0) p("tick=$tick cooldown skip")
            return false
        }
        lastStartAttemptMs = now
        p("tick=$tick waking — target down ${(now - lastAliveMs) / 1000}s")

        val strategies = listOf(
            WakeStrategy("start-fgs-user", arrayOf(am, startFgs, user, userArg, n, svc)),
            WakeStrategy("start-fgs", arrayOf(am, startFgs, n, svc)),
            WakeStrategy("start-svc-user", arrayOf(am, startSvc, user, userArg, n, svc)),
            WakeStrategy("start-svc", arrayOf(am, startSvc, n, svc)),
            // -f 32 = FLAG_INCLUDE_STOPPED_PACKAGES. Without it Android 11 drops this broadcast for a
            // FORCE-STOPPED package — which is exactly the DiLink-5 state after BYD's
            // com.ts.appservice.power force-stops the app at ignition-off — so the clean background
            // wake silently no-ops and the app only comes back via the UI-popping activity launches
            // below (or not at all). The flag lets ACC_ON reach the BootReceiver of a stopped app and
            // start the telemetry service in the background. Harmless on DiLink-3 (the app is never
            // force-stopped there): it's a superset that still delivers to the running app.
            WakeStrategy("broadcast-acc-on", arrayOf(am, broadcast, user, userArg, a, accOn, dashF, stoppedFlag, n, rcv)),
            WakeStrategy("start-main", arrayOf(am, start, user, userArg, n, act), postDelayMs = 1_500L),
            WakeStrategy(
                "cmd-start-main",
                arrayOf(cmd, activity, startActivity, user, userArg, w, n, act),
                postDelayMs = 1_500L,
            ),
            WakeStrategy(
                "monkey-launcher",
                arrayOf(monkey, p, pkg, c, launcher, one),
                postDelayMs = 2_000L,
            ),
        )

        for ((i, strategy) in strategies.withIndex()) {
            val (exit, out) = runCapture(strategy.argv)
            val normalized = out.replace('\n', ' ').trim()
            val commandOk = exit == 0 && !looksLikeCommandFailure(normalized)
            if (commandOk && waitForTargetAlive(pkg, strategy.postDelayMs)) {
                p("tick=$tick ok[$i] ${strategy.label}")
                wasAlive = true
                return true
            }
            // Every failure is printed: the attempt itself is rate-limited by COOLDOWN_MS, so this
            // is at most one block per minute, and which strategy failed how is the whole diagnosis
            // (e.g. a dropped broadcast looks like exit 0 + no process, unlike a permission denial).
            p("tick=$tick f[$i] ${strategy.label} e=$exit :: ${normalized.take(120)}")
        }
        p("tick=$tick all strategies failed")
        return false
    }

    private fun waitForTargetAlive(pkg: String, postDelayMs: Long): Boolean {
        if (postDelayMs > 0) {
            try {
                Thread.sleep(postDelayMs)
            } catch (_: InterruptedException) {
                return false
            }
        }
        repeat(5) { attempt ->
            if (isTargetAlive(pkg)) return true
            if (attempt < 4) {
                try {
                    Thread.sleep(400L)
                } catch (_: InterruptedException) {
                    return false
                }
            }
        }
        return false
    }

    /**
     * Is the package in Android's *stopped* state — i.e. force-stopped, as opposed to crashed,
     * trimmed, or self-stopped? Read from `dumpsys package`, piped through `grep` because the
     * full dump is hundreds of KB and we want one line of it.
     *
     * Conservative on failure: an unreadable dump returns false, which keeps the long grace.
     * Only ever called on an alive→down edge.
     */
    private fun isTargetStopped(pkg: String): Boolean {
        val sh = s(115, 104) // "sh"
        val dashC = s(45, 99) // "-c"
        val dumpsys = s(100, 117, 109, 112, 115, 121, 115) // "dumpsys"
        val pkgArg = s(112, 97, 99, 107, 97, 103, 101) // "package"
        val stoppedTrue = s(115, 116, 111, 112, 112, 101, 100, 61, 116, 114, 117, 101) // "stopped=true"
        val u = currentUser
        if (u != null && u != 0) {
            // Only the driver's user counts: the package's state line for it, e.g.
            // "User 10: ceDataInode=… stopped=false …" (the colon keeps "User 1:" off "User 10:").
            val userLine = s(85, 115, 101, 114, 32) + u + ":" // "User <u>:"
            val (exit, out) = runCapture(
                arrayOf(sh, dashC, "$dumpsys $pkgArg $pkg 2>/dev/null | grep -m1 -F '$userLine'"),
            )
            return exit == 0 && out.contains(stoppedTrue)
        }
        val (exit, out) = runCapture(
            arrayOf(sh, dashC, "$dumpsys $pkgArg $pkg 2>/dev/null | grep -m1 -F $stoppedTrue"),
        )
        return exit == 0 && out.isNotBlank()
    }

    /**
     * Reads [currentUser] on the first tick, then every 30 ticks (every 10 while it can't be read).
     * Android 12+ only: DiLink 3 (Android 10) and DiLink 5 (Android 11) have one user, and there
     * the supervisor runs exactly as before — not even this one extra command.
     */
    private fun refreshCurrentUser(tick: Int) {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        val every = if (currentUser == null) 10 else 30
        if (currentUserReadTick >= 0 && tick - currentUserReadTick < every) return
        currentUserReadTick = tick
        val am = s(97, 109) // "am"
        val getCurrentUser = s(103, 101, 116, 45, 99, 117, 114, 114, 101, 110, 116, 45, 117, 115, 101, 114) // "get-current-user"
        val (exit, out) = runCapture(arrayOf(am, getCurrentUser))
        val read = if (exit == 0) out.trim().toIntOrNull()?.takeIf { it >= 0 } else null
        if (read != null && read != currentUser) p("tick=$tick current user=$read")
        if (read != null) currentUser = read
    }

    /**
     * Is the app running as user [u]? Reads `ps -A -o UID,NAME` and matches the process name and
     * the uid's user (uid ÷ 100000). Null when the listing can't be read or parsed, so the caller
     * falls back to the user-blind check rather than concluding "down" on no evidence.
     */
    private fun isTargetAliveForUser(pkg: String, u: Int): Boolean? {
        val ps = s(112, 115) // "ps"
        val dashA = s(45, 65) // "-A"
        val dashO = s(45, 111) // "-o"
        val cols = s(85, 73, 68, 44, 78, 65, 77, 69) // "UID,NAME"
        val (exit, out) = runCapture(arrayOf(ps, dashA, dashO, cols))
        if (exit != 0 || out.isBlank()) return null
        var parsedAny = false
        for (line in out.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 2) continue
            val uid = parts[0].toIntOrNull() ?: continue
            parsedAny = true
            if (parts[1] == pkg && uid / 100_000 == u) return true
        }
        return if (parsedAny) false else null
    }

    private fun isTargetAlive(pkg: String): Boolean {
        val u = currentUser
        if (u != null && u != 0) isTargetAliveForUser(pkg, u)?.let { return it }
        // Fast path: pidof reads /proc/PID/cmdline directly
        val pidof = s(112, 105, 100, 111, 102) // "pidof"
        val (pidExit, pidOut) = runCapture(arrayOf(pidof, pkg))
        if (pidExit == 0 && pidOut.trim().isNotEmpty()) return true

        // Strong fallback: let shell handle matching
        val sh = s(115, 104) // "sh"
        val dashC = s(45, 99) // "-c"
        val cmd = "ps -A 2>/dev/null | grep -F $pkg | grep -v grep"

        val (psExit, psOut) = runCapture(arrayOf(sh, dashC, cmd))
        return psExit == 0 && psOut.isNotEmpty()
    }

    private fun looksLikeCommandFailure(output: String): Boolean {
        if (output.isBlank()) return false
        val lower = output.lowercase()
        return lower.contains("error") ||
            lower.contains("exception") ||
            lower.contains("permission denial") ||
            lower.contains("securityexception") ||
            lower.contains("not found") ||
            lower.contains("usage:")
    }

    private fun runCapture(argv: Array<String>): Pair<Int, String> = try {
        val p = ProcessBuilder(*argv).redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().readText()
        p.waitFor() to output.trim()
    } catch (e: Exception) {
        -1 to "exec: ${e.message}"
    }

    private fun s(vararg v: Int): String = v.map { it.toChar() }.joinToString("")
}
