package com.byd.tripstats.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [keepEarlierSnapshot] — whether a re-dispatch keeps the supervisor-log snapshot
 * already on disk. A dispatch truncates the log first, so a second one seconds later sees only the
 * fresh header; on the dev Seal that replaced the 19 KB record of an overnight reboot with 40 bytes.
 */
class SupervisorSnapshotTest {

    @Test
    fun `a second dispatch seconds later keeps the fuller record`() {
        assertTrue(keepEarlierSnapshot(earlierAgeMs = 1_000L, earlierBytes = 19_300L, newBytes = 40))
    }

    @Test
    fun `a larger new log replaces it`() {
        assertFalse(keepEarlierSnapshot(earlierAgeMs = 1_000L, earlierBytes = 400L, newBytes = 19_245))
    }

    @Test
    fun `an old snapshot is a different failure and is replaced`() {
        assertFalse(keepEarlierSnapshot(earlierAgeMs = SNAPSHOT_EPISODE_MS, earlierBytes = 19_300L, newBytes = 40))
        assertFalse(keepEarlierSnapshot(earlierAgeMs = 3_600_000L, earlierBytes = 19_300L, newBytes = 40))
    }

    @Test
    fun `no earlier snapshot - always written`() {
        // A missing file reads as length 0 and lastModified 0.
        assertFalse(keepEarlierSnapshot(earlierAgeMs = System.currentTimeMillis(), earlierBytes = 0L, newBytes = 40))
    }

    @Test
    fun `a clock step back doesn't pin an old snapshot`() {
        assertFalse(keepEarlierSnapshot(earlierAgeMs = -5_000L, earlierBytes = 19_300L, newBytes = 40))
    }
}
