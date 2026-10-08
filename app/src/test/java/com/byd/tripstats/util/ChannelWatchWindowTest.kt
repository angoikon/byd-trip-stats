package com.byd.tripstats.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RtDispatch.channelWatchActive] and [RtDispatch.channelWatchPollMs] — the watch
 * that dispatches the background helper once the adb port opens, timed from its own start.
 */
class ChannelWatchWindowTest {

    private val minute = 60_000L

    @Test
    fun `a watch runs for twenty minutes from its own start`() {
        assertTrue(RtDispatch.channelWatchActive(0L))
        assertTrue(RtDispatch.channelWatchActive(20 * minute - 1))
        assertFalse(RtDispatch.channelWatchActive(20 * minute))
    }

    @Test
    fun `a watch started hours after kernel boot still runs`() {
        // 2026-10-07: Android restarted itself 3 hours into a boot. The window used to be measured
        // from kernel boot, so this watch ended before its first poll. Only time since the watch
        // started counts now, so how long the unit has been up makes no difference.
        val sinceKernelBootAtRestart = 10_808_000L
        assertTrue(sinceKernelBootAtRestart > 20 * minute)   // the old window was long gone
        assertTrue(RtDispatch.channelWatchActive(0L))         // the new one starts with the watch
    }

    @Test
    fun `polls are tight at first and relaxed later`() {
        assertEquals(5_000L, RtDispatch.channelWatchPollMs(0L))
        assertEquals(5_000L, RtDispatch.channelWatchPollMs(2 * minute - 1))
        assertEquals(15_000L, RtDispatch.channelWatchPollMs(2 * minute))
    }
}
