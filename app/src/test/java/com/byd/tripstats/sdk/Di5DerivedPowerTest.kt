package com.byd.tripstats.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [di5DerivedPowerKw] — when the DiLink-5 power estimated from the usable kWh may
 * write the power value. The case it exists for: a Sealion 7 showing 11–18 kW at red lights.
 */
class Di5DerivedPowerTest {

    private val stale = DI5_MEASURED_POWER_FRESH_MS + 1_000L

    @Test
    fun `a fresh measured reading owns the value`() {
        assertNull(di5DerivedPowerKw(estimateKw = 18.0, speedKmh = 50.0, msSinceMeasuredPower = 200L))
        assertNull(di5DerivedPowerKw(estimateKw = 18.0, speedKmh = 0.0, msSinceMeasuredPower = 200L))
    }

    @Test
    fun `stopped at a red light reads 0, not the drive before it`() {
        assertEquals(0.0, di5DerivedPowerKw(estimateKw = 18.0, speedKmh = 0.0, msSinceMeasuredPower = stale)!!, 0.0)
        assertEquals(0.0, di5DerivedPowerKw(estimateKw = 12.6, speedKmh = 1.5, msSinceMeasuredPower = stale)!!, 0.0)
    }

    @Test
    fun `stopped reads 0 even before any estimate exists`() {
        assertEquals(0.0, di5DerivedPowerKw(estimateKw = Double.NaN, speedKmh = 0.0, msSinceMeasuredPower = stale)!!, 0.0)
    }

    @Test
    fun `moving with no measured reading uses the estimate`() {
        assertEquals(18.0, di5DerivedPowerKw(estimateKw = 18.0, speedKmh = 50.0, msSinceMeasuredPower = stale)!!, 0.0)
        // A car that has never sent a measured reading (the Shark 6 sends no voltage).
        assertEquals(9.5, di5DerivedPowerKw(estimateKw = 9.5, speedKmh = 30.0, msSinceMeasuredPower = Long.MAX_VALUE)!!, 0.0)
    }

    @Test
    fun `moving with no positive estimate leaves the value alone`() {
        assertNull(di5DerivedPowerKw(estimateKw = -4.0, speedKmh = 50.0, msSinceMeasuredPower = stale))
        assertNull(di5DerivedPowerKw(estimateKw = 0.0, speedKmh = 50.0, msSinceMeasuredPower = stale))
        assertNull(di5DerivedPowerKw(estimateKw = Double.NaN, speedKmh = 50.0, msSinceMeasuredPower = stale))
    }
}
