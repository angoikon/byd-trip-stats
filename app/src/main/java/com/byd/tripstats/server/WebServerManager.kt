package com.byd.tripstats.server

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.byd.tripstats.data.local.BydStatsDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

object WebServerManager {

    private const val TAG = "WebServerManager"

    /** The official Tailscale Android client. We never depend on it — only recognise it. */
    private const val TAILSCALE_PACKAGE = "com.tailscale.ipn"

    private var server: LocalWebServer? = null

    val isRunning: Boolean get() = server?.isAlive == true

    private val _lockedOutCount = MutableStateFlow(0)
    val lockedOutCount: StateFlow<Int> = _lockedOutCount.asStateFlow()

    fun clearLockouts() {
        server?.clearLockouts()
    }

    /** Returns null on success, or an error message string if the server failed to bind. */
    fun start(
        context: Context,
        port: Int = LocalWebServer.DEFAULT_PORT,
        pin: String
    ): String? {
        if (isRunning) return null
        _lockedOutCount.value = 0
        val db = BydStatsDatabase.getDatabase(context.applicationContext)
        val s  = LocalWebServer(context.applicationContext, db, port, pin) { count ->
            _lockedOutCount.value = count
        }
        return try {
            s.start(NanoHTTPDReadTimeout, /* daemon= */ true)
            server = s
            Log.i(TAG, "Started on port $port")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start on port $port", e)
            e.message ?: "Failed to start server"
        }
    }

    fun stop() {
        server?.stop()
        server = null
        _lockedOutCount.value = 0
        Log.i(TAG, "Stopped")
    }

    /** Which network an address reaches the car on — decides what we call it in the UI. */
    enum class AccessKind { TAILNET_HTTPS, LAN, TAILNET, VPN, MOBILE, OTHER }

    /**
     * Interface-name prefixes for the cellular modem, which varies by chipset vendor.
     *
     * The head unit's 4G interface carries a carrier-assigned RFC1918 address (rmnet_data0 on the
     * dev car holds 10.25.6.183/28), and an address test alone reads that as "a private address,
     * so it must be the LAN" — which put a third row in the companion's address list that nothing
     * can ever connect to, sitting behind carrier NAT.
     */
    private val MOBILE_INTERFACE_PREFIXES =
        listOf("rmnet", "ccmni", "pdp", "wwan", "rmnet_data", "v4-rmnet", "seth_")

    data class AccessUrl(val kind: AccessKind, val host: String, val url: String)

    /**
     * Every address the companion can be reached at, most useful first.
     *
     * The server binds 0.0.0.0, so it answers on *all* of them — but the app used to show only
     * `NetworkInterface.getNetworkInterfaces()`'s first non-loopback IPv4, and enumeration order
     * is not guaranteed. On a car with a VPN up (Tailscale) that made the displayed URL a coin
     * flip between the Wi-Fi address and the tailnet one, so half the time it was the address
     * that doesn't work from wherever the user happened to be. Listing them all, labelled,
     * removes the guess.
     */
    fun listAccessUrls(context: Context): List<AccessUrl> {
        val port = server?.listeningPort ?: return emptyList()
        // The daemon's address is added explicitly: in userspace-networking mode it has no OS
        // interface, so it would otherwise be missing from the list entirely.
        val daemonIp = com.byd.tripstats.util.TailscaleManager.currentIp()
        val found = localIpv4Addresses().ifEmpty {
            listOfNotNull(wifiIpAddress(context)?.let { LocalAddress(null, it) })
        }
        val hosts = (found + listOfNotNull(daemonIp?.let { LocalAddress(null, it) }))
            .distinctBy { it.host }
        val tailscalePresent = isTailscaleInstalled(context) || daemonIp != null
        // Listed first when present: it is the only URL that gives the browser a secure context,
        // and that is what the Notification API and the clipboard both hang off.
        val httpsUrl = com.byd.tripstats.util.TailscaleManager.currentHttpsUrl()
            ?.let { listOf(AccessUrl(AccessKind.TAILNET_HTTPS, it.removePrefix("https://").trimEnd('/'), it)) }
            .orEmpty()
        return httpsUrl + hosts
            .map { AccessUrl(classify(it, daemonIp, tailscalePresent), it.host, "http://${it.host}:$port") }
            // A carrier-NAT mobile address answers nothing from outside the modem, so listing it
            // would only invite people to try an address that cannot work.
            .filterNot { it.kind == AccessKind.MOBILE && (isPrivate(it.host) || isCgnat(it.host)) }
            // LAN first: it is the common case and the fastest path when you are next to the car.
            .sortedBy { it.kind.ordinal }
    }

    /**
     * The port the companion is actually answering on, or the default when it isn't running.
     *
     * `tailscale serve` needs the real one: it proxies to a fixed loopback port, so pointing it at
     * the default after the user has moved the companion elsewhere would proxy to nothing.
     */
    fun currentPort(): Int = server?.listeningPort ?: LocalWebServer.DEFAULT_PORT

    /** Back-compat single URL — the first of [listAccessUrls], or null when the server is down. */
    fun getUrl(context: Context): String? = listAccessUrls(context).firstOrNull()?.url

    private data class LocalAddress(val iface: String?, val host: String)

    private fun classify(addr: LocalAddress, daemonIp: String?, tailscalePresent: Boolean): AccessKind = when {
        // The daemon told us its own address, so that one is known rather than guessed. Checked
        // first because a carrier can hand out CGNAT too, and then a range test alone would label
        // the modem's address "works from anywhere" — the exact opposite of the truth.
        addr.host == daemonIp -> AccessKind.TAILNET
        isMobileInterface(addr.iface) -> AccessKind.MOBILE
        // 100.64.0.0/10, the CGNAT range Tailscale hands out. Any VPN could use it, so the
        // "Tailscale" label is only claimed when its client is actually installed.
        isCgnat(addr.host) -> if (tailscalePresent) AccessKind.TAILNET else AccessKind.VPN
        isPrivate(addr.host) -> AccessKind.LAN
        else -> AccessKind.OTHER
    }

    private fun isMobileInterface(iface: String?): Boolean {
        val name = iface?.lowercase() ?: return false
        return MOBILE_INTERFACE_PREFIXES.any { name.startsWith(it) }
    }

    private fun octets(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        return if (nums.all { it in 0..255 }) nums.toIntArray() else null
    }

    private fun isCgnat(host: String): Boolean =
        octets(host)?.let { it[0] == 100 && it[1] in 64..127 } ?: false

    private fun isPrivate(host: String): Boolean = octets(host)?.let {
        it[0] == 10 ||
            (it[0] == 192 && it[1] == 168) ||
            (it[0] == 172 && it[1] in 16..31)
    } ?: false

    /**
     * True when the official Tailscale client is installed. Only used to decide whether a CGNAT
     * address may be *called* Tailscale; nothing depends on the app being there.
     *
     * NOTE if targetSdk is ever raised past 29: Android 11 introduced package-visibility
     * filtering, which would make this always return false unless `com.tailscale.ipn` is declared
     * in a manifest `<queries>` element. It works today only because we target 29.
     */
    fun isTailscaleInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(TAILSCALE_PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    /**
     * The car's tailnet address, when it has one.
     *
     * Asks our own daemon first: it runs in **userspace-networking** mode, which creates no
     * OS-level interface, so the address exists only inside tailscaled and an interface scan can
     * never see it. The scan remains as the fallback for the other way onto a tailnet — someone
     * running the official Tailscale client, which does create a real interface.
     */
    fun tailnetAddress(context: Context): String? =
        com.byd.tripstats.util.TailscaleManager.currentIp()
            // A CGNAT address on the modem is the carrier's, not a tailnet's, so the fallback
            // skips the cellular interface.
            ?: localIpv4Addresses()
                .firstOrNull { isCgnat(it.host) && !isMobileInterface(it.iface) }
                ?.host

    /** Every non-loopback IPv4 address, each paired with the interface carrying it. */
    private fun localIpv4Addresses(): List<LocalAddress> = try {
        NetworkInterface.getNetworkInterfaces()
            ?.asSequence()
            ?.filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            ?.flatMap { nif ->
                nif.inetAddresses.asSequence()
                    .filterIsInstance<Inet4Address>()
                    .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                    .mapNotNull { it.hostAddress }
                    .map { LocalAddress(nif.name, it) }
            }
            ?.distinctBy { it.host }
            ?.toList()
            ?: emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "Interface enumeration failed: ${e.message}")
        emptyList()
    }

    private fun wifiIpAddress(context: Context): String? = try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val ip = wm.connectionInfo.ipAddress
        if (ip == 0) null
        else "${ip and 0xff}.${(ip shr 8) and 0xff}.${(ip shr 16) and 0xff}.${(ip shr 24) and 0xff}"
    } catch (_: Exception) { null }

    // NanoHTTPD socket read timeout (ms); 5 s is a safe default
    private const val NanoHTTPDReadTimeout = 5_000
}
