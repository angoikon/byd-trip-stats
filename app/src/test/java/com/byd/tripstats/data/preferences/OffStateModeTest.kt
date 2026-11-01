package com.byd.tripstats.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [effectiveOffStateMode] — DiLink-5 always runs Always On. */
class OffStateModeTest {

    @Test fun `DiLink-3 runs the mode that was picked`() {
        OffStateMode.values().forEach { assertEquals(it, effectiveOffStateMode(it, isDiLink5 = false)) }
    }

    @Test fun `DiLink-5 runs Always On whatever was picked`() {
        // A Sealion 7 left on Minimal after a reinstall missed three drives (2026-10-31/11-01)
        OffStateMode.values().forEach {
            assertEquals(OffStateMode.ENABLED, effectiveOffStateMode(it, isDiLink5 = true))
        }
    }
}
