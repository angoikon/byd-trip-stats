package com.byd.tripstats.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the DiLink-5 rules that use the car's bodywork power level (0 = OFF, 2 = ON):
 * [effectiveCarOffTimeoutMs] and [di5SwitchedOffBlocksStart].
 */
class Di5SwitchOffTest {

    private val default = 3 * 60_000L

    private fun timeout(
        configured: Long = default,
        isDiLink5: Boolean = true,
        powerLevel: Int? = 0,
    ) = effectiveCarOffTimeoutMs(configured, default, isDiLink5, powerLevel)

    @Test
    fun `a switched-off DiLink-5 car closes the trip after 90 s`() {
        assertEquals(90_000L, DI5_SWITCHED_OFF_TIMEOUT_MS)
        assertEquals(DI5_SWITCHED_OFF_TIMEOUT_MS, timeout())
    }

    @Test
    fun `DiLink-3 keeps the configured timeout whatever its power level reads`() {
        assertEquals(default, timeout(isDiLink5 = false))
        assertEquals(10 * 60_000L, timeout(configured = 10 * 60_000L, isDiLink5 = false))
    }

    @Test
    fun `switched back on, or no reading, the trip waits the full timeout`() {
        // On the same car: switched on 79 s after switching off, moving at 89 s — one trip.
        assertEquals(default, timeout(powerLevel = 2))
        assertEquals(default, timeout(powerLevel = null))
        assertEquals(default, timeout(powerLevel = 255))
        assertEquals(default, timeout(powerLevel = 1))
    }

    @Test
    fun `a timeout the user changed is kept`() {
        assertEquals(10 * 60_000L, timeout(configured = 10 * 60_000L))
        assertEquals(2 * 60_000L, timeout(configured = 2 * 60_000L))
        assertEquals(60_000L, timeout(configured = 60_000L))
    }

    @Test
    fun `a parked DiLink-5 car that reads switched off opens no trip`() {
        // 18:39 on 2026-11-10: odometer 2666.8 → 2666.9, speed 0, powerLevel 0.
        assertTrue(di5SwitchedOffBlocksStart(isDiLink5 = true, powerLevel = 0, carSpeedKmh = 0.0))
        assertTrue(di5SwitchedOffBlocksStart(isDiLink5 = true, powerLevel = 0, carSpeedKmh = 5.0))
    }

    @Test
    fun `a moving car starts its trip even on a reading left over from before a sleep`() {
        // 18:54:53 the same evening: speed 7.6, powerLevel still the 0 from before the sleep;
        // the reading turned 2 a second later.
        assertFalse(di5SwitchedOffBlocksStart(isDiLink5 = true, powerLevel = 0, carSpeedKmh = 7.6))
    }

    @Test
    fun `only an OFF reading on DiLink 5 refuses a start`() {
        assertFalse(di5SwitchedOffBlocksStart(isDiLink5 = false, powerLevel = 0, carSpeedKmh = 0.0))
        assertFalse(di5SwitchedOffBlocksStart(isDiLink5 = true, powerLevel = 2, carSpeedKmh = 0.0))
        assertFalse(di5SwitchedOffBlocksStart(isDiLink5 = true, powerLevel = null, carSpeedKmh = 0.0))
    }
}
