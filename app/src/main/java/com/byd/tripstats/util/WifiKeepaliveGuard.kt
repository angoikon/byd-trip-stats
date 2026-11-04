package com.byd.tripstats.util

/**
 * The parked Wi-Fi keepalive's decision, and the guard line the app hands the daemon. Pure Kotlin, so
 * both sides share one definition and the decision is unit-tested (WifiKeepaliveGuardTest): the app
 * writes [format] (WifiKeepalive.pushGuard), and the UID-2000 daemon reads it back with [parse] and
 * asks [reason] every 30 s (TelemetryDaemonMain.startWifiKeepalive).
 *
 * The daemon runs `svc wifi enable` only on "enable". Every other reason leaves Wi-Fi alone:
 *  - "skip-driving": our own speed says the car moves, so a Wi-Fi-off the driver set is never undone.
 *  - "skip-car-on": the app reports the car switched on (carOn > 0, the rule its own isCarOn uses).
 *    The keepalive is for the minutes after switch-off, when the head unit drops Wi-Fi. With the car
 *    on, Wi-Fi being down is somebody's choice, or the radio is busy as a hotspot (next line).
 *  - "skip-hotspot": the head unit's own Wi-Fi hotspot is up or starting. Wireless Android Auto and
 *    CarPlay run on it, and on these head units the hotspot takes the radio away from normal Wi-Fi,
 *    so normal Wi-Fi reads down for the whole session. Turning normal Wi-Fi on there tears the
 *    hotspot down: the phone link dropped, BYD started the hotspot again, and 30 s later the keepalive
 *    tore it down again (issue #35, Seal, wireless Android Auto). BYD's own power service makes the
 *    same check before it touches the hotspot (CarPowerBinder.turnOffWifiAp / isCarPlayLink).
 *  - "skip-12v" / "skip-soc": the 12V or traction battery is low.
 *  - "wifi-up": nothing to do.
 * An unknown car state or hotspot state (null) blocks nothing, so a car that reports neither keeps
 * today's behaviour.
 */
internal object WifiKeepaliveGuard {

    const val V12_MIN = 12.0          // volts — skip below this to protect the 12V battery
    const val SOC_MIN = 15.0          // percent — skip below this (traction battery)
    const val MOVING_KMH = 2.0        // above this = driving → leave Wi-Fi alone

    /** WifiManager.WIFI_AP_STATE_ENABLING / WIFI_AP_STATE_ENABLED (hidden constants, Android 10+). */
    const val AP_STATE_ENABLING = 12
    const val AP_STATE_ENABLED = 13

    /** What the app last told the daemon. Each field is null when blank, missing or unreadable. */
    data class Guard(val v12: Double?, val soc: Double?, val carOn: Int?)

    /** The guard line: "12V,SoC,carOn", unknown values left blank. */
    fun format(v12: Double?, soc: Double?, carOn: Int?): String =
        "${v12 ?: ""},${soc ?: ""},${carOn ?: ""}"

    /** Reads [format]'s line. A line from before carOn was added ("12V,SoC") reads carOn as unknown. */
    fun parse(text: String): Guard {
        val parts = text.trim().split(",")
        fun field(i: Int) = parts.getOrNull(i)?.trim()
        return Guard(field(0)?.toDoubleOrNull(), field(1)?.toDoubleOrNull(), field(2)?.toIntOrNull())
    }

    /** Whether a WifiManager.getWifiApState() value means the hotspot is up or starting. */
    fun hotspotActive(apState: Int): Boolean = apState == AP_STATE_ENABLING || apState == AP_STATE_ENABLED

    /** The decision, see the class comment. "enable" is the only one that turns Wi-Fi on. */
    fun reason(
        speedKmh: Double,
        carOn: Int?,
        hotspotOn: Boolean?,
        v12: Double?,
        soc: Double?,
        wifiUp: Boolean,
    ): String = when {
        speedKmh > MOVING_KMH -> "skip-driving"
        carOn != null && carOn > 0 -> "skip-car-on"
        hotspotOn == true -> "skip-hotspot"
        // Only trust a 12V reading that looks like volts (10–15). Anything else is a units mismatch
        // (mV / 0.1V) or unreadable → don't block on it (the daemon's diag line logs it).
        v12 != null && v12 in 10.0..15.0 && v12 < V12_MIN -> "skip-12v"
        soc != null && soc in 0.0..100.0 && soc < SOC_MIN -> "skip-soc"
        wifiUp -> "wifi-up"
        else -> "enable"
    }
}
