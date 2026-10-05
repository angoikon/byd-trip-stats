package com.byd.tripstats.util

import android.content.Context
import android.util.Log
import com.byd.tripstats.adb.AdbPermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Puts the head unit on the user's Tailscale network, so the web companion and ADB are reachable
 * from anywhere rather than only from the home Wi-Fi.
 *
 * ## Why it looks like this
 *
 * We ship the Tailscale daemon itself (`jniLibs/arm64-v8a/libtailscale.so` — a static Go binary
 * under a library's name so Android extracts it as an executable file) and run it as a subprocess
 * through the same privileged shell channel the telemetry daemon uses. The official Tailscale
 * Android client is not a workable alternative on these units: no Play Store, and its sign-in is a
 * browser OAuth flow on a car screen.
 *
 * Three details were established by testing on a real DiLink-3 (see MD/TAILSCALE.md — do not
 * re-derive them):
 *
 *  - **`--tun=userspace-networking`.** No VpnService, no VPN consent dialog, no root, and no
 *    `/dev/net/tun`. tailscaled keeps WireGuard inside its own process and forwards inbound TCP on
 *    the node's `100.x` address to localhost, which is exactly what makes our companion (:8888)
 *    and adbd (:5555) reachable. There is deliberately no outbound proxy: nothing on the car needs
 *    to *reach into* the tailnet.
 *  - **`--socket=@tailscaled`** — an abstract-namespace socket. A socket *file* under
 *    `/data/local/tmp` fails with `bind: permission denied`, because Android's SELinux policy
 *    grants the shell domain `file` access to `shell_data_file` but not `sock_file { create }`.
 *    The abstract namespace creates no file, and tailscale's `safesocket.listen()` ignores the
 *    error from the `os.Chmod` that then can't apply.
 *  - **`TS_BE_CLI=1`.** The binary is a combined daemon+CLI build; it runs as the CLI only when
 *    invoked as `tailscale` or with this variable set. Without it, subcommands are rejected with
 *    "tailscaled does not take non-flag arguments".
 *
 * Also: `HOME` must be set, or `os.UserCacheDir()` resolves to `/` and the daemon fails writing to
 * a read-only filesystem.
 *
 * ## What this class does not do
 *
 * It never touches the tailnet itself, only the local daemon. Account, auth keys, ACLs and key
 * expiry all live in the user's Tailscale admin console.
 */
object TailscaleManager {

    private const val TAG = "TailscaleManager"
    private const val PREFS = "tailscale_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_AUTH_KEY = "auth_key"
    private const val KEY_HOSTNAME = "hostname"
    private const val KEY_HTTPS = "https_serve"

    /** Shell-owned, and the only directory the daemon and our shell channel can both write. */
    private const val DIR = "/data/local/tmp/.tailscale"
    private const val BIN = "$DIR/tsd"
    private const val SOCKET = "@tailscaled"
    private const val LOG = "$DIR/log"

    /** Rotate the daemon's log above this much disk use, keeping its last [LOG_KEEP_KB] in `log.prev`. */
    private const val LOG_MAX_KB = 4 * 1024
    private const val LOG_KEEP_KB = 1024

    /** The vendored daemon, named as a library so Android extracts it executable. */
    private const val LIB_NAME = "libtailscale.so"

    /**
     * Android's root certificates, passed to the daemon as `SSL_CERT_DIR`.
     *
     * The daemon is a Go binary built `GOOS=linux`, and crypto/x509 only consults Android's store
     * behind a `GOOS=android` check — so left alone it searches `/etc/ssl/certs` and friends, finds
     * nothing, and fails every TLS verification with "certificate signed by unknown authority".
     * That is what stopped it fetching a Let's Encrypt certificate for `tailscale serve`.
     *
     * The daemon sets this itself too (see `tailscale-patch/android_dns.go`); passing it here as
     * well means a daemon left running from an older launch line still gets it.
     */
    private const val CA_CERT_DIRS = "/system/etc/security/cacerts:/data/misc/keychain/certs-added"

    /**
     * What to hand `pkill -f`, with the first character of the filename bracketed.
     *
     * `pkill -f` matches against whole command lines, and the shell running the pkill has the
     * pattern in its own — so a plain path makes the shell a candidate for its own kill. `[t]sd`
     * is a regex matching the literal `tsd` in the daemon's command line, while the shell's line
     * holds the bracketed text, which that regex does not match.
     *
     * It must stay **single-quoted** at the call site: unquoted, the shell would glob `[t]sd`
     * against the real `tsd` sitting in that very directory and hand pkill the plain path again,
     * undoing the whole trick. And the kill has to be its own command — a line that also mentions
     * the unbracketed path (a `logout` before it, say) reintroduces the self-match.
     */
    private const val KILL_PATTERN = "$DIR/[t]sd"

    enum class State {
        /** No binary in this build (or a non-arm64 device) — the feature cannot run at all. */
        UNAVAILABLE,
        /** Binary present, but the one-time ADB authorisation the shell channel needs is missing. */
        NEEDS_ADB,
        /** Off, by the user's choice. */
        STOPPED,
        /** Daemon up but not signed in — needs an auth key or a browser sign-in. */
        NEEDS_KEY,
        /** Browser sign-in started: [Status.authUrl] is waiting to be scanned or opened. */
        AWAITING_LOGIN,
        STARTING,
        /** On the tailnet. [Status.ip] is the address to hand the user. */
        RUNNING,
        ERROR,
    }

    data class Status(
        val state: State,
        val ip: String? = null,
        val hostname: String? = null,
        val detail: String? = null,
        /** Short login URL from the daemon, shown as a QR code for the user's phone. */
        val authUrl: String? = null,
        /** Full MagicDNS name, e.g. `my-car.tail1234.ts.net` — the only host a cert can match. */
        val dnsName: String? = null,
        /** `https://<dnsName>/` while the daemon is terminating TLS for the companion. */
        val httpsUrl: String? = null,
    )

    private val _status = MutableStateFlow(Status(State.STOPPED))
    val status: StateFlow<Status> = _status.asStateFlow()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun savedAuthKey(context: Context): String = prefs(context).getString(KEY_AUTH_KEY, "") ?: ""

    fun hostname(context: Context): String =
        prefs(context).getString(KEY_HOSTNAME, null) ?: defaultHostname()

    fun saveHostname(context: Context, name: String) {
        prefs(context).edit().putString(KEY_HOSTNAME, sanitizeHostname(name)).apply()
    }

    /**
     * Tailscale hostnames become DNS labels, so anything but letters, digits and hyphens would be
     * rewritten by the control plane into something the user doesn't recognise.
     */
    private fun sanitizeHostname(raw: String): String =
        raw.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
            .take(40)
            .ifBlank { defaultHostname() }

    /**
     * The name this node registers under when the user hasn't chosen one.
     *
     * The model alone, with no "byd-" prefix: it is a name the user never asked for, and on a head
     * unit whose model string already says BYD it read as "byd-byd-…". Changing it only affects the
     * name the client reports — a machine renamed in the admin console keeps that name, because
     * Tailscale stops tracking the client hostname once it has been renamed there.
     */
    private fun defaultHostname(): String = sanitizeRaw(android.os.Build.MODEL).ifBlank { "byd-car" }

    private fun sanitizeRaw(raw: String): String =
        raw.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("").trim('-').take(20)

    /** The shipped daemon, or null when this build/ABI has none. */
    private fun binarySource(context: Context): File? {
        val f = File(context.applicationInfo.nativeLibraryDir, LIB_NAME)
        return f.takeIf { it.exists() }
    }

    fun isSupported(context: Context): Boolean = binarySource(context) != null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Copies the daemon out of the app's native-library directory and starts it, then reports what
     * happened. Idempotent: an already-running daemon is left alone.
     *
     * The copy is repeated on every start rather than checked, because an app update changes the
     * native-library path and can change the binary — and a 5 MB copy on local storage is cheaper
     * than being subtly out of date.
     */
    suspend fun start(context: Context): Status = withContext(Dispatchers.IO) {
        val source = binarySource(context)
            ?: return@withContext publish(Status(State.UNAVAILABLE, detail = "No Tailscale binary in this build"))
        if (!AdbPermissionManager.isSetupComplete(context)) {
            return@withContext publish(Status(State.NEEDS_ADB))
        }
        diag(context, "start: binary=${source.absolutePath} (${source.length()} bytes)")
        rotateLogIfLarge(context)

        val running = queryStatus(context)
        val replaced = installBinary(context, source)

        if (running != null && running.state != State.ERROR) {
            if (!replaced) {
                // Already up (the daemon outlives our process), so don't restart and drop the tailnet.
                diag(context, "start: daemon already up, state=${running.state} ip=${running.ip}")
                return@withContext publish(running)
            }
            // The process in memory is still the previous build. The CLI on disk is the new one, so
            // it would accept a new subcommand and then hand it to a daemon that has never heard of
            // it — which is how an app update used to look like a broken feature. Restart instead.
            diag(context, "start: daemon is the previous build — restarting it to pick up the update")
            publish(Status(State.STARTING))
            sh(context, "pkill -f '$KILL_PATTERN'", timeoutMs = 10_000L)
            kotlinx.coroutines.delay(500)
        }

        publish(Status(State.STARTING))
        // setsid so it outlives the shell that launched it — proven to survive the adb session.
        // `>>`, appending: rotateLogIfLarge empties the log in place while the daemon runs, and an
        // appending writer simply carries on at the new end.
        sh(
            context,
            "cd $DIR && HOME=$DIR SSL_CERT_DIR=$CA_CERT_DIRS setsid $BIN --tun=userspace-networking " +
                "--statedir=$DIR --socket=$SOCKET >> $LOG 2>&1 &",
            timeoutMs = 10_000L,
        )

        // The daemon needs a moment before its socket answers; poll rather than guess.
        repeat(10) { attempt ->
            kotlinx.coroutines.delay(600)
            val s = queryStatus(context)
            if (s != null) {
                diag(context, "start: daemon answered on poll ${attempt + 1}, state=${s.state}")
                return@withContext publish(s)
            }
        }
        diag(context, "start: daemon never answered after 10 polls")
        publish(Status(State.ERROR, detail = "Daemon did not come up — see $LOG"))
    }

    /** Signs in with [authKey] (and remembers it, so a later restart can re-register silently). */
    suspend fun connect(context: Context, authKey: String): Status = withContext(Dispatchers.IO) {
        val key = authKey.trim()
        if (key.isEmpty()) return@withContext publish(Status(State.NEEDS_KEY, detail = "Enter an auth key"))

        diag(context, "connect: requested (key ${key.length} chars)")
        val started = start(context)
        if (started.state == State.UNAVAILABLE || started.state == State.NEEDS_ADB) {
            diag(context, "connect: aborted, state=${started.state}")
            return@withContext started
        }

        // --accept-dns=false: in userspace mode it cannot set system DNS anyway, and accepting it
        // invites the tailnet-DNS-outage failure mode described in MD/TAILSCALE.md.
        val out = sh(
            context,
            "TS_BE_CLI=1 $BIN --socket=$SOCKET up --authkey='${key.replace("'", "")}' " +
                "--hostname=${hostname(context)} --accept-dns=false 2>&1; echo EXIT=$?",
            timeoutMs = 60_000L,
        ).orEmpty()

        if (!out.contains("EXIT=0")) {
            // Distinguish the two failures that look identical from the outside: the channel was
            // unusable (no output at all) versus tailscale itself refusing the key.
            val reason = out.lineSequence()
                .filter { it.isNotBlank() && !it.startsWith("EXIT=") }
                .firstOrNull()
                ?.take(160)
                ?: "The car's ADB channel did not answer — reopen the app and try again"
            diag(context, "connect: up FAILED :: ${out.replace('\n', ' ').take(200)}")
            return@withContext publish(Status(State.ERROR, detail = reason))
        }
        diag(context, "connect: up OK")
        prefs(context).edit()
            .putString(KEY_AUTH_KEY, key)
            .putBoolean(KEY_ENABLED, true)
            .apply()
        publish(queryStatus(context) ?: Status(State.ERROR, detail = "Signed in but no status"))
    }

    /**
     * Starts a **browser sign-in**: no auth key to generate, copy or type — the daemon produces a
     * short login URL, which the UI shows as a QR code for the user's phone.
     *
     * `tailscale up` without `--authkey` blocks until the user authorises, so it is launched
     * detached and the URL is collected by polling `status --json`. That also means the attempt
     * survives the user leaving the screen: the daemon keeps waiting, and re-opening Settings finds
     * the same pending URL.
     */
    suspend fun beginBrowserLogin(context: Context): Status = withContext(Dispatchers.IO) {
        diag(context, "browser login: requested")
        val started = start(context)
        if (started.state == State.UNAVAILABLE || started.state == State.NEEDS_ADB) return@withContext started
        if (started.state == State.RUNNING) return@withContext started
        if (started.authUrl != null) return@withContext publish(started)   // already pending

        // TS_BE_CLI=1 like every other CLI call here: without it the combined binary starts as the
        // daemon, rejects `up` as a non-flag argument and exits at once — so no sign-in URL was ever
        // minted and the QR never appeared, on any car (2.17.0; reported on an Atto 3).
        sh(
            context,
            "cd $DIR && HOME=$DIR TS_BE_CLI=1 setsid $BIN --socket=$SOCKET up " +
                // stdin from /dev/null too: `up` waits for the sign-in, and an inherited input is
                // enough for a shell to wait on it instead of returning straight away.
                "--hostname=${hostname(context)} --accept-dns=false > $DIR/up.log 2>&1 < /dev/null &",
            timeoutMs = 15_000L,
        )

        // The control plane has to mint the URL; a few seconds is normal.
        repeat(20) {
            kotlinx.coroutines.delay(1_000)
            val s = queryStatus(context)
            if (s?.authUrl != null) {
                diag(context, "browser login: url ready")
                prefs(context).edit().putBoolean(KEY_ENABLED, true).apply()
                return@withContext publish(s)
            }
            if (s?.state == State.RUNNING) return@withContext publish(s)
        }
        diag(context, "browser login: no url after 20s")
        publish(Status(State.ERROR, detail = "Could not start the sign-in — check the car's connection"))
    }

    /**
     * Polls while the user completes the sign-in on their phone. Returns as soon as the daemon
     * reports Running, or after [timeoutMs] — the caller decides what to say about a timeout, and
     * the daemon keeps waiting either way.
     */
    suspend fun awaitBrowserLogin(context: Context, timeoutMs: Long = 5 * 60_000L): Status =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(3_000)
                val s = queryStatus(context) ?: continue
                publish(s)
                if (s.state == State.RUNNING) {
                    diag(context, "browser login: authorised, ip=${s.ip}")
                    return@withContext s
                }
                // The URL expiring (or the user cancelling in the browser) drops us back here.
                if (s.state == State.NEEDS_KEY) return@withContext s
            }
            _status.value
        }

    /** Abandons a pending browser sign-in. The daemon stays up, just logged out. */
    suspend fun cancelBrowserLogin(context: Context): Status = withContext(Dispatchers.IO) {
        sh(context, "TS_BE_CLI=1 $BIN --socket=$SOCKET logout 2>&1", timeoutMs = 20_000L)
        publish(queryStatus(context) ?: Status(State.NEEDS_KEY))
    }

    /** Stops the daemon and forgets the key. The node stays in the user's console until removed. */
    suspend fun disconnect(context: Context): Status = withContext(Dispatchers.IO) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, false)
            .remove(KEY_AUTH_KEY)
            .apply()
        // Two calls, not one line: a command that also names the unbracketed binary would put that
        // path in the shell's own command line, and pkill would match the shell as well.
        sh(context, "TS_BE_CLI=1 $BIN --socket=$SOCKET logout 2>&1", timeoutMs = 20_000L)
        sh(context, "pkill -f '$KILL_PATTERN'", timeoutMs = 10_000L)
        publish(Status(State.STOPPED))
    }

    /**
     * Starts the daemon again if the user had it on — called when the telemetry service starts,
     * which is the app's "the car woke up" moment. A no-op when disabled or unsupported.
     */
    suspend fun restoreIfEnabled(context: Context) {
        if (!isEnabled(context) || !isSupported(context)) return
        val s = start(context)
        // A head-unit reboot wipes /data/local/tmp, taking the state directory with it — so the
        // daemon comes back logged out and has to re-register from the saved key.
        if (s.state == State.NEEDS_KEY) {
            val key = savedAuthKey(context)
            if (key.isNotEmpty()) connect(context, key)
        }
    }

    /** Re-reads the daemon's own view of the world. Null when it isn't answering. */
    suspend fun refresh(context: Context): Status = withContext(Dispatchers.IO) {
        if (!isSupported(context)) return@withContext publish(Status(State.UNAVAILABLE))
        if (!AdbPermissionManager.isSetupComplete(context)) return@withContext publish(Status(State.NEEDS_ADB))
        publish(queryStatus(context) ?: Status(if (isEnabled(context)) State.ERROR else State.STOPPED))
    }

    /**
     * The tailnet address, straight from the daemon. The interface scan in `WebServerManager`
     * cannot find this: userspace-networking creates no OS-level interface, so the address exists
     * only inside tailscaled.
     */
    fun currentIp(): String? = _status.value.ip

    /** `https://<machine>.<tailnet>.ts.net/`, or null when TLS isn't being terminated. */
    fun currentHttpsUrl(): String? = _status.value.httpsUrl

    // ── HTTPS (tailscale serve) ───────────────────────────────────────────────

    private val _httpsEnabled = MutableStateFlow(false)

    /**
     * Whether the daemon is terminating TLS for the companion.
     *
     * A flow rather than a plain preference read, because the switch driving it can leave
     * composition in the middle of its own operation — `enableHttps` calls [start], which may
     * publish [State.STARTING], and the row is only shown while [State.RUNNING]. Local state would
     * then be discarded and re-seeded from a preference the operation has not written yet, which is
     * exactly why the toggle used to need pressing twice.
     */
    val httpsEnabled: StateFlow<Boolean> = _httpsEnabled.asStateFlow()

    fun isHttpsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HTTPS, false).also { _httpsEnabled.value = it }

    /**
     * Puts the companion behind real TLS on the tailnet.
     *
     * `tailscale serve` makes the daemon itself listen on 443 for the node, terminate TLS with a
     * Let's Encrypt certificate for the node's MagicDNS name, and proxy to our plain-HTTP server on
     * loopback. The certificate is fetched and renewed by the daemon — nothing here handles keys.
     *
     * Only the `.ts.net` name gets TLS: a certificate cannot be issued for a bare 100.x address, so
     * the LAN and IP URLs stay HTTP. Needs **MagicDNS** and **HTTPS Certificates** switched on for
     * the tailnet, which is a one-time admin-console setting and the usual reason this fails.
     */
    suspend fun enableHttps(context: Context, port: Int): Status = withContext(Dispatchers.IO) {
        // `serve` only exists in builds from 2.17.0 onward, so make sure the daemon on the device is
        // the one this app ships before asking it for a subcommand it may never have had.
        start(context)

        val out = sh(
            context,
            "TS_BE_CLI=1 $BIN --socket=$SOCKET serve --bg --https=443 " +
                "http://127.0.0.1:$port 2>&1; echo EXIT=$?",
            60_000L,
        ) ?: return@withContext publish(
            _status.value.copy(state = State.ERROR, detail = "ADB channel unavailable")
        )

        val ok = out.contains("EXIT=0")
        if (!ok) {
            // The daemon's own words are more useful than anything invented here — "HTTPS must be
            // enabled" points straight at the admin console.
            val reason = out.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() && !it.startsWith("EXIT=") }
                ?: "serve failed"
            diag(context, "https enable failed :: $reason")
            return@withContext publish(_status.value.copy(detail = reason))
        }

        prefs(context).edit().putBoolean(KEY_HTTPS, true).apply()
        _httpsEnabled.value = true
        diag(context, "https enabled on :443 -> 127.0.0.1:$port")
        refresh(context)
    }

    suspend fun disableHttps(context: Context): Status = withContext(Dispatchers.IO) {
        sh(context, "TS_BE_CLI=1 $BIN --socket=$SOCKET serve --https=443 off 2>&1; echo EXIT=$?", 30_000L)
        prefs(context).edit().putBoolean(KEY_HTTPS, false).apply()
        _httpsEnabled.value = false
        diag(context, "https disabled")
        refresh(context)
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /**
     * Puts the shipped daemon on the device, and reports whether it actually replaced an older one.
     *
     * Two things here are the fix for a real bug rather than caution. The binary is **staged and
     * renamed** instead of copied over the top, because the target is normally a *running*
     * executable and writing to one fails with `ETXTBSY`; a rename leaves the running process on
     * its old inode and succeeds. And the swap is **hash-checked** rather than done every time, so
     * the common case costs one `sha256sum` and the daemon is left alone.
     *
     * Before this, an app update shipping a new daemon could never deploy it: `start()` returned
     * early whenever a daemon was already up, and the copy would have failed anyway. The car kept
     * running the build it first installed, so a newly added subcommand came back as
     * "unknown subcommand".
     */
    private suspend fun installBinary(context: Context, source: File): Boolean {
        val want = sha256(source)
        val have = sh(context, "sha256sum $BIN 2>/dev/null", timeoutMs = 20_000L)
            ?.trim()?.substringBefore(' ')?.takeIf { it.length == 64 }

        if (want != null && want == have) {
            diag(context, "install: already current (${want.take(12)})")
            return false
        }
        diag(context, "install: replacing daemon have=${have?.take(12) ?: "none"} want=${want?.take(12)}")

        val staged = "$BIN.new"
        val out = sh(
            context,
            "mkdir -p $DIR && cp ${source.absolutePath} $staged && chmod 755 $staged && " +
                "mv -f $staged $BIN && echo OK",
            timeoutMs = 30_000L,
        )
        if (out?.contains("OK") != true) {
            // Not fatal: an older daemon that still runs beats no daemon at all, and the caller
            // will surface whatever the old one can or can't do.
            diag(context, "install: FAILED :: ${out.orEmpty().take(160)}")
            return false
        }
        return true
    }

    private fun sha256(file: File): String? = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private suspend fun queryStatus(context: Context): Status? {
        val json = sh(context, "TS_BE_CLI=1 $BIN --socket=$SOCKET status --json 2>/dev/null", 15_000L)
            ?.trim()
            ?.takeIf { it.startsWith("{") }
            ?: return null
        return try {
            val o = JSONObject(json)
            val backend = o.optString("BackendState")
            val self = o.optJSONObject("Self")
            val ips = self?.optJSONArray("TailscaleIPs")
            val ip = (0 until (ips?.length() ?: 0))
                .map { ips!!.getString(it) }
                .firstOrNull { !it.contains(':') }          // IPv4
            // Two names exist and they are not the same thing. HostName is what this client
            // reports about itself, which we set with --hostname and re-assert on every `up`.
            // DNSName is the MagicDNS name, which follows the machine name in the admin console —
            // so a rename there shows up only here, and that is the name to put in front of the
            // user. HostName stays as the fallback for a tailnet with MagicDNS switched off,
            // where DNSName comes back empty.
            // MagicDNS hands it back fully qualified and root-dotted; the trailing dot is correct
            // DNS but wrong in a URL.
            val dnsName = self?.optString("DNSName")?.trimEnd('.')?.takeIf { it.isNotBlank() }
            val host = dnsName?.substringBefore('.')
                ?: self?.optString("HostName")?.takeIf { it.isNotBlank() }
            val authUrl = o.optString("AuthURL").takeIf { it.isNotBlank() }
            val httpsUrl = dnsName
                ?.takeIf { backend == "Running" && isHttpsEnabled(context) }
                ?.let { "https://$it/" }
            when (backend) {
                "Running" -> Status(State.RUNNING, ip, host, dnsName = dnsName, httpsUrl = httpsUrl)
                // An AuthURL while logged out means a browser sign-in is already in flight — the
                // daemon outlives the app, so this survives the user leaving the screen.
                "NeedsLogin", "NoState" ->
                    if (authUrl != null) Status(State.AWAITING_LOGIN, null, host, authUrl = authUrl, dnsName = dnsName)
                    else Status(State.NEEDS_KEY, null, host, dnsName = dnsName)
                "Starting" -> Status(State.STARTING, ip, host, dnsName = dnsName)
                "Stopped" -> Status(State.STOPPED, ip, host, dnsName = dnsName)
                else -> Status(State.ERROR, ip, host, detail = backend, dnsName = dnsName)
            }
        } catch (e: Exception) {
            Log.w(TAG, "status parse failed: ${e.message}")
            null
        }
    }

    /**
     * Keeps the daemon's own log from growing without end. It writes a few MB a day and used to be
     * emptied only when the daemon restarted, which on a car that stays up can be weeks apart (35 MB
     * on the dev car, 2026-10-05, in /data/local/tmp). Over [LOG_MAX_KB] the last [LOG_KEEP_KB] go to
     * `log.prev` and the log is emptied in place, so the running daemon keeps writing to it.
     *
     * Measured by disk blocks (`du`), not length: a daemon started before this didn't open the log
     * for appending, so after an in-place empty it writes on at its old offset — the file reads as
     * long but holds only the new data, and a length check would rotate it again on every call.
     */
    private suspend fun rotateLogIfLarge(context: Context) {
        sh(
            context,
            "f=$LOG; if [ -f \$f ] && [ \$(du -k \$f | cut -f1) -gt $LOG_MAX_KB ]; then " +
                "tail -c ${LOG_KEEP_KB * 1024} \$f > \$f.prev && : > \$f && echo rotated; " +
                "else echo kept; fi",
            timeoutMs = 20_000L,
        )
    }

    /**
     * One shell command over the ADB channel. Returns null when the channel itself was unusable —
     * which is a different failure from "the command ran and said no", and the two used to be
     * indistinguishable from the outside.
     */
    private suspend fun sh(context: Context, command: String, timeoutMs: Long): String? {
        val started = System.currentTimeMillis()
        val res = AdbPermissionManager.runShellBatch(context, listOf(command), timeoutMs)
        val r = res.firstOrNull()
        val took = System.currentTimeMillis() - started
        if (r == null) {
            diag(context, "shell UNREACHABLE after ${took}ms :: ${redact(command).take(120)}")
            return null
        }
        diag(
            context,
            "shell exit=${r.exitCode} ${took}ms :: ${redact(command).take(90)} " +
                ":: out='${r.output.replace('\n', ' ').take(160)}'",
        )
        return r.output
    }

    private fun publish(s: Status): Status {
        _status.value = s
        return s
    }

    /**
     * Release builds strip `Log.*`, so diag.log is the only record of what this did — and this is a
     * multi-step orchestration over a shell channel, where "it didn't work" has half a dozen
     * possible meanings. Every step reports, and the auth key is redacted so a user can send the
     * file without leaking a credential that would let anyone join their network.
     */
    private fun diag(context: Context, message: String) {
        DiagLog.event(context.applicationContext, TAG, message)
    }

    private fun redact(command: String): String =
        command.replace(Regex("--authkey='?[^ ']*'?"), "--authkey=<redacted>")
}
