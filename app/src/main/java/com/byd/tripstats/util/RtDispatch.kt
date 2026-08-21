package com.byd.tripstats.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.byd.tripstats.adb.AdbPermissionManager
import com.byd.tripstats.runtimebridge.RuntimeExtensionBridge
import java.io.File

internal object RtDispatch {
    private const val TAG = "RtDispatch"

    /**
     * Snapshot of the supervisor's persistent log, written next to diag.log so the in-app
     * "send diagnostics" flow can ship it — no PC, no adb. See [snapshotSupervisorLog].
     */
    const val SNAPSHOT_FILE = "supd-prev.log"

    /** Tail bytes kept. The supervisor appends a filtered logcat, so the source file can grow. */
    private const val SNAPSHOT_TAIL_BYTES = 64_000

    /**
     * [snapshotSupervisor] buys one extra shell round-trip, so it is off for callers on a budget:
     * BootReceiver runs this inside an 8 s `withTimeout` that already has to cover the probe and the
     * dispatch itself, and starving the re-dispatch would defeat the point. The Application.onCreate
     * and watchdog callers have no such budget.
     *
     * @return true when the supervisor is up afterwards — either it already was, or we re-dispatched
     *         it successfully. Lets the caller's retry ladder stop instead of re-probing for nothing.
     */
    suspend fun launch(context: Context, snapshotSupervisor: Boolean = true): Boolean {
        if (!AdbPermissionManager.isSetupComplete(context)) {
            // Means the background restarter is never dispatched at all — the permission grants are
            // gone. Silent in release before this line existed, because Log.* is stripped.
            logState(context, "setup", "skipped — adb setup incomplete ${bootAge()} ${channelDiag(context)}")
            return false
        }
        val apk = context.applicationInfo.sourceDir
        if (apk.isNullOrBlank()) {
            Log.w(TAG, "no apk path")
            return false
        }
        val payload = RuntimeExtensionBridge.stringMap("r01", apk)
        val probe = payload["probe"] ?: return false
        val dispatch = payload["dispatch"] ?: return false

        val probeRes = AdbPermissionManager.runShellBatch(
            context,
            listOf(probe),
            perCommandTimeoutMs = 3_000L,
        )
        val probeOutput = probeRes.firstOrNull()?.output?.trim().orEmpty()
        if (probeOutput == "ALIVE") {
            // Healthy: nothing is about to truncate the supervisor log, so only seed a snapshot if
            // we have none yet. Never overwrite here — an existing snapshot was taken at a failure,
            // which is the record actually worth sending.
            if (snapshotSupervisor) snapshotSupervisorLog(context, probeOutput, overwrite = false)
            Log.i(TAG, "healthy — skip")
            logState(context, "alive", "supd=ALIVE ${bootAge()} ${channelDiag(context)}")
            return true
        }
        Log.i(TAG, "re-dispatch :: ${probeOutput.take(100).ifBlank { "(no probe)" }}")
        val verdict = probeOutput.take(80).ifBlank { "(no probe — channel silent)" }

        // MUST happen before the dispatch below: the dispatch script's first act is to truncate the
        // supervisor log, and that log is the only post-hoc record of whether the background
        // restarter was alive during the window the app failed to come back. Snapshot it first, so
        // a user who simply opens the app after a failed auto-start still has the evidence to send.
        if (snapshotSupervisor) snapshotSupervisorLog(context, probeOutput, overwrite = true)

        val results = AdbPermissionManager.runShellBatch(
            context,
            listOf(dispatch),
            perCommandTimeoutMs = 6_000L,
        )
        if (results.isEmpty()) {
            Log.w(TAG, "channel unreachable")
            logState(
                context, "unreachable",
                "supd=DOWN ${bootAge()} ${channelDiag(context)} — dispatch channel unreachable :: $verdict",
            )
            return false
        }
        val r = results.first()
        Log.i(TAG, "dispatched exit=${r.exitCode} :: ${r.output.take(120)}")
        logState(
            context, "redispatch",
            "supd=DOWN ${bootAge()} ${channelDiag(context)} — re-dispatched exit=${r.exitCode} " +
                "out='${r.output.replace('\n', ' ').take(40)}' probe='$verdict'",
        )
        return r.exitCode == 0
    }

    // ── Channel diagnosis without the channel ────────────────────────────────────────────────
    //
    // Read in-process, so these values are available exactly when the shell channel is NOT — which
    // is the case we can never otherwise explain. They separate the competing causes of a dead
    // channel: WiFi-gated adb (port empty while the OEM flag is on and WiFi is down) vs the flag
    // being off (wiress=false) vs adbd up but unreachable for some third reason (port=5555).

    /** e.g. `port=[5555] sock=open wiress=[true] conn=[1] wifi=up` */
    private fun channelDiag(context: Context): String {
        val port = prop("service.adb.tcp.port")
        val wiress = prop("persist.sys.adb.wiress.enable")
        val conn = prop("sys.connect.adb.wiress")
        return "port=[$port] sock=${if (sockOpen()) "open" else "closed"} " +
            "wiress=[$wiress] conn=[$conn] wifi=${if (wifiUp(context)) "up" else "down"}"
    }

    /**
     * Raw TCP probe of the adb loopback port — the discriminator the property alone can't give.
     * `sock=open` while the dispatch still fails means adbd IS listening and the *adb handshake*
     * was refused: with `ro.adb.secure=1` that is an authorization problem (our key not in
     * /data/misc/adb/adb_keys, so an unattended "Allow debugging?" dialog is swallowing every
     * attempt), not a closed port. `sock=closed` means adbd isn't listening at all.
     * Port mirrors AdbPermissionManager.ADB_PORT, which is private to that object.
     */
    private fun sockOpen(): Boolean = runCatching {
        java.net.Socket().use { sock ->
            sock.connect(java.net.InetSocketAddress("127.0.0.1", 5555), 400)
            true
        }
    }.getOrDefault(false)

    /**
     * `SystemProperties.get` first (fast, and the hidden-API exemption is applied in
     * Application.onCreate before anything else runs); `getprop` exec as a fallback. Empty string
     * when both are refused — an empty value is itself informative, so never throw.
     */
    private fun prop(name: String): String = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        cls.getMethod("get", String::class.java).invoke(null, name) as? String
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: runCatching {
            val p = ProcessBuilder("getprop", name).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            out
        }.getOrDefault("")

    /** Any connected network with a WiFi transport — no location permission needed. */
    private fun wifiUp(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        cm?.allNetworks?.any {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: false
    }.getOrDefault(false)

    // ── Logging ──────────────────────────────────────────────────────────────────────────────

    /**
     * Log on state change, with a heartbeat every [REPEAT_HEARTBEAT] repeats. The watchdog retries
     * every 15 min, so an unchanged "channel unreachable" would otherwise write ~96 identical lines
     * a day and shred diag.log's history.
     */
    private fun logState(context: Context, key: String, message: String) {
        if (key == lastKey) {
            repeats++
            if (repeats % REPEAT_HEARTBEAT != 0) return
            DiagLog.event(context, TAG, "$message (unchanged ×$repeats)")
            return
        }
        lastKey = key
        repeats = 0
        DiagLog.event(context, TAG, message)
    }

    /** ~2 h at the watchdog's 15-minute cadence. */
    private const val REPEAT_HEARTBEAT = 8

    @Volatile private var lastKey: String? = null
    @Volatile private var repeats = 0

    /** Seconds since the head unit booted — counts through suspend, resets only on a cold boot. */
    private fun bootAge(): String = "sinceBoot=${android.os.SystemClock.elapsedRealtime() / 1000}s"

    // ── Supervisor-log snapshot ──────────────────────────────────────────────────────────────

    /**
     * Copy the tail of the supervisor's log into the app's own files dir. Read over the shell
     * channel (uid 2000) rather than directly: the file lives under /data/local/tmp, which an app
     * uid can't be relied on to read.
     */
    private suspend fun snapshotSupervisorLog(
        context: Context,
        probeOutput: String,
        overwrite: Boolean,
    ) {
        val dir = context.getExternalFilesDir(null) ?: return
        val target = File(dir, SNAPSHOT_FILE)
        if (!overwrite && target.exists() && target.length() > 0L) return

        // "tail -c <n> /data/local/tmp/.supd.log" — assembled at runtime like the other runtime paths.
        val path = s(
            47, 100, 97, 116, 97, 47, 108, 111, 99, 97, 108, 47, 116, 109, 112, 47,
            46, 115, 117, 112, 100, 46, 108, 111, 103,
        )
        val cmd = "${s(116, 97, 105, 108)} -c $SNAPSHOT_TAIL_BYTES $path"
        val res = AdbPermissionManager.runShellBatch(context, listOf(cmd), perCommandTimeoutMs = 3_000L)
        val body = res.firstOrNull()?.takeIf { it.exitCode == 0 }?.output.orEmpty()
        if (body.isBlank()) {
            Log.i(TAG, "snapshot: nothing to copy")
            return
        }
        runCatching {
            val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            target.writeText(
                "=== snapshot $stamp probe=${probeOutput.take(80).ifBlank { "(none)" }} ===\n$body\n"
            )
            DiagLog.event(
                context, TAG,
                "supervisor log snapshot: ${body.length}B probe=${probeOutput.take(40).ifBlank { "(none)" }}",
            )
        }.onFailure { Log.w(TAG, "snapshot write failed: ${it.message}") }
    }

    private fun s(vararg v: Int): String = v.map { it.toChar() }.joinToString("")
}
