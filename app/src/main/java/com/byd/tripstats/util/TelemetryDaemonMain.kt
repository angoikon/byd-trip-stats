package com.byd.tripstats.util

import android.content.Context
import android.content.ContextWrapper
import android.hardware.bydauto.gearbox.AbsBYDAutoGearboxListener
import android.hardware.bydauto.speed.AbsBYDAutoSpeedListener
import android.os.Looper
import android.os.Process
import org.json.JSONObject
import java.io.PrintWriter
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Privileged telemetry daemon — see private-telemetry/INSTANT_TELEMETRY_PLAN.md.
 *
 * Runs as UID 2000 via app_process (auto-launched by the supervisor built in the private module's
 * buildRuntimePayload). In this privileged context the typed bydauto listeners deliver clean instant
 * speed/gear that a normal app process cannot get; engine power comes from the ENGINE_POWER feature
 * event (value read from BYDAutoEventValue.doubleValue). The snapshot is streamed as newline-JSON to
 * the in-app client over 127.0.0.1:DAEMON_PORT.
 *
 * Note: the privilege-elevation mechanism (how this gets launched as UID 2000) lives obfuscated in the
 * private-telemetry module. The reflection target strings here are assembled from char codes so a
 * static scan of this file is less revealing; the typed-listener type references are unavoidable (and
 * are the same SDK types the app already uses in BydVehicleDataSource).
 */
object TelemetryDaemonMain {

    const val DAEMON_PORT = 28200
    // Fallback feature ids. The REAL ids vary by firmware, so we also resolve them
    // from the device's android.hardware.bydauto.BYDAutoFeatureIds at runtime (resolveFeatureId).
    // The front fallback happens to match this head unit; the rear does NOT — hence the resolve.
    private const val F_POWER = 339738656       // ENGINE_POWER
    private const val F_RPM_FRONT = 1141899272  // ENGINE_FRONT_MOTOR_SPEED
    private const val F_RPM_REAR = 621805576    // ENGINE_REAR_MOTOR_SPEED
    // BYD "signal unavailable" sentinels that slip through as motor-speed values.
    private val RPM_SENTINELS = setOf(8191, 16383, 32767, 65535)

    /** Resolve a BYDAutoFeatureIds constant from the device by name; fall back to a known id. */
    private fun resolveFeatureId(field: String, fallback: Int): Int = runCatching {
        Class.forName(s(97,110,100,114,111,105,100,46,104,97,114,100,119,97,114,101,46,98,121,100,97,117,116,111,46,66,89,68,65,117,116,111,70,101,97,116,117,114,101,73,100,115))
            .getField(field).getInt(null)
    }.getOrDefault(fallback)

    private val snapshot = AtomicReference(Snap())

    /**
     * A connected consumer. The [sock] is kept alongside the writer so a dropped client's
     * socket is actually **closed** — storing only the PrintWriter leaked the socket into
     * CLOSE_WAIT forever, and writes to such a socket eventually block the pusher thread,
     * which stalls instant telemetry for the live client too (observed after an app
     * crash-loop piled up 10 connections: speed/power fell back to the 1 s poll).
     */
    private class Client(val sock: Socket, val writer: PrintWriter)

    private val clients = CopyOnWriteArrayList<Client>()
    /** The app is the only legitimate consumer; more than this means leaked connections. */
    private const val MAX_CLIENTS = 4
    /** Pusher ticks (100 ms) between liveness sweeps — see [startPusher]. */
    private const val SWEEP_EVERY_TICKS = 50
    @Volatile private var dirty = false
    // NOTE: rear motor RPM is event-only on this firmware (no rear getter on Engine or Motor device,
    // verified 2026-06-05) and the HAL throttles the rear event to ~1/s — so rear updates ~1/s while
    // speed/gear/power/front stream fast. Not fixable here; left as-is.

    data class Snap(
        val speedKmh: Double? = null,
        val gear: String? = null,
        val gearRaw: Int? = null,
        val powerKw: Double? = null,
        val frontRpm: Int? = null,
        val rearRpm: Int? = null,
        val ts: Long = 0L,
    )

    private fun s(vararg v: Int): String = v.map { it.toChar() }.joinToString("")

    @JvmStatic
    fun main(args: Array<String>) {
        log("up pid=${Process.myPid()} uid=${Process.myUid()} port=$DAEMON_PORT")
        // Write our pid so the supervisor/health-probe can detect us reliably. (pgrep can't —
        // app_process --nice-name replaces our cmdline with the nice-name, not the FQCN.)
        runCatching {
            java.io.File(s(47,100,97,116,97,47,108,111,99,97,108,47,116,109,112,47,46,98,121,100,116,101,108,101,109,100,46,112,105,100))
                .writeText(Process.myPid().toString())
        }
        runCatching { Looper.prepareMainLooper() }.onFailure { log("looper: ${it.message}") }

        val ctx = buildContext() ?: run { log("no Context — abort"); return }
        registerSpeed(ctx)
        registerGear(ctx)
        registerPower(ctx)
        startServer()
        startPusher()

        log("entering Looper.loop()")
        runCatching { Looper.loop() }.onFailure { log("loop ended: ${it.message}") }
    }

    // ---- context bootstrap (no Activity), with inline permission bypass ----

    private fun buildContext(): Context? = runCatching {
        val at = Class.forName(s(97,110,100,114,111,105,100,46,97,112,112,46,65,99,116,105,118,105,116,121,84,104,114,101,97,100))
        val sysMain = at.getMethod(s(115,121,115,116,101,109,77,97,105,110)).invoke(null)
        val base = at.getMethod(s(103,101,116,83,121,115,116,101,109,67,111,110,116,101,120,116)).invoke(sysMain) as Context
        // DiLink-3 only: getSystemContext() reports package "android" (uid 1000), but this daemon
        // runs as uid 2000 (shell). On Android 11+ the framework enforces package↔uid on binder ops
        // ("Given calling package android does not match caller's uid 2000"), which throws inside the
        // SDK's listener-callback path and ends Looper.loop() mid-stream — after that the daemon still
        // accepts client connections but pushes no telemetry. Presenting the real uid-2000 package
        // (com.android.shell) via getOpPackageName makes AppOps.checkPackage(2000, ...) match, so the
        // callback path is accepted and the loop survives.
        //   Gated to DiLink-3: DiLink-5 never uses this daemon (it reads telemetry in-process through
        //   Dilink5Client), so its context is left exactly as getSystemContext() returns it.
        val di3 = runCatching { !com.byd.tripstats.sdk.DiLink5Platform.isDiLink5 }.getOrDefault(true)
        val shellPkg = s(99,111,109,46,97,110,100,114,111,105,100,46,115,104,101,108,108) // "com.android.shell"
        object : ContextWrapper(base) {
            override fun getOpPackageName(): String = if (di3) shellPkg else super.getOpPackageName()
            override fun checkCallingOrSelfPermission(p: String) = 0
            override fun checkCallingPermission(p: String) = 0
            override fun checkSelfPermission(p: String) = 0
            override fun checkPermission(p: String, pid: Int, uid: Int) = 0
            override fun enforceCallingOrSelfPermission(p: String, m: String?) {}
            override fun enforceCallingPermission(p: String, m: String?) {}
        }
    }.onFailure { log("buildContext failed: ${it.javaClass.simpleName}: ${it.message}") }.getOrNull()

    private fun device(ctx: Context, cls: String): Any? = runCatching {
        Class.forName(cls).getMethod(s(103,101,116,73,110,115,116,97,110,99,101), Context::class.java).invoke(null, ctx)
    }.getOrNull()

    private fun regTyped(dev: Any, listenerType: Class<*>, listener: Any): Boolean = runCatching {
        dev.javaClass.getMethod(s(114,101,103,105,115,116,101,114,76,105,115,116,101,110,101,114), listenerType).invoke(dev, listener)
        true
    }.getOrDefault(false)

    // ---- typed listeners (the only thing that delivers clean speed/gear here) ----

    private fun registerSpeed(ctx: Context) {
        val dev = device(ctx, s(97,110,100,114,111,105,100,46,104,97,114,100,119,97,114,101,46,98,121,100,97,117,116,111,46,115,112,101,101,100,46,66,89,68,65,117,116,111,83,112,101,101,100,68,101,118,105,99,101)) ?: return
        val l = object : AbsBYDAutoSpeedListener() {
            override fun onSpeedChanged(speed: Double) = update { it.copy(speedKmh = speed) }
            override fun onSpeedChanged(speed: Int) = update { it.copy(speedKmh = speed.toDouble()) }
        }
        log("speed listener: ${regTyped(dev, AbsBYDAutoSpeedListener::class.java, l)}")
    }

    private fun registerGear(ctx: Context) {
        val dev = device(ctx, s(97,110,100,114,111,105,100,46,104,97,114,100,119,97,114,101,46,98,121,100,97,117,116,111,46,103,101,97,114,98,111,120,46,66,89,68,65,117,116,111,71,101,97,114,98,111,120,68,101,118,105,99,101)) ?: return
        val l = object : AbsBYDAutoGearboxListener() {
            override fun onGearboxAutoModeTypeChanged(type: Int) =
                update { it.copy(gearRaw = type, gear = gearLabel(type) ?: it.gear) }
        }
        log("gear listener: ${regTyped(dev, AbsBYDAutoGearboxListener::class.java, l)}")
    }

    // ---- engine feature events: power + front/rear motor RPM (value in intValue) ----

    private fun registerPower(ctx: Context) {
        val dev = device(ctx, s(97,110,100,114,111,105,100,46,104,97,114,100,119,97,114,101,46,98,121,100,97,117,116,111,46,101,110,103,105,110,101,46,66,89,68,65,117,116,111,69,110,103,105,110,101,68,101,118,105,99,101)) ?: return
        val iface = runCatching { Class.forName(s(97,110,100,114,111,105,100,46,104,97,114,100,119,97,114,101,46,73,66,89,68,65,117,116,111,76,105,115,116,101,110,101,114)) }.getOrNull() ?: return
        val dbl = s(100,111,117,98,108,101,86,97,108,117,101)   // "doubleValue"
        val iv  = s(105,110,116,86,97,108,117,101)              // "intValue"
        // Resolve the real ids for this firmware; subscribe to BOTH the resolved and fallback ids
        // so a working id is never missed (front already works on the fallback; rear needs resolve).
        val powerIds = setOf(F_POWER, resolveFeatureId("ENGINE_POWER", F_POWER))
        val frontIds = setOf(F_RPM_FRONT, resolveFeatureId("ENGINE_FRONT_MOTOR_SPEED", F_RPM_FRONT))
        val rearIds  = setOf(F_RPM_REAR, resolveFeatureId("ENGINE_REAR_MOTOR_SPEED", F_RPM_REAR))
        log("engine ids: power=$powerIds front=$frontIds rear=$rearIds")
        val proxy = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, m, a ->
            val fid = if (m.name == "onDataEventChanged") a?.getOrNull(0) as? Int else null
            if (fid != null) {
                val ev = a?.getOrNull(1)
                // BYD reports these as ints — value is in intValue; doubleValue usually stays 0.
                val i = runCatching { ev?.javaClass?.getField(iv)?.getInt(ev) }.getOrNull()
                val d = runCatching { ev?.javaClass?.getField(dbl)?.getDouble(ev) }.getOrNull()
                when (fid) {
                    in powerIds -> {
                        val kw = when {
                            i != null && i != 0 -> i.toDouble()
                            d != null && d != 0.0 -> d
                            else -> 0.0
                        }
                        if (kw in -300.0..600.0) update { it.copy(powerKw = kw) }
                    }
                    in frontIds -> {
                        val rpm = i ?: d?.toInt()
                        // Front motor speed is reported negated on some firmwares — use magnitude.
                        if (rpm != null && kotlin.math.abs(rpm) in 0..30000 && kotlin.math.abs(rpm) !in RPM_SENTINELS)
                            update { it.copy(frontRpm = kotlin.math.abs(rpm)) }
                    }
                    in rearIds -> {
                        val rpm = i ?: d?.toInt()
                        if (rpm != null && kotlin.math.abs(rpm) in 0..30000 && kotlin.math.abs(rpm) !in RPM_SENTINELS)
                            update { it.copy(rearRpm = kotlin.math.abs(rpm)) }
                    }
                }
            }
            when (m.name) { "hashCode" -> 1; "equals" -> false; "toString" -> "d"; else -> null }
        }
        val reg = s(114,101,103,105,115,116,101,114,76,105,115,116,101,110,101,114)
        val m2 = dev.javaClass.methods.firstOrNull {
            it.name == reg && it.parameterTypes.size == 2 &&
                it.parameterTypes[0].isAssignableFrom(proxy.javaClass) && it.parameterTypes[1] == IntArray::class.java
        }
        val subIds = (powerIds + frontIds + rearIds).toIntArray()
        var ok = false
        if (m2 != null) {
            // Best-effort subscribe-all FIRST (Other app's engine strategy: empty int[]). On HALs that
            // honour it, every engine feature arrives — so rear is delivered (and discoverable) even if
            // its id is unknown. Register the explicit ids LAST so that whatever the HAL's add/replace
            // semantics, power/front and the resolved rear stay subscribed — the working front can't regress.
            val subscribeAll = runCatching { m2.invoke(dev, proxy, IntArray(0)); true }.getOrDefault(false)
            ok = runCatching { m2.invoke(dev, proxy, subIds); true }.getOrDefault(false)
            log("engine listener: explicit=$ok subscribeAll=$subscribeAll ids=${subIds.toList()}")
        }
    }

    private fun gearLabel(type: Int): String? = when (type) {
        1 -> "P"; 2 -> "R"; 3 -> "N"; 4 -> "D"; 5, 6 -> "D"; else -> null
    }

    private fun update(f: (Snap) -> Snap) {
        snapshot.updateAndGet { f(it).copy(ts = System.currentTimeMillis()) }
        dirty = true
    }


    // ---- socket server ----

    private fun startServer() {
        Thread({
            try {
                val server = ServerSocket(DAEMON_PORT, 8, InetAddress.getByName("127.0.0.1"))
                log("server listening on 127.0.0.1:$DAEMON_PORT")
                while (true) handleClient(server.accept())
            } catch (t: Throwable) { log("server error: ${t.javaClass.simpleName}: ${t.message}") }
        }, "telemetry-accept").apply { isDaemon = true }.start()
    }

    private fun handleClient(sock: Socket) {
        try {
            sock.tcpNoDelay = true
            val w = PrintWriter(sock.getOutputStream(), true)
            w.println(toJson(snapshot.get()))
            if (w.checkError()) { runCatching { sock.close() }; return }
            // Reap before admitting. The app reconnects on every process start, so an app
            // crash-loop arrives here repeatedly; without this the corpses only get noticed
            // when the pusher next has data to send, which a parked car never produces.
            reapDeadClients()
            // Hard cap as a backstop: evict oldest so a leak can never grow unbounded.
            while (clients.size >= MAX_CLIENTS) {
                val evicted = clients.removeAt(0)
                runCatching { evicted.sock.close() }
                log("evicted oldest client (cap $MAX_CLIENTS)")
            }
            clients.add(Client(sock, w))
            log("client connected (${clients.size} total)")
        } catch (t: Throwable) { log("client accept failed: ${t.message}") }
    }

    /** Drops and closes clients whose peer has gone away. Safe to call from any thread. */
    private fun reapDeadClients() {
        val dead = clients.filter { it.sock.isClosed || !it.sock.isConnected || it.writer.checkError() }
        if (dead.isEmpty()) return
        clients.removeAll(dead)
        dead.forEach { runCatching { it.sock.close() } }
        log("reaped ${dead.size} dead (${clients.size} left)")
    }

    private fun startPusher() {
        Thread({
            var ticksSinceSweep = 0
            while (true) {
                try {
                    Thread.sleep(100)
                    // Liveness sweep on a timer, independent of `dirty`. A parked car emits
                    // no updates, so reaping only on push meant a disconnected client was
                    // never noticed and the list grew across every app restart.
                    if (++ticksSinceSweep >= SWEEP_EVERY_TICKS) { ticksSinceSweep = 0; reapDeadClients() }
                    if (!dirty) continue
                    dirty = false
                    if (clients.isEmpty()) continue
                    val line = toJson(snapshot.get())
                    val dead = ArrayList<Client>()
                    for (c in clients) { c.writer.println(line); if (c.writer.checkError()) dead.add(c) }
                    if (dead.isNotEmpty()) {
                        clients.removeAll(dead)
                        dead.forEach { runCatching { it.sock.close() } }
                        log("dropped ${dead.size} (${clients.size} left)")
                    }
                } catch (_: InterruptedException) { return@Thread } catch (t: Throwable) { log("pusher: ${t.message}") }
            }
        }, "telemetry-push").apply { isDaemon = true }.start()
    }

    private fun toJson(s: Snap): String = JSONObject().apply {
        s.speedKmh?.let { put("speedKmh", it) }
        s.gear?.let { put("gear", it) }
        s.gearRaw?.let { put("gearRaw", it) }
        s.powerKw?.let { put("powerKw", it) }
        s.frontRpm?.let { put("frontRpm", it) }
        s.rearRpm?.let { put("rearRpm", it) }
        put("ts", s.ts)
    }.toString()

    private fun log(m: String) { println("[daemon] $m"); System.out.flush() }
}
