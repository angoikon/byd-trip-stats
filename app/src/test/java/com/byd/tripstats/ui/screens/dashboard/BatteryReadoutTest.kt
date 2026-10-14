package com.byd.tripstats.ui.screens.dashboard

import com.byd.tripstats.data.config.CarCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [remainingKwhForTile] — the battery tile's "kWh remaining" view.
 */
class BatteryReadoutTest {

    private val shark = CarCatalog.BYD_SHARK_6_AWD                 // PHEV, 29.58 kWh, 25 % EV reserve
    private val sealion7 = CarCatalog.BYD_SEALION_7_DESIGN         // BEV, 82.5 kWh
    private val phevNoReserve = CarCatalog.allCars.first { it.isPhev && it.phevEvReserveSocPct == null }

    @Test fun `a sane reading is shown as it is`() {
        assertEquals(30.0, remainingKwhForTile(30.0, 37.0, sealion7)!!, 0.0)
        assertEquals(2.1, remainingKwhForTile(2.1, 19.0, phevNoReserve)!!, 0.0)
    }

    @Test fun `the Shark's constant 128 gives way to the energy above its reserve`() {
        // 29.58 kWh × (42 − 25) % — the figure the range projection uses
        assertEquals(29.58 * 0.17, remainingKwhForTile(128.0, 42.0, shark)!!, 1e-9)
    }

    @Test fun `SoC passed off as kWh is dropped on a PHEV`() {
        // Shark 2026-10-05: getRemainingBatteryPower 95.5 at 95 % SoC
        assertEquals(29.58 * 0.70, remainingKwhForTile(95.5, 95.0, shark)!!, 1e-9)
        // A PHEV without a measured reserve shows a dash instead
        assertNull(remainingKwhForTile(18.0, 18.0, phevNoReserve.copy(batteryKwh = 26.0)))
    }

    @Test fun `low SoC on a PHEV isn't mistaken for the SoC bug`() {
        assertEquals(3.0, remainingKwhForTile(3.0, 6.0, phevNoReserve)!!, 0.0)
    }

    @Test fun `a BEV is only held to its pack size`() {
        assertNull(remainingKwhForTile(200.0, 50.0, sealion7))
        // a reading equal to the SoC is fine on a BEV (a ~100 kWh pack does that)
        assertEquals(50.0, remainingKwhForTile(50.0, 50.0, sealion7)!!, 0.0)
    }

    @Test fun `nothing reported shows a dash, unless the reserve is known`() {
        assertNull(remainingKwhForTile(null, 50.0, sealion7))
        assertNull(remainingKwhForTile(0.0, 50.0, phevNoReserve))
        assertEquals(29.58 * 0.25, remainingKwhForTile(null, 50.0, shark)!!, 1e-9)
    }

    @Test fun `below the reserve there is nothing left`() {
        assertEquals(0.0, remainingKwhForTile(128.0, 20.0, shark)!!, 0.0)
    }

    @Test fun `no car selected keeps any positive reading`() {
        assertEquals(128.0, remainingKwhForTile(128.0, 42.0, null)!!, 0.0)
    }
}
