package com.byd.tripstats.data.repository

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [ChargingRepository.implausibleReconstruction] — the guard against "Reconstructed"
 * charges built from a bad reading. The phantoms are a Sealion 7's (@luads, 2026-10-10 11:00 and
 * 11:31): 0–1 minute, panel 63 % → 63 %, 51 kWh from a saved BMS reading of about 1 %.
 */
class ImplausibleReconstructionTest {

    private val minute = 60_000L
    private val hour = 60 * minute

    private fun check(kwh: Double, elapsedMs: Long, bmsBefore: Double, panelBefore: Int) =
        ChargingRepository.implausibleReconstruction(kwh, elapsedMs, bmsBefore, panelBefore)

    @Test
    fun `the 10 October phantoms are rejected`() {
        assertNotNull(check(51.0, 1 * minute, 1.2, 63))
        assertNotNull(check(51.0, 40_000L, 1.2, 63))
    }

    @Test
    fun `energy that would need more than any charger is rejected, whatever the readings`() {
        // A plausible-looking baseline, but 40 kWh in five minutes is 480 kW.
        assertNotNull(check(40.0, 5 * minute, 30.0, 31))
    }

    @Test
    fun `a saved BMS reading far from the saved panel is rejected`() {
        assertNotNull(check(20.0, 3 * hour, 5.0, 40))
    }

    @Test
    fun `a real overnight AC charge passes`() {
        // 11 kW for six hours, 20 % → 72 % on an 82.5 kWh pack.
        assertNull(check(42.9, 6 * hour, 20.2, 21))
    }

    @Test
    fun `a fast DC charge passes`() {
        // 10 % → 80 % in 30 minutes: about 116 kW.
        assertNull(check(57.8, 30 * minute, 10.0, 11))
    }

    @Test
    fun `without a saved panel only the power rule applies`() {
        assertNull(check(12.4, 2 * hour, 42.0, 0))
        assertNotNull(check(51.0, 1 * minute, 1.2, 0))
    }
}
