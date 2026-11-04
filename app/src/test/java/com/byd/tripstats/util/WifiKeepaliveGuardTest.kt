package com.byd.tripstats.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [WifiKeepaliveGuard] — the decision the UID-2000 daemon makes every 30 s on whether
 * to run `svc wifi enable`, and the "12V,SoC,carOn" line the app hands it. A false "enable" with the
 * car on tears down the hotspot wireless Android Auto runs on (issue #35); a false skip leaves the
 * head unit off the LAN after switch-off.
 */
class WifiKeepaliveGuardTest {

    private fun reason(
        speedKmh: Double = 0.0,
        carOn: Int? = 0,
        hotspotOn: Boolean? = false,
        v12: Double? = 12.8,
        soc: Double? = 60.0,
        wifiUp: Boolean = false,
    ) = WifiKeepaliveGuard.reason(speedKmh, carOn, hotspotOn, v12, soc, wifiUp)

    @Test
    fun `parked, car off, Wi-Fi cut - turns Wi-Fi on`() {
        assertEquals("enable", reason())
    }

    @Test
    fun `issue 35 - standing still with the car on and wireless Android Auto - leaves Wi-Fi alone`() {
        // Normal Wi-Fi reads down for the whole AA session: the hotspot has the radio.
        assertEquals("skip-car-on", reason(carOn = 2, hotspotOn = true))
        // The hotspot alone is enough, for a car that reports no car state.
        assertEquals("skip-hotspot", reason(carOn = null, hotspotOn = true))
        // And a hotspot left on after switch-off is not torn down either.
        assertEquals("skip-hotspot", reason(carOn = 0, hotspotOn = true))
    }

    @Test
    fun `car on or awake - leaves a Wi-Fi-off set while parked alone`() {
        assertEquals("skip-car-on", reason(carOn = 2))
        assertEquals("skip-car-on", reason(carOn = 1))
    }

    @Test
    fun `unknown car and hotspot state block nothing`() {
        assertEquals("enable", reason(carOn = null, hotspotOn = null))
    }

    @Test
    fun `driving - leaves Wi-Fi alone whatever else`() {
        assertEquals("skip-driving", reason(speedKmh = 50.0, carOn = null, hotspotOn = null))
        assertEquals("enable", reason(speedKmh = 2.0))
    }

    @Test
    fun `low batteries back off, implausible readings do not block`() {
        assertEquals("skip-12v", reason(v12 = 11.6))
        assertEquals("skip-soc", reason(soc = 12.0))
        assertEquals("enable", reason(v12 = 11800.0))   // millivolts: units mismatch, ignored
        assertEquals("enable", reason(v12 = null, soc = null))
    }

    @Test
    fun `Wi-Fi already up - nothing to do`() {
        assertEquals("wifi-up", reason(wifiUp = true))
    }

    @Test
    fun `guard line round-trips, blanks read as unknown`() {
        assertEquals("12.8,61.5,2", WifiKeepaliveGuard.format(12.8, 61.5, 2))
        assertEquals(",,", WifiKeepaliveGuard.format(null, null, null))
        assertEquals(WifiKeepaliveGuard.Guard(12.8, 61.5, 0), WifiKeepaliveGuard.parse("12.8,61.5,0\n"))
        assertEquals(WifiKeepaliveGuard.Guard(null, null, null), WifiKeepaliveGuard.parse(",,"))
        assertEquals(WifiKeepaliveGuard.Guard(null, null, null), WifiKeepaliveGuard.parse(""))
    }

    @Test
    fun `a line written before carOn existed reads carOn as unknown`() {
        assertEquals(WifiKeepaliveGuard.Guard(12.9, 53.4, null), WifiKeepaliveGuard.parse("12.9,53.4"))
    }

    @Test
    fun `hotspot enabling or enabled counts as on`() {
        assertTrue(WifiKeepaliveGuard.hotspotActive(12))
        assertTrue(WifiKeepaliveGuard.hotspotActive(13))
        assertFalse(WifiKeepaliveGuard.hotspotActive(10))  // disabling
        assertFalse(WifiKeepaliveGuard.hotspotActive(11))  // disabled
        assertFalse(WifiKeepaliveGuard.hotspotActive(14))  // failed
    }
}
