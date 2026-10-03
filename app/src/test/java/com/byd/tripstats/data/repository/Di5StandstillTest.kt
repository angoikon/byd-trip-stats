package com.byd.tripstats.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [di5StandstillCountsAsCarOn] — the DiLink-5 rule that counts a standstill in a
 * process that has run since the trip last moved as the car being on.
 */
class Di5StandstillTest {

    private val cap = 30 * 60_000L   // 30 min

    private fun holds(
        isDiLink5: Boolean = true,
        moved: Boolean = true,
        windowOpen: Boolean = false,
        standstillMs: Long = 60_000L,
        charging: Boolean = false
    ) = di5StandstillCountsAsCarOn(isDiLink5, moved, windowOpen, standstillMs, cap, charging)

    @Test
    fun `never applies on DiLink-3`() {
        assertFalse(holds(isDiLink5 = false))
        assertFalse(holds(isDiLink5 = false, standstillMs = 0L))
    }

    @Test
    fun `a long red light or a queue counts as car on`() {
        assertTrue(holds(standstillMs = 2 * 60_000L + 30_000L))   // the 2½-min light
        assertTrue(holds(standstillMs = 12 * 60_000L))            // 24 Sep 18:26–18:38, car on
    }

    @Test
    fun `a trip this process never saw move is not held`() {
        // e.g. a process revived after a switch-off, before the car moves again
        assertFalse(holds(moved = false))
    }

    @Test
    fun `a window inherited from a dead process runs its course`() {
        assertFalse(holds(windowOpen = true))
    }

    @Test
    fun `the cap hands a very long standstill back to the car-off timeout`() {
        assertTrue(holds(standstillMs = cap - 1))
        assertFalse(holds(standstillMs = cap))
    }

    @Test
    fun `a car on a charger is parked, not queueing`() {
        // 2026-10-03: a 35-min DC charge was held as car-on until the cap, keeping the trip open.
        assertFalse(holds(standstillMs = 2 * 60_000L, charging = true))
        assertTrue(holds(standstillMs = 2 * 60_000L, charging = false))
    }
}
