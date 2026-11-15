package com.byd.tripstats.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Unit tests for [effectiveOffStateMode] — DiLink-5 always runs Always On, and Minimal (removed in
 * 2.21.0) runs as Deep Sleep.
 */
class OffStateModeTest {

    @Test fun `DiLink-3 runs Always On and Deep Sleep as picked`() {
        assertEquals(OffStateMode.ENABLED, effectiveOffStateMode(OffStateMode.ENABLED, isDiLink5 = false))
        assertEquals(OffStateMode.DEEP_SLEEP, effectiveOffStateMode(OffStateMode.DEEP_SLEEP, isDiLink5 = false))
    }

    @Test fun `a stored Minimal runs as Deep Sleep`() {
        // Minimal's 90-min alarm never fired on DiLink-3 (dev Seal: armed 5x, fired 0x), so Deep Sleep
        // is what a Minimal user already had.
        assertEquals(OffStateMode.DEEP_SLEEP, effectiveOffStateMode(OffStateMode.DISABLED, isDiLink5 = false))
    }

    @Test fun `no mode in effect is Minimal`() {
        listOf(false, true).forEach { di5 ->
            OffStateMode.values().forEach {
                assertNotEquals(OffStateMode.DISABLED, effectiveOffStateMode(it, di5))
            }
        }
    }

    @Test fun `DiLink-5 runs Always On whatever was picked`() {
        // A Sealion 7 left on Minimal after a reinstall missed three drives (2026-10-31/11-01)
        OffStateMode.values().forEach {
            assertEquals(OffStateMode.ENABLED, effectiveOffStateMode(it, isDiLink5 = true))
        }
    }
}
