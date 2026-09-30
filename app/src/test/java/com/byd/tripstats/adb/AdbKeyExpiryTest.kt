package com.byd.tripstats.adb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [shouldDisableAdbKeyExpiry] — which head units get Android 11's seven-day adb key
 * expiry switched off, and when the setting is left alone.
 */
class AdbKeyExpiryTest {

    private val sevenDaysMs = 7L * 24 * 60 * 60 * 1000

    @Test
    fun `Android 11 and later with expiry on gets it switched off`() {
        assertTrue(shouldDisableAdbKeyExpiry(30, sevenDaysMs))   // DiLink 5
        assertTrue(shouldDisableAdbKeyExpiry(34, sevenDaysMs))   // DiLink 100
    }

    @Test
    fun `an unset value counts as expiry on`() {
        // Settings.Global returns the caller's default when the row doesn't exist yet.
        assertTrue(shouldDisableAdbKeyExpiry(30, -1L))
    }

    @Test
    fun `already off is never written again`() {
        assertFalse(shouldDisableAdbKeyExpiry(30, 0L))
    }

    @Test
    fun `DiLink 3 is never touched - Android 10 has no key expiry`() {
        assertFalse(shouldDisableAdbKeyExpiry(29, sevenDaysMs))
        assertFalse(shouldDisableAdbKeyExpiry(29, -1L))
    }
}
