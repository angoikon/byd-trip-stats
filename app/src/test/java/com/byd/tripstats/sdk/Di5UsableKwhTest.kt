package com.byd.tripstats.sdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [isPlausibleDi5UsableKwh] — the first frame after a restart carries a usable kWh
 * of exactly 0 while the panel shows the real SoC (Sealion 7, @luads, 2026-10-15).
 */
class Di5UsableKwhTest {

    @Test
    fun `the first-frame zero is not a reading`() {
        assertFalse(isPlausibleDi5UsableKwh(0.0))
    }

    @Test
    fun `real usable energy is kept, down to a nearly empty pack`() {
        assertTrue(isPlausibleDi5UsableKwh(51.0))
        assertTrue(isPlausibleDi5UsableKwh(0.3))
        assertTrue(isPlausibleDi5UsableKwh(200.0))
    }

    @Test
    fun `negative, oversized and unreadable values are not readings`() {
        assertFalse(isPlausibleDi5UsableKwh(-1.0))
        assertFalse(isPlausibleDi5UsableKwh(200.1))
        assertFalse(isPlausibleDi5UsableKwh(Double.NaN))
    }
}
