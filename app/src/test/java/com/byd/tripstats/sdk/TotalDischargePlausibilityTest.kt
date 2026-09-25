package com.byd.tripstats.sdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [isPlausibleTotalDischargeKwh] — the guard on the lifetime discharge counter. */
class TotalDischargePlausibilityTest {

    @Test
    fun `real lifetime readings pass`() {
        assertTrue(isPlausibleTotalDischargeKwh(2891.5))
        assertTrue(isPlausibleTotalDischargeKwh(0.0))            // stale-0, handled downstream
        assertTrue(isPlausibleTotalDischargeKwh(-12.3))          // net counter can dip with regen
        assertTrue(isPlausibleTotalDischargeKwh(250_000.0))      // a very high-mileage car
    }

    @Test
    fun `the 0xFFFFFF sentinel is rejected in raw and scaled form`() {
        assertFalse(isPlausibleTotalDischargeKwh(16_777_215.0))  // 0xFFFFFF
        assertFalse(isPlausibleTotalDischargeKwh(1_677_721.5))   // 0xFFFFFF × 0.1
        assertFalse(isPlausibleTotalDischargeKwh(-16_777_215.0))
    }

    @Test
    fun `non-finite values are rejected`() {
        assertFalse(isPlausibleTotalDischargeKwh(Double.NaN))
        assertFalse(isPlausibleTotalDischargeKwh(Double.POSITIVE_INFINITY))
    }
}
