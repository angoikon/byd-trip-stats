package com.byd.tripstats.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [describeBootReason] — the boot reason on diag.log's boot-session line, which has
 * to tell BYD's parked cloud restart (DiLink-3) from a reboot by hand. Times are the dev Seal's of
 * 2026-11-14: BYD decided at 23:11:10, the head unit booted 63 s later.
 */
class BootReasonTest {

    private val cloudDecisionSec = 1_794_690_670L
    private val bootStartMs = (cloudDecisionSec + 63) * 1000L

    @Test
    fun `a bydcloud boot reason is tagged`() {
        assertEquals(
            "boot reason reboot,bydcloud (BYD cloud restart)",
            describeBootReason("reboot,bydcloud", bootStartMs, cloudDecisionSec),
        )
    }

    @Test
    fun `an unreadable reason is still tagged when BYD decided a minute before the boot`() {
        assertEquals(
            "boot reason unknown (BYD cloud restart)",
            describeBootReason("", bootStartMs, cloudDecisionSec),
        )
    }

    @Test
    fun `a reboot by hand a day after the last cloud restart is not tagged`() {
        val nextDayMs = bootStartMs + 24 * 3_600_000L
        assertEquals("boot reason reboot", describeBootReason("reboot", nextDayMs, cloudDecisionSec))
    }

    @Test
    fun `no cloud time and no reason`() {
        assertEquals("boot reason unknown", describeBootReason("", bootStartMs, null))
    }

    @Test
    fun `a cloud time well after the boot is not this boot`() {
        val earlierBootMs = (cloudDecisionSec - 3_600) * 1000L
        assertEquals("boot reason reboot", describeBootReason("reboot", earlierBootMs, cloudDecisionSec))
    }

    @Test
    fun `the match window ends after ten minutes`() {
        val lateMs = cloudDecisionSec * 1000L + CLOUD_REBOOT_MATCH_MS + 1
        assertEquals("boot reason reboot", describeBootReason("reboot", lateMs, cloudDecisionSec))
    }

    @Test
    fun `an Android-only restart is measured from Android's start, not the kernel's`() {
        // Dev Seal 2026-11-15: kernel up 49 713 s, Android restarted at 49 635.3 s (adb reboot).
        assertEquals(77_683L, sinceAndroidStartMs(elapsedMs = 49_713_000L, systemServerStartElapsedMs = 49_635_317L))
    }

    @Test
    fun `the Android-only restart the morning after a cloud restart is not tagged as the cloud's`() {
        // Dev Seal 2026-11-15: BYD restarted the head unit at 23:11 (kernel boot); Android alone
        // restarted at 12:58:46 the next day. Measured from the kernel, the boot matched BYD's time.
        val kernelUptimeMs = 49_713_000L
        val serverStartMs = 49_635_317L
        val nowMs = bootStartMs + kernelUptimeMs
        assertEquals(
            "boot reason unknown (Android-only restart)",
            describeBootReason(
                sysBootReason = "",
                bootStartMs = nowMs - sinceAndroidStartMs(kernelUptimeMs, serverStartMs),
                cloudRebootSec = cloudDecisionSec,
                androidOnlyRestart = isAndroidOnlyRestart(kernelUptimeMs, serverStartMs),
            ),
        )
    }

    @Test
    fun `SystemServer starting seconds into a real boot is not an Android-only restart`() {
        assertEquals(false, isAndroidOnlyRestart(elapsedMs = 600_000L, systemServerStartElapsedMs = 9_500L))
        assertEquals(false, isAndroidOnlyRestart(elapsedMs = 600_000L, systemServerStartElapsedMs = null))
        assertEquals(true, isAndroidOnlyRestart(elapsedMs = 49_713_000L, systemServerStartElapsedMs = 49_635_317L))
    }

    @Test
    fun `no or nonsensical start time falls back to the kernel's uptime`() {
        assertEquals(49_713_000L, sinceAndroidStartMs(49_713_000L, null))
        assertEquals(49_713_000L, sinceAndroidStartMs(49_713_000L, 60_000_000L))   // after "now"
        assertEquals(49_713_000L, sinceAndroidStartMs(49_713_000L, -5L))
    }
}
