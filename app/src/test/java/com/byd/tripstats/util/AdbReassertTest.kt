package com.byd.tripstats.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [adbNeedsReassert] — when the app switches adb back on after BYD has cleared it.
 */
class AdbReassertTest {

    @Test
    fun `a newer head unit with the port shut and a setting cleared is re-asserted`() {
        // BYD cleared adb again at the user unlock after our first assert (09-27, 23 s).
        assertTrue(adbNeedsReassert(postDiLink3 = true, portOpen = false, settingsCleared = true))
    }

    @Test
    fun `an open port is never written to`() {
        assertFalse(adbNeedsReassert(postDiLink3 = true, portOpen = true, settingsCleared = true))
    }

    @Test
    fun `a port that is only slow to open is left alone`() {
        // Settings already on: adbd is still starting, and writing again would change nothing.
        assertFalse(adbNeedsReassert(postDiLink3 = true, portOpen = false, settingsCleared = false))
    }

    @Test
    fun `DiLink 3 is never touched, whatever its port and settings say`() {
        assertFalse(adbNeedsReassert(postDiLink3 = false, portOpen = false, settingsCleared = true))
        assertFalse(adbNeedsReassert(postDiLink3 = false, portOpen = true, settingsCleared = true))
    }
}
