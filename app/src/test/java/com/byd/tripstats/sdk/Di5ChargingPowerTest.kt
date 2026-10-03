package com.byd.tripstats.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [di5ChargingPowerKw] — which DiLink-5 charge-power readings count as a charge.
 * The readings are a Sealion 7's, through one DC charge (@luads, 2026-10-03).
 */
class Di5ChargingPowerTest {

    @Test
    fun `the at-rest reading with no gun in is not a charge`() {
        assertNull(di5ChargingPowerKw(359.4, 0))
    }

    @Test
    fun `the at-rest reading is not a charge even with a gun in`() {
        // It sits inside the accepted range, so the gun can't be the only thing keeping it out.
        assertNull(di5ChargingPowerKw(359.4, 2))
        // The listener delivers it as a Float.
        assertNull(di5ChargingPowerKw(359.4f.toDouble(), 2))
    }

    @Test
    fun `a gun in but no power yet is not a charge`() {
        assertNull(di5ChargingPowerKw(0.0, 2))   // plugged, before the ramp
        assertNull(di5ChargingPowerKw(0.0, 1))   // a DC plug passing through AC on the way in
    }

    @Test
    fun `power with a charging gun in is a charge`() {
        assertEquals(5.6, di5ChargingPowerKw(5.6, 2)!!, 0.0)     // start of the ramp
        assertEquals(107.0, di5ChargingPowerKw(107.0, 2)!!, 0.0) // peak
        assertEquals(2.2, di5ChargingPowerKw(2.2, 1)!!, 0.0)     // AC
        assertEquals(50.0, di5ChargingPowerKw(50.0, 3)!!, 0.0)   // both
    }

    @Test
    fun `power without a charging gun is never a charge`() {
        assertNull(di5ChargingPowerKw(80.0, 0))
        assertNull(di5ChargingPowerKw(3.0, 4))     // discharge (V2L) gun
        assertNull(di5ChargingPowerKw(80.0, null)) // gun state not read yet
    }

    @Test
    fun `faster chargers are counted up to the cap`() {
        assertEquals(360.0, di5ChargingPowerKw(360.0, 2)!!, 0.0)
        assertEquals(400.0, di5ChargingPowerKw(DI5_CHARGING_POWER_MAX_KW, 2)!!, 0.0)
        assertNull(di5ChargingPowerKw(400.1, 2))
    }

    @Test
    fun `unreadable values are not a charge`() {
        assertNull(di5ChargingPowerKw(null, 2))
        assertNull(di5ChargingPowerKw(Double.NaN, 2))
        assertNull(di5ChargingPowerKw(-5.0, 2))
    }
}
