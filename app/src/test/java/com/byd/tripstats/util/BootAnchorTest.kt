package com.byd.tripstats.util

import com.byd.tripstats.data.preferences.OffStateMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BootAnchor.shouldHoldAccess] — when the DiLink-3 boot listener holds notification
 * access. Only Always On on DiLink-3 proper: Minimal and Deep Sleep must stay dark while parked, and
 * other platforms don't have BYD's QuickBoot.
 */
class BootAnchorTest {

    @Test
    fun `DiLink-3 in Always On holds access`() {
        assertTrue(BootAnchor.shouldHoldAccess(classicDiLink3 = true, mode = OffStateMode.ENABLED))
    }

    @Test
    fun `Minimal and Deep Sleep withdraw it`() {
        assertFalse(BootAnchor.shouldHoldAccess(true, OffStateMode.DISABLED))
        assertFalse(BootAnchor.shouldHoldAccess(true, OffStateMode.DEEP_SLEEP))
    }

    @Test
    fun `never outside DiLink-3`() {
        OffStateMode.values().forEach { assertFalse(BootAnchor.shouldHoldAccess(false, it)) }
    }
}
